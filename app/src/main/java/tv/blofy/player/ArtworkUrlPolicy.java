package tv.blofy.player;

import java.net.URI;
import java.util.Locale;

/** Keeps provider credentials and signed URLs out of artwork memory, Intents and disk caches. */
final class ArtworkUrlPolicy {
    private static final int MAX_URL_LENGTH = 2_048;

    private ArtworkUrlPolicy() {}

    static String sanitize(String value) {
        return sanitizeForPortal(value, BuildConfig.BLOFY_BASE_URL);
    }

    static String sanitizeForPortal(String value, String portalBaseUrl) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > MAX_URL_LENGTH) return "";
        try {
            URI artwork = new URI(clean);
            if (!"https".equalsIgnoreCase(artwork.getScheme())
                    || artwork.getHost() == null || artwork.getHost().isEmpty()
                    || artwork.getRawUserInfo() != null
                    || artwork.getRawQuery() != null
                    || artwork.getRawFragment() != null) return "";

            String host = artwork.getHost().toLowerCase(Locale.US);
            URI portal = new URI(portalBaseUrl == null ? "" : portalBaseUrl.trim());
            boolean samePortal = "https".equalsIgnoreCase(portal.getScheme())
                    && portal.getHost() != null
                    && host.equals(portal.getHost().toLowerCase(Locale.US))
                    && effectivePort(artwork) == effectivePort(portal);
            boolean publicTmdb = effectivePort(artwork) == 443
                    && (host.equals("image.tmdb.org")
                    || host.endsWith(".tmdb.org")
                    || host.endsWith(".themoviedb.org"));
            return samePortal || publicTmdb ? clean : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean isSafe(String value) {
        String clean = value == null ? "" : value.trim();
        return !clean.isEmpty() && clean.equals(sanitize(clean));
    }

    private static int effectivePort(URI value) {
        return value.getPort() >= 0 ? value.getPort() : 443;
    }
}
