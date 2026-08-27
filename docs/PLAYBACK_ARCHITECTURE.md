# NEXT Playback Architecture

## State ownership
`PlaybackCoordinator` owns exactly one `PlaybackSession`. A new request increments the epoch, cancels the previous session and invalidates all callbacks from the old epoch.

## Bounded pipeline
`IDLE -> RESOLVING -> PREPARING -> BUFFERING -> PLAYING`

Failures may move through a bounded route ladder before `FAILED`. A user/channel switch moves immediately to `CANCELLED`; a cancelled or stale session is never allowed to show a final error.

Default budgets are deliberately short and will later become profile-aware:
- resolve: 4 s
- prepare/connect: 8 s
- first frame: 10 s
- preview first frame: 4.5 s
- stall confirmation: 4 s without playback-position progress while expected to be playing

No single retry inherits the full budget of a previous route.

## Playback request identity
Each request contains playlist/provider scope, content kind, stream id/url, declared extension, User-Agent/Referer and quality hints. The adaptive profile key includes playlist/provider + content kind + container family + device profile.

## Engine policy
1. Media3 direct provider route
2. Media3 alternate compatible transport (e.g. TS/HLS when provider allows it)
3. Media3 compatibility headers/transport
4. LibVLC fallback for container/codec/device failures

FFmpeg is an extension renderer for audio/container codec gaps. It is not used as another competing playback-session owner.

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
First Frame is only a startup milestone. While PLAYING, the watchdog samples playback position. If position does not advance during a confirmed stall window, the current route is cancelled and one bounded recovery is attempted. Repeated stall failures demote the route in the adaptive profile.

## Diagnostics
Every session records monotonic timestamps and typed events: resolve-start/result, HTTP/redirect/content type, prepare, ready, first-frame, stall, route change, engine fallback, cancellation and final failure. User-facing messages are brief; diagnostic reports carry details.
