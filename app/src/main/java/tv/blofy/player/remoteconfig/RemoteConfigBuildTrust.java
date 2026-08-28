package tv.blofy.player.remoteconfig;

import tv.blofy.player.BuildConfig;

/** Optional build-time trust anchor. Empty defaults intentionally trust no config. */
public final class RemoteConfigBuildTrust {
    private RemoteConfigBuildTrust() {}

    public static RemoteConfigVerifier.TrustedKeys trustedKeys() {
        return RemoteConfigVerifier.oneEs256Key(
                BuildConfig.BLOFY_REMOTE_CONFIG_KEY_ID,
                BuildConfig.BLOFY_REMOTE_CONFIG_PUBLIC_KEY_SPKI);
    }
}
