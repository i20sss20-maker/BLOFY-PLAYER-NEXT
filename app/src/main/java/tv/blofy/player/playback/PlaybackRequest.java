package tv.blofy.player.playback;

import java.util.Locale;
import java.util.Objects;

public final class PlaybackRequest {
    public enum Kind { LIVE, MOVIE, EPISODE, PREVIEW }

    public final String playlistId;
    public final String providerHost;
    public final Kind kind;
    public final String streamId;
    public final String sourceUrl;
    public final String extension;
    public final String userAgent;
    public final String referer;
    public final boolean ultraHd;

    public PlaybackRequest(String playlistId, String providerHost, Kind kind, String streamId,
                           String sourceUrl, String extension, String userAgent, String referer,
                           boolean ultraHd) {
        this.playlistId = clean(playlistId);
        this.providerHost = clean(providerHost).toLowerCase(Locale.US);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.streamId = clean(streamId);
        this.sourceUrl = clean(sourceUrl);
        this.extension = normalizeExtension(extension);
        this.userAgent = clean(userAgent);
        this.referer = clean(referer);
        this.ultraHd = ultraHd;
        if (this.streamId.isEmpty() && this.sourceUrl.isEmpty()) {
            throw new IllegalArgumentException("streamId or sourceUrl is required");
        }
    }

    public String containerFamily() {
        if (extension.contains("m3u8") || extension.contains("hls")) return "hls";
        if (extension.equals("ts") || extension.equals("mts") || extension.equals("m2ts")) return "ts";
        return "vod";
    }

    public String profileKey(String deviceProfile) {
        return playlistId + "|" + providerHost + "|" + kind.name().toLowerCase(Locale.US)
                + "|" + containerFamily() + "|" + clean(deviceProfile);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String normalizeExtension(String value) {
        String result = clean(value).toLowerCase(Locale.US);
        while (result.startsWith(".")) result = result.substring(1);
        return result;
    }
}
