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
    public final String redirectPolicy;
    public final boolean vlcCompatible;

    public PlaybackSourceCandidate(String id, String url, String extension,
                                   PlaybackRoute.Transport transport, String mimeType,
                                   String evidence) {
        this(id, url, extension, transport, mimeType, evidence,
                "same-scheme", false);
    }

    public PlaybackSourceCandidate(String id, String url, String extension,
                                   PlaybackRoute.Transport transport, String mimeType,
                                   String evidence, String redirectPolicy,
                                   boolean vlcCompatible) {
        this.id = clean(id);
        this.url = clean(url);
        this.extension = normalizeExtension(extension);
        this.transport = transport == null ? PlaybackRoute.Transport.DIRECT : transport;
        this.mimeType = clean(mimeType);
        this.evidence = clean(evidence);
        String redirects = clean(redirectPolicy).toLowerCase(Locale.US);
        boolean knownRedirectPolicy = "upgrade-only".equals(redirects)
                || "same-scheme".equals(redirects);
        this.redirectPolicy = "upgrade-only".equals(redirects)
                ? "upgrade-only" : "same-scheme";
        this.vlcCompatible = knownRedirectPolicy && vlcCompatible;
        if (this.id.isEmpty() || this.url.isEmpty()) {
            throw new IllegalArgumentException("candidate id and URL are required");
        }
    }

    boolean vlcNoDowngradeGuaranteed() {
        if (!vlcCompatible) return false;
        if ("same-scheme".equals(redirectPolicy)) return true;
        return "upgrade-only".equals(redirectPolicy)
                && url.regionMatches(true, 0, "http://", 0, 7);
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
        // A signed provider contract may explicitly prove a DIRECT endpoint whose
        // path intentionally has no suffix (common with tokenised VOD/live gateways).
        // Do not invent or strip extensions locally: accept the exact evidence-backed
        // URL and let its stable candidate ID participate in persistent route learning.
        return transport == PlaybackRoute.Transport.DIRECT;
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
