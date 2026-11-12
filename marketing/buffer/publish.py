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
FREE_QUEUE_GUARD = 9
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


def check_media(url):
    req = urllib.request.Request(url, headers={"User-Agent": "BLOFY-MediaCheck/1.0"})
    with urllib.request.urlopen(req, timeout=18) as res:
        mime = res.headers.get("Content-Type", "").lower()
        if res.status != 200 or not mime.startswith("image/"):
            raise RuntimeError("Image URL did not return a public image (" + mime + ").")
        # Do not download full media file here.
        res.read(256)


def buffer_post(ch, service, slot, dry_run=False, immediate=False):
    if (
        service == "instagram"
        and str(ch.get("type", "")).lower() == "profile"
        and not ch.get("hasActiveMemberDevice", False)
    ):
        print("SKIP instagram: personal account has no Buffer mobile app/device for required notification publishing.")
        print("Fix: connect the Buffer mobile app with notifications, or upgrade Instagram to Professional and reconnect.")
        return
    text, slug = choose_text(service, slot)
    if len(text) > 280 and service == "twitter":
        raise RuntimeError("Generated X post is longer than 280 characters.")
    posts = recent_posts(ch)
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
            return
    if len(scheduled) >= FREE_QUEUE_GUARD:
        print(f"SKIP {service}: {len(scheduled)} scheduled posts; free queue nearly full.")
        return
    if any((p.get("text") or "") == text for p in posts):
        print(f"SKIP {service}: same content already queued/sent recently.")
        return

    if service != "twitter":
        image_url = (BANK["instagramImageUrls"] if service == "instagram" else BANK["imageUrls"])[slug]
        if not dry_run:
            check_media(image_url)
        asset = ", assets: [{image: {url: " + qstring(image_url) + "}}]"
    else:
        asset = ""

    now = dt.datetime.now(UTC)
    when = (now + dt.timedelta(minutes=6)) if immediate else max(slot + dt.timedelta(minutes=30), now + dt.timedelta(minutes=6))
    # If scheduled workflow was badly delayed, don't post an extra outdated ad.
    if not immediate and service != "twitter" and when > slot + dt.timedelta(hours=2):
        print(f"SKIP {service}: scheduled job ran too late.")
        return
    if not immediate and service == "twitter" and when > slot + dt.timedelta(hours=1, minutes=50):
        print(f"SKIP {service}: scheduled job ran too late.")
        return
    due_at = when.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    # Guard against retrying the same time slot after a queue edit.
    if any(p.get("dueAt", "").startswith(when.strftime("%Y-%m-%dT%H:%M")) for p in posts):
        print(f"SKIP {service}: an existing post already has this publishing time.")
        return

    print(f"{service}: target channel {ch.get('displayName') or ch.get('name')}, due {due_at}")
    if dry_run:
        print("DRY RUN: no post created.")
        return
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("scheduled", "check", "post-x-now", "post-instagram-now", "post-tiktok-test"), default="check")
    args = parser.parse_args()
    now = dt.datetime.now(UTC)
    channels = channels_by_service()
    print("Channels found: " + ", ".join(
        str(ch.get("service")) + "/" + str(ch.get("displayName") or ch.get("name"))
        + "[type=" + str(ch.get("type", "unknown")) + ",device=" + str(ch.get("hasActiveMemberDevice", "unknown")) + "]"
        for ch in channels
    ))
    if args.mode == "check":
        for service in ("twitter", "instagram", "tiktok"):
            ch = get_channel(channels, service)
            if ch:
                posts = recent_posts(ch)
                statuses = {}
                for post in posts:
                    state = str(post.get("status", "unknown")).lower()
                    statuses[state] = statuses.get(state, 0) + 1
                print(f"{service}: accessible; posts by status: {json.dumps(statuses)}")
                for post in posts:
                    state = str(post.get("status", "")).lower()
                    if state in ("sent", "error", "sending", "scheduled"):
                        print(
                            f"POST {service}: id={post.get('id')}, status={state}, "
                            f"due={post.get('dueAt')}, sentAt={post.get('sentAt')}, "
                            f"delivery={post.get('schedulingType')}, "
                            f"notification={post.get('notificationStatus')}, "
                            f"url={post.get('externalLink') or 'unavailable'}, "
                            f"error={(post.get('error') or {}).get('message') or 'none'}"
                        )
        print("CHECK OK: no posts created.")
        return

    jobs = []
    if args.mode == "post-tiktok-test":
        # One future test date after the current Metricool TikTok campaign ends.
        slot = dt.datetime(2026, 11, 28, 15, 0, tzinfo=UTC)
        jobs.append(("tiktok", slot))
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
        if now.hour in X_SLOT_UTC:
            slot = now.replace(minute=0, second=0, microsecond=0)
            jobs.append(("twitter", slot))
        if now.hour == IMAGE_SLOT_UTC:
            # Instagram daily. TikTok starts only after the existing Metricool
            # scheduled campaign completes on 27 November 2026.
            slot = now.replace(hour=15, minute=0, second=0, microsecond=0)
            jobs.append(("instagram", slot))
            if now.astimezone(RIYADH).date() >= dt.date(2026, 11, 28):
                jobs.append(("tiktok", slot))

    errors = []
    for service, slot in jobs:
        ch = get_channel(channels, service)
        if not ch:
            continue
        try:
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
