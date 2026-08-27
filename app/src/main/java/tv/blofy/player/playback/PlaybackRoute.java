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

    public PlaybackRoute(String id, Engine engine, Transport transport, String url,
                         Map<String, String> headers) {
        this.id = id == null ? "" : id;
        this.engine = engine == null ? Engine.MEDIA3 : engine;
        this.transport = transport == null ? Transport.DIRECT : transport;
        this.url = url == null ? "" : url.trim();
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                headers == null ? Collections.emptyMap() : headers));
    }
}
