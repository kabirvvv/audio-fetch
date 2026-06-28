import yt_dlp
import os
import re
import json
import time
import threading
import urllib.request

from mutagen.mp4 import MP4, MP4Cover

try:
    from ytmusicapi import YTMusic
    _ytmusic = YTMusic()
except Exception:
    _ytmusic = None


# ─────────────────────────────────────────────────────────────────────────────
# Shelf cache
# ─────────────────────────────────────────────────────────────────────────────

_CACHE_DIR = os.path.join(os.path.dirname(__file__), "cache")
os.makedirs(_CACHE_DIR, exist_ok=True)

# TTLs in seconds per shelf key
_SHELF_TTL = {
    "quick_picks":   1800,   # 30 min  — based on current track
    "trending":      3600,   # 1 hr
    "new_releases":  7200,   # 2 hr
    "charts":        7200,   # 2 hr
    "moods":        86400,   # 24 hr  — changes very rarely
}
_DEFAULT_TTL = 3600

_cache_lock = threading.Lock()


def _cache_path(key: str) -> str:
    return os.path.join(_CACHE_DIR, f"{key}.json")


def _read_cache(key: str):
    """Return cached items if still fresh, else None."""
    path = _cache_path(key)
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        ttl = _SHELF_TTL.get(key, _DEFAULT_TTL)
        if time.time() - data.get("updated", 0) < ttl:
            return data.get("items")
    except Exception:
        pass
    return None


def _write_cache(key: str, items) -> None:
    path = _cache_path(key)
    try:
        with _cache_lock:
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"updated": time.time(), "items": items}, f)
    except Exception:
        pass


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def sanitize(name: str) -> str:
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', '-', name)
    name = re.sub(r'\s+', '_', name)
    name = name.strip('-_')
    return name[:150] or "audio"


def resolve_query(text: str) -> str:
    text = text.strip()
    if re.match(r'^[a-zA-Z][a-zA-Z0-9+.\-]*://', text):
        return text
    return f"ytsearch1:{text}"


def first_entry(info: dict) -> dict:
    if info and "entries" in info:
        entries = [e for e in info["entries"] if e]
        if not entries:
            raise ValueError("No results found for that search.")
        return entries[0]
    return info


def embed_cover_art(audio_path: str, thumb_path: str, title: str, artist: str) -> None:
    try:
        tags = MP4(audio_path)
        with open(thumb_path, "rb") as f:
            cover_data = f.read()
        tags["covr"] = [MP4Cover(cover_data, imageformat=MP4Cover.FORMAT_JPEG)]
        if title:
            tags["\xa9nam"] = [title]
        if artist:
            tags["\xa9ART"] = [artist]
        tags.save()
    except Exception:
        pass


def _format_duration(seconds) -> str:
    try:
        s = int(seconds)
        return f"{s // 60}:{s % 60:02d}"
    except (TypeError, ValueError):
        return ""


def _best_thumbnail(thumbnails) -> str:
    if not thumbnails:
        return ""
    try:
        return thumbnails[-1].get("url", "")
    except Exception:
        return ""


def _card(video_id: str, title: str, artist: str, thumbnail: str,
          card_type: str = "TRACK", playlist_id: str = None) -> dict:
    """Build a normalised HomeCard dict."""
    c = {
        "videoId":   video_id,
        "title":     title,
        "artist":    artist,
        "thumbnail": thumbnail,
        "type":      card_type,
    }
    if playlist_id:
        c["playlistId"] = playlist_id
    return c


# ─────────────────────────────────────────────────────────────────────────────
# Home shelf fetchers  (each returns List[dict] or raises)
# ─────────────────────────────────────────────────────────────────────────────

