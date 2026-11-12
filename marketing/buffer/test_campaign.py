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
    # Refilling starts at the next future slot, never creates a post in the past.
    now = dt.datetime(2026, 11, 12, 10, 24, tzinfo=dt.timezone.utc)
    slots = list(publish.planned_x_slots(now))
    assert len(slots) >= 24
    assert all(slot > now for slot in slots)
    assert len(slots) == len(set(slots))
    assert slots[0].astimezone(ZoneInfo("Asia/Riyadh")).hour == 15
    morning = dt.datetime(2026, 11, 13, 6, 10, tzinfo=dt.timezone.utc)
    morning_slot = next(publish.planned_x_slots(morning))
    assert morning_slot.astimezone(ZoneInfo("Asia/Riyadh")).hour == 9
    assert morning_slot + dt.timedelta(minutes=30) > morning
    assert publish.FREE_QUEUE_GUARD == 8, "Leave room for two manual posts in Buffer Free."
    # TikTok must alternate between image and original video from Nov 30 onward.
    start = dt.datetime(2026, 11, 30, 15, tzinfo=dt.timezone.utc)
    expected = ["video", "image", "image", "video", "image", "image", "video"]
    for i, kind in enumerate(expected):
        t, media, media_type = publish.media_for_post("tiktok", start + dt.timedelta(days=i))
        assert media_type == kind, (i, media_type, kind)
        assert media.endswith(".mp4" if kind == "video" else ".jpg")
        assert "0568941484" in t and "#BLOFY" in t
    for v in bank["videoUrls"]:
        assert "/marketing/videos/" in v["url"] and v["url"].endswith(".mp4")
    print("PASS: 360 unique X posts, queue refill, native JPEG and TikTok MP4 video rotation.")


if __name__ == "__main__":
    verify()
