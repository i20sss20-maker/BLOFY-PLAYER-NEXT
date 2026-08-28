package tv.blofy.player.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    public final String origin;
    public final boolean ultraHd;
    public final String providerProfileId;
    public final int providerProfileRevision;
    public final PlaybackConnectionPolicy connectionPolicy;
    public final List<PlaybackSourceCandidate> candidates;

    public PlaybackRequest(String playlistId, String providerHost, Kind kind, String streamId,
                           String sourceUrl, String extension, String userAgent, String referer,
                           boolean ultraHd) {
        this(playlistId, providerHost, kind, streamId, sourceUrl, extension,
                userAgent, referer, "", ultraHd, "", 0,
                PlaybackConnectionPolicy.UNKNOWN, Collections.emptyList());
    }

    PlaybackRequest(String playlistId, String providerHost, Kind kind, String streamId,
                    String sourceUrl, String extension, String userAgent, String referer,
                    String origin, boolean ultraHd, String providerProfileId,
                    int providerProfileRevision, PlaybackConnectionPolicy connectionPolicy,
                    List<PlaybackSourceCandidate> candidates) {
        this.playlistId = clean(playlistId);
        this.providerHost = clean(providerHost).toLowerCase(Locale.US);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.streamId = clean(streamId);
        this.sourceUrl = clean(sourceUrl);
        this.extension = normalizeExtension(extension);
        this.userAgent = clean(userAgent);
        this.referer = clean(referer);
        this.origin = clean(origin);
        this.ultraHd = ultraHd;
        this.providerProfileId = profileId(providerProfileId);
        this.providerProfileRevision = Math.max(0, providerProfileRevision);
        this.connectionPolicy = connectionPolicy == null
                ? PlaybackConnectionPolicy.UNKNOWN : connectionPolicy;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(
                candidates == null ? Collections.emptyList() : candidates));
        if (this.streamId.isEmpty() && this.sourceUrl.isEmpty() && this.candidates.isEmpty()) {
            throw new IllegalArgumentException("streamId or sourceUrl is required");
        }
    }

    PlaybackRequest withProviderContract(String resolvedReferer, String resolvedOrigin,
                                         String resolvedUserAgent, String profileId,
                                         int profileRevision,
                                         PlaybackConnectionPolicy policy,
                                         List<PlaybackSourceCandidate> resolvedCandidates) {
        List<PlaybackSourceCandidate> sources = resolvedCandidates == null
                ? Collections.emptyList() : resolvedCandidates;
        String firstUrl = sources.isEmpty() ? "" : sources.get(0).url;
        String firstExtension = sources.isEmpty() ? extension : sources.get(0).extension;
        return new PlaybackRequest(playlistId, providerHost, kind, streamId,
                firstUrl, firstExtension, resolvedUserAgent, resolvedReferer,
                resolvedOrigin, ultraHd, profileId, profileRevision, policy, sources);
    }

    public String containerFamily() {
        if (extension.contains("m3u8") || extension.contains("hls")) return "hls";
        if (extension.equals("ts") || extension.equals("mts") || extension.equals("m2ts")) return "ts";
        return "vod";
    }

    public String profileKey(String deviceProfile) {
        if (!providerProfileId.isEmpty()) {
            return providerProfileId + "|revision=" + providerProfileRevision
                    + "|" + profileFamily()
                    + "|" + clean(deviceProfile);
        }
        return playlistId + "|" + providerHost + "|" + kind.name().toLowerCase(Locale.US)
                + "|" + containerFamily() + "|" + clean(deviceProfile);
    }

    public boolean hasProviderCandidates() {
        return !candidates.isEmpty();
    }

    private String profileFamily() {
        return kind == Kind.LIVE || kind == Kind.PREVIEW ? "live" : "vod";
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String normalizeExtension(String value) {
        String result = clean(value).toLowerCase(Locale.US);
        while (result.startsWith(".")) result = result.substring(1);
        return result;
    }

    private static String profileId(String value) {
        String clean = clean(value);
        return clean.matches("pp_[A-Za-z0-9_-]{8,80}") ? clean : "";
    }
}
