package tv.blofy.player.playback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlaybackRoute {
    public enum Engine { MEDIA3, VLC }
    public enum Transport { DIRECT, HLS, TS, COMPAT }

    public final String id;
    public final Engine engine;
    public final Transport transport;
    public final String url;
    public final Map<String, String> headers;
    /**
     * True only when the signed provider contract guarantees that LibVLC may follow this
     * route without an HTTPS-to-HTTP redirect. Unknown/legacy routes stay false so the
     * compatibility engine fails closed instead of forwarding headers through opaque hops.
     */
    public final boolean vlcNoDowngradeGuaranteed;
    public final int connectTimeoutMs;
    public final int readTimeoutMs;

    public PlaybackRoute(String id, Engine engine, Transport transport, String url,
                         Map<String, String> headers) {
        this(id, engine, transport, url, headers, false, 8000, 12000);
    }

    public PlaybackRoute(String id, Engine engine, Transport transport, String url,
                         Map<String, String> headers, boolean vlcNoDowngradeGuaranteed) {
        this(id, engine, transport, url, headers, vlcNoDowngradeGuaranteed,
                8000, 12000);
    }

    PlaybackRoute(String id, Engine engine, Transport transport, String url,
                  Map<String, String> headers, boolean vlcNoDowngradeGuaranteed,
                  long connectTimeoutMs, long readTimeoutMs) {
        this.id = id == null ? "" : id;
        this.engine = engine == null ? Engine.MEDIA3 : engine;
        this.transport = transport == null ? Transport.DIRECT : transport;
        this.url = url == null ? "" : url.trim();
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                headers == null ? Collections.emptyMap() : headers));
        this.vlcNoDowngradeGuaranteed = vlcNoDowngradeGuaranteed;
        this.connectTimeoutMs = boundedTimeout(connectTimeoutMs, 8000);
        this.readTimeoutMs = boundedTimeout(readTimeoutMs, 12000);
    }

    private static int boundedTimeout(long value, int fallback) {
        if (value <= 0L || value > 60_000L) return fallback;
        return (int) value;
    }
}
