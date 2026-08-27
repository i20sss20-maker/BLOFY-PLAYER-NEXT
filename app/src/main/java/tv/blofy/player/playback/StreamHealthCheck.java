package tv.blofy.player.playback;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight provider-route probe. Reads at most one byte and never starts a decoder. */
public final class StreamHealthCheck {
    public static final class Result {
        public final boolean reachable;
        public final int httpStatus;
        public final String contentType;
        public final String finalUrl;
        public final long elapsedMs;
        public final String error;

        Result(boolean reachable, int httpStatus, String contentType, String finalUrl,
               long elapsedMs, String error) {
            this.reachable = reachable;
            this.httpStatus = httpStatus;
            this.contentType = contentType == null ? "" : contentType;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
            this.elapsedMs = elapsedMs;
            this.error = error == null ? "" : error;
        }
    }

    public Result probe(PlaybackRoute route, int timeoutMs) {
        long started = System.nanoTime();
        if (route == null || route.url.isEmpty()) return result(false, 0, "", "", started, "empty-url");
        HttpURLConnection connection = null;
        try {
            URL current = new URL(route.url);
            Map<String, String> headers = new LinkedHashMap<>(route.headers);
            for (int redirect = 0; redirect <= 4; redirect++) {
                connection = (HttpURLConnection) current.openConnection();
                int timeout = Math.max(1000, timeoutMs);
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Range", "bytes=0-0");
                connection.setRequestProperty("Accept-Encoding", "identity");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                int status = connection.getResponseCode();
                String type = connection.getContentType();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        return result(false, status, type, current.toString(), started, "redirect-without-location");
                    }
                    URL next = new URL(current, location);
                    connection.disconnect();
                    connection = null;
                    current = next;
                    continue;
                }
                boolean ok = status >= 200 && status < 300;
                return result(ok, status, type, current.toString(), started, ok ? "" : "http-" + status);
            }
            return result(false, 0, "", current.toString(), started, "too-many-redirects");
        } catch (IOException failure) {
            return result(false, 0, "", route.url, started, failure.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Result result(boolean ok, int status, String type, String url, long startedNs, String error) {
        long elapsed = (System.nanoTime() - startedNs) / 1_000_000L;
        return new Result(ok, status, type, url, elapsed, error);
    }
}