def _fetch_quick_picks(seed_video_id: str, limit: int = 10):
    """Fetch watch-playlist recommendations for seed_video_id.
    Falls back to first shelf from get_home() if no seed is available.
    """
    if _ytmusic is None:
        return []

    # If we have a seed, use watch playlist
    if seed_video_id:
        try:
            raw = json.loads(get_watch_playlist(seed_video_id, limit))
            if isinstance(raw, list) and raw:
                return raw
        except Exception:
            pass

    # Fallback: pull first batch of tracks from ytmusicapi home
    try:
        shelves = _ytmusic.get_home(limit=3)
        results = []
        for shelf in shelves:
            for item in shelf.get("contents") or []:
                vid = item.get("videoId")
                if not vid:
                    continue
                artists = item.get("artists") or []
                artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
                thumbnail = _best_thumbnail(item.get("thumbnails") or [])
                results.append(_card(vid, item.get("title", "Unknown"), artist, thumbnail, "TRACK"))
                if len(results) >= limit:
                    return results
        return results
    except Exception:
        return []


# Inside _fetch_trending(), change the first line to:
def _fetch_trending(limit: int = 10):
    yt = _get_authed() or _ytmusic   # prefer authed, fall back to anonymous
    if yt is None:
        return []
    try:
        shelves = yt.get_home(limit=limit)
    except Exception:
        return []

    results = []
    for shelf in shelves:
        shelf_title = shelf.get("title", "")
        items = []

        for item in shelf.get("contents") or []:
            # ── Tracks ────────────────────────────────────────────────────────
            vid = item.get("videoId")
            if vid:
                artists = item.get("artists") or []
                artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
                thumbnail = _best_thumbnail(item.get("thumbnails") or [])
                items.append(_card(vid, item.get("title", "Unknown"), artist, thumbnail, "TRACK"))
                continue  # processed as track — move on

            # ── Albums / Playlists ────────────────────────────────────────────
            # ytmusicapi puts the ID in different places depending on content type:
            #   Albums:    item["browseId"]  (starts with MPREb)
            #   Playlists: item["playlistId"] (plain) or item["browseId"] (VL-prefixed)
            browse_id   = item.get("browseId", "")
            playlist_id = item.get("playlistId", "")

            # Determine the best ID to pass as playlistId to get_playlist_tracks()
            if browse_id.startswith("MPREb"):
                # Album — use browseId directly (get_playlist_tracks handles MPREb)
                resolved_id = browse_id
                card_type   = "ALBUM"
            elif playlist_id:
                # Playlist with a direct playlistId
                resolved_id = playlist_id
                card_type   = "PLAYLIST"
            elif browse_id.startswith("VL"):
                # VL-prefixed browseId — strip prefix so get_playlist() works
                resolved_id = browse_id[2:]
                card_type   = "PLAYLIST"
            elif browse_id:
                # Unknown browseId — treat as playlist and let get_playlist_tracks() figure it out
                resolved_id = browse_id
                card_type   = "PLAYLIST"
            else:
                # No usable ID — skip
                continue

            thumbnail = _best_thumbnail(item.get("thumbnails") or [])
            subtitle  = (item.get("subtitle") or item.get("description") or "")
            # subtitle can be a list of dicts (e.g. [{text: "2024"}, {text: " • "}, {text: "Album"}])
            if isinstance(subtitle, list):
                subtitle = "".join(
                    part.get("text", "") if isinstance(part, dict) else str(part)
                    for part in subtitle
                )

            items.append(_card(
                video_id    = "",
                title       = item.get("title", "Unknown"),
                artist      = subtitle,
                thumbnail   = thumbnail,
                card_type   = card_type,
                playlist_id = resolved_id,
            ))

        if items:
            results.append({"shelfTitle": shelf_title, "items": items})

    return results

def _fetch_moods():
    """Fetch mood/genre categories from ytmusicapi."""
    if _ytmusic is None:
        return []
    raw = _ytmusic.get_mood_categories()
    moods = []
    for _section_title, chips in raw.items():
        for chip in chips:
            title  = chip.get("title", "")
            params = chip.get("params", "")
            if title and params:
                moods.append({"title": title, "params": params})
    return moods


# ─────────────────────────────────────────────────────────────────────────────
# Public API
# ─────────────────────────────────────────────────────────────────────────────

