package tv.blofy.player.playback;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

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

    /** Keeps DNS/TLS/timeout separate so fallback never retries a decoder for them. */
    static PlaybackFailure.Type networkType(Throwable cause, boolean timeoutSignal) {
        boolean timedOut = timeoutSignal;
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof UnknownHostException) return PlaybackFailure.Type.DNS;
            if (current instanceof SSLException) return PlaybackFailure.Type.TLS;
            if (current instanceof SocketTimeoutException) timedOut = true;
            current = current.getCause();
        }
        return timedOut ? PlaybackFailure.Type.TIMEOUT : PlaybackFailure.Type.NETWORK;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
