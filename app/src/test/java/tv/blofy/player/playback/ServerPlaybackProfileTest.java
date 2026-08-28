package tv.blofy.player.playback;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ServerPlaybackProfileTest {
    private static PlaybackRoute route(String id) {
        return new PlaybackRoute(id, PlaybackRoute.Engine.MEDIA3,
                PlaybackRoute.Transport.DIRECT, "https://example.com/a",
                Collections.emptyMap());
    }

    @Test public void learnedBestEngineAndCandidateMovesAheadForProfileRevision() {
        ServerPlaybackProfile profile = new ServerPlaybackProfile();
        PlaybackRoute a = route("a");
        PlaybackRoute b = route("b");
        profile.recordFailure("key", "a");
        profile.recordSuccess("key", "b", 900);
        profile.recordSuccess("key", "b", 1100);
        List<PlaybackRoute> ranked = profile.rank("key", Arrays.asList(a, b));
        assertEquals("b", ranked.get(0).id);
        assertEquals("a", ranked.get(1).id);
    }
}
