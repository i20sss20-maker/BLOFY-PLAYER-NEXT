package tv.blofy.player.remoteconfig;

import android.content.Context;

import org.json.JSONObject;

import tv.blofy.player.BuildConfig;

/**
 * Verifies, anti-rolls back and atomically caches optional bootstrap/native-link config.
 * Playback retains only the immutable verified snapshot returned by this manager.
 */
public final class RemoteConfigManager {
    public interface Clock { long epochSeconds(); }

    private final RemoteConfigStore store;
    private final RemoteConfigVerifier verifier;
    private final Clock clock;
    private final int appVersionCode;

    public RemoteConfigManager(Context context) {
        this(new RemoteConfigStore(context),
                new RemoteConfigVerifier(RemoteConfigBuildTrust.trustedKeys()),
                () -> System.currentTimeMillis() / 1000L,
                BuildConfig.VERSION_CODE);
    }

    public RemoteConfigManager(RemoteConfigStore store, RemoteConfigVerifier verifier,
                               Clock clock, int appVersionCode) {
        if (store == null || verifier == null || clock == null) {
            throw new IllegalArgumentException("remote config dependencies are required");
        }
        this.store = store;
        this.verifier = verifier;
        this.clock = clock;
        this.appVersionCode = Math.max(0, appVersionCode);
    }

    /** Parses optional bootstrap.remoteConfig. Rejection never breaks device boot. */
    public synchronized UpdateResult acceptBootstrap(JSONObject bootstrap) {
        try {
            RemoteConfigEnvelope envelope = RemoteConfigEnvelope.optional(
                    bootstrap, "remoteConfig");
            return acceptGlobal(envelope);
        } catch (RemoteConfigException rejected) {
            return UpdateResult.rejected(current("", 0), rejected.reason);
        }
    }

    /** Direct envelope API for callers/tests that do not retain the bootstrap JSONObject. */
    public synchronized UpdateResult acceptGlobal(RemoteConfigEnvelope envelope) {
        if (envelope == null) return UpdateResult.unchanged(current("", 0));
        try {
            RemoteConfigPolicy policy = verified(envelope);
            if (policy.scope != RemoteConfigPolicy.Scope.GLOBAL) {
                throw reject(RemoteConfigException.Reason.SCOPE,
                        "bootstrap config must have global scope");
            }
            saveGlobal(envelope, policy);
            return UpdateResult.accepted(current("", 0));
        } catch (RemoteConfigException rejected) {
            return UpdateResult.rejected(current("", 0), rejected.reason);
        }
    }

    /** Parses optional native-link.providerConfig and binds it to the opaque profile. */
    public synchronized UpdateResult acceptNativeLink(JSONObject nativeLink) {
        String profileId = nativeLink == null ? "" : clean(nativeLink.optString("profileId", ""));
        int profileRevision = nativeLink == null ? 0
                : Math.max(0, nativeLink.optInt("profileRevision", 0));
        try {
            RemoteConfigEnvelope envelope = RemoteConfigEnvelope.optional(
                    nativeLink, "providerConfig");
            return acceptProvider(envelope, profileId, profileRevision);
        } catch (RemoteConfigException rejected) {
            return UpdateResult.rejected(current(profileId, profileRevision), rejected.reason);
        }
    }

    /** Direct envelope API bound to native-link's already validated opaque identity. */
    public synchronized UpdateResult acceptProvider(RemoteConfigEnvelope envelope,
                                                    String profileId, int profileRevision) {
        String cleanProfile = clean(profileId);
        if (envelope == null) return UpdateResult.unchanged(
                current(cleanProfile, profileRevision));
        try {
            if (!cleanProfile.matches("pp_[A-Za-z0-9_-]{8,80}") || profileRevision <= 0) {
                throw reject(RemoteConfigException.Reason.CLAIMS,
                        "native-link profile identity is invalid");
            }
            RemoteConfigPolicy policy = verified(envelope);
            if (policy.scope != RemoteConfigPolicy.Scope.PROVIDER
                    || !cleanProfile.equals(policy.profileId)
                    || profileRevision != policy.profileRevision) {
                throw reject(RemoteConfigException.Reason.SCOPE,
                        "provider config does not match native-link");
            }
            saveProvider(envelope, policy);
            return UpdateResult.accepted(current(cleanProfile, profileRevision));
        } catch (RemoteConfigException rejected) {
            return UpdateResult.rejected(current(cleanProfile, profileRevision), rejected.reason);
        }
    }

    /** Returns only verified, unexpired cached values; otherwise compiled defaults. */
    public synchronized RemoteConfigSnapshot current(String profileId, int profileRevision) {
        RemoteConfigPolicy global = loadGlobal();
        RemoteConfigPolicy provider = loadProvider(profileId, profileRevision);
        return new RemoteConfigSnapshot(global, provider);
    }

    private RemoteConfigPolicy verified(RemoteConfigEnvelope envelope)
            throws RemoteConfigException {
        return RemoteConfigPolicy.parse(verifier.verify(envelope),
                clock.epochSeconds(), appVersionCode);
    }

