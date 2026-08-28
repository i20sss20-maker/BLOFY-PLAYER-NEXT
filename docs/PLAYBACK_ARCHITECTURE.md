# NEXT Playback Architecture

## State ownership
`PlaybackCoordinator` owns exactly one `PlaybackSession`. A new request increments the epoch, cancels the previous session and invalidates all callbacks from the old epoch.

## Bounded pipeline
`IDLE -> RESOLVING -> PREPARING -> BUFFERING -> PLAYING`

Failures may move through a bounded route ladder before `FAILED`. A user/channel switch moves immediately to `CANCELLED`; a cancelled or stale session is never allowed to show a final error.

Default budgets are deliberately short and bounded by an overall startup deadline:
- resolve: 4 s
- prepare/connect: 8 s
- first frame: 10 s
- preview first frame: 4.5 s
- preview total startup: 8 s
- normal total startup: 15 s
- 4K total startup: 20 s
- stall confirmation: 4 s without playback-position progress while expected to be playing
- post-first-frame recovery: fresh 15 s (20 s for 4K), at most 3 recoveries per 5-minute window

No single retry inherits the full budget of a previous route.

## Playback request identity
Each request contains playlist scope, content kind, catalog stream ID, declared extension, sanitized User-Agent/Referer/Origin and quality hints. Public catalog rows and Intents intentionally contain no provider URL. When the URL is absent, the core resolves the ID through the device-authenticated BLOFY `native-link v2` endpoint. Each evidence-backed signed grant is opened only far enough to read BLOFY's single 302, then only the resulting provider URL enters the active in-memory session.

The adaptive key uses the opaque provider profile ID + profile revision + content family + device profile. On a new profile, every Media3 candidate precedes the LibVLC compatibility ladder. Persisted real first-frame history may later promote a proven candidate/engine combination for that exact revision.

## Engine policy
1. Media3 over every real candidate returned by the provider contract
2. LibVLC over those same exact URLs as a bounded compatibility ladder

The same URL is never relabeled and retried as contradictory HLS and TS. HTTP 404/405/410/415 may advance to a different signed candidate; 401/403/429 never amplify traffic. Network, timeout, codec, container, player and stall failures move through the bounded sequential ladder. Stop always completes before a different provider URL starts.

FFmpeg is an extension renderer for audio/container codec gaps. It is not used as another competing playback-session owner.

Preview and fullscreen bind different Surfaces to one application-owned `PlaybackCore`. A handoff reuses the same decoder/provider connection; ownership tokens and grace expiries prevent stale Activities from stealing or leaking it.

## Cancellation rules
Changing channel/content:
1. increment epoch
2. disconnect active resolver connection
3. cancel pending Futures/timers
4. stop/release unhealthy player session
5. clear session-only retry/error flags
6. start the new request

Callbacks compare their captured epoch to the active epoch before touching UI or player state.

## Stall recovery
First Frame is only a startup milestone. While PLAYING or rebuffering, the watchdog samples playback position. If position does not advance during a confirmed stall window, or a Live source ends unexpectedly, the current engine is stopped and the catalog ID is resolved again under a fresh bounded recovery deadline. Repeated failures demote the route in the adaptive profile.

## Diagnostics
Every session records monotonic timestamps and typed events: resolve-start/result, HTTP/redirect/content type, prepare, ready, first-frame, stall, route change, engine fallback, cancellation and final failure. User-facing messages are brief; diagnostic reports carry details.
