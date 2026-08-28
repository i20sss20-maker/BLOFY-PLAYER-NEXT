package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** One source of truth for VOD resume positions and the last watched series episode. */
public final class PlaybackProgress {
    private static final String PREFS = "blofy_positions";
    public static final long RESUME_THRESHOLD_MS = 10_000L;

    private PlaybackProgress() {}

    public static long get(Context context, String kind, String id) {
        SharedPreferences values = prefs(context);
        String scope = CatalogScope.active(context);
        String key = positionKey(scope, kind, id);
        if (values.contains(key)) return values.getLong(key, 0L);
        long position = migrateLegacyPosition(values, scope, kind, id);
        return position;
    }

    public static void save(Context context, String kind, String id, long position) {
        String scope = CatalogScope.active(context);
        prefs(context).edit().putLong(positionKey(scope, kind, id), Math.max(0L, position)).apply();
    }

    public static void clear(Context context, String kind, String id) {
        String scope = CatalogScope.active(context);
        prefs(context).edit().remove(positionKey(scope, kind, id)).apply();
    }

    static void rememberEpisode(Context context, String seriesId, String episodeId,
                                String title, String extension) {
        String key = seriesKey(CatalogScope.active(context), seriesId);
        prefs(context).edit()
                .putString(key + "_id", value(episodeId))
                .putString(key + "_title", value(title))
                .putString(key + "_extension", value(extension))
                .apply();
    }

    /** Remembers the ordered successor selected from the provider's real season graph. */
    static void rememberNextEpisode(Context context, String currentEpisodeId, String seriesId,
                                    String nextEpisodeId, String title, String extension) {
        String key = nextKey(CatalogScope.active(context), currentEpisodeId);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (value(nextEpisodeId).isEmpty()) {
            editor.remove(key + "_series").remove(key + "_id")
                    .remove(key + "_title").remove(key + "_extension").apply();
            return;
        }
        editor.putString(key + "_series", value(seriesId))
                .putString(key + "_id", value(nextEpisodeId))
                .putString(key + "_title", value(title))
                .putString(key + "_extension", value(extension))
                .apply();
    }

    static NextEpisode nextEpisode(Context context, String currentEpisodeId) {
        SharedPreferences values = prefs(context);
        String key = nextKey(CatalogScope.active(context), currentEpisodeId);
        String id = values.getString(key + "_id", "");
        if (id == null || id.isEmpty()) return null;
        return new NextEpisode(values.getString(key + "_series", ""), id,
                values.getString(key + "_title", ""),
                values.getString(key + "_extension", ""));
    }

    static EpisodeResume episode(Context context, String seriesId) {
        SharedPreferences values = prefs(context);
        String scope = CatalogScope.active(context);
        String key = seriesKey(scope, seriesId);
        String id = values.getString(key + "_id", "");
        if ((id == null || id.isEmpty()) && claimLegacyScope(values, scope)) {
            String v2 = legacyV2SeriesKey(seriesId);
            id = values.getString(v2 + "_id", "");
            if (id != null && !id.isEmpty()) {
                String title = values.getString(v2 + "_title", "");
                String extension = values.getString(v2 + "_extension", "");
                values.edit().putString(key + "_id", id)
                        .putString(key + "_title", value(title))
                        .putString(key + "_extension", value(extension)).apply();
            }
        }
        if ((id == null || id.isEmpty()) && claimLegacyScope(values, scope)) {
            String legacy = legacySeriesKey(seriesId);
            id = values.getString(legacy + "_id", "");
            if (id != null && !id.isEmpty()) {
                String title = values.getString(legacy + "_title", "");
                String extension = values.getString(legacy + "_extension", "");
                values.edit().putString(key + "_id", id)
                        .putString(key + "_title", value(title))
                        .putString(key + "_extension", value(extension))
                        .remove(legacy + "_id").remove(legacy + "_title")
                        .remove(legacy + "_extension").apply();
            }
        }
        if (id == null || id.isEmpty()) return null;
        String title = values.getString(key + "_title", "");
        String extension = values.getString(key + "_extension", "");
        long position = get(context, "episode", id);
        return new EpisodeResume(id, value(title), value(extension), position);
    }

