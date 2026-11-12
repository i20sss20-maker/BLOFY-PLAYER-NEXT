#!/usr/bin/env python3
"""Assemble a simple original vertical BLOFY ad from our own poster artwork."""
import json
import pathlib
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
BANK = json.loads((ROOT / "content.json").read_text(encoding="utf-8"))
CAMPAIGNS = [
    ("blofy-vertical-distribution-01.mp4", ["04-retail-partners", "02-server-solutions", "03-player-app"]),
    ("blofy-vertical-distributor-02.mp4", ["06-distributor", "04-retail-partners", "08-how-to-order"]),
    ("blofy-vertical-player-03.mp4", ["03-player-app", "01-iptv-codes", "07-contact"]),
    ("blofy-vertical-servers-04.mp4", ["02-server-solutions", "06-distributor", "07-contact"]),
]


def execute(cmd):
    subprocess.run(cmd, check=True, timeout=360)


def build_campaign(output, selection):
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise SystemExit("ffmpeg and ffprobe must be installed.")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        images = []
        for n, slug in enumerate(selection):
            image = pathlib.Path(tmp) / f"scene_{n+1}.jpg"
            request = urllib.request.Request(
                BANK["imageUrls"][slug],
                headers={"User-Agent": "BLOFY-Creative-Production/1.0"},
            )
            with urllib.request.urlopen(request, timeout=30) as response:
                mime = response.headers.get("Content-Type", "").lower()
                if not mime.startswith("image/jpeg"):
                    raise RuntimeError(f"{slug}: expected JPEG media, got {mime}")
                image.write_bytes(response.read(12_000_000))
            if image.stat().st_size < 10000 or image.stat().st_size > 12_000_000:
                raise RuntimeError(f"{slug}: invalid JPEG size")
            images.append(image)

        cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "warning"]
        for img in images:
            cmd += ["-loop", "1", "-framerate", "24", "-t", "4.4", "-i", str(img)]
        filters = []
        for n in range(3):
            filters.append(
                f"[{n}:v]scale=1080:1920:force_original_aspect_ratio=decrease,"
                f"pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=0x081225,"
                f"setsar=1,format=yuv420p[v{n}]"
            )
        filters.append("[v0][v1]xfade=transition=fade:duration=0.8:offset=3.6[x]")
        filters.append("[x][v2]xfade=transition=fade:duration=0.8:offset=7.2[out]")
        cmd += [
            "-filter_complex", ";".join(filters), "-map", "[out]",
            "-r", "24", "-c:v", "libx264", "-preset", "veryfast",
            "-crf", "24", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", "-t", "11.6",
            str(output),
        ]
        execute(cmd)
        duration = float(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=nw=1:nk=1", str(output)
        ], text=True).strip())
        if not (10.0 <= duration <= 13.0):
            raise RuntimeError(f"Invalid video duration: {duration}")
        print(f"Created {output} duration={duration:.1f}s size={output.stat().st_size} bytes")
        if output.stat().st_size > 18_000_000:
            raise RuntimeError("Video file unexpectedly large; not uploading.")


def main():
    for filename, scenes in CAMPAIGNS:
        build_campaign(ROOT.parent / "videos" / filename, scenes)


if __name__ == "__main__":
    main()
