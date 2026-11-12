#!/usr/bin/env python3
"""BLOFY -> Buffer: safe free-tier scheduled publishing (stdlib only).

Required GitHub Actions secret: BUFFER_API_KEY.
The key is never written to logs or files.
"""
import argparse
import datetime as dt
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

API = "https://api.buffer.com"
RIYADH = ZoneInfo("Asia/Riyadh")
UTC = dt.timezone.utc
SOURCE = pathlib.Path(__file__).with_name("content.json")
BANK = json.loads(SOURCE.read_text(encoding="utf-8"))
FREE_QUEUE_GUARD = 8
X_SLOT_UTC = (6, 8, 10, 12, 14, 16, 18, 20)
IMAGE_SLOT_UTC = 14


def gql(query):
    token = os.environ.get("BUFFER_API_KEY", "").strip()
    if not token:
        raise RuntimeError(
            "Missing BUFFER_API_KEY. Create a Buffer API key in Settings > API and "
            "save it as a GitHub Actions repository secret. Do not paste it into code."
        )
    body = json.dumps({"query": query}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        API, body,
        {"Content-Type": "application/json",
         "Authorization": "Bearer " + token, "User-Agent": "BLOFY-Buffer-Automation/1.0"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=28) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as ex:
        # Never echo response headers or credentials.
        raise RuntimeError(f"Buffer API HTTP {ex.code}") from None
    if data.get("errors"):
        msgs = "; ".join(e.get("message", "GraphQL error") for e in data["errors"])
        raise RuntimeError("Buffer API error: " + msgs)
    return data.get("data") or {}


def qstring(value):
    # Escapes content when embedding as a GraphQL string literal.
    return json.dumps(value, ensure_ascii=False)


def channels_by_service():
    account = gql("query { account { organizations { id name } } }")
    orgs = (account.get("account") or {}).get("organizations") or []
    if not orgs:
        raise RuntimeError("No organizations returned by the linked Buffer account.")
    channels = []
    for org in orgs:
        d = gql(
            "query { channels(input: {organizationId: " + qstring(org["id"])
            + "}) { id name displayName service type isQueuePaused hasActiveMemberDevice } }"
        )
        for ch in d.get("channels") or []:
            channels.append(dict(ch, organizationId=org["id"]))
    return channels


def channel_matches(ch, service):
    actual = str(ch.get("service", "")).lower().replace("/", "")
    acceptable = {
        "twitter": {"twitter", "x", "xtwitter"},
        "instagram": {"instagram"},
        "tiktok": {"tiktok"},
    }
    if actual not in acceptable[service]:
        return False
    if service == "twitter":
        tag = (str(ch.get("name", "")) + " " + str(ch.get("displayName", ""))).lower()
        return "i20au" in tag.replace("@", "")
    if service == "tiktok":
        tag = (str(ch.get("name", "")) + " " + str(ch.get("displayName", ""))).lower()
        return "i.20as" in tag or "i20as" in tag.replace(".", "")
    return True


def get_channel(channels, service):
    matches = [ch for ch in channels if channel_matches(ch, service)]
    if len(matches) != 1:
        print(f"SKIP {service}: expected one matching connected profile; found {len(matches)}.")
        return None
    if service == "instagram" and matches[0].get("hasActiveMemberDevice") is False:
        print("NOTICE instagram: no Buffer mobile device registered for notification publishing.")
    if matches[0].get("isQueuePaused"):
        print(f"SKIP {service}: queue paused in Buffer.")
        return None
    return matches[0]


def recent_posts(ch):
    d = gql(
        "query { posts(first: 50, input: {organizationId: "
        + qstring(ch["organizationId"])
        + ", filter: {status: [scheduled, sending, sent, error], channelIds: ["
        + qstring(ch["id"])
        + "]}}) { edges { node { id text dueAt status sentAt externalLink schedulingType notificationStatus error { message } } } "
        + "pageInfo { hasNextPage endCursor } } }"
    )
    edges = ((d.get("posts") or {}).get("edges") or [])
    return [item["node"] for item in edges if item.get("node")]


def choose_text(service, slot):
    local = slot.astimezone(RIYADH)
    day = local.date().toordinal()
    if service == "twitter":
        pos = (local.hour - 9) // 2
        index = day * len(X_SLOT_UTC) + pos
        headline, detail, slug = BANK["topics"][index % len(BANK["topics"])]
        emoji = BANK["openers"][(index // len(BANK["topics"])) % len(BANK["openers"])]
        call = BANK["calls"][(index // (len(BANK["topics"]) * len(BANK["openers"]))) % len(BANK["calls"])]
        tags = BANK["hashtagPairs"][(index // 8) % len(BANK["hashtagPairs"])]
        text = f"{emoji} {headline}\n\n{detail}\n\n{call}\n\n{tags}"
        return text, slug
    options = BANK["insta"] if service == "instagram" else BANK["tiktok"]
    line, slug = options[day % len(options)]
    text = f"{line}\n\nللتفاصيل: {BANK['contact']}\n\n#BLOFY #IPTV #لوحات_توزيع"
    return text, slug


def media_for_post(service, slot):
    """Return text, visual media URL and type; use a video every third TikTok day."""
    text, slug = choose_text(service, slot)
    if service == "twitter":
        return text, None, "text"
    day = slot.astimezone(RIYADH).date()
    config = BANK["videoSchedule"]
    begin = dt.date.fromisoformat(config["startDate"])
    days_after = (day - begin).days
    if service == "tiktok" and days_after >= 0 and days_after % int(config["intervalDays"]) == 0:
        creative = BANK["videoUrls"][(days_after // int(config["intervalDays"])) % len(BANK["videoUrls"])]
        return creative["text"], creative["url"], "video"
    urls = BANK["instagramImageUrls"] if service == "instagram" else BANK["imageUrls"]
    return text, urls[slug], "image"


def check_media(url, media_type="image"):
    req = urllib.request.Request(url, headers={"User-Agent": "BLOFY-MediaCheck/1.0"})
    with urllib.request.urlopen(req, timeout=18) as res:
        mime = res.headers.get("Content-Type", "").lower()
        good_type = mime.startswith("image/") if media_type == "image" else (mime.startswith("video/") or mime == "application/octet-stream")
        if res.status != 200 or not good_type:
            raise RuntimeError(media_type + " URL did not return expected public media (" + mime + ").")
        # Do not download full media file here.
        res.read(256)


def planned_x_slots(now):
    """Upcoming local 09:00–23:00 slots; schedule posts 30 minutes after each."""
    local_now = now.astimezone(RIYADH)
    for offset in range(4):
        day = (local_now + dt.timedelta(days=offset)).date()
        for hour in range(9, 24, 2):
            slot = dt.datetime(
                day.year, day.month, day.day, hour, 0, 0, tzinfo=RIYADH
            ).astimezone(UTC)
            # The actual post goes out 30 minutes after its slot.
            # Include the current slot while it can still be scheduled safely.
            if slot + dt.timedelta(minutes=30) > now + dt.timedelta(minutes=8):
                yield slot


def refill_x(ch, now):
    """Keep up to 8 X posts queued without exhausting Buffer's 10-slot free tier."""
    posts = recent_posts(ch)
    queued = sum(
        str(p.get("status", "")).lower() in ("scheduled", "sending")
        for p in posts
    )
    if queued >= FREE_QUEUE_GUARD:
        print(f"REFILL twitter: queue already has {queued} pending items.")
        return
    created = 0
    for slot in planned_x_slots(now):
        if queued >= FREE_QUEUE_GUARD:
            break
        if buffer_post(ch, "twitter", slot, cached_posts=posts):
            created += 1
            queued += 1
    print(f"REFILL twitter: created {created} posts; {queued} posts queued.")


def buffer_post(ch, service, slot, dry_run=False, immediate=False, cached_posts=None):
    if (
        service == "instagram"
        and str(ch.get("type", "")).lower() == "profile"
        and not ch.get("hasActiveMemberDevice", False)
    ):
        print("SKIP instagram: personal account has no Buffer mobile app/device for required notification publishing.")
        print("Fix: connect the Buffer mobile app with notifications, or upgrade Instagram to Professional and reconnect.")
        return False
    text, media_url, media_type = media_for_post(service, slot)
    if len(text) > 280 and service == "twitter":
        raise RuntimeError("Generated X post is longer than 280 characters.")
    posts = recent_posts(ch) if cached_posts is None else cached_posts
    if service in ("instagram", "tiktok"):
        # Only one visual campaign post per Saudi-local date, including test posts.
        local_date = (slot + dt.timedelta(minutes=30)).astimezone(RIYADH).date()
        for p in posts:
            if str(p.get("status", "")).lower() not in ("scheduled", "sending", "sent"):
                continue
            due = p.get("dueAt")
            if due and dt.datetime.fromisoformat(due.replace("Z", "+00:00")).astimezone(RIYADH).date() == local_date:
                print(f"SKIP {service}: a post already exists for {local_date}.")
                return False
    scheduled = [p for p in posts if str(p.get("status")).lower() == "scheduled"]
    if service == "instagram":
        # Personal Instagram is notification-only. Do not silently build up
        # a backlog of manual posts if the owner has not published the last one.
        now_utc = dt.datetime.now(UTC)
        uncompleted = [
            p for p in scheduled
            if p.get("schedulingType") == "notification"
            and p.get("dueAt")
            and dt.datetime.fromisoformat(p["dueAt"].replace("Z", "+00:00")) <= now_utc
        ]
        if uncompleted:
            print(f"SKIP instagram: {len(uncompleted)} past-due mobile notification post(s) need manual publishing.")
            return False
    if len(scheduled) >= FREE_QUEUE_GUARD:
        print(f"SKIP {service}: {len(scheduled)} scheduled posts; free queue nearly full.")
        return False
    if any((p.get("text") or "") == text for p in posts):
        print(f"SKIP {service}: same content already queued/sent recently.")
        return False

    if media_type != "text":
        if not dry_run:
            check_media(media_url, media_type)
        if media_type == "video":
            asset = ", assets: [{video: {url: " + qstring(media_url) + ", metadata: {thumbnailOffset: 2000}}}]"
        else:
            asset = ", assets: [{image: {url: " + qstring(media_url) + "}}]"
    else:
        asset = ""

    now = dt.datetime.now(UTC)
    when = (now + dt.timedelta(minutes=6)) if immediate else max(slot + dt.timedelta(minutes=30), now + dt.timedelta(minutes=6))
    # If scheduled workflow was badly delayed, don't post an extra outdated ad.
    if not immediate and service != "twitter" and when > slot + dt.timedelta(hours=2):
        print(f"SKIP {service}: scheduled job ran too late.")
        return False
    if not immediate and service == "twitter" and when > slot + dt.timedelta(hours=1, minutes=50):
        print(f"SKIP {service}: scheduled job ran too late.")
        return False
    due_at = when.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    # Guard against retrying the same time slot after a queue edit.
    if any(p.get("dueAt", "").startswith(when.strftime("%Y-%m-%dT%H:%M")) for p in posts):
        print(f"SKIP {service}: an existing post already has this publishing time.")
        return False

    print(f"{service}: target channel {ch.get('displayName') or ch.get('name')}, due {due_at}")
    if dry_run:
        print("DRY RUN: no post created.")
        return False
    metadata = ", metadata: { instagram: { type: post, shouldShareToFeed: true } }" if service == "instagram" else ""
    mutation = (
        "mutation { createPost(input: {text: " + qstring(text)
        + ", channelId: " + qstring(ch["id"])
        + ", schedulingType: automatic, mode: customScheduled, dueAt: " + qstring(due_at)
        + asset + metadata
        + "}) { ... on PostActionSuccess { post { id dueAt text } } "
        + "... on MutationError { message } } }"
    )
    d = gql(mutation)
    result = d.get("createPost") or {}
    post = result.get("post")
    if service == "instagram" and not post and "require notification scheduling" in str(result.get("message", "")).lower():
        print("NOTICE instagram: personal Instagram profiles cannot auto-publish via Buffer.")
        print("NOTICE instagram: switching to mobile notification scheduling (manual final tap required).")
        d = gql(mutation.replace("schedulingType: automatic", "schedulingType: notification"))
        result = d.get("createPost") or {}
        post = result.get("post")
    if not post or not post.get("id"):
        raise RuntimeError("Buffer refused post: " + str(result.get("message", result)))
    print(f"CREATED {service}: {post['id']} scheduled for {post.get('dueAt')}")
    # Cache the confirmed API response to avoid re-fetching posts per queue item.
    # This reduces Buffer Free API use, while the next separate run re-reads live state.
    posts.append({"id": post["id"], "dueAt": post.get("dueAt") or due_at,
                  "text": text, "status": "scheduled", "schedulingType": "automatic"})
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("scheduled", "refill", "check", "post-x-now", "post-instagram-now", "post-tiktok-video-test"), default="check")
    args = parser.parse_args()
    now = dt.datetime.now(UTC)
    channels = channels_by_service()
    print("Channels found: " + ", ".join(
        str(ch.get("service")) + "/" + str(ch.get("displayName") or ch.get("name"))
        + "[type=" + str(ch.get("type", "unknown")) + ",device=" + str(ch.get("hasActiveMemberDevice", "unknown")) + "]"
        for ch in channels
    ))
    if args.mode == "check":
        summary = [
            "# BLOFY | حالة النشر على Buffer",
            "",
            "وقت الفحص: " + now.astimezone(RIYADH).strftime("%Y-%m-%d %H:%M")
            + " بتوقيت السعودية",
            "",
            "| المنصة | مجدول | منشور | أخطاء |",
            "|---|---:|---:|---:|",
        ]
        notes = []
        for service in ("twitter", "instagram", "tiktok"):
            ch = get_channel(channels, service)
            if ch:
                posts = recent_posts(ch)
                statuses = {}
                for post in posts:
                    state = str(post.get("status", "unknown")).lower()
                    statuses[state] = statuses.get(state, 0) + 1
                print(f"{service}: accessible; posts by status: {json.dumps(statuses)}")
                summary.append(
                    f"| {service} | {statuses.get('scheduled', 0)} | "
                    f"{statuses.get('sent', 0)} | {statuses.get('error', 0)} |"
                )
                if service == "instagram" and ch.get("hasActiveMemberDevice") is False:
                    notes.append("**Instagram:** الحساب شخصي دون هاتف Buffer مرتبط؛ النشر التلقائي غير مفعل.")
                for post in posts:
                    state = str(post.get("status", "")).lower()
                    if state == "error":
                        message = (post.get("error") or {}).get("message") or "unknown error"
                        notes.append(f"**{service} خطأ:** {message[:240]}")
                    if state == "sent" and post.get("externalLink"):
                        notes.append(f"**{service} منشور:** {post['externalLink']}")
                    if state in ("sent", "error", "sending", "scheduled"):
                        print(
                            f"POST {service}: id={post.get('id')}, status={state}, "
                            f"due={post.get('dueAt')}, sentAt={post.get('sentAt')}, "
                            f"delivery={post.get('schedulingType')}, "
                            f"notification={post.get('notificationStatus')}, "
                            f"url={post.get('externalLink') or 'unavailable'}, "
                            f"error={(post.get('error') or {}).get('message') or 'none'}"
                        )
        summary += ["", "## آخر النتائج", ""]
        summary += notes[:15] or ["لا توجد ملاحظات مهمة في هذا الفحص."]
        summary.append("")
        summary.append("_ملاحظة: إعلانات TikTok المجدولة في Metricool لا تظهر ضمن هذا التقرير._")
        report = os.environ.get("GITHUB_STEP_SUMMARY")
        if report:
            with open(report, "a", encoding="utf-8") as output:
                output.write("\\n".join(summary) + "\\n")
        print("CHECK OK: no posts created.")
        return

    jobs = []
    if args.mode == "post-tiktok-video-test":
        # One date after the current Metricool TikTok campaign ends, with no other Buffer post.
        slot = dt.datetime(2026, 11, 30, 15, 0, tzinfo=UTC)
        jobs.append(("tiktok", slot))
    elif args.mode == "refill":
        jobs.append(("twitter-refill", now))
    elif args.mode == "post-instagram-now":
        slot = now.replace(minute=0, second=0, microsecond=0)
        jobs.append(("instagram", slot))
    elif args.mode == "post-x-now":
        local = now.astimezone(RIYADH)
        # Use the nearest regular daily text-slot for this manual publish.
        hour = min((9, 11, 13, 15, 17, 19, 21, 23), key=lambda h: abs(h-local.hour))
        slot = local.replace(hour=hour, minute=0, second=0, microsecond=0).astimezone(UTC)
        jobs.append(("twitter", slot))
    else:
        # The scheduled GitHub Actions workflow runs on UTC clock hours.
        # Refill even if GitHub Actions started in a later UTC hour.
        jobs.append(("twitter-refill", now))
        if now.hour == IMAGE_SLOT_UTC:
            # Instagram daily. TikTok starts only after the existing Metricool
            # scheduled campaign completes on 27 November 2026.
            slot = now.replace(hour=15, minute=0, second=0, microsecond=0)
            jobs.append(("instagram", slot))
            if now.astimezone(RIYADH).date() >= dt.date(2026, 11, 28):
                jobs.append(("tiktok", slot))

    errors = []
    for service, slot in jobs:
        ch = get_channel(channels, "twitter" if service == "twitter-refill" else service)
        if not ch:
            continue
        try:
            if service == "twitter-refill":
                refill_x(ch, now)
            else:
                buffer_post(ch, service, slot, immediate=(args.mode in ("post-x-now", "post-instagram-now")))
        except Exception as ex:
            # A failed channel should not block other channels. Surface all errors.
            print(f"ERROR {service}: {ex}", file=sys.stderr)
            errors.append(service)
    if errors:
        raise SystemExit("Some scheduled posts were not created: " + ", ".join(errors))
    if not jobs:
        print("No scheduled actions for this run.")


if __name__ == "__main__":
    main()
