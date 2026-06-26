import yt_dlp
import os
import re
import json
import urllib.request

from mutagen.mp4 import MP4, MP4Cover


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


def get_stream_url(url: str) -> str:
    """Extract a direct streamable audio URL without downloading.
    Returns JSON with url/title/artist/thumbnail, or 'ERROR: ...' on failure.
    Also returns webpage_url so Kotlin can pass it back for downloading."""
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

        return json.dumps({
            "url": stream_url,
            "title": info.get("title", "Unknown"),
            "artist": info.get("artist") or info.get("uploader") or info.get("channel") or "",
            "thumbnail": info.get("thumbnail", ""),
            "webpage_url": info.get("webpage_url") or info.get("url") or query,
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