def get_home(seed_video_id: str = "") -> str:
    """Return the full Home data map as JSON.

    Keys returned:
        quick_picks   – List[HomeCard]
        shelves       – List[{shelfTitle, items: List[HomeCard]}]  (trending etc.)
        moods         – List[{title, params}]

    Each shelf is served from cache if fresh.
    Only stale shelves trigger a network call.
    All network calls run in parallel threads.

    Args:
        seed_video_id: videoId of the currently / last-played track.
                       Used for quick_picks. Pass "" if nothing is playing.
    """
    result = {}
    errors = {}
    lock   = threading.Lock()

    def fetch(key, fn, *args):
        cached = _read_cache(key)
        if cached is not None:
            with lock:
                result[key] = cached
            return
        try:
            data = fn(*args)
            _write_cache(key, data)
            with lock:
                result[key] = data
        except Exception as e:
            with lock:
                errors[key] = str(e)
                result[key] = []        # empty shelf on error — UI shows nothing

    threads = [
        threading.Thread(target=fetch, args=("quick_picks", _fetch_quick_picks, seed_video_id)),
        threading.Thread(target=fetch, args=("shelves",     _fetch_trending, 5)),
        threading.Thread(target=fetch, args=("moods",       _fetch_moods)),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=15)      # never block the UI more than 15 s total

    return json.dumps(result)


def prefetch_quick_picks(video_id: str) -> None:
    """Silently refresh the quick_picks cache for video_id in a background thread.
    Called from Kotlin when a new track starts playing.
    Returns immediately — fire and forget.
    """
    def _run():
        try:
            data = _fetch_quick_picks(video_id)
            _write_cache("quick_picks", data)
        except Exception:
            pass

    threading.Thread(target=_run, daemon=True).start()


def get_mood_playlists(params: str) -> str:
    """Return tracks for a mood/genre chip.

    Returns JSON array of HomeCard dicts (type=TRACK).
    """
    if _ytmusic is None:
        return "ERROR: ytmusicapi unavailable"
    try:
        raw = _ytmusic.get_mood_playlists(params)
        results = []
        for item in raw:
            vid = item.get("videoId", "")
            if not vid:
                continue
            artists = item.get("artists") or []
            artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
            thumbnail = _best_thumbnail(item.get("thumbnails") or [])
            results.append(_card(vid, item.get("title", "Unknown"), artist, thumbnail))
        return json.dumps(results)
    except Exception as e:
        return f"ERROR: {str(e)}"


# ─────────────────────────────────────────────────────────────────────────────
# Unchanged functions
# ─────────────────────────────────────────────────────────────────────────────

def search_tracks(query: str, limit: int = 15) -> str:
    """Search YouTube Music for tracks.
    Returns JSON array of up to `limit` results, each with:
      videoId, title, artist, duration, durationSeconds, thumbnail, webpage_url
    Falls back to yt-dlp ytsearch if ytmusicapi is unavailable.
    """
    query = query.strip()
    if not query:
        return "ERROR: empty query"

    if _ytmusic is not None:
        try:
            raw = _ytmusic.search(query, filter="songs", limit=limit)
            results = []
            for item in raw[:limit]:
                video_id = item.get("videoId", "")
                if not video_id:
                    continue
                title = item.get("title", "Unknown")
                artists = item.get("artists") or []
                artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
                duration_str  = item.get("duration") or ""
                duration_secs = item.get("duration_seconds") or 0
                thumbnails    = item.get("thumbnails") or []
                thumbnail     = _best_thumbnail(thumbnails)
                results.append({
                    "videoId": video_id,
                    "title":   title,
                    "artist":  artist,
                    "duration": duration_str,
                    "durationSeconds": duration_secs,
                    "thumbnail": thumbnail,
                    "webpage_url": f"https://music.youtube.com/watch?v={video_id}",
                })
            if results:
                return json.dumps(results)
        except Exception:
            pass

    try:
        opts = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch{limit}:{query}", download=False)
        entries = (info or {}).get("entries") or []
        results = []
        for e in entries[:limit]:
            if not e:
                continue
            video_id      = e.get("id") or e.get("url", "")
            duration_secs = e.get("duration") or 0
            results.append({
                "videoId": video_id,
                "title":   e.get("title", "Unknown"),
                "artist":  e.get("uploader") or e.get("channel") or "",
                "duration": _format_duration(duration_secs),
                "durationSeconds": duration_secs,
                "thumbnail": e.get("thumbnail") or "",
                "webpage_url": e.get("webpage_url") or f"https://www.youtube.com/watch?v={video_id}",
            })
        return json.dumps(results)
    except Exception as ex:
        return f"ERROR: {str(ex)}"


