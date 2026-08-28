package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Stable, non-secret partition key for catalog and personal watch state. */
final class CatalogScope {
    private static final String PREFS = "blofy_catalog_scope";
    private static final String KEY_ACTIVE = "active_source_v1";
    private static final String LEGACY = "legacy";

    private CatalogScope() {}

    static String active(Context context) {
        SharedPreferences values = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = values.getString(KEY_ACTIVE, LEGACY);
        return clean(value).isEmpty() ? LEGACY : clean(value);
    }

    static void activate(Context context, String sourceId) {
        String value = clean(sourceId);
        if (value.isEmpty()) value = LEGACY;
        // commit() is intentional: a player activity launched immediately after
        // catalog commit must observe the same partition synchronously.
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ACTIVE, value).commit();
    }

    static String forPlaylist(String playlistId) {
        String value = clean(playlistId);
        return value.isEmpty() ? "" : stable("playlist|" + value);
    }

    static String forSession(String rawIdentity) {
        return stable("session|" + clean(rawIdentity));
    }

    private static String stable(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("src-");
            for (int index = 0; index < 16; index++) {
                result.append(String.format(Locale.US, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return "src-" + Integer.toHexString(raw.hashCode());
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
