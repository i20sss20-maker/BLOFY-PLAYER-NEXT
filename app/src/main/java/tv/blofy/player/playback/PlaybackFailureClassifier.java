package tv.blofy.player.playback;

import java.util.Locale;

/** Pure failure mapping shared by engines and fallback policy. */
public final class PlaybackFailureClassifier {
    private PlaybackFailureClassifier() {}

    public static PlaybackFailure http(int status, String engine, Throwable cause) {
        String prefix = clean(engine).isEmpty()
                ? "PLAYER" : clean(engine).toUpperCase(Locale.ROOT);
        PlaybackFailure.Type type;
        boolean retryable;
        if (status == 401 || status == 403) {
            type = PlaybackFailure.Type.AUTH;
            retryable = false;
        } else if (status == 404 || status == 410) {
            type = PlaybackFailure.Type.SOURCE_EXPIRED;
            retryable = false;
        } else {
            type = PlaybackFailure.Type.HTTP;
            retryable = status == 408 || status == 429 || status >= 500;
        }
        return new PlaybackFailure(type, prefix + "-HTTP-" + status,
                "HTTP " + status, status, retryable, cause);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