    private void saveGlobal(RemoteConfigEnvelope envelope, RemoteConfigPolicy incoming)
            throws RemoteConfigException {
        // Never compare against unauthenticated SharedPreferences metadata.
        loadGlobal();
        RemoteConfigStore.CachedEntry cached = store.global();
        antiRollback(cached, incoming);
        if (cached != null && cached.revision == incoming.revision) return;
        store.saveGlobal(entry(envelope, incoming));
    }

    private void saveProvider(RemoteConfigEnvelope envelope, RemoteConfigPolicy incoming)
            throws RemoteConfigException {
        RemoteConfigStore.CachedEntry cached = store.provider(incoming.profileId);
        if (cached != null && cached.profileRevision > 0) {
            loadProvider(incoming.profileId, cached.profileRevision);
        } else if (cached != null) {
            store.clearProvider(incoming.profileId);
        }
        cached = store.provider(incoming.profileId);
        antiRollback(cached, incoming);
        if (cached != null && cached.revision == incoming.revision) return;
        store.saveProvider(incoming.profileId, entry(envelope, incoming));
    }

    private static void antiRollback(RemoteConfigStore.CachedEntry cached,
                                     RemoteConfigPolicy incoming)
            throws RemoteConfigException {
        if (cached == null) return;
        if (incoming.revision < cached.revision) {
            throw reject(RemoteConfigException.Reason.ROLLBACK,
                    "remote config revision rollback rejected");
        }
        if (incoming.revision == cached.revision
                && !incoming.compactDigest.equals(cached.compactDigest)) {
            throw reject(RemoteConfigException.Reason.ROLLBACK,
                    "remote config revision is equivocal");
        }
    }

    private RemoteConfigPolicy loadGlobal() {
        RemoteConfigStore.CachedEntry cached = store.global();
        if (cached == null) return null;
        if (cached.expiresAtEpochSeconds <= clock.epochSeconds()) {
            store.clearGlobal();
            return null;
        }
        try {
            RemoteConfigPolicy policy = verified(new RemoteConfigEnvelope(cached.compactJws));
            if (policy.scope != RemoteConfigPolicy.Scope.GLOBAL
                    || !matches(cached, policy)) throw new RemoteConfigException(
                    RemoteConfigException.Reason.STORAGE, "global cache metadata mismatch");
            return policy;
        } catch (RemoteConfigException invalid) {
            store.clearGlobal();
            return null;
        }
    }

    private RemoteConfigPolicy loadProvider(String profileId, int profileRevision) {
        String cleanProfile = clean(profileId);
        if (!cleanProfile.matches("pp_[A-Za-z0-9_-]{8,80}") || profileRevision <= 0) return null;
        RemoteConfigStore.CachedEntry cached = store.provider(cleanProfile);
        if (cached == null) return null;
        if (cached.expiresAtEpochSeconds <= clock.epochSeconds()
                || cached.profileRevision != profileRevision) {
            store.clearProvider(cleanProfile);
            return null;
        }
        try {
            RemoteConfigPolicy policy = verified(new RemoteConfigEnvelope(cached.compactJws));
            if (policy.scope != RemoteConfigPolicy.Scope.PROVIDER
                    || !cleanProfile.equals(policy.profileId)
                    || profileRevision != policy.profileRevision
                    || !matches(cached, policy)) throw new RemoteConfigException(
                    RemoteConfigException.Reason.STORAGE, "provider cache metadata mismatch");
            return policy;
        } catch (RemoteConfigException invalid) {
            store.clearProvider(cleanProfile);
            return null;
        }
    }

    private static boolean matches(RemoteConfigStore.CachedEntry cached,
                                   RemoteConfigPolicy policy) {
        return cached.revision == policy.revision
                && cached.expiresAtEpochSeconds == policy.expiresAtEpochSeconds
                && cached.profileRevision == policy.profileRevision
                && cached.compactDigest.equals(policy.compactDigest);
    }

    private static RemoteConfigStore.CachedEntry entry(
            RemoteConfigEnvelope envelope, RemoteConfigPolicy policy) {
        return new RemoteConfigStore.CachedEntry(envelope.compactJws,
                policy.compactDigest, policy.revision, policy.expiresAtEpochSeconds,
                policy.profileRevision);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static RemoteConfigException reject(RemoteConfigException.Reason reason,
                                                String message) {
        return new RemoteConfigException(reason, message);
    }

    public static final class UpdateResult {
        public final RemoteConfigSnapshot snapshot;
        public final boolean accepted;
        public final boolean present;
        public final RemoteConfigException.Reason rejection;

        private UpdateResult(RemoteConfigSnapshot snapshot, boolean accepted,
                             boolean present, RemoteConfigException.Reason rejection) {
            this.snapshot = snapshot == null ? RemoteConfigSnapshot.defaults() : snapshot;
            this.accepted = accepted;
            this.present = present;
            this.rejection = rejection;
        }

        static UpdateResult accepted(RemoteConfigSnapshot snapshot) {
            return new UpdateResult(snapshot, true, true, null);
        }

        static UpdateResult unchanged(RemoteConfigSnapshot snapshot) {
            return new UpdateResult(snapshot, false, false, null);
        }

        static UpdateResult rejected(RemoteConfigSnapshot snapshot,
                                     RemoteConfigException.Reason reason) {
            return new UpdateResult(snapshot, false, true, reason);
        }
    }
}
