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
        assertTrue(value.contains("https://***"));
    }

    @Test public void redactsHeaderMapsJsonAuthAndUnknownProviderQueries() {
        String value = DiagnosticsStore.sanitize(
                "Authorization=Bearer MAPSECRET | {\"Authorization\":\"Bearer JSONSECRET\"} "
                        + "https://provider.example/stream.ts?access_token=ACCESS&signature=SIG");
        assertFalse(value.contains("MAPSECRET"));
        assertFalse(value.contains("JSONSECRET"));
        assertFalse(value.contains("ACCESS"));
        assertFalse(value.contains("SIG"));
        assertTrue(value.contains("https://***"));
    }

    @Test public void summarizesLatestTypedPlaybackSessionOnly() {
        String report = "APP START\n"
                + "+1ms [PLAYBACK] PB session=old session_seq=1 stage=FINAL_FAILURE at_ms=9 "
                + "attempt=1 duration_ms=na available=1 detail=code:OLD\n"
                + "+2ms [PLAYBACK] PB session=new session_seq=2 stage=RESOLVE_RESULT at_ms=20 "
                + "attempt=0 duration_ms=20 available=1 detail=cycle:1\n"
                + "+3ms [PLAYBACK] PB session=new session_seq=2 stage=DNS at_ms=21 "
                + "attempt=1 duration_ms=na available=0 detail=engine:media3\n"
                + "+4ms [PLAYBACK] PB session=new session_seq=2 stage=FIRST_FRAME at_ms=140 "
                + "attempt=1 duration_ms=120 available=1 detail=estimated:false\n"
                // A stale callback may persist after the new session. Sequence, not line order,
                // owns the summary.
                + "+5ms [PLAYBACK] PB session=old session_seq=1 stage=FINAL_FAILURE at_ms=99 "
                + "attempt=1 duration_ms=na available=1 detail=code:LATE_OLD";
        String summary = DiagnosticsStore.summarize(report);
        assertTrue(summary.contains("Resolve: 20 ms"));
        assertTrue(summary.contains("DNS: غير متاح"));
        assertTrue(summary.contains("First Frame: 120 ms"));
        assertTrue(summary.contains("الحالة: يعمل"));
        assertFalse(summary.contains("OLD"));
    }
}