def get_stream_url(url: str) -> str:
    """Extract a direct streamable audio URL without downloading."""
    try:
        query = resolve_query(url)
        info_opts = {
            "quiet": True,
            "no_warnings": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
            "format": "bestaudio[ext=m4a]/bestaudio[ext=aac]/bestaudio",
        }
        with yt_dlp.YoutubeDL(info_opts) as ydl:
            info = first_entry(ydl.extract_info(query, download=False))

        stream_url = info.get("url", "")
        if not stream_url:
            return "ERROR: No stream URL found."

        duration_secs = info.get("duration") or 0
        return json.dumps({
            "url":      stream_url,
            "title":    info.get("title", "Unknown"),
            "artist":   info.get("artist") or info.get("uploader") or info.get("channel") or "",
            "thumbnail": info.get("thumbnail", ""),
            "duration": _format_duration(duration_secs),
            "durationSeconds": duration_secs,
            "webpage_url": info.get("webpage_url") or info.get("url") or query,
        })
    except Exception as e:
        return f"ERROR: {str(e)}"


def get_stream_url_by_id(video_id: str) -> str:
    """Resolve a stream URL directly from a YouTube video ID."""
    url = f"https://music.youtube.com/watch?v={video_id}"
    try:
        info_opts = {
            "quiet": True,
            "no_warnings": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
            "format": "bestaudio[ext=m4a]/bestaudio[ext=aac]/bestaudio",
        }
        with yt_dlp.YoutubeDL(info_opts) as ydl:
            info = ydl.extract_info(url, download=False)

        stream_url = info.get("url", "")
        if not stream_url:
            return "ERROR: No stream URL found."

        duration_secs = info.get("duration") or 0
        return json.dumps({
            "url":      stream_url,
            "title":    info.get("title", "Unknown"),
            "artist":   info.get("artist") or info.get("uploader") or info.get("channel") or "",
            "thumbnail": info.get("thumbnail", ""),
            "duration": _format_duration(duration_secs),
            "durationSeconds": duration_secs,
            "webpage_url": url,
        })
    except Exception as e:
        return f"ERROR: {str(e)}"


def download_audio(url: str, download_dir: str) -> str:
    try:
        query = resolve_query(url)

        info_opts = {
            "quiet": True,
            "no_warnings": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        }
        with yt_dlp.YoutubeDL(info_opts) as ydl:
            info = first_entry(ydl.extract_info(query, download=False))

        raw_title     = info.get("title", "audio")
        title         = sanitize(raw_title)
        artist        = info.get("artist") or info.get("uploader") or info.get("channel") or ""
        thumbnail_url = info.get("thumbnail", "")
        video_url     = info.get("webpage_url") or info.get("url") or query

        thumb_path = ""
        if thumbnail_url:
            try:
                thumb_path = os.path.join(download_dir, f"{title}_thumb.jpg")
                urllib.request.urlretrieve(thumbnail_url, thumb_path)
            except Exception:
                thumb_path = ""

        output_path = os.path.join(download_dir, f"{title}.%(ext)s")
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
            "format": "bestaudio[ext=m4a]/bestaudio[ext=aac]/bestaudio",
            "outtmpl": output_path,
            "concurrent_fragment_downloads": 16,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            dl_info = first_entry(ydl.extract_info(video_url, download=True))

        ext        = dl_info.get("ext", "m4a")
        final_path = os.path.join(download_dir, f"{title}.{ext}")
        if not os.path.exists(final_path):
            return "ERROR: File not found after download."

        if ext in ("m4a", "mp4", "m4b") and thumb_path and os.path.exists(thumb_path):
            embed_cover_art(final_path, thumb_path, raw_title, artist)

        if thumb_path and os.path.exists(thumb_path):
            os.remove(thumb_path)

        return final_path
    except Exception as e:
        return f"ERROR: {str(e)}"


