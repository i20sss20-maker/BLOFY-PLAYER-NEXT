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

The adaptive key uses the opaque provider profile ID + profile revision + content family + device profile. On a new profile, every Media3 candidate precedes only the LibVLC routes that passed the compatibility and redirect guards below. Persisted real first-frame history may later promote a proven candidate/engine combination for that exact revision.

## Engine and transport policy

1. Media3 over every real candidate returned by the provider contract.
2. LibVLC over the same exact URL only when the candidate explicitly declares `vlcCompatible` and its redirect policy guarantees that VLC cannot downgrade a protected request. Missing, unknown or contradictory compatibility metadata disables that VLC route.

The same URL is never relabeled and retried as contradictory HLS and TS. HLS/TS fallback means moving to another real provider-signed candidate, not rewriting an extension. HTTP 404/405/410/415 may advance to a different signed candidate; 401/403/429 never amplify traffic. Network, timeout, codec, container, player and stall failures move through the bounded sequential ladder. Stop always completes before a different provider URL starts.

Each candidate carries its declared transport, MIME evidence, sanitized headers and redirect policy. Only User-Agent, Referer and Origin are currently accepted. BLOFY session cookies and device credentials never cross to a provider. Cookie or Authorization support must remain disabled until it can be origin-bound and covered by explicit contract tests.

FFmpeg is an extension renderer for audio/container codec gaps. It is not used as another competing playback-session owner.

Media3 uses four bounded streaming buffers: low-latency Preview, Live Fast for TS, Live Stable for HLS/other Live transports, and VOD for movies/episodes. Selection uses only the already-resolved request and route. A Preview promoted to fullscreen keeps its existing player and buffer profile; it is never restarted merely to change buffering, preserving the single provider connection.

Preview and fullscreen bind different Surfaces to one application-owned `PlaybackCore`. A handoff reuses the same decoder/provider connection; ownership tokens and grace expiries prevent stale Activities from stealing or leaking it.

## Long-running fullscreen playback

Fullscreen playback starts a `mediaPlayback` foreground service with a low-importance private notification and one non-reference-counted partial WakeLock. The service deliberately owns no resolver, URL, credentials, network client or player. It protects the process-wide session lifecycle only, releases the WakeLock when that exact fullscreen session stops, and fails safely if a device policy refuses foreground-service or WakeLock access.

Preview does not start another provider connection during the fullscreen handoff. Connection-limit safety still depends on keeping `PlaybackSessionHost` as the only playback owner and completing stop-before-next-start.

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

Every session records a bounded local timeline with monotonic timestamps and typed events: resolve start/result, route readiness, DNS/connect/first-byte availability, player ready, decoder information when reported by the engine, first frame, buffering, stall, recovery and final failure. Unknown timing is recorded as unavailable instead of being guessed or measured by an extra network probe.

Telemetry is redacted before local persistence. URLs, signed queries, user-info, provider credentials, cookies, Authorization values, device keys and pairing tokens are not retained in diagnostic details. There is no telemetry upload path in the current Android source.

## Signed Remote Config boundary

Remote Config envelopes are strict compact JWS messages signed with ES256 on the exact P-256 curve. The client validates the allowlisted header, signature, claims, scope, expiry, app version, revision and provider-profile binding before atomically caching a policy. Expired, malformed, untrusted, equivocal or rolled-back policies are rejected without blocking bootstrap or playback. Playback freezes one verified snapshot per session; an accepted provider layer may extend that snapshot without replacing its global layer.

The effective snapshot controls the allowlisted User-Agent override, HLS/TS filter or preference, Media3/LibVLC order, VLC fallback and Live Preview kill switches, native-link budgets, first-frame/stall watchdogs and total startup windows. Connect/read timeouts and Origin are applied by Media3; LibVLC receives the sanitized User-Agent/Referer and remains bounded by the shared first-frame/stall watchdogs. Explicit signed preferences outrank adaptive route history. Provider-specific Preview disablement is applied after native-link and before any provider engine is opened.

The compiled trust key is the root of authority. When `BLOFY_REMOTE_CONFIG_KEY_ID` or `BLOFY_REMOTE_CONFIG_PUBLIC_KEY_SPKI` is absent or invalid, no remote policy can activate and the app continues with compiled safe defaults. The repository must never contain the matching private key.

A signed DNS-policy identifier is not a DNS implementation. The current playback path uses the Android/platform resolver and may fail over only to distinct provider-signed endpoint hosts. App-owned DoH, DNS pinning or arbitrary Panel DNS replacement is not implemented.

## Deferred and release-blocking work

- mpv is not part of the current engine ladder. Consider it only after provider field evidence shows cases Media3/FFmpeg/LibVLC cannot handle safely.
- Production Remote Config requires an external signer, a deliberately provisioned public trust key and a deployed control plane; the default Android build keeps it inactive.
- Production signing must use the existing signer whose SHA-256 is recorded in this repository.
- Three-provider testing, ARMv7/ARM64 device coverage, one-connection subscription testing and 20–30 minute continuous playback soaks remain mandatory. Unit tests and CI builds do not replace these field gates.
