package tv.blofy.player.playback;

import java.util.Locale;

/** One evidence-backed provider URL returned by the signed native-link contract. */
public final class PlaybackSourceCandidate {
    public final String id;
    public final String url;
    public final String extension;
    public final PlaybackRoute.Transport transport;
    public final String mimeType;
    public final String evidence;

    public PlaybackSourceCandidate(String id, String url, String extension,
                                   PlaybackRoute.Transport transport, String mimeType,
                                   String evidence) {
        this.id = clean(id);
        this.url = clean(url);
        this.extension = normalizeExtension(extension);
        this.transport = transport == null ? PlaybackRoute.Transport.DIRECT : transport;
        this.mimeType = clean(mimeType);
        this.evidence = clean(evidence);
        if (this.id.isEmpty() || this.url.isEmpty()) {
            throw new IllegalArgumentException("candidate id and URL are required");
        }
    }

    static PlaybackRoute.Transport parseTransport(String value) {
        String clean = clean(value).toLowerCase(Locale.US);
        if ("hls".equals(clean)) return PlaybackRoute.Transport.HLS;
        if ("ts".equals(clean)) return PlaybackRoute.Transport.TS;
        if ("direct".equals(clean)) return PlaybackRoute.Transport.DIRECT;
        return null;
    }

    static boolean transportMatchesExtension(PlaybackRoute.Transport transport,
                                             String extension) {
        String ext = normalizeExtension(extension);
        if (transport == PlaybackRoute.Transport.HLS) {
            return "m3u8".equals(ext) || "hls".equals(ext);
        }
        if (transport == PlaybackRoute.Transport.TS) {
            return "ts".equals(ext) || "mts".equals(ext) || "m2ts".equals(ext);
        }
        return transport == PlaybackRoute.Transport.DIRECT && !ext.isEmpty();
    }

    private static String normalizeExtension(String value) {
        String result = clean(value).toLowerCase(Locale.US);
        while (result.startsWith(".")) result = result.substring(1);
        return result.replaceAll("[^a-z0-9]", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
