package tv.blofy.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ArtworkUrlPolicyTest {
    private static final String PORTAL = "https://player.blofy.example";

    @Test public void acceptsOnlyPublicPortalOrTmdbHttpsArtwork() {
        assertEquals("https://player.blofy.example/art/poster.webp",
                ArtworkUrlPolicy.sanitizeForPortal(
                        "https://player.blofy.example/art/poster.webp", PORTAL));
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg",
                ArtworkUrlPolicy.sanitizeForPortal(
                        "https://image.tmdb.org/t/p/w500/poster.jpg", PORTAL));
    }

    @Test public void rejectsProviderAndCredentialBearingArtwork() {
        assertRejected("http://image.tmdb.org/t/p/w500/poster.jpg");
        assertRejected("https://provider.example/live/user/pass/logo.png");
        assertRejected("https://user:pass@player.blofy.example/poster.jpg");
        assertRejected("https://player.blofy.example/poster.jpg?token=secret");
        assertRejected("https://image.tmdb.org/poster.jpg#secret");
        assertRejected("https://player.blofy.example:444/poster.jpg");
    }

    private static void assertRejected(String value) {
        assertEquals("", ArtworkUrlPolicy.sanitizeForPortal(value, PORTAL));
    }
}
