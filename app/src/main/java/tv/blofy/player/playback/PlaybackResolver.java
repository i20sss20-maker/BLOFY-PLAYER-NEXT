package tv.blofy.player.playback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tv.blofy.player.remoteconfig.RemoteConfigPolicy;

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
        if (!source.isEmpty() && !PlaybackUrlPolicy.isSafeSource(source)) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN,
                    "SOURCE-SCHEME", "unsupported source scheme", 0, false, null);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        RemoteConfigPolicy remote = request.remoteConfig.effective;
        PlaybackBudgets budgets = PlaybackBudgets.forRequest(request);
        String requestedUserAgent = request.remoteConfig.hasUserAgentOverride()
                ? remote.userAgent : request.userAgent;
        String userAgent = requestedUserAgent.isEmpty()
                ? DEFAULT_USER_AGENT : safeHeaderValue(requestedUserAgent);
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
                if (candidate == null || !PlaybackUrlPolicy.isSafeSource(candidate.url)
                        || !PlaybackSourceCandidate.transportMatchesExtension(
                        candidate.transport, candidate.extension)) continue;
                if (transportAllowed(request, remote.transportPolicy,
                        candidate.transport)) usable.add(candidate);
            }
            usable = orderByTransport(usable, remote.transportPolicy);
            if (usable.isEmpty()) {
                throw new PlaybackFailure(PlaybackFailure.Type.CONTAINER,
                        "SOURCE-NO-USABLE-CANDIDATES", "no usable provider candidates",
                        0, false, null);
            }
            boolean vlcEnabled = remote.feature("vlcFallback");
            boolean vlcFirst = vlcEnabled && "vlc_first".equals(remote.enginePreference);
            if (vlcFirst) addVlcRoutes(routes, usable, headers, budgets);
            addMedia3Routes(routes, usable, headers, budgets);
            if (!vlcFirst && vlcEnabled) addVlcRoutes(routes, usable, headers, budgets);
            return routes;
        }

        PlaybackRoute.Transport declared = transport(request.extension, source);
        if (!transportAllowed(request, remote.transportPolicy, declared)) {
            throw new PlaybackFailure(PlaybackFailure.Type.CONTAINER,
                    "SOURCE-TRANSPORT-POLICY", "source transport is disabled by policy",
                    0, false, null);
        }
        // Legacy HTTP was already cleartext before any redirect; allowing VLC
        // here cannot downgrade a confidential HTTPS request. Legacy HTTPS stays
        // Media3-only until native-link supplies an explicit no-downgrade grant.
        boolean vlcEnabled = remote.feature("vlcFallback")
                && source.regionMatches(true, 0, "http://", 0, 7);
        boolean vlcFirst = vlcEnabled && "vlc_first".equals(remote.enginePreference);
        if (vlcFirst) {
            routes.add(new PlaybackRoute("vlc-fallback", PlaybackRoute.Engine.VLC,
                    declared, source, headers, true,
                    budgets.prepareMs, budgets.readMs));
        }
        routes.add(new PlaybackRoute("media3-direct", PlaybackRoute.Engine.MEDIA3,
                declared, source, headers, false,
                budgets.prepareMs, budgets.readMs));
        if (vlcEnabled && !vlcFirst) routes.add(new PlaybackRoute(
                "vlc-fallback", PlaybackRoute.Engine.VLC,
                declared, source, headers, true,
                budgets.prepareMs, budgets.readMs));
        return routes;
    }

    private static void addMedia3Routes(List<PlaybackRoute> routes,
                                        List<PlaybackSourceCandidate> candidates,
                                        Map<String, String> headers,
                                        PlaybackBudgets budgets) {
        for (PlaybackSourceCandidate candidate : candidates) {
            routes.add(new PlaybackRoute(candidate.id + ":media3",
                    PlaybackRoute.Engine.MEDIA3, candidate.transport,
                    candidate.url, headers, false,
                    budgets.prepareMs, budgets.readMs));
        }
    }

    private static void addVlcRoutes(List<PlaybackRoute> routes,
                                     List<PlaybackSourceCandidate> candidates,
                                     Map<String, String> headers,
                                     PlaybackBudgets budgets) {
        for (PlaybackSourceCandidate candidate : candidates) {
            if (!candidate.vlcNoDowngradeGuaranteed()) continue;
            routes.add(new PlaybackRoute(candidate.id + ":vlc",
                    PlaybackRoute.Engine.VLC, candidate.transport,
                    candidate.url, headers, true,
                    budgets.prepareMs, budgets.readMs));
        }
    }

    private static List<PlaybackSourceCandidate> orderByTransport(
            List<PlaybackSourceCandidate> candidates, String policy) {
        PlaybackRoute.Transport preferred = "prefer_ts".equals(policy)
                ? PlaybackRoute.Transport.TS
                : "prefer_hls".equals(policy) ? PlaybackRoute.Transport.HLS : null;
        if (preferred == null || candidates.size() < 2) return candidates;
        List<PlaybackSourceCandidate> ordered = new ArrayList<>(candidates.size());
        for (PlaybackSourceCandidate candidate : candidates) {
            if (candidate.transport == preferred) ordered.add(candidate);
        }
        for (PlaybackSourceCandidate candidate : candidates) {
            if (candidate.transport != preferred) ordered.add(candidate);
        }
        return ordered;
    }

    private static boolean transportAllowed(PlaybackRequest request, String policy,
                                            PlaybackRoute.Transport transport) {
        if (request.kind != PlaybackRequest.Kind.LIVE
                && request.kind != PlaybackRequest.Kind.PREVIEW) return true;
        if ("ts_only".equals(policy)) return transport == PlaybackRoute.Transport.TS;
        if ("hls_only".equals(policy)) return transport == PlaybackRoute.Transport.HLS;
        return true;
    }

    /** Reject unsafe HTTP header values instead of forwarding injected header lines. */
    private static String safeHeaderValue(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.indexOf('\r') >= 0 || clean.indexOf('\n') >= 0) return "";
        return clean;
    }

    private static String safeHttpUrl(String value) {
        String clean = safeHeaderValue(value);
        return PlaybackUrlPolicy.isSafeSource(clean) ? clean : "";
    }

    private static String safeOrigin(String value) {
        String clean = safeHeaderValue(value);
        if (clean.isEmpty()) return "";
        try {
            java.net.URL url = new java.net.URL(clean);
            String path = url.getPath();
            if (!PlaybackUrlPolicy.isSafeSource(clean)
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