def get_watch_playlist(video_id: str, limit: int = 10) -> str:
    """Fetch autoplay candidates for a given videoId using ytmusicapi."""
    if _ytmusic is None:
        return "ERROR: ytmusicapi unavailable"
    try:
        data   = _ytmusic.get_watch_playlist(videoId=video_id, limit=limit + 1)
        tracks = data.get("tracks", [])
        results = []
        for item in tracks:
            vid = item.get("videoId", "")
            if not vid or vid == video_id:
                continue
            artists   = item.get("artists") or []
            artist    = ", ".join(a.get("name", "") for a in artists if a.get("name"))
            thumbnails = item.get("thumbnail") or []
            thumbnail  = _best_thumbnail(thumbnails)
            duration_secs = item.get("length") or 0
            results.append({
                "videoId":         vid,
                "title":           item.get("title", "Unknown"),
                "artist":          artist,
                "durationSeconds": duration_secs,
                "thumbnail":       thumbnail,
            })
            if len(results) >= limit:
                break
        return json.dumps(results)
    except Exception as e:
        return f"ERROR: {str(e)}"
        
def get_playlist_tracks(browse_id: str, limit: int = 50) -> str:
    """Fetch tracks for an album or playlist by browseId or playlistId.

    Tries get_album() first (for album browseIds starting with 'MPREb'),
    falls back to get_playlist() for everything else.

    Returns JSON array of SearchResult-compatible dicts:
      videoId, title, artist, duration, durationSeconds, thumbnail, webpage_url
    """
    if not browse_id or _ytmusic is None:
        return "ERROR: ytmusicapi unavailable"
    try:
        tracks = []

        # Albums have browseIds starting with MPREb
        if browse_id.startswith("MPREb"):
            raw = _ytmusic.get_album(browse_id)
            album_title = raw.get("title", "")
            for item in (raw.get("tracks") or [])[:limit]:
                vid = item.get("videoId", "")
                if not vid:
                    continue
                artists = item.get("artists") or []
                artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
                duration_secs = item.get("duration_seconds") or 0
                thumbnails = item.get("thumbnails") or raw.get("thumbnails") or []
                thumbnail = _best_thumbnail(thumbnails)
                tracks.append({
                    "videoId":         vid,
                    "title":           item.get("title", "Unknown"),
                    "artist":          artist or album_title,
                    "duration":        _format_duration(duration_secs),
                    "durationSeconds": duration_secs,
                    "thumbnail":       thumbnail,
                    "webpage_url":     f"https://music.youtube.com/watch?v={vid}",
                })
        else:
            # Playlist — browseId may be a VL-prefixed string or plain playlist id
            playlist_id = browse_id
            if browse_id.startswith("VL"):
                playlist_id = browse_id[2:]
            raw = _ytmusic.get_playlist(playlist_id, limit=limit)
            for item in (raw.get("tracks") or [])[:limit]:
                vid = item.get("videoId", "")
                if not vid:
                    continue
                artists = item.get("artists") or []
                artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
                duration_secs = item.get("duration_seconds") or 0
                thumbnails = item.get("thumbnails") or []
                thumbnail = _best_thumbnail(thumbnails)
                tracks.append({
                    "videoId":         vid,
                    "title":           item.get("title", "Unknown"),
                    "artist":          artist,
                    "duration":        _format_duration(duration_secs),
                    "durationSeconds": duration_secs,
                    "thumbnail":       thumbnail,
                    "webpage_url":     f"https://music.youtube.com/watch?v={vid}",
                })

        return json.dumps(tracks)
    except Exception as e:
        return f"ERROR: {str(e)}"
# ─────────────────────────────────────────────────────────────────────────────
# Auth
# ─────────────────────────────────────────────────────────────────────────────

_AUTH_PATH = os.path.join(os.path.dirname(__file__), "headers_auth.json")
_ytmusic_authed = None   # authenticated YTMusic instance, None if not logged in


