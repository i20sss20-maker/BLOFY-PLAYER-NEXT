package tv.blofy.player.playback;

import android.content.Context;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tv.blofy.player.BlofyApi;
import tv.blofy.player.BlofyApplication;
import tv.blofy.player.remoteconfig.RemoteConfigManager;
import tv.blofy.player.remoteconfig.RemoteConfigSnapshot;

/** Resolves a catalog id through BLOFY, then hands only the provider URL to an engine. */
@UnstableApi
final class BlofyPlaybackLinkResolver {
    private final BlofyApi api;
    private final RemoteConfigManager remoteConfig;

    BlofyPlaybackLinkResolver(Context context) {
        Context app = context.getApplicationContext();
        api = new BlofyApi(app);
        remoteConfig = app instanceof BlofyApplication
                ? ((BlofyApplication) app).remoteConfig()
                : new RemoteConfigManager(app);
    }

    /** Reads only the verified local cache; this method never opens a network connection. */
    PlaybackRequest withCachedConfig(PlaybackRequest request) {
        if (request == null) return null;
        return request.withRemoteConfig(remoteConfig.current(
                request.providerProfileId, request.providerProfileRevision));
    }

    PlaybackRequest resolve(PlaybackRequest request, BlofyApi.Cancellation cancellation)
            throws PlaybackFailure {
        if (request == null) {
            throw failure(PlaybackFailure.Type.UNKNOWN, "REQUEST-NULL", 0,
                    "request is null", false, null);
        }
        // PlaybackCore freezes the cached global snapshot exactly once at session start.
        // A native-link provider policy may extend that frozen view, but we never reload
        // global cache here on the resolution worker.
        PlaybackRequest configured = request;
        if (!configured.sourceUrl.isEmpty()) return configured;
        if (configured.streamId.isEmpty()) {
            throw failure(PlaybackFailure.Type.UNKNOWN, "SOURCE-UNRESOLVED", 0,
                    "stream id is missing", false, null);
        }

        String extension = configured.extension.isEmpty()
                ? defaultExtension(configured.kind) : configured.extension;
        String path = nativeLinkPath(configured.kind, configured.streamId, extension);
        try {
            long nativeLinkTimeoutMs = configured.remoteConfig.effective.timeouts
                    .nativeLinkTotalMs;
            JSONObject response = api.getPlayback(path, cancellation, nativeLinkTimeoutMs);
            if (response.optInt("contractVersion", 0) >= 2) {
                RemoteConfigManager.UpdateResult update = remoteConfig.acceptNativeLink(response);
                RemoteConfigSnapshot sessionSnapshot = configured.remoteConfig
                        .withProviderFrom(update.snapshot);
                return resolveV2(configured, response, sessionSnapshot);
            }
            String url = clean(response.optString("url", ""));
            if (!PlaybackUrlPolicy.isSafeSource(url)) {
                throw failure(PlaybackFailure.Type.UNKNOWN, "SOURCE-INVALID", 0,
                        "BLOFY did not return a provider URL", false, null);
            }
            String resolvedExtension = normalizeExtension(
                    response.optString("extension", extension));
            HeaderContract headers = headers(response, configured);
            return new PlaybackRequest(configured.playlistId, configured.providerHost,
                    configured.kind, configured.streamId, url, resolvedExtension,
                    headers.userAgent, headers.referer, headers.origin,
                    configured.ultraHd, "", 0, PlaybackConnectionPolicy.UNKNOWN,
                    java.util.Collections.emptyList(), configured.remoteConfig);
        } catch (PlaybackFailure failure) {
            throw failure;
        } catch (BlofyApi.ApiException failure) {
            PlaybackFailure.Type type = failure.status == 401 || failure.status == 403
                    ? PlaybackFailure.Type.AUTH : PlaybackFailure.Type.HTTP;
            boolean retryable = failure.status == 408 || failure.status == 429
                    || failure.status >= 500;
            throw failure(type, "LINK-HTTP-" + failure.status, failure.status,
                    failure.getMessage(), retryable, failure);
        } catch (InterruptedIOException failure) {
            String detail = clean(failure.getMessage()).toLowerCase(Locale.US);
            if (detail.contains("cancel")) throw PlaybackFailure.cancelled();
            throw failure(PlaybackFailure.Type.TIMEOUT, "LINK-TIMEOUT", 0,
                    "playback link timeout", true, failure);
        } catch (Exception failure) {
            PlaybackFailure.Type type = PlaybackFailureClassifier.networkType(failure, false);
            String code = type == PlaybackFailure.Type.TIMEOUT ? "LINK-TIMEOUT"
                    : type == PlaybackFailure.Type.DNS ? "LINK-DNS"
                    : type == PlaybackFailure.Type.TLS ? "LINK-TLS" : "LINK-NETWORK";
            throw failure(type, code,
                    0, failure.getMessage(), true, failure);
        }
    }

