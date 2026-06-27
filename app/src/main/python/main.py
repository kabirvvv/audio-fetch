import yt_dlp
import os
import re
import json
import urllib.request

from mutagen.mp4 import MP4, MP4Cover

try:
    from ytmusicapi import YTMusic
    _ytmusic = YTMusic()
except Exception:
    _ytmusic = None


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
    """Convert seconds (int or None) to m:ss string."""
    try:
        s = int(seconds)
        return f"{s // 60}:{s % 60:02d}"
    except (TypeError, ValueError):
        return ""


def _best_thumbnail(thumbnails) -> str:
    """Pick the highest-res thumbnail URL from a ytmusicapi thumbnail list."""
    if not thumbnails:
        return ""
    # ytmusicapi returns list sorted ascending by size; take last
    try:
        return thumbnails[-1].get("url", "")
    except Exception:
        return ""


def search_tracks(query: str, limit: int = 15) -> str:
    """Search YouTube Music for tracks.
    Returns JSON array of up to `limit` results, each with:
      videoId, title, artist, duration, durationSeconds, thumbnail, webpage_url
    Falls back to yt-dlp ytsearch if ytmusicapi is unavailable.
    """
    query = query.strip()
    if not query:
        return "ERROR: empty query"

    # ── ytmusicapi path ───────────────────────────────────────────────────────
    if _ytmusic is not None:
        try:
            raw = _ytmusic.search(query, filter="songs", limit=limit)
            results = []
            for item in raw[:limit]:
                video_id = item.get("videoId", "")
                if not video_id:
                    continue
                title = item.get("title", "Unknown")
                # artists is a list of dicts with 'name'
                artists = item.get("artists") or []
                artist = ", ".join(a.get("name", "") for a in artists if a.get("name"))
                duration_str = item.get("duration") or ""          # e.g. "3:45"
                duration_secs = item.get("duration_seconds") or 0
                thumbnails = item.get("thumbnails") or []
                thumbnail = _best_thumbnail(thumbnails)
                results.append({
                    "videoId": video_id,
                    "title": title,
                    "artist": artist,
                    "duration": duration_str,
                    "durationSeconds": duration_secs,
                    "thumbnail": thumbnail,
                    "webpage_url": f"https://music.youtube.com/watch?v={video_id}",
                })
            if results:
                return json.dumps(results)
            # fall through to yt-dlp if no results
        except Exception:
            pass  # fall through

    # ── yt-dlp fallback ───────────────────────────────────────────────────────
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
            video_id = e.get("id") or e.get("url", "")
            duration_secs = e.get("duration") or 0
            results.append({
                "videoId": video_id,
                "title": e.get("title", "Unknown"),
                "artist": e.get("uploader") or e.get("channel") or "",
                "duration": _format_duration(duration_secs),
                "durationSeconds": duration_secs,
                "thumbnail": e.get("thumbnail") or "",
                "webpage_url": e.get("webpage_url") or f"https://www.youtube.com/watch?v={video_id}",
            })
        return json.dumps(results)
    except Exception as ex:
        return f"ERROR: {str(ex)}"


def get_stream_url(url: str) -> str:
    """Extract a direct streamable audio URL without downloading.
    Returns JSON with url/title/artist/thumbnail/webpage_url, or 'ERROR: ...' on failure.
    """
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
            "url": stream_url,
            "title": info.get("title", "Unknown"),
            "artist": info.get("artist") or info.get("uploader") or info.get("channel") or "",
            "thumbnail": info.get("thumbnail", ""),
            "duration": _format_duration(duration_secs),
            "durationSeconds": duration_secs,
            "webpage_url": info.get("webpage_url") or info.get("url") or query,
        })
    except Exception as e:
        return f"ERROR: {str(e)}"
def get_stream_url_by_id(video_id: str) -> str:
    """Resolve a stream URL directly from a YouTube video ID.
    Faster than get_stream_url() — no search step, just direct resolution.
    Returns same JSON schema as get_stream_url().
    """
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
            "url": stream_url,
            "title": info.get("title", "Unknown"),
            "artist": info.get("artist") or info.get("uploader") or info.get("channel") or "",
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

        raw_title = info.get("title", "audio")
        title = sanitize(raw_title)
        artist = info.get("artist") or info.get("uploader") or info.get("channel") or ""
        thumbnail_url = info.get("thumbnail", "")
        video_url = info.get("webpage_url") or info.get("url") or query

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

        ext = dl_info.get("ext", "m4a")
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