def _get_authed() -> "YTMusic | None":
    """Return the authenticated YTMusic instance, or None if not set up."""
    return _ytmusic_authed


def setup_account(cookie_string: str) -> str:
    """Write headers_auth.json from a raw cookie string and reinitialise _ytmusic_authed.

    Returns JSON: {"success": true, "name": "..."} or "ERROR: ..."
    """
    global _ytmusic_authed
    try:
        headers = {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/124.0.0.0 Safari/537.36"
            ),
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Content-Type": "application/json",
            "X-Goog-AuthUser": "0",
            "x-origin": "https://music.youtube.com",
            "Cookie": cookie_string.strip(),
        }
        with open(_AUTH_PATH, "w", encoding="utf-8") as f:
            json.dump(headers, f, indent=2)

        yt = YTMusic(_AUTH_PATH)
        info = yt.get_account_info()
        _ytmusic_authed = yt

        name = info.get("accountName") or info.get("name") or "YouTube Music user"
        email = info.get("accountEmail") or info.get("email") or ""
        return json.dumps({"success": True, "name": name, "email": email})
    except Exception as e:
        # Clean up bad auth file
        try:
            os.remove(_AUTH_PATH)
        except Exception:
            pass
        _ytmusic_authed = None
        return f"ERROR: {str(e)}"


def get_account_info() -> str:
    """Return cached account info if authenticated.

    Returns JSON: {"authenticated": bool, "name": "...", "email": "..."}
    """
    global _ytmusic_authed

    if not os.path.exists(_AUTH_PATH):
        return json.dumps({"authenticated": False, "name": "", "email": ""})

    try:
        if _ytmusic_authed is None:
            _ytmusic_authed = YTMusic(_AUTH_PATH)
        info = _ytmusic_authed.get_account_info()
        name  = info.get("accountName") or info.get("name") or "YouTube Music user"
        email = info.get("accountEmail") or info.get("email") or ""
        return json.dumps({"authenticated": True, "name": name, "email": email})
    except Exception:
        _ytmusic_authed = None
        return json.dumps({"authenticated": False, "name": "", "email": ""})


def sign_out() -> str:
    """Delete auth file and clear the authenticated instance."""
    global _ytmusic_authed
    _ytmusic_authed = None
    try:
        if os.path.exists(_AUTH_PATH):
            os.remove(_AUTH_PATH)
    except Exception as e:
        return f"ERROR: {str(e)}"
    return json.dumps({"success": True})


def rate_song(video_id: str, rating: str) -> str:
    """Rate a song. rating must be LIKE, DISLIKE, or INDIFFERENT.

    Returns JSON: {"success": true} or "ERROR: ..."
    """
    yt = _get_authed()
    if yt is None:
        return "ERROR: not authenticated"
    if rating not in ("LIKE", "DISLIKE", "INDIFFERENT"):
        return "ERROR: invalid rating"
    try:
        yt.rate_song(video_id, rating)
        return json.dumps({"success": True})
    except Exception as e:
        return f"ERROR: {str(e)}"


def get_liked_songs(limit: int = 100) -> str:
    """Fetch the user's liked songs playlist.

    Returns JSON array of SearchResult-compatible dicts.
    """
    yt = _get_authed()
    if yt is None:
        return "ERROR: not authenticated"
    try:
        raw = yt.get_liked_songs(limit=limit)
        results = []
        for item in (raw.get("tracks") or []):
            vid = item.get("videoId", "")
            if not vid:
                continue
            artists = item.get("artists") or []
            artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
            duration_secs = item.get("duration_seconds") or 0
            thumbnail = _best_thumbnail(item.get("thumbnails") or [])
            results.append({
                "videoId":         vid,
                "title":           item.get("title", "Unknown"),
                "artist":          artist,
                "duration":        _format_duration(duration_secs),
                "durationSeconds": duration_secs,
                "thumbnail":       thumbnail,
                "webpage_url":     f"https://music.youtube.com/watch?v={vid}",
            })
        return json.dumps(results)
    except Exception as e:
        return f"ERROR: {str(e)}"
