package tv.blofy.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tv.blofy.player.playback.PlaybackUrlPolicy;

/** Device-authenticated client for the BLOFY portal. Provider credentials never leave it. */
public final class BlofyApi {
    private static final String PREFS = "blofy_native_http";
    private static final String KEY_COOKIES = "cookies";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int PLAYBACK_LINK_TIMEOUT_MS = 4_000;
    private static final Object COOKIE_LOCK = new Object();
    private static final Map<String, String> PROCESS_COOKIES = new LinkedHashMap<>();
    private static boolean cookiesLoaded;

    public static final class ApiException extends Exception {
        public final int status;
        public final String code;

        ApiException(int status, String message) {
            this(status, "", message);
        }

        ApiException(int status, String code, String message) {
            super(message == null ? "" : message);
            this.status = status;
            this.code = code == null ? "" : code;
        }
    }

    /** Disconnects a native-link request even when its worker Future is interrupted. */
    public static final class Cancellation {
        private HttpURLConnection connection;
        private boolean cancelled;

        synchronized void attach(HttpURLConnection next) throws InterruptedIOException {
            if (cancelled) {
                next.disconnect();
                throw new InterruptedIOException("playback-link-cancelled");
            }
            connection = next;
        }

        synchronized void detach(HttpURLConnection current) {
            if (connection == current) connection = null;
        }

