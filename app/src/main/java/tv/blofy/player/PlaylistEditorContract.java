package tv.blofy.player;

import org.json.JSONObject;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Pure validation and request shaping for the server-authoritative playlist editor. */
final class PlaylistEditorContract {
    static final String XTREAM = "xtream";
    static final String M3U = "m3u";

    private PlaylistEditorContract() {}

    static Prepared prepare(String originalKind, boolean editing, String requestedKind,
                            String name, String serverUrl, String username,
                            String password, String playlistUrl) {
        String previous = normalizeKind(originalKind);
        String kind = normalizeKind(requestedKind);
        String cleanName = clean(name);
        if (cleanName.isEmpty()) cleanName = "قائمتي";
        if (cleanName.length() > 120) {
            return Prepared.invalid("اسم القائمة طويل جدًا.");
        }

        String server = clean(serverUrl);
        String user = clean(username);
        String secret = clean(password);
        String url = clean(playlistUrl);
        boolean connectionRequired = !editing || !kind.equals(previous);

        if (XTREAM.equals(kind)) {
            if (connectionRequired && (server.isEmpty() || user.isEmpty() || secret.isEmpty())) {
                return Prepared.invalid("أدخل رابط الخادم واسم المستخدم وكلمة المرور.");
            }
            if (!server.isEmpty() && !isHttpUrl(server)) {
                return Prepared.invalid("رابط الخادم يجب أن يبدأ بـ http:// أو https://.");
            }
            if (containsLineBreak(user) || containsLineBreak(secret)) {
                return Prepared.invalid("اسم المستخدم أو كلمة المرور غير صالحين.");
            }
            return new Prepared(kind, cleanName, server, user, secret, "", "");
        }

        if (connectionRequired && url.isEmpty()) {
            return Prepared.invalid("أدخل رابط M3U أو M3U8.");
        }
        if (!url.isEmpty() && !isHttpUrl(url)) {
            return Prepared.invalid("رابط M3U يجب أن يبدأ بـ http:// أو https://.");
        }
        return new Prepared(kind, cleanName, "", "", "", url, "");
    }

    static String normalizeKind(String value) {
        return M3U.equals(clean(value).toLowerCase(Locale.US)) ? M3U : XTREAM;
    }

    private static boolean isHttpUrl(String value) {
        if (containsLineBreak(value)) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().trim().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean containsLineBreak(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Prepared {
        final String kind;
        final String name;
        final String serverUrl;
        final String username;
        final String password;
        final String playlistUrl;
        final String error;

        private Prepared(String kind, String name, String serverUrl, String username,
                         String password, String playlistUrl, String error) {
            this.kind = kind;
            this.name = name;
            this.serverUrl = serverUrl;
            this.username = username;
            this.password = password;
            this.playlistUrl = playlistUrl;
            this.error = error;
        }

        static Prepared invalid(String error) {
            return new Prepared("", "", "", "", "", "", clean(error));
        }

        boolean valid() {
            return error.isEmpty();
        }

        boolean changesConnection() {
            return !serverUrl.isEmpty() || !username.isEmpty() || !password.isEmpty()
                    || !playlistUrl.isEmpty();
        }

        JSONObject body() {
            if (!valid()) throw new IllegalStateException(error);
            JSONObject body = new JSONObject();
            try {
                for (Map.Entry<String, String> field : bodyFields().entrySet()) {
                    body.put(field.getKey(), field.getValue());
                }
            } catch (Exception failure) {
                throw new IllegalStateException("تعذر تجهيز بيانات القائمة.", failure);
            }
            return body;
        }

        /** JVM-testable representation; iteration order also keeps diagnostics deterministic. */
        Map<String, String> bodyFields() {
            if (!valid()) throw new IllegalStateException(error);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", name);
            fields.put("kind", kind);
            if (XTREAM.equals(kind)) {
                if (!serverUrl.isEmpty()) fields.put("serverUrl", serverUrl);
                if (!username.isEmpty()) fields.put("username", username);
                if (!password.isEmpty()) fields.put("password", password);
            } else if (!playlistUrl.isEmpty()) {
                fields.put("url", playlistUrl);
            }
            return fields;
        }
    }
}
