#!/usr/bin/env python3
"""Offline regression tests for BLOFY's no-cost Buffer campaign."""
import datetime as dt
import pathlib
import sys
from zoneinfo import ZoneInfo

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import publish


def verify():
    bank = publish.BANK
    assert len(bank["topics"]) >= 72, "Editorial bank is too small for 8 X posts/day."
    assert len({tuple(x) for x in bank["topics"]}) == len(bank["topics"]), "Duplicate base X copy."
    assert bank["contact"] == "0568941484", "Contact number unexpectedly changed."
    assert bank["xAccount"] == "i20au"
    assert bank["tikTokAccount"] == "i.20as"
    required = {"01-iptv-codes", "02-server-solutions", "03-player-app",
                "04-retail-partners", "06-distributor"}
    assert required <= set(bank["imageUrls"])
    assert required <= set(bank["instagramImageUrls"])
    for image in bank["imageUrls"].values():
        assert "/marketing/images/tiktok/" in image and image.endswith(".jpg"), "TikTok should receive self-hosted JPEG."
    for image in bank["instagramImageUrls"].values():
        assert "/marketing/images/instagram/" in image and image.endswith(".jpg"), "Instagram should receive native 4:5 JPEG."
    x_texts = []
    for day in range(45):
        local_day = dt.datetime(2026, 11, 12, tzinfo=ZoneInfo("Asia/Riyadh")) + dt.timedelta(days=day)
        for hour in range(9, 24, 2):
            text, slug = publish.choose_text("twitter", local_day.replace(hour=hour))
            assert 65 <= len(text) <= 280, f"Invalid X length: {len(text)}"
            assert "0568941484" in text and "#BLOFY" in text
            assert slug in bank["imageUrls"]
            x_texts.append(text)
    assert len(x_texts) == len(set(x_texts)), "Repeated X text in the 45-day test."
    for day in range(30):
        local = dt.datetime(2026, 11, 12, tzinfo=ZoneInfo("Asia/Riyadh")) + dt.timedelta(days=day)
        for service in ("instagram", "tiktok"):
            text, slug = publish.choose_text(service, local)
            assert len(text) < 2200 and "0568941484" in text
            assert slug in bank["imageUrls"]
    print("PASS: 360 unique X posts, media references, platform limits and 30-day image campaign.")


if __name__ == "__main__":
    verify()