        public synchronized void cancel() {
            cancelled = true;
            if (connection != null) connection.disconnect();
            connection = null;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final String baseUrl;
    private final String deviceId;
    private final String deviceSecret;

    public BlofyApi(Context context) {
        Context app = context.getApplicationContext();
        this.context = app;
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        baseUrl = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "");
        deviceId = DeviceIdentity.id(app);
        deviceSecret = DeviceIdentity.secret(app);
        synchronized (COOKIE_LOCK) {
            if (!cookiesLoaded) {
                loadCookiesLocked();
                cookiesLoaded = true;
            }
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String deviceId() {
        return deviceId;
    }

    public String playbackSessionKey() {
        return Integer.toHexString(cookieHeader().hashCode());
    }

    public String activationUrl(String supplied) {
        String root = supplied == null || supplied.trim().isEmpty()
                ? baseUrl + "/activate" : supplied.trim();
        if (!DeviceIdentity.hasRegisteredPublicIdentity(context)) return root;
        String result = root + (root.contains("?") ? "&" : "?")
                + "device_id=" + encode(DeviceIdentity.displayId(context));
        String token = DeviceIdentity.pairToken(context);
        return token == null || token.isEmpty()
                ? result : result + "&pair_token=" + encode(token);
    }

    public JSONObject get(String path) throws Exception {
        return path.startsWith("/api/native-link/")
                ? getPlayback(path, new Cancellation())
                : request("GET", path, null);
    }

    public JSONObject get(String path, Cancellation cancellation) throws Exception {
        return path.startsWith("/api/native-link/")
                ? getPlayback(path, cancellation)
                : request("GET", path, null, 0L, cancellation);
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body);
    }

    public JSONObject getPlayback(String path, Cancellation cancellation) throws Exception {
        return getPlayback(path, cancellation, PLAYBACK_LINK_TIMEOUT_MS);
    }

    public JSONObject getPlayback(String path, Cancellation cancellation,
                                  long totalTimeoutMs) throws Exception {
        if (!path.startsWith("/api/native-link/")) {
            throw new IllegalArgumentException("getPlayback requires a native-link path");
        }
        Cancellation active = cancellation == null ? new Cancellation() : cancellation;
        long boundedTimeout = Math.max(1500L, Math.min(8000L, totalTimeoutMs));
        long deadline = SystemClock.elapsedRealtime() + boundedTimeout;
        JSONObject result = request("GET", path, null, deadline, active);

        // v2 advertises only evidence-backed signed candidates. Resolve each BLOFY
        // grant to its provider URL without following the redirect or opening the
        // provider stream. An empty v2 list stays empty; the legacy URL must not be
        // relabelled into an unsupported TS/HLS candidate.
        if (result.optInt("contractVersion", 0) >= 2) {
            JSONArray advertised = result.optJSONArray("candidates");
            JSONArray resolved = new JSONArray();
            Map<String, String> redirects = new LinkedHashMap<>();
            Exception firstFailure = null;
            int count = advertised == null ? 0 : Math.min(advertised.length(), 8);
            for (int index = 0; index < count; index++) {
                JSONObject candidate = advertised.optJSONObject(index);
                if (candidate == null) continue;
                String nativePath = candidate.optString("nativePath", "").trim();
                if (!isSignedNativePath(nativePath)) continue;
                try {
                    String providerUrl = redirects.get(nativePath);
                    if (providerUrl == null) {
                        providerUrl = resolveNativePlaybackRedirect(nativePath, deadline, active);
                        redirects.put(nativePath, providerUrl);
                    }
                    candidate.put("url", providerUrl);
                    resolved.put(candidate);
                } catch (Exception failure) {
                    if (firstFailure == null) firstFailure = failure;
                }
            }
            result.put("candidates", resolved);
            if (count > 0 && resolved.length() == 0 && firstFailure != null) throw firstFailure;
            if (count == 0) {
                // Movies, episodes and older/opaque providers may expose only the
                // backward-compatible exact grant. Resolve that one exact URL, but
                // never manufacture alternate transports from it.
                String legacyPath = result.optString("url", "").trim();
                if (isSignedNativePath(legacyPath)) {
                    result.put("url", resolveNativePlaybackRedirect(
                            legacyPath, deadline, active));
                    result.put("mode", "direct-provider");
                }
            }
            return result;
        }

        String playbackPath = result.optString("url", "");
        if (playbackPath.startsWith("/api/native-play")) {
            result.put("url", resolveNativePlaybackRedirect(playbackPath, deadline, active));
            result.put("mode", "direct-provider");
        }
        return result;
    }

    private static boolean isSignedNativePath(String value) {
        return value != null && value.startsWith("/api/native-play?")
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                && value.indexOf('#') < 0;
    }

    @SuppressLint("ApplySharedPref")
    public void clearAllCookies() {
        synchronized (COOKIE_LOCK) {
            PROCESS_COOKIES.clear();
            preferences.edit().remove(KEY_COOKIES).commit();
        }
    }

    public static boolean isDeviceRecoveryConflict(Throwable failure) {
        if (!(failure instanceof ApiException)) return false;
        ApiException api = (ApiException) failure;
        if (api.status == 409 && "DEVICE_IDENTITY_CONFLICT".equals(api.code)) return true;
        String message = api.getMessage() == null ? "" : api.getMessage().trim();
        return api.status == 500 && api.code.isEmpty()
                && "تعذر استعادة الجهاز. تحقق من رقم الجهاز ورمز الربط.".equals(message);
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        return request(method, path, body, 0L, null);
    }

    private JSONObject request(String method, String path, JSONObject body,
                               long deadline, Cancellation cancellation) throws Exception {
        HttpURLConnection connection = open(path, method, deadline);
        if (cancellation != null) cancellation.attach(connection);
        int status = 0;
        String text;
        try {
            checkActive(deadline, cancellation);
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            status = connection.getResponseCode();
            checkActive(deadline, cancellation);
            captureCookies(connection);
            connection.setReadTimeout(remainingTimeout(deadline, READ_TIMEOUT_MS));
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            text = stream == null ? "" : readText(stream, deadline, cancellation);
        } finally {
            if (cancellation != null) cancellation.detach(connection);
            connection.disconnect();
        }

        JSONObject result;
        try {
            result = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception invalidJson) {
            throw new ApiException(status, "الخادم أعاد بيانات غير صالحة.");
        }
        if (status < 200 || status >= 300) {
            throw new ApiException(status, result.optString("errorCode", ""),
                    result.optString("error", "تعذر إكمال الطلب (" + status + ")."));
        }
        return result;
    }

    private String resolveNativePlaybackRedirect(String path, long deadline,
                                                  Cancellation cancellation) throws Exception {
        checkActive(deadline, cancellation);
        HttpURLConnection connection = open(path, "GET", deadline);
        cancellation.attach(connection);
        int status;
        String location;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "*/*");
            status = connection.getResponseCode();
            checkActive(deadline, cancellation);
            captureCookies(connection);
            location = connection.getHeaderField("Location");
        } finally {
            cancellation.detach(connection);
            connection.disconnect();
        }
        if (status < 300 || status >= 400 || location == null || location.isEmpty()) {
            throw new ApiException(status, "تعذر استخراج رابط المصدر المباشر من BLOFY.");
        }
        if (!PlaybackUrlPolicy.isSafeSource(location)) {
            throw new ApiException(403, "رابط المصدر المباشر غير صالح.");
        }
        return location.trim();
    }

