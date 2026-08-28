# BLOFY PLAYER NEXT

Clean Android TV playback architecture for BLOFY PLAYER.

## Non-negotiable compatibility
- Android application id: `tv.blofy.player`
- Minimum Android: API 23
- Java: 17
- Existing update signing identity must be preserved.
- Expected signer certificate SHA-256: `f54493426cd16e2fc67390c307937d5d2bb04f81201f4bad3355ae66ff4813a6`
- Private signing keys and passwords must never be committed to this repository.

## Architecture rules
1. Source in `main` is the source that is built. No `apply_vXXX` mutation scripts.
2. Exactly one active playback session is owned by `PlaybackCoordinator`.
3. Every resolve/prepare/retry callback is scoped to a playback epoch and stale callbacks are ignored.
4. Every stage is bounded by a timeout; no unbounded spinner is allowed.
5. Playback failures are typed and recorded by diagnostics before any user-facing final error.
6. Catalog/network/player work stays off the UI thread.
7. Cache-first UI: previously synchronized catalog data opens immediately and refreshes in the background.
8. New playback behavior is covered by tests before being promoted to release.

## Current vertical slice

NEXT.2 restores the complete BLOFY TV experience on top of the NEXT core: the original purple visual identity, device ID/pairing code/QR panel, remote-first Xtream and M3U editor, server-authoritative add/edit/delete/connect, staged Live/Movies/Series import with real 0–100 progress, partitioned per-playlist cache, tile Home, details, search, favorites, history and shared Live preview/fullscreen playback. Sensitive playlist fields are never persisted in Android saved state, Autofill, catalog rows or Intents.

The NEXT.1 control plane remains underneath it. It preserves the production device identity and activation flow, lists cloud playlists without auto-connecting, and supplies only real signed provider candidates through `native-link v2`. Preview and fullscreen hand off one application-owned player connection. Media3 is primary. LibVLC is a bounded compatibility route only when the candidate contract explicitly marks it compatible and its redirect policy is safe; missing or invalid compatibility metadata fails closed. Release CI builds the pinned FFmpeg AC3/EAC3/DTS extension. Raw playlist credentials, BLOFY cookies and provider URLs are never stored in the public catalog or sent through Intents.

Fullscreen playback is guarded by a media-playback foreground service and one partial WakeLock. The service does not own a player, resolver or second provider connection; `PlaybackSessionHost` remains the single session owner. Media3 selects bounded Preview, Live Fast, Live Stable or VOD buffers from request/transport metadata without probing the provider. Playback telemetry is bounded, typed, redacted and stored locally. It does not upload provider URLs or credentials and does not open diagnostic probe connections.

The source includes an ES256-signed Remote Config runtime with strict parsing, expiry, anti-rollback and atomic caching. A verified global/provider snapshot controls User-Agent, HLS/TS selection, Media3/LibVLC order and kill switch, preview enablement and bounded network/startup/stall timeouts for one frozen playback session. Remote policy is intentionally inactive when no trusted public key is compiled into the app; the default build has no trust key and therefore uses compiled safe defaults. Supplying a signing service, private key or production Panel deployment is outside this Android repository.

## Current boundaries before production release

- Provider profiles and signed HLS/TS candidates are supported; candidates are never invented by changing a URL extension.
- Multiple distinct signed provider endpoints can be attempted sequentially. There is no app-owned DoH resolver or arbitrary DNS override yet; normal requests still use the platform DNS stack.
- mpv is not included. It remains a later compatibility-engine option after Media3/FFmpeg/LibVLC field results justify it.
- A production APK must be signed by the existing key and pass the expected certificate check. A debug artifact is not an upgrade-compatible release.
- Provider/device acceptance and 20–30 minute continuous-playback soak tests are still mandatory release gates.

See `docs/NEXT_PLAN.md` and `docs/PLAYBACK_ARCHITECTURE.md`.