    private static PlaybackRequest resolveV2(PlaybackRequest request, JSONObject response,
                                             RemoteConfigSnapshot snapshot)
            throws PlaybackFailure {
        String profileId = clean(response.optString("profileId", ""));
        int profileRevision = Math.max(0, response.optInt("profileRevision", 0));
        if (!profileId.matches("pp_[A-Za-z0-9_-]{8,80}") || profileRevision <= 0) {
            throw failure(PlaybackFailure.Type.UNKNOWN, "PROFILE-CONTRACT-INVALID", 0,
                    "native-link profile metadata is invalid", false, null);
        }

        List<PlaybackSourceCandidate> candidates = candidates(response.optJSONArray("candidates"));
        HeaderContract headers = headers(response, request);
        PlaybackConnectionPolicy policy = connectionPolicy(
                response.optJSONObject("connectionPolicy"));
        return resolveV2Contract(request, profileId, profileRevision, policy, candidates,
                clean(response.optString("url", "")),
                normalizeExtension(response.optString("extension", request.extension)),
                headers.userAgent, headers.referer, headers.origin, snapshot);
    }

    static PlaybackRequest resolveV2Contract(
            PlaybackRequest request, String profileId, int profileRevision,
            PlaybackConnectionPolicy policy, List<PlaybackSourceCandidate> candidates,
            String exactUrl, String exactExtension, String userAgent,
            String referer, String origin) throws PlaybackFailure {
        return resolveV2Contract(request, profileId, profileRevision, policy, candidates,
                exactUrl, exactExtension, userAgent, referer, origin,
                request == null ? RemoteConfigSnapshot.defaults() : request.remoteConfig);
    }

    static PlaybackRequest resolveV2Contract(
            PlaybackRequest request, String profileId, int profileRevision,
            PlaybackConnectionPolicy policy, List<PlaybackSourceCandidate> candidates,
            String exactUrl, String exactExtension, String userAgent,
            String referer, String origin, RemoteConfigSnapshot snapshot)
            throws PlaybackFailure {
        List<PlaybackSourceCandidate> resolved = candidates == null
                ? new ArrayList<>() : new ArrayList<>(candidates);
        if (resolved.isEmpty()) {
            exactUrl = clean(exactUrl);
            exactExtension = normalizeExtension(exactExtension);
            PlaybackRoute.Transport exactTransport = transportForExtension(exactExtension);
            if (!PlaybackUrlPolicy.isSafeSource(exactUrl)
                    || !PlaybackSourceCandidate.transportMatchesExtension(
                    exactTransport, exactExtension)) {
                throw failure(PlaybackFailure.Type.CONTAINER,
                        "SOURCE-NO-SIGNED-CANDIDATES", 0,
                        "provider advertised no supported signed playback candidate",
                        false, null);
            }
            // Backward compatibility is one exact signed source only. It never
            // creates an HLS/TS alternative; alternates must come from v2 candidates.
            boolean cleartextInitial = exactUrl.regionMatches(
                    true, 0, "http://", 0, 7);
            resolved.add(new PlaybackSourceCandidate("legacy-exact", exactUrl,
                    exactExtension, exactTransport, "", "legacy-signed-exact",
                    cleartextInitial ? "upgrade-only" : "same-scheme",
                    cleartextInitial));
        }
        return request.withProviderContract(referer, origin, userAgent,
                profileId, profileRevision, policy, resolved, snapshot);
    }