    private HttpURLConnection open(String path, String method, long deadline) throws Exception {
        String target = path.startsWith("http://") || path.startsWith("https://")
                ? path : baseUrl + (path.startsWith("/") ? path : "/" + path);
        URL url = new URL(target);
        URL portal = new URL(baseUrl);
        if (!sameOrigin(url, portal)) {
            throw new ApiException(403, "تم رفض رابط خارج خادم BLOFY.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(remainingTimeout(deadline, CONNECT_TIMEOUT_MS));
        connection.setReadTimeout(remainingTimeout(deadline, READ_TIMEOUT_MS));
        connection.setUseCaches(false);
        // JSON control-plane calls never follow a redirect to a different host;
        // native-play resolves its single provider redirect explicitly below.
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "BLOFY-ANDROID-NATIVE/" + BuildConfig.VERSION_NAME);
        connection.setRequestProperty("X-Blofy-Device-Id", deviceId);
        connection.setRequestProperty("X-Blofy-Device-Key", deviceSecret);
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    private static boolean sameOrigin(URL left, URL right) {
        return left.getProtocol().equalsIgnoreCase(right.getProtocol())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right)
                && ("http".equalsIgnoreCase(left.getProtocol())
                || "https".equalsIgnoreCase(left.getProtocol()));
    }

    private static int effectivePort(URL value) {
        if (value.getPort() >= 0) return value.getPort();
        return "https".equalsIgnoreCase(value.getProtocol()) ? 443 : 80;
    }

    private void loadCookiesLocked() {
        String value = preferences.getString(KEY_COOKIES, "");
        if (value == null || value.isEmpty()) return;
        for (String part : value.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                PROCESS_COOKIES.put(part.substring(0, separator).trim(),
                        part.substring(separator + 1).trim());
            }
        }
    }

    private String cookieHeader() {
        synchronized (COOKIE_LOCK) {
            return cookieHeaderLocked();
        }
    }

    private String cookieHeaderLocked() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : PROCESS_COOKIES.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private void captureCookies(HttpURLConnection connection) {
        synchronized (COOKIE_LOCK) {
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null
                        || !"set-cookie".equals(header.getKey().toLowerCase(Locale.US))) continue;
                for (String value : header.getValue()) {
                    String pair = value.split(";", 2)[0];
                    int separator = pair.indexOf('=');
                    if (separator <= 0) continue;
                    String name = pair.substring(0, separator).trim();
                    String content = pair.substring(separator + 1).trim();
                    if (content.isEmpty()) PROCESS_COOKIES.remove(name);
                    else PROCESS_COOKIES.put(name, content);
                }
            }
            preferences.edit().putString(KEY_COOKIES, cookieHeaderLocked()).apply();
        }
    }

    private static String readText(InputStream input, long deadline,
                                   Cancellation cancellation) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                checkActive(deadline, cancellation);
                output.write(buffer, 0, read);
                if (output.size() > 64 * 1024 * 1024) {
                    throw new ApiException(413, "البيانات أكبر من الحد المسموح.");
                }
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static int remainingTimeout(long deadline, int normal) throws InterruptedIOException {
        if (deadline <= 0) return normal;
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new InterruptedIOException("playback-link-timeout");
        return (int) Math.max(1L, Math.min((long) normal, remaining));
    }

    private static void checkActive(long deadline, Cancellation cancellation)
            throws InterruptedIOException {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new InterruptedIOException("playback-link-cancelled");
        }
        if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) {
            throw new InterruptedIOException("playback-link-timeout");
        }
    }

    public static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(String.valueOf(value), "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

}
