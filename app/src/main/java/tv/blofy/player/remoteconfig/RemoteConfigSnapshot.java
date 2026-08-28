package tv.blofy.player.remoteconfig;

/** Immutable effective view; safe to retain for exactly one playback session. */
public final class RemoteConfigSnapshot {
    public final RemoteConfigPolicy global;
    public final RemoteConfigPolicy provider;
    public final RemoteConfigPolicy effective;
    private final RemoteConfigPolicy sourceGlobal;
    private final boolean userAgentOverride;

    RemoteConfigSnapshot(RemoteConfigPolicy global, RemoteConfigPolicy provider) {
        RemoteConfigPolicy compiled = RemoteConfigPolicy.defaults();
        this.sourceGlobal = global;
        this.userAgentOverride = (global != null && global.declares("userAgent"))
                || (provider != null && provider.declares("userAgent"));
        this.global = (global == null ? compiled : global.mergedOver(compiled));
        this.provider = provider;
        this.effective = provider == null ? this.global : provider.mergedOver(this.global);
    }

    public static RemoteConfigSnapshot defaults() {
        return new RemoteConfigSnapshot(null, null);
    }

    /** True only when a verified global/provider payload explicitly supplied a user agent. */
    public boolean hasUserAgentOverride() {
        return userAgentOverride;
    }

    /**
     * Adds only the verified provider layer from a native-link update. The global layer remains
     * the exact snapshot captured when the playback session began, even if cache changed later.
     */
    public RemoteConfigSnapshot withProviderFrom(RemoteConfigSnapshot nativeLinkUpdate) {
        return new RemoteConfigSnapshot(sourceGlobal,
                nativeLinkUpdate == null ? null : nativeLinkUpdate.provider);
    }
}
