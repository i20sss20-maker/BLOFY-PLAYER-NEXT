package tv.blofy.player.playback;

import java.net.URI;
import java.util.Locale;

/** Strict URL-shape and redirect policy shared by playback entry points. */
public final class PlaybackUrlPolicy {
    private PlaybackUrlPolicy() {}

    /**
     * Allows an initial HTTP(S) source without pinning a provider domain. User info,
     * fragments, control characters and malformed authorities are never accepted.
     */
    public static boolean isSafeSource(String value) {
        return parse(value) != null;
    }

    /**
     * Resolves a provider redirect while rejecting unsafe shapes and HTTPS-to-HTTP
     * downgrade. An empty result means the redirect must not be followed.
     */
    public static String resolveRedirect(String currentUrl, String location) {
        Parsed current = parse(currentUrl);
        String nextLocation = location == null ? "" : location.trim();
        if (current == null || nextLocation.isEmpty() || hasControl(location)) return "";
        try {
            URI targetUri = current.uri.resolve(new URI(nextLocation));
            Parsed target = parse(targetUri.toASCIIString());
            if (target == null) return "";
            if ("https".equals(current.scheme) && "http".equals(target.scheme)) return "";
            return targetUri.toASCIIString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Origin-equivalent endpoint comparison; paths and query grants are ignored. */
    static boolean sameEndpoint(String left, String right) {
        String first = endpointKey(left);
        return !first.isEmpty() && first.equals(endpointKey(right));
    }

    /** Package-private because provider origins must never enter logs or persistence. */
    static String endpointKey(String value) {
        // TODO(native-link endpointId): use a backend-issued opaque endpoint id
        // once it is part of PlaybackRoute. Until then the in-memory origin is
        // the only safe way to avoid retrying one DNS/TLS endpoint via two paths.
        Parsed parsed = parse(value);
        if (parsed == null) return "";
        int port = parsed.uri.getPort();
        if (port < 0) port = "https".equals(parsed.scheme) ? 443 : 80;
        return parsed.scheme + "|" + parsed.host + "|" + port;
    }

    private static Parsed parse(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || hasControl(clean)) return null;
        try {
            URI uri = new URI(clean);
            String scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.US);
            String host = uri.getHost() == null
                    ? "" : uri.getHost().toLowerCase(Locale.US);
            if (uri.isOpaque() || host.isEmpty()
                    || !("http".equals(scheme) || "https".equals(scheme))
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
            return new Parsed(uri, scheme, host);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasControl(String value) {
        if (value == null) return true;
        for (int index = 0; index < value.length(); index++) {
            char part = value.charAt(index);
            if (part <= 0x1f || part == 0x7f) return true;
        }
        return false;
    }

    private static final class Parsed {
        final URI uri;
        final String scheme;
        final String host;

        Parsed(URI uri, String scheme, String host) {
            this.uri = uri;
            this.scheme = scheme;
            this.host = host;
        }
    }
}
