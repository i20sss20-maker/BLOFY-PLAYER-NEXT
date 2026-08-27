package tv.blofy.player.playback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a small, deterministic route ladder without starting a player. */
public final class PlaybackResolver {
    public List<PlaybackRoute> resolve(PlaybackRequest request) throws PlaybackFailure {
        if (request == null) throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN,
                "REQUEST-NULL", "request is null", 0, false, null);

        String source = request.sourceUrl == null ? "" : request.sourceUrl.trim();
        if (source.isEmpty()) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN,
                    "SOURCE-UNRESOLVED", "direct source is not available yet", 0, true, null);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (!request.userAgent.isEmpty()) headers.put("User-Agent", request.userAgent);
        if (!request.referer.isEmpty()) headers.put("Referer", request.referer);

        List<PlaybackRoute> routes = new ArrayList<>();
        PlaybackRoute.Transport declared = transport(request.extension, source);
        routes.add(new PlaybackRoute("media3-direct", PlaybackRoute.Engine.MEDIA3,
                declared, source, headers));

        if (request.kind == PlaybackRequest.Kind.LIVE || request.kind == PlaybackRequest.Kind.PREVIEW) {
            if (declared != PlaybackRoute.Transport.HLS) {
                routes.add(new PlaybackRoute("media3-hls-compat", PlaybackRoute.Engine.MEDIA3,
                        PlaybackRoute.Transport.HLS, source, headers));
            }
            if (declared != PlaybackRoute.Transport.TS) {
                routes.add(new PlaybackRoute("media3-ts-compat", PlaybackRoute.Engine.MEDIA3,
                        PlaybackRoute.Transport.TS, source, headers));
            }
        }

        routes.add(new PlaybackRoute("vlc-fallback", PlaybackRoute.Engine.VLC,
                PlaybackRoute.Transport.COMPAT, source, headers));
        return routes;
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
