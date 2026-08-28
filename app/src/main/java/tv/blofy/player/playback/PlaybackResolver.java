package tv.blofy.player.playback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a small, deterministic route ladder without starting a player. */
public final class PlaybackResolver {
    private static final String DEFAULT_USER_AGENT = "BLOFY-PLAYER/2026 AndroidTV";

    public List<PlaybackRoute> resolve(PlaybackRequest request) throws PlaybackFailure {
        if (request == null) throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN,
                "REQUEST-NULL", "request is null", 0, false, null);

        String source = request.sourceUrl == null ? "" : request.sourceUrl.trim();
        if (source.isEmpty() && !request.hasProviderCandidates()) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN,
                    "SOURCE-UNRESOLVED", "direct source is not available yet", 0, true, null);
        }
        if (!source.isEmpty() && !isHttpUrl(source)) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN,
                    "SOURCE-SCHEME", "unsupported source scheme", 0, false, null);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String userAgent = request.userAgent.isEmpty()
                ? DEFAULT_USER_AGENT : safeHeaderValue(request.userAgent);
        String referer = safeHttpUrl(request.referer);
        if (!userAgent.isEmpty()) headers.put("User-Agent", userAgent);
        if (!referer.isEmpty()) headers.put("Referer", referer);
        String origin = safeOrigin(request.origin);
        if (origin.isEmpty()) origin = origin(referer);
        if (!origin.isEmpty()) headers.put("Origin", origin);

        List<PlaybackRoute> routes = new ArrayList<>();
        if (request.hasProviderCandidates()) {
            List<PlaybackSourceCandidate> usable = new ArrayList<>();
            for (PlaybackSourceCandidate candidate : request.candidates) {
                if (candidate == null || !isHttpUrl(candidate.url)
                        || !PlaybackSourceCandidate.transportMatchesExtension(
                        candidate.transport, candidate.extension)) continue;
                usable.add(candidate);
                routes.add(new PlaybackRoute(candidate.id + ":media3",
                        PlaybackRoute.Engine.MEDIA3, candidate.transport,
                        candidate.url, headers));
            }
            // Media3 remains the primary engine across every real provider
            // candidate. LibVLC is the compatibility ladder after those routes,
            // unless persisted first-frame history later promotes a proven route.
            for (PlaybackSourceCandidate candidate : usable) {
                routes.add(new PlaybackRoute(candidate.id + ":vlc",
                        PlaybackRoute.Engine.VLC, candidate.transport,
                        candidate.url, headers));
            }
            if (routes.isEmpty()) {
                throw new PlaybackFailure(PlaybackFailure.Type.CONTAINER,
                        "SOURCE-NO-USABLE-CANDIDATES", "no usable provider candidates",
                        0, false, null);
            }
            return routes;
        }

        PlaybackRoute.Transport declared = transport(request.extension, source);
        routes.add(new PlaybackRoute("media3-direct", PlaybackRoute.Engine.MEDIA3,
                declared, source, headers));
        routes.add(new PlaybackRoute("vlc-fallback", PlaybackRoute.Engine.VLC,
                declared, source, headers));
        return routes;
    }

    /** Reject unsafe HTTP header values instead of forwarding injected header lines. */
    private static String safeHeaderValue(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.indexOf('\r') >= 0 || clean.indexOf('\n') >= 0) return "";
        return clean;
    }

    private static String safeHttpUrl(String value) {
        String clean = safeHeaderValue(value);
        return isHttpUrl(clean) ? clean : "";
    }

    private static String safeOrigin(String value) {
        String clean = safeHeaderValue(value);
        if (clean.isEmpty()) return "";
        try {
            java.net.URL url = new java.net.URL(clean);
            String path = url.getPath();
            if (!isHttpUrl(clean)
                    || (!path.isEmpty() && !"/".equals(path))
                    || url.getQuery() != null || url.getRef() != null
                    || url.getUserInfo() != null) return "";
            return origin(clean);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String origin(String referer) {
        try {
            java.net.URL url = new java.net.URL(referer);
            String scheme = url.getProtocol();
            if (url.getHost().isEmpty()
                    || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) return "";
            int port = url.getPort();
            return scheme.toLowerCase(Locale.US) + "://" + url.getHost()
                    + (port > 0 ? ":" + port : "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isHttpUrl(String value) {
        try {
            java.net.URL url = new java.net.URL(value == null ? "" : value);
            String scheme = url.getProtocol();
            return !url.getHost().isEmpty()
                    && ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) {
            return false;
        }
    }

    static PlaybackRoute.Transport transport(String extension, String url) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.US);
        String source = url == null ? "" : url.toLowerCase(Locale.US);
        if (ext.contains("m3u8") || ext.contains("hls") || source.contains(".m3u8")) {
            return PlaybackRoute.Transport.HLS;
        }
        if (ext.equals("ts") || ext.equals("mts") || ext.equals("m2ts") || source.contains(".ts?")) {
            return PlaybackRoute.Transport.TS;
        }
        return PlaybackRoute.Transport.DIRECT;
    }
}
