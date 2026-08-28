package tv.blofy.player.remoteconfig;

import org.junit.Test;

import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tv.blofy.player.playback.LivePreviewController;
import tv.blofy.player.playback.PlaybackBudgets;
import tv.blofy.player.playback.PlaybackConnectionPolicy;
import tv.blofy.player.playback.PlaybackCore;
import tv.blofy.player.playback.PlaybackRequest;
import tv.blofy.player.playback.PlaybackResolver;
import tv.blofy.player.playback.PlaybackRoute;
import tv.blofy.player.playback.PlaybackSourceCandidate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RemoteConfigPlaybackIntegrationTest {
    private static final long NOW = 1_700_000_000L;

    @Test public void verifiedPolicyControlsRoutesHeadersBudgetsAndPreview() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        RemoteConfigManager manager = manager(pair);
        String policy = "{\"playback\":{"
                + "\"enginePreference\":\"vlc_first\","
                + "\"transportPolicy\":\"prefer_hls\","
                + "\"userAgent\":\"BLOFY-Panel-UA/7\","
                + "\"timeoutsMs\":{"
                + "\"nativeLinkTotal\":2500,\"providerConnect\":3333,"
                + "\"providerRead\":7777,\"previewFirstFrame\":3500,"
                + "\"liveFirstFrame\":9000,\"uhdFirstFrame\":13000,"
                + "\"stall\":3000,\"previewTotalStartup\":9000,"
                + "\"liveTotalStartup\":16000,\"uhdTotalStartup\":22000}},"
                + "\"features\":{\"livePreview\":false,\"vlcFallback\":true}}";
        RemoteConfigManager.UpdateResult accepted = manager.acceptGlobal(
                RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.global(
                        1, NOW, NOW + 3600, policy), pair));
        assertTrue(accepted.accepted);

        PlaybackRequest unresolved = new PlaybackRequest("playlist", "",
                PlaybackRequest.Kind.PREVIEW, "42", "", "ts",
                "Provider-UA", "", false);
        List<PlaybackSourceCandidate> candidates = Arrays.asList(
                new PlaybackSourceCandidate("ts", "http://ts.example/live/42.ts", "ts",
                        PlaybackRoute.Transport.TS, "video/mp2t", "signed",
                        "upgrade-only", true),
                new PlaybackSourceCandidate("hls", "http://hls.example/live/42.m3u8", "m3u8",
                        PlaybackRoute.Transport.HLS, "application/x-mpegURL", "signed",
                        "upgrade-only", true));
        PlaybackRequest request = unresolved.withProviderContract("", "", "Provider-UA",
                "pp_abcdefghijklmnopqrstuvwx", 2, PlaybackConnectionPolicy.UNKNOWN,
                candidates).withRemoteConfig(accepted.snapshot);

        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);
        PlaybackBudgets budgets = PlaybackBudgets.forRequest(request);

        assertEquals("hls:vlc", routes.get(0).id);
        assertEquals("ts:vlc", routes.get(1).id);
        assertEquals("hls:media3", routes.get(2).id);
        assertEquals("BLOFY-Panel-UA/7", routes.get(0).headers.get("User-Agent"));
        assertEquals(3333, routes.get(2).connectTimeoutMs);
        assertEquals(7777, routes.get(2).readTimeoutMs);
        assertEquals(2500L, budgets.resolveMs);
        assertEquals(3333L, budgets.prepareMs);
        assertEquals(7777L, budgets.readMs);
        assertEquals(3500L, budgets.firstFrameMs);
        assertEquals(3000L, budgets.stallMs);
        assertEquals(9000L, budgets.totalStartupMs);
        assertFalse(LivePreviewController.isLivePreviewEnabled(accepted.snapshot));
        assertTrue(PlaybackCore.shouldSuppressPreview(request));
    }

    @Test public void verifiedVlcKillSwitchLeavesOnlyMedia3() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        RemoteConfigManager manager = manager(pair);
        RemoteConfigSnapshot snapshot = manager.acceptGlobal(
                RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.global(
                        1, NOW, NOW + 3600,
                        "{\"features\":{\"vlcFallback\":false}}"), pair)).snapshot;
        PlaybackRequest request = new PlaybackRequest("p", "provider",
                PlaybackRequest.Kind.LIVE, "1", "http://provider/live/1.ts", "ts",
                "Provider-UA", "", false).withRemoteConfig(snapshot);

        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);

        assertEquals(1, routes.size());
        assertEquals(PlaybackRoute.Engine.MEDIA3, routes.get(0).engine);
        // A payload that did not explicitly set userAgent must preserve provider identity.
        assertEquals("Provider-UA", routes.get(0).headers.get("User-Agent"));
        assertFalse(snapshot.hasUserAgentOverride());
    }

    @Test public void nativeLinkProviderLayerKeepsSessionGlobalFrozen() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        RemoteConfigManager manager = manager(pair);
        RemoteConfigSnapshot frozen = manager.acceptGlobal(
                RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.global(
                        1, NOW, NOW + 3600,
                        "{\"playback\":{\"enginePreference\":\"media3_first\"}}"), pair))
                .snapshot;
        manager.acceptGlobal(RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.global(
                2, NOW, NOW + 3600,
                "{\"playback\":{\"enginePreference\":\"vlc_first\"}}"), pair));
        String profile = "pp_abcdefghijklmnopqrstuvwx";
        RemoteConfigEnvelope provider = RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.provider(3, NOW, NOW + 3600, profile, 7,
                        "{\"playback\":{\"transportPolicy\":\"hls_only\"}}"), pair);
        RemoteConfigManager.UpdateResult update = manager.acceptProvider(provider, profile, 7);
        RemoteConfigSnapshot session = frozen.withProviderFrom(update.snapshot);

        assertTrue(update.accepted);
        assertEquals("media3_first", session.effective.enginePreference);
        assertEquals("hls_only", session.effective.transportPolicy);
        assertEquals(1L, session.global.revision);

        PlaybackRequest request = new PlaybackRequest("playlist", "",
                PlaybackRequest.Kind.LIVE, "42", "", "ts", "", "", false)
                .withProviderContract("", "", "", profile, 7,
                        PlaybackConnectionPolicy.UNKNOWN, Arrays.asList(
                                new PlaybackSourceCandidate("ts",
                                        "http://ts.example/live/42.ts", "ts",
                                        PlaybackRoute.Transport.TS, "", "signed",
                                        "upgrade-only", true),
                                new PlaybackSourceCandidate("hls",
                                        "http://hls.example/live/42.m3u8", "m3u8",
                                        PlaybackRoute.Transport.HLS, "", "signed",
                                        "upgrade-only", true)))
                .withRemoteConfig(session);
        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);
        assertTrue(routes.stream().allMatch(route -> route.transport
                == PlaybackRoute.Transport.HLS));
        assertEquals("hls:media3", routes.get(0).id);
    }

    private static RemoteConfigManager manager(KeyPair pair) {
        return new RemoteConfigManager(new RemoteConfigStore(new MemoryBackend()),
                new RemoteConfigVerifier(RemoteConfigVerifier.oneEs256Key(
                        RemoteConfigTestSupport.KID,
                        RemoteConfigTestSupport.publicKey(pair))),
                () -> NOW, 1001001);
    }

    private static final class MemoryBackend implements RemoteConfigStore.Backend {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Long> numbers = new HashMap<>();

        @Override public String string(String key) { return strings.getOrDefault(key, ""); }
        @Override public long number(String key) { return numbers.getOrDefault(key, 0L); }

        @Override public boolean atomic(Map<String, String> nextStrings,
                                        Map<String, Long> nextNumbers,
                                        Set<String> removals) {
            for (String key : removals) { strings.remove(key); numbers.remove(key); }
            strings.putAll(nextStrings);
            numbers.putAll(nextNumbers);
            return true;
        }

        @Override public void removePrefix(String prefix) {
            for (String key : new HashSet<>(strings.keySet())) {
                if (key.startsWith(prefix)) strings.remove(key);
            }
            for (String key : new HashSet<>(numbers.keySet())) {
                if (key.startsWith(prefix)) numbers.remove(key);
            }
        }
    }
}
