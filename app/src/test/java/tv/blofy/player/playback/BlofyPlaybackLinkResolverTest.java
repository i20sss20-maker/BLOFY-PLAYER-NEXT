package tv.blofy.player.playback;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BlofyPlaybackLinkResolverTest {
    @Test public void mapsKindsToProductionNativeLinkContract() {
        assertEquals("/api/native-link/live/10?ext=ts&variant=canonical",
                BlofyPlaybackLinkResolver.nativeLinkPath(
                        PlaybackRequest.Kind.LIVE, "10", "ts"));
        assertEquals("/api/native-link/live/10?ext=m3u8&variant=canonical",
                BlofyPlaybackLinkResolver.nativeLinkPath(
                        PlaybackRequest.Kind.PREVIEW, "10", "m3u8"));
        assertEquals("/api/native-link/movies/20?ext=mp4&variant=canonical",
                BlofyPlaybackLinkResolver.nativeLinkPath(
                        PlaybackRequest.Kind.MOVIE, "20", "mp4"));
        assertEquals("/api/native-link/episode/30?ext=mkv&variant=canonical",
                BlofyPlaybackLinkResolver.nativeLinkPath(
                        PlaybackRequest.Kind.EPISODE, "30", "mkv"));
    }

    @Test public void encodesIdAndExtensionAsQueryComponents() {
        assertEquals("/api/native-link/live/id+with+space?ext=m3u8%2Fbad&variant=canonical",
                BlofyPlaybackLinkResolver.nativeLinkPath(
                        PlaybackRequest.Kind.LIVE, "id with space", "m3u8/bad"));
    }

    @Test public void emptyLiveCandidatesKeepOneSignedExactSourceOnly() throws Exception {
        PlaybackRequest request = new PlaybackRequest("playlist", "",
                PlaybackRequest.Kind.LIVE, "42", "", "ts", "", "", false);

        PlaybackRequest resolved = BlofyPlaybackLinkResolver.resolveV2Contract(
                request, "pp_abcdefghijklmnopqrstuvwx", 1,
                new PlaybackConnectionPolicy(1, true, "exclusive",
                        "stop-before-next", "xtream-account"),
                Collections.emptyList(), "https://provider.example/live/42.ts", "ts",
                "", "https://provider.example/", "");

        assertEquals(1, resolved.candidates.size());
        assertEquals("legacy-exact", resolved.candidates.get(0).id);
        assertEquals(PlaybackRoute.Transport.TS, resolved.candidates.get(0).transport);
        List<PlaybackRoute> routes = new PlaybackResolver().resolve(resolved);
        assertEquals(1, routes.size());
        assertTrue(routes.stream().noneMatch(route -> route.id.contains("hls")));
    }

    @Test public void movieAndEpisodeV2RemainBackwardCompatibleExactOnly() throws Exception {
        assertExactVod(PlaybackRequest.Kind.MOVIE, "movie-7", "mp4",
                "https://provider.example/movie/7.mp4");
        assertExactVod(PlaybackRequest.Kind.EPISODE, "episode-9", "mkv",
                "https://provider.example/series/9.mkv");
    }

    private static void assertExactVod(PlaybackRequest.Kind kind, String streamId,
                                       String extension, String url) throws Exception {
        PlaybackRequest request = new PlaybackRequest("playlist", "", kind,
                streamId, "", extension, "", "", false);
        PlaybackRequest resolved = BlofyPlaybackLinkResolver.resolveV2Contract(
                request, "pp_abcdefghijklmnopqrstuvwx", 1,
                PlaybackConnectionPolicy.UNKNOWN, Collections.emptyList(),
                url, extension, "", "", "");

        assertEquals(1, resolved.candidates.size());
        assertEquals(PlaybackRoute.Transport.DIRECT, resolved.candidates.get(0).transport);
        List<PlaybackRoute> routes = new PlaybackResolver().resolve(resolved);
        assertEquals(1, routes.size());
        assertEquals(url, routes.get(0).url);
    }

    @Test public void signedExactHttpAllowsVlcWithoutGrantingItToHttps() throws Exception {
        PlaybackRequest request = new PlaybackRequest("playlist", "",
                PlaybackRequest.Kind.LIVE, "42", "", "ts", "", "", false);

        PlaybackRequest resolved = BlofyPlaybackLinkResolver.resolveV2Contract(
                request, "pp_abcdefghijklmnopqrstuvwx", 1,
                PlaybackConnectionPolicy.UNKNOWN, Collections.emptyList(),
                "http://provider.example/live/42.ts", "ts", "", "", "");

        List<PlaybackRoute> routes = new PlaybackResolver().resolve(resolved);
        assertEquals(2, routes.size());
        assertEquals(PlaybackRoute.Engine.VLC, routes.get(1).engine);
    }

    @Test public void redirectMetadataFailsClosedForVlc() throws Exception {
        List<PlaybackSourceCandidate> parsed = java.util.Arrays.asList(
                candidate("http-upgrade", "http://provider.example/live/1.ts",
                        "upgrade-only", true),
                candidate("https-disabled", "https://provider.example/live/2.ts",
                        "same-scheme", false),
                candidate("unknown-policy", "http://provider.example/live/3.ts",
                        "anything-goes", true));

        assertEquals(3, parsed.size());
        assertTrue(parsed.get(0).vlcNoDowngradeGuaranteed());
        assertTrue(!parsed.get(1).vlcNoDowngradeGuaranteed());
        assertTrue(!parsed.get(2).vlcNoDowngradeGuaranteed());
    }

    @Test public void compatibilityGrantsRequireRealJsonBooleans() {
        assertTrue(BlofyPlaybackLinkResolver.strictTrue(Boolean.TRUE));
        assertTrue(!BlofyPlaybackLinkResolver.strictTrue(Boolean.FALSE));
        assertTrue(!BlofyPlaybackLinkResolver.strictTrue("true"));
        assertTrue(!BlofyPlaybackLinkResolver.strictTrue(1));
        assertTrue(!BlofyPlaybackLinkResolver.strictTrue(null));
    }

    private static PlaybackSourceCandidate candidate(
            String id, String url, String redirectPolicy, boolean vlcCompatible) {
        return new PlaybackSourceCandidate(id, url, "ts", PlaybackRoute.Transport.TS,
                "video/mp2t", "allowed_output_formats", redirectPolicy, vlcCompatible);
    }
}