    static String format(long positionMs) {
        return DateUtils.formatElapsedTime(Math.max(0L, positionMs) / 1000L);
    }

    static String positionKey(String scope, String kind, String id) {
        return "position_v3_" + encoded(value(scope)) + "_" + encoded(value(kind) + ":" + value(id));
    }

    private static String seriesKey(String scope, String seriesId) {
        return "series_last_v3_" + encoded(value(scope)) + "_" + encoded(value(seriesId));
    }

    private static String nextKey(String scope, String episodeId) {
        return "episode_next_v3_" + encoded(value(scope)) + "_" + encoded(value(episodeId));
    }

    static void clearAll(Context context) {
        prefs(context).edit().clear().apply();
    }

    static void clearScope(Context context, String sourceId) {
        SharedPreferences values = prefs(context);
        String encodedScope = encoded(value(sourceId));
        String positionPrefix = "position_v3_" + encodedScope + "_";
        String seriesPrefix = "series_last_v3_" + encodedScope + "_";
        String nextPrefix = "episode_next_v3_" + encodedScope + "_";
        SharedPreferences.Editor editor = values.edit();
        for (Map.Entry<String, ?> row : values.getAll().entrySet()) {
            String key = row.getKey();
            if (key.startsWith(positionPrefix) || key.startsWith(seriesPrefix)
                    || key.startsWith(nextPrefix)) editor.remove(key);
        }
        editor.apply();
    }

    private static long migrateLegacyPosition(SharedPreferences values, String scope,
                                              String kind, String id) {
        if (!claimLegacyScope(values, scope)) return 0L;
        String v2 = legacyV2PositionKey(kind, id);
        long position = values.getLong(v2, 0L);
        if (position <= 0L) position = values.getLong(legacyPositionKey(kind, id), 0L);
        if (position > 0L) {
            values.edit().putLong(positionKey(scope, kind, id), position).apply();
        }
        return position;
    }

    private static synchronized boolean claimLegacyScope(SharedPreferences values, String scope) {
        String claimed = values.getString("legacy_scope_v3", "");
        if (claimed == null || claimed.isEmpty()) {
            // Only the first active playlist inherits pre-v323 unscoped progress.
            values.edit().putString("legacy_scope_v3", value(scope)).commit();
            return true;
        }
        return claimed.equals(value(scope));
    }

    private static String legacyV2PositionKey(String kind, String id) {
        return "position_v2_" + encoded(value(kind) + ":" + value(id));
    }

    private static String legacyV2SeriesKey(String seriesId) {
        return "series_last_v2_" + encoded(value(seriesId));
    }

    private static String legacyPositionKey(String kind, String id) {
        return "position_" + Integer.toHexString((value(kind) + ":" + value(id)).hashCode());
    }

    private static String legacySeriesKey(String seriesId) {
        return "series_last_" + Integer.toHexString(value(seriesId).hashCode());
    }

    private static String encoded(String raw) {
        return Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String value(String value) { return value == null ? "" : value; }

    static final class EpisodeResume {
        final String id;
        final String title;
        final String extension;
        final long position;

        EpisodeResume(String id, String title, String extension, long position) {
            this.id = id;
            this.title = title;
            this.extension = extension;
            this.position = position;
        }

        boolean available() { return position >= RESUME_THRESHOLD_MS; }
    }

    static final class NextEpisode {
        final String seriesId;
        final String id;
        final String title;
        final String extension;

        NextEpisode(String seriesId, String id, String title, String extension) {
            this.seriesId = value(seriesId);
            this.id = value(id);
            this.title = value(title);
            this.extension = value(extension);
        }
    }
}