    static List<PlaybackSourceCandidate> candidates(JSONArray rows) {
        List<PlaybackSourceCandidate> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int count = rows == null ? 0 : Math.min(rows.length(), 8);
        for (int index = 0; index < count; index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null || !strictTrue(row.opt("lazy"))) continue;
            String id = clean(row.optString("id", ""));
            String nativePath = clean(row.optString("nativePath", ""));
            String url = clean(row.optString("url", ""));
            String extension = normalizeExtension(row.optString("extension", ""));
            String evidence = clean(row.optString("evidence", ""));
            String redirectPolicy = clean(row.optString("redirectPolicy", "same-scheme"))
                    .toLowerCase(Locale.US);
            boolean knownRedirectPolicy = "same-scheme".equals(redirectPolicy)
                    || "upgrade-only".equals(redirectPolicy);
            PlaybackRoute.Transport transport = PlaybackSourceCandidate.parseTransport(
                    row.optString("transport", ""));
            if (!id.matches("[A-Za-z0-9._-]{1,80}") || !ids.add(id)
                    || !nativePath.startsWith("/api/native-play?")
                    || !PlaybackUrlPolicy.isSafeSource(url) || evidence.isEmpty()
                    || evidence.indexOf('\r') >= 0 || evidence.indexOf('\n') >= 0
                    || !PlaybackSourceCandidate.transportMatchesExtension(
                    transport, extension)) continue;
            result.add(new PlaybackSourceCandidate(id, url, extension, transport,
                    clean(row.optString("mimeType", "")), evidence,
                    knownRedirectPolicy ? redirectPolicy : "same-scheme",
                    knownRedirectPolicy && strictTrue(row.opt("vlcCompatible"))));
        }
        return result;
    }

    /** org.json accepts string booleans in optBoolean; security grants require a real Boolean. */
    static boolean strictTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static PlaybackConnectionPolicy connectionPolicy(JSONObject value) {
        if (value == null) return PlaybackConnectionPolicy.UNKNOWN;
        return new PlaybackConnectionPolicy(
                Math.max(0, value.optInt("maxConcurrentStreams", 0)),
                value.optBoolean("singleConnection", false),
                value.optString("mode", "unknown"),
                value.optString("handoff", "stop-before-next"),
                value.optString("source", "unknown"));
    }

    private static PlaybackRoute.Transport transportForExtension(String extension) {
        String ext = normalizeExtension(extension);
        if ("m3u8".equals(ext) || "hls".equals(ext)) return PlaybackRoute.Transport.HLS;
        if ("ts".equals(ext) || "mts".equals(ext) || "m2ts".equals(ext)) {
            return PlaybackRoute.Transport.TS;
        }
        return PlaybackRoute.Transport.DIRECT;
    }

    /** Only the allowlisted provider headers cross into the playback data plane. */
    private static HeaderContract headers(JSONObject response, PlaybackRequest request) {
        JSONObject supplied = response.optJSONObject("headers");
        String userAgent = request.userAgent;
        String referer = clean(response.optString("referer", request.referer));
        String origin = request.origin;
        if (supplied != null) {
            userAgent = clean(supplied.optString("User-Agent", userAgent));
            referer = clean(supplied.optString("Referer", referer));
            origin = clean(supplied.optString("Origin", origin));
            // Cookie, Authorization and every other supplied name are deliberately ignored.
        }
        return new HeaderContract(userAgent, referer, origin);
    }

    static String nativeLinkPath(PlaybackRequest.Kind kind, String streamId, String extension) {
        String type;
        if (kind == PlaybackRequest.Kind.MOVIE) type = "movies";
        else if (kind == PlaybackRequest.Kind.EPISODE) type = "episode";
        else type = "live";
        return "/api/native-link/" + BlofyApi.encode(type) + "/"
                + BlofyApi.encode(streamId) + "?ext=" + BlofyApi.encode(extension)
                + "&variant=canonical";
    }

    private static String defaultExtension(PlaybackRequest.Kind kind) {
        return kind == PlaybackRequest.Kind.LIVE || kind == PlaybackRequest.Kind.PREVIEW
                ? "ts" : "mp4";
    }

    private static String normalizeExtension(String value) {
        String extension = clean(value).toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]", "");
        return extension;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static PlaybackFailure failure(PlaybackFailure.Type type, String code,
                                           int status, String message, boolean retryable,
                                           Throwable cause) {
        return new PlaybackFailure(type, code, message, status, retryable, cause);
    }

    private static final class HeaderContract {
        final String userAgent;
        final String referer;
        final String origin;

        HeaderContract(String userAgent, String referer, String origin) {
            this.userAgent = clean(userAgent);
            this.referer = clean(referer);
            this.origin = clean(origin);
        }
    }
}
