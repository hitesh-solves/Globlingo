"""Globlingo whisper helper: download audio via yt-dlp, transcribe with faster-whisper.
Usage: python whisper_transcribe.py <video_url> <lang> <out_dir>
Writes <out_dir>/transcript.json: {"language": str, "segments": [{"start","end","text"}]}
Requires: pip install yt-dlp faster-whisper  (faster-whisper bundles ffmpeg via PyAV)
"""
import json
import os
import sys
import tempfile


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: whisper_transcribe.py <url> <lang> <out_dir>", file=sys.stderr)
        return 2
    url, lang, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out_dir, exist_ok=True)

    import yt_dlp
    import av

    # av>=15 removed the metadata_errors kwarg that faster-whisper 1.2.x still
    # passes to av.open(); shim it so newer av versions work unchanged.
    _orig_av_open = av.open
    def _av_open_compat(*args, **kwargs):
        kwargs.pop("metadata_errors", None)
        return _orig_av_open(*args, **kwargs)
    av.open = _av_open_compat

    from faster_whisper import WhisperModel

    with tempfile.TemporaryDirectory() as tmp:
        audio_path = os.path.join(tmp, "audio")
        ydl_opts = {
            "format": "bestaudio/best",
            "outtmpl": audio_path + ".%(ext)s",
            "quiet": True,
            "no_warnings": True,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
        files = [f for f in os.listdir(tmp) if f.startswith("audio.")]
        if not files:
            print("no audio downloaded", file=sys.stderr)
            return 1
        src = os.path.join(tmp, files[0])

        model_size = os.environ.get("WHISPER_MODEL", "base")
        compute = "int8"
        model = WhisperModel(model_size, device="cpu", compute_type=compute)
        segments, info_obj = model.transcribe(
            src, language=lang if lang and lang != "auto" else None,
            vad_filter=True,
        )
        result = {
            "language": info_obj.language,
            "segments": [
                {"start": round(s.start, 3), "end": round(s.end, 3), "text": s.text.strip()}
                for s in segments
            ],
        }
        with open(os.path.join(out_dir, "transcript.json"), "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
