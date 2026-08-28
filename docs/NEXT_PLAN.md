# BLOFY PLAYER NEXT — Complete Plan

This repository replaces the accumulated patch-chain architecture with clean source that builds directly.

## Scope carried forward

1. UI and remote responsiveness: no network/SQLite/player preparation on the UI thread; Back/OK/D-pad remain responsive; stale work is cancelled; repeated OK is de-duplicated; loading is bounded; focus is restored after list updates.
2. Durable catalog cache for Live, Movies, Series, categories, episodes and Home. Cache opens first; background refresh never blocks the screen; offline mode shows the last valid package.
3. Pagination and lazy loading for all large catalog screens.
4. Smart preload: focused item metadata/manifest and nearby Live candidates only; never download the whole video in advance.
5. Home is cache-first and includes latest Movies, Series and Episodes.
6. One unified StreamResolver builds and classifies Live/Movie/Episode playback sources and MIME/container information.
7. One unified adaptive profile per playlist/provider + content kind + container family + device capability. No duplicate profile systems.
8. Provider capability contract: API/account state, redirects/content type/status and declared allowed output formats. Never pre-open or Range-probe media because that can consume a provider connection.
9. Compatibility layer: redirects, HTTP status, content type and allowlisted User-Agent/Referer/Origin. BLOFY cookies/device credentials never cross to a provider; future provider Cookie/Authorization support must be origin-bound before it can be enabled.
10. Smart bounded retry: re-resolve/reprepare/TS-HLS alternate route/transport alternate/LibVLC fallback. Never show the final error until the current session's bounded ladder is exhausted.
11. Diagnostics mode with typed codes and copyable report.
12. Performance telemetry: page open, category open, resolve, HTTP, prepare, player-ready, first-frame and recovery durations.
13. Resilient Movie/Series details: partial metadata/image failure cannot close the whole page.
14. Image pipeline: sampled images, memory + disk cache, placeholders, lazy loading and true cancellation of off-screen jobs.
15. 4K/HEVC device profile with hardware-first decode, decoder fallback and diagnostics.
16. Separate Live/VOD/4K/Preview buffer profiles.
17. Adaptive Live switching: warm only after a healthy previous session; hard clean switch after timeout/stall/error.
18. Preview: debounce focus, exactly one preview, cancellation, short timeout, hard reset on failed preview, no effect on fullscreen playback.
19. Typed user errors: network/server/auth/timeout/container/codec/expired source.
20. Stable signing identity forever. Expected certificate SHA-256 is stored in `config/expected-signing-cert.sha256`; private material stays out of Git.
21. Matrix tests across three provider profiles, repeated navigation, rapid D-pad, Back during loading, rapid category changes and 20–30 minute continuous playback.
22. Preserve the current BLOFY visual identity. NEXT is an architecture/stability rebuild, not a redesign.

## Additional guards proven necessary during field testing
- Live Guard: channel change cancels all previous resolve/prepare/retry/timeout work.
- Session Leak Guard: every screen/player/session releases timers, requests, player references and network resources.
- Stale Callback Guard: callbacks from an older epoch are ignored.
- Stall Watchdog: first-frame success is not enough; playback progression is monitored and bounded recovery is triggered after a real stall.
- Error Guard: one recovery pipeline and one final dialog per current playback session.
- Long-session soak tests are mandatory.

## Delivery order

### Implemented vertical slice — NEXT.1
- production-compatible device identity and registration preferences
- explicit `bootstrap?connect=0` playlist hub; no automatic server selection
- device-authenticated playlist connect and Live catalog paging through BLOFY
- cache-first Live open with staged atomic background refresh and stable provider ordering
- public catalog keeps provider credentials and media URLs out of SQLite
- cancellable `native-link v2`, opaque provider profiles and evidence-backed signed HLS/TS candidates
- exact legacy route for VOD/older providers without manufacturing alternate formats
- Media3 primary across real candidates, fail-closed LibVLC compatibility routes and persisted best route per profile revision
- shared preview/fullscreen `PlaybackCore` with Surface handoff and strict stop-before-next
- first-frame and post-first-frame stall/ended watchdog recovery with bounded re-resolve windows
- pinned Media3 FFmpeg build contract for AC3/EAC3/DTS; release packaging is mandatory in CI
- fullscreen media-playback foreground service and partial WakeLock without a second player or provider connection
- bounded local-only playback telemetry with typed stages, explicit unavailable timings and credential/URL redaction
- coarse device capabilities without hardware identifiers, model strings or fingerprints
- signed Remote Config foundation with strict ES256 verification, expiry, provider binding, anti-rollback and atomic cache; no trusted key means compiled defaults only
- verified Remote Config snapshots are frozen per session and drive User-Agent, engine/transport order, VLC/preview kill switches and bounded playback/network timeouts
- bounded Media3 `PREVIEW`, `LIVE_FAST`, `LIVE_STABLE` and `VOD` buffer profiles selected without probes or extra connections
- D-pad/OK/touch de-duplication, deep-focus restoration and paging retry

### Not yet a production-complete claim

- The default build contains no Remote Config trust key, so the implemented runtime policy remains inactive. Production activation requires an external signer, deliberate public-key provisioning and a deployed control plane.
- Provider profiles may return distinct signed endpoint hosts, but the Android client has no app-owned DoH or arbitrary DNS override. It continues to use the platform resolver.
- mpv is not implemented and is intentionally deferred until field evidence justifies a third engine.
- A CI/debug APK is not a production release. The final APK must use the existing signing identity and pass the certificate fingerprint gate.
- Provider/device matrix testing, one-connection subscriptions and 20–30 minute continuous playback soaks remain required.

### Phase 0 — compatibility foundation
- preserve `tv.blofy.player`
- lock signer fingerprint
- clean Android project and CI
- no patch scripts

### Phase 1 — playback core
- request/session/coordinator
- cancellable resolver
- typed failure model
- diagnostics timeline
- route/engine profile
- Media3 transport
- VLC fallback
- FFmpeg extension support
- live switching and stall recovery

### Phase 2 — data/cache
- encrypted playlist store
- partitioned catalog database
- staged/background sync
- pagination/lazy loading
- cache-first Home

### Phase 3 — TV UX
- existing BLOFY theme
- remote/focus controller
- Live preview
- Movies/Series/details
- search/favorites/history/settings

### Phase 4 — advanced performance
- smart preload
- image memory+disk cache
- device/4K profile
- telemetry and diagnostics UI (local typed summary implemented; wider UX remains)

### Phase 5 — acceptance
- build/lint/unit tests
- playback state-machine tests
- instrumentation/soak tests
- provider matrix tests
- signer verification
- signed APK artifact

## Release gate
A release must fail rather than ship if the expected signing certificate does not match:
`f54493426cd16e2fc67390c307937d5d2bb04f81201f4bad3355ae66ff4813a6`

Passing unit tests, lint and APK assembly is necessary but not sufficient. The signer check and the field/soak matrix above must also pass before calling a build production-ready.
