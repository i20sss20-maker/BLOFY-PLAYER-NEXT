package tv.blofy.player.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticsStoreTest {
    @Test public void redactsSensitiveHeadersAndJson() {
        String value = DiagnosticsStore.sanitize(
                "Authorization: Bearer SECRET | Cookie: blofy_session=COOKIE "
                        + "| X-Blofy-Device-Key: DEVICEKEY "
                        + "| {\"deviceKey\":\"JSONKEY\",\"password\":\"PASS\"}");
        assertFalse(value.contains("SECRET"));
        assertFalse(value.contains("COOKIE"));
        assertFalse(value.contains("DEVICEKEY"));
        assertFalse(value.contains("JSONKEY"));
        assertFalse(value.contains("PASS"));
    }

    @Test public void redactsSignedQueriesUserInfoAndXtreamPaths() {
        String value = DiagnosticsStore.sanitize(
                "https://user:pass@example.com/live/name/password/7.ts "
                        + "/api/native-play?u=ENCODED&e=1&s=SIGNATURE "
                        + "https://portal.example/activate?pair_token=PAIR&token=TOKEN");
        assertFalse(value.contains("user:pass"));
        assertFalse(value.contains("/name/password/"));
        assertFalse(value.contains("ENCODED"));
        assertFalse(value.contains("SIGNATURE"));
        assertFalse(value.contains("PAIR"));
        assertFalse(value.contains("TOKEN"));
        assertTrue(value.contains("/live/***/***/"));
    }
}
