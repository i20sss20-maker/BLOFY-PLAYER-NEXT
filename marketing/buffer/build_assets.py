#!/usr/bin/env python3
"""Freeze BLOFY advertising images as native JPEG assets in this repository.

This removes dependence on on-the-fly third-party image conversion at post time.
"""
import json
import pathlib
import shutil
import subprocess
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
DATA = json.loads((ROOT / "content.json").read_text(encoding="utf-8"))
OUT = ROOT.parent / "images"


def run(cmd):
    subprocess.run(cmd, check=True, timeout=90, stdout=subprocess.DEVNULL)


def main():
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise SystemExit("FFmpeg and FFprobe are required")
    for slug, source in DATA["imageUrls"].items():
        path = OUT / "tiktok" / (slug + ".jpg")
        ig = OUT / "instagram" / (slug + ".jpg")
        path.parent.mkdir(parents=True, exist_ok=True)
        ig.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(source, headers={"User-Agent": "BLOFY-Marketing-Assets/1.0"})
        with urllib.request.urlopen(request, timeout=40) as response:
            if not response.headers.get("Content-Type", "").startswith("image/jpeg"):
                raise RuntimeError(f"{slug}: unexpected MIME")
            data = response.read(12_000_000)
        if not (15_000 < len(data) < 12_000_000):
            raise RuntimeError(f"{slug}: unexpected JPEG size")
        path.write_bytes(data)
        run([
            "ffmpeg", "-y", "-v", "error", "-i", str(path), "-frames:v", "1",
            "-vf", "scale=1080:1350:force_original_aspect_ratio=decrease,"
            "pad=1080:1350:(ow-iw)/2:(oh-ih)/2:color=0x081225",
            "-q:v", "3", str(ig),
        ])
        for dest in (path, ig):
            if dest.stat().st_size < 10000:
                raise RuntimeError(f"{dest}: output too small")
        print(f"Saved {slug}: TikTok {path.stat().st_size} bytes; "
              f"Instagram {ig.stat().st_size} bytes")


if __name__ == "__main__":
    main()
