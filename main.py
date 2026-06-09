import yt_dlp
import os
import re
import uuid


def download_audio(url: str, download_dir: str):
    """
    Downloads audio from a URL and returns the local file path.
    Called directly by Android Kotlin via Chaquopy.
    Returns a plain path string on success, or "ERROR: ..." on failure.
    """
    file_id = str(uuid.uuid4())
    output_path = os.path.join(download_dir, f"{file_id}.%(ext)s")

    ydl_opts = {
        "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        "format": "bestaudio",
        "outtmpl": output_path,
        "concurrent_fragment_downloads": 16,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            ext = info.get("ext", "m4a")
            title = info.get("title", "audio")

        # Strip non-ASCII characters for a safe filename
        safe_title = re.sub(r'[^\x00-\x7f]+', '_', title).strip('_') or "audio"
        final_path = os.path.join(download_dir, f"{file_id}.{ext}")

        if not os.path.exists(final_path):
            return "ERROR: File not found after download"

        return final_path

    except Exception as e:
        return f"ERROR: {str(e)}"
