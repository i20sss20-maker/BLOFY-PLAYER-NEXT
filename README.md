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

NEXT.1 preserves the production device identity and activation flow, lists cloud playlists without auto-connecting, publishes paged Live refreshes atomically, and opens the last complete cache immediately. `native-link v2` supplies only real signed provider candidates and an opaque adaptive profile; preview and fullscreen hand off one application-owned player connection. Media3 is primary, LibVLC is the bounded compatibility ladder, and release CI builds the pinned FFmpeg AC3/EAC3/DTS extension. Raw playlist credentials, BLOFY cookies and provider URLs are never stored in the public catalog or sent through Intents.

See `docs/NEXT_PLAN.md` and `docs/PLAYBACK_ARCHITECTURE.md`.
