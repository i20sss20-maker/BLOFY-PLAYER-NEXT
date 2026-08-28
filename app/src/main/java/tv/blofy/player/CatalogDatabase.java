package tv.blofy.player;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Partitioned local catalog with atomic, staged package replacement. */
final class CatalogDatabase extends SQLiteOpenHelper {
    private static final String NAME = "blofy_catalog.db";
    private static final int VERSION = 8;
    private static final String MEDIA_COLUMNS =
            "m.id,m.name,m.image,m.backdrop,m.category_id,m.rating,m.year,m.extension,m.type," +
                    "m.release_date,m.rating_source,m.updated_at";

    private final Context context;
    private String activeSource;
    private String importToken;
    private String importSource;

    CatalogDatabase(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
        this.context = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase database) {
        createCoreTables(database);
        createIndexes(database);
        createFts(database);
        createStagingTables(database);
    }

    @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            database.delete("categories", null, null);
            database.delete("media", null, null);
        }
        if (oldVersion < 4) {
            try { database.execSQL("ALTER TABLE categories ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { database.execSQL("ALTER TABLE media ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            database.delete("categories", null, null);
            database.delete("media", null, null);
        }
        if (oldVersion < 5) {
            try { database.execSQL("ALTER TABLE media ADD COLUMN release_date TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { database.execSQL("ALTER TABLE media ADD COLUMN rating_source TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { database.execSQL("ALTER TABLE media ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
        }
        if (oldVersion < 6) {
            try { database.execSQL("ALTER TABLE media ADD COLUMN search_name TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            database.delete("categories", null, null);
            database.delete("media", null, null);
        }
        if (oldVersion < 7) migrateToPartitionedSchema(database);
        if (oldVersion < 8) purgeLegacyArtwork(database);
        putMetadata(database, "sync_state", "upgrade_required");
    }

    private static void purgeLegacyArtwork(SQLiteDatabase database) {
        // Earlier builds could persist provider artwork URLs containing account
        // material. Secure deletion removes those bytes; the next sync restores
        // only public BLOFY/TMDB artwork accepted by ArtworkUrlPolicy.
        database.execSQL("PRAGMA secure_delete=ON");
        database.delete("media", null, null);
        database.delete("media_staging", null, null);
        database.delete("media_fts", null, null);
    }

    private void migrateToPartitionedSchema(SQLiteDatabase database) {
        String legacySource = readMetadata(database, "source_identity", "legacy");
        if (legacySource.trim().isEmpty()) legacySource = "legacy";
        database.beginTransaction();
        try {
            database.execSQL("DROP INDEX IF EXISTS media_type_category_order");
            database.execSQL("DROP INDEX IF EXISTS media_type_order");
            database.execSQL("DROP INDEX IF EXISTS media_type_release");
            database.execSQL("DROP INDEX IF EXISTS media_name_search");
            database.execSQL("DROP INDEX IF EXISTS media_normalized_search");
            database.execSQL("ALTER TABLE categories RENAME TO categories_v6");
            database.execSQL("ALTER TABLE media RENAME TO media_v6");
            database.execSQL("ALTER TABLE favorites RENAME TO favorites_v6");
            database.execSQL("ALTER TABLE history RENAME TO history_v6");
            createCoreTables(database);
            createIndexes(database);
            createStagingTables(database);
            database.execSQL("INSERT INTO categories(source_id,type,id,name,sort_order) " +
                    "SELECT ?,type,id,name,sort_order FROM categories_v6", new Object[]{legacySource});
            database.execSQL("INSERT INTO media(source_id,type,id,name,search_name,image,backdrop,category_id," +
                    "rating,year,extension,release_date,rating_source,updated_at,sort_order) " +
                    "SELECT ?,type,id,name,search_name,image,backdrop,category_id,rating,year,extension," +
                    "release_date,rating_source,updated_at,sort_order FROM media_v6", new Object[]{legacySource});
            database.execSQL("INSERT INTO favorites(source_id,type,id,created_at) " +
                    "SELECT ?,type,id,created_at FROM favorites_v6", new Object[]{legacySource});
            database.execSQL("INSERT INTO history(source_id,type,id,watched_at) " +
                    "SELECT ?,type,id,watched_at FROM history_v6", new Object[]{legacySource});
            database.execSQL("DROP TABLE categories_v6");
            database.execSQL("DROP TABLE media_v6");
            database.execSQL("DROP TABLE favorites_v6");
            database.execSQL("DROP TABLE history_v6");
            database.execSQL("DROP TABLE IF EXISTS media_fts");
            createFts(database);
            rebuildFts(database, legacySource);
            putMetadata(database, "active_source_id", legacySource);
            putMetadata(database, "source_identity", legacySource);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    private static void createCoreTables(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS categories(" +
                "source_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL," +
                "sort_order INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(source_id,type,id))");
        database.execSQL("CREATE TABLE IF NOT EXISTS media(" +
                "source_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL," +
                "search_name TEXT NOT NULL DEFAULT '',image TEXT,backdrop TEXT,category_id TEXT,rating TEXT," +
                "year TEXT,extension TEXT,release_date TEXT NOT NULL DEFAULT ''," +
                "rating_source TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL DEFAULT ''," +
                "sort_order INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(source_id,type,id))");
        database.execSQL("CREATE TABLE IF NOT EXISTS favorites(" +
                "source_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,created_at INTEGER NOT NULL," +
                "PRIMARY KEY(source_id,type,id))");
        database.execSQL("CREATE TABLE IF NOT EXISTS history(" +
                "source_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,watched_at INTEGER NOT NULL," +
                "PRIMARY KEY(source_id,type,id))");
        database.execSQL("CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    private static void createIndexes(SQLiteDatabase database) {
        database.execSQL("CREATE INDEX IF NOT EXISTS media_source_type_category_order " +
                "ON media(source_id,type,category_id,sort_order)");
        database.execSQL("CREATE INDEX IF NOT EXISTS media_source_type_order " +
                "ON media(source_id,type,sort_order)");
        database.execSQL("CREATE INDEX IF NOT EXISTS media_source_type_release " +
                "ON media(source_id,type,release_date,year)");
        database.execSQL("CREATE INDEX IF NOT EXISTS category_source_type_order " +
                "ON categories(source_id,type,sort_order)");
        database.execSQL("CREATE INDEX IF NOT EXISTS history_source_time " +
                "ON history(source_id,watched_at DESC)");
    }

    private static void createFts(SQLiteDatabase database) {
        try {
            database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS media_fts USING fts4(" +
                    "source_id,type,id,search_name,tokenize=unicode61)");
        } catch (Exception unsupportedTokenizer) {
            database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS media_fts USING fts4(" +
                    "source_id,type,id,search_name)");
        }
    }

    private static void createStagingTables(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS categories_staging(" +
                "import_token TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL," +
                "sort_order INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(import_token,type,id))");
        database.execSQL("CREATE TABLE IF NOT EXISTS media_staging(" +
                "import_token TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL," +
                "search_name TEXT NOT NULL DEFAULT '',image TEXT,backdrop TEXT,category_id TEXT,rating TEXT," +
                "year TEXT,extension TEXT,release_date TEXT NOT NULL DEFAULT ''," +
                "rating_source TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL DEFAULT ''," +
                "sort_order INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(import_token,type,id))");
        database.execSQL("CREATE INDEX IF NOT EXISTS media_staging_token_type_order " +
                "ON media_staging(import_token,type,sort_order)");
    }

    /** Starts an isolated import. The currently active package remains readable. */
    void beginStagedImport(String sourceId) {
        String cleanSource = cleanSource(sourceId);
        SQLiteDatabase database = getWritableDatabase();
        String token = UUID.randomUUID().toString();
        database.beginTransaction();
        try {
            resetStagingTables(database);
            putMetadata(database, "sync_state", "in_progress");
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        importSource = cleanSource;
        importToken = token;
    }

    /** Atomically replaces one source partition only after every page succeeded. */
    void commitStagedImport(String sourceId, String serverName, String sessionKind,
                            String playbackProfile) {
        String cleanSource = cleanSource(sourceId);
        String previousSource = activeSource();
        if (importToken == null || !cleanSource.equals(importSource)) {
            throw new IllegalStateException("لا توجد عملية استيراد مطابقة لإكمالها.");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("categories", "source_id=?", new String[]{cleanSource});
            database.delete("media", "source_id=?", new String[]{cleanSource});
            database.execSQL("INSERT INTO categories(source_id,type,id,name,sort_order) " +
                    "SELECT ?,type,id,name,sort_order FROM categories_staging WHERE import_token=?",
                    new Object[]{cleanSource, importToken});
            database.execSQL("INSERT INTO media(source_id,type,id,name,search_name,image,backdrop,category_id," +
                    "rating,year,extension,release_date,rating_source,updated_at,sort_order) " +
                    "SELECT ?,type,id,name,search_name,image,backdrop,category_id,rating,year,extension," +
                    "release_date,rating_source,updated_at,sort_order FROM media_staging WHERE import_token=?",
                    new Object[]{cleanSource, importToken});
            if (readMetadata(database, "personal_scope_migrated_v7", "").isEmpty()
                    && !cleanSource.equals(previousSource)) {
                // Assign pre-v323 unscoped favorites/history to the first playlist
                // explicitly connected after upgrade, and never copy them again.
                database.execSQL("INSERT OR IGNORE INTO favorites(source_id,type,id,created_at) " +
                                "SELECT ?,type,id,created_at FROM favorites WHERE source_id=?",
                        new Object[]{cleanSource, previousSource});
                database.execSQL("INSERT OR IGNORE INTO history(source_id,type,id,watched_at) " +
                                "SELECT ?,type,id,watched_at FROM history WHERE source_id=?",
                        new Object[]{cleanSource, previousSource});
                putMetadata(database, "personal_scope_migrated_v7", cleanSource);
            }
            // Rebuilding an FTS4 table for very large IPTV packages can keep a
            // low-power TV on the final import frame for several minutes. The
            // catalog already stores normalized names and uses them directly,
            // so committing the playable package must not wait for FTS.
            // Leave the staging pages in place after the atomic swap. The next
            // import drops the staging tables in one operation, which is much
            // faster than deleting tens of thousands of rows on this screen.
            putMetadata(database, "active_source_id", cleanSource);
            putMetadata(database, "source_identity", cleanSource);
            putMetadata(database, "server_name", serverName);
            putMetadata(database, "session_kind", sessionKind);
            putMetadata(database, "playback_profile", playbackProfile);
            putMetadata(database, "last_sync", String.valueOf(System.currentTimeMillis()));
            putMetadata(database, "sync_state", "complete");
            putSourceMetadata(database, cleanSource, "server_name", serverName);
            putSourceMetadata(database, cleanSource, "session_kind", sessionKind);
            putSourceMetadata(database, cleanSource, "playback_profile", playbackProfile);
            putSourceMetadata(database, cleanSource, "last_sync",
                    String.valueOf(System.currentTimeMillis()));
            putSourceMetadata(database, cleanSource, "sync_state", "complete");
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        importToken = null;
        importSource = null;
        activeSource = cleanSource;
        CatalogScope.activate(context, cleanSource);
    }

    void abortStagedImport() {
        if (importToken == null) return;
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            resetStagingTables(database);
            putMetadata(database, "sync_state", "failed");
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        importToken = null;
        importSource = null;
    }

    /** Legacy cache-clear action: clear only the active catalog partition. */
    void beginFreshImport() {
        String source = activeSource();
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("categories", "source_id=?", new String[]{source});
            database.delete("media", "source_id=?", new String[]{source});
            database.delete("media_fts", "source_id=?", new String[]{source});
            putMetadata(database, "sync_state", "refresh_required");
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void saveCategories(List<BlofyModels.Category> categories) {
        if (categories == null || categories.isEmpty()) return;
        SQLiteDatabase database = getWritableDatabase();
        boolean staged = importToken != null;
        String table = staged ? "categories_staging" : "categories";
        String partitionColumn = staged ? "import_token" : "source_id";
        String partition = staged ? importToken : activeSource();
        long order = nextOrder(database, table, partitionColumn, partition, categories.get(0).type);
        database.beginTransaction();
        try {
            for (BlofyModels.Category category : categories) {
                ContentValues values = new ContentValues();
                values.put(partitionColumn, partition);
                values.put("type", category.type);
                values.put("id", category.id);
                values.put("name", category.name);
                values.put("sort_order", order++);
                database.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void saveMedia(List<BlofyModels.Media> items) {
        if (items == null || items.isEmpty()) return;
        SQLiteDatabase database = getWritableDatabase();
        boolean staged = importToken != null;
        String table = staged ? "media_staging" : "media";
        String partitionColumn = staged ? "import_token" : "source_id";
        String partition = staged ? importToken : activeSource();
        long order = nextOrder(database, table, partitionColumn, partition, items.get(0).type);
        database.beginTransaction();
        try {
            for (BlofyModels.Media item : items) {
                ContentValues values = mediaValues(item, order++);
                values.put(partitionColumn, partition);
                database.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                if (!staged) {
                    database.delete("media_fts", "source_id=? AND type=? AND id=?",
                            new String[]{partition, item.type, item.id});
                    ContentValues search = new ContentValues();
                    search.put("source_id", partition);
                    search.put("type", item.type);
                    search.put("id", item.id);
                    search.put("search_name", ArabicNormalizer.normalizeForSearch(item.name));
                    database.insert("media_fts", null, search);
                }
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    private static ContentValues mediaValues(BlofyModels.Media item, long order) {
        ContentValues values = new ContentValues();
        values.put("type", item.type);
        values.put("id", item.id);
        values.put("name", item.name);
        values.put("search_name", ArabicNormalizer.normalizeForSearch(item.name));
        values.put("image", item.image);
        values.put("backdrop", item.backdrop);
        values.put("category_id", item.categoryId);
        values.put("rating", item.rating);
        values.put("year", item.year);
        values.put("extension", item.extension);
        values.put("release_date", item.releaseDate);
        values.put("rating_source", item.ratingSource);
        values.put("updated_at", item.updatedAt);
        values.put("sort_order", order);
        return values;
    }

    private static long nextOrder(SQLiteDatabase database, String table, String partitionColumn,
                                  String partition, String type) {
        String sql = "SELECT COALESCE(MAX(sort_order),-1)+1 FROM " + table +
                " WHERE " + partitionColumn + "=? AND type=?";
        try (Cursor cursor = database.rawQuery(sql, new String[]{partition, type})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    int importCount(String type) {
        if (importToken == null) return count(type);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM media_staging WHERE import_token=? AND type=?",
                new String[]{importToken, type})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    List<BlofyModels.Category> categories(String type) {
        List<BlofyModels.Category> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("categories", new String[]{"id", "name"},
                "source_id=? AND type=?", new String[]{activeSource(), type}, null, null,
                "sort_order ASC")) {
            while (cursor.moveToNext()) result.add(new BlofyModels.Category(
                    cursor.getString(0), cursor.getString(1), type));
        }
        return result;
    }

    List<BlofyModels.Media> media(String type, String category, String search,
                                  boolean favoritesOnly, boolean historyOnly,
                                  int limit, int offset) {
        List<BlofyModels.Media> result = new ArrayList<>();
        String originalSearch = search == null ? "" : search.trim();
        String cleanSearch = ArabicNormalizer.normalizeForSearch(originalSearch);
        boolean searching = !originalSearch.isEmpty();

        StringBuilder sql = new StringBuilder("SELECT ").append(MEDIA_COLUMNS).append(" FROM media m ");
        if (favoritesOnly) {
            sql.append("INNER JOIN favorites f ON f.source_id=m.source_id AND f.type=m.type AND f.id=m.id ");
        }
        if (historyOnly) {
            sql.append("INNER JOIN history h ON h.source_id=m.source_id AND h.type=m.type AND h.id=m.id ");
        }
        List<String> where = new ArrayList<>();
        List<String> args = new ArrayList<>();
        where.add("m.source_id=?");
        args.add(activeSource());
        if (type != null && !type.isEmpty()) { where.add("m.type=?"); args.add(type); }
        if (category != null && !category.isEmpty()) { where.add("m.category_id=?"); args.add(category); }
        if (searching) {
            String[] words = cleanSearch.split("\\s+");
            for (String word : words) {
                if (word.isEmpty()) continue;
                where.add("m.search_name LIKE ? ESCAPE '\\'");
                args.add("%" + escapeLike(word) + "%");
            }
        }
        sql.append("WHERE ").append(TextUtils.join(" AND ", where)).append(' ');
        if (historyOnly) {
            sql.append("ORDER BY h.watched_at DESC ");
        } else if (searching) {
            sql.append("ORDER BY CASE WHEN m.search_name LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END," +
                    "CASE m.type WHEN 'live' THEN 0 WHEN 'movies' THEN 1 ELSE 2 END,m.sort_order ASC ");
            args.add(escapeLike(cleanSearch) + "%");
        } else {
            sql.append("ORDER BY m.sort_order ASC ");
        }
        sql.append("LIMIT ? OFFSET ?");
        args.add(String.valueOf(Math.max(1, limit)));
        args.add(String.valueOf(Math.max(0, offset)));
        try (Cursor cursor = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (cursor.moveToNext()) result.add(readMedia(cursor));
        }
        return result;
    }

    List<BlofyModels.Media> searchAll(String search, int limit, int offset) {
        if (search == null || search.trim().isEmpty()) return new ArrayList<>();
        return media("", "", search, false, false, limit, offset);
    }

    List<BlofyModels.Media> latest(String type, int limit, int offset) {
        List<BlofyModels.Media> result = new ArrayList<>();
        String sql = "SELECT " + MEDIA_COLUMNS + " FROM media m WHERE m.source_id=? AND m.type=? " +
                "ORDER BY CASE WHEN m.year=strftime('%Y','now') OR substr(m.release_date,1,4)=strftime('%Y','now') " +
                "THEN 0 ELSE 1 END,m.updated_at DESC,m.release_date DESC," +
                "CAST(m.year AS INTEGER) DESC,m.sort_order DESC LIMIT ? OFFSET ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{activeSource(), type,
                String.valueOf(Math.max(1, limit)), String.valueOf(Math.max(0, offset))})) {
            while (cursor.moveToNext()) result.add(readMedia(cursor));
        }
        return result;
    }

    List<BlofyModels.Media> featured(int limit) {
        List<BlofyModels.Media> result = new ArrayList<>();
        String sql = "SELECT " + MEDIA_COLUMNS + " FROM media m " +
                "WHERE m.source_id=? AND m.type IN ('movies','series') AND (m.backdrop<>'' OR m.image<>'') " +
                "ORDER BY CASE WHEN m.rating_source<>'' AND m.rating<>'' THEN 0 ELSE 1 END," +
                "CASE WHEN m.year=strftime('%Y','now') OR substr(m.release_date,1,4)=strftime('%Y','now') THEN 0 ELSE 1 END," +
                "CAST(REPLACE(REPLACE(m.rating,'/10',''),',','.') AS REAL) DESC," +
                "m.updated_at DESC,m.release_date DESC,CAST(m.year AS INTEGER) DESC,m.sort_order DESC LIMIT ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql,
                new String[]{activeSource(), String.valueOf(Math.max(1, limit))})) {
            while (cursor.moveToNext()) result.add(readMedia(cursor));
        }
        return result;
    }

    int count(String type) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM media WHERE source_id=? AND type=?",
                new String[]{activeSource(), type})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Activates a previously completed source partition without downloading it again. */
    boolean activateCachedSource(String sourceId) {
        String source = cleanSource(sourceId);
        SQLiteDatabase database = getWritableDatabase();
        int rows;
        try (Cursor cursor = database.rawQuery(
                "SELECT COUNT(*) FROM media WHERE source_id=?", new String[]{source})) {
            rows = cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
        if (rows <= 0) return false;

        String completed = readSourceMetadata(database, source, "sync_state", "");
        String current = readMetadata(database, "active_source_id", "");
        if (completed.isEmpty() && source.equals(current)) {
            completed = readMetadata(database, "sync_state", "");
        }
        if (!"complete".equals(completed)) return false;

        database.beginTransaction();
        try {
            String[] keys = {"server_name", "session_kind", "playback_profile", "last_sync"};
            for (String key : keys) {
                String value = readSourceMetadata(database, source, key, "");
                if (value.isEmpty() && source.equals(current)) {
                    value = readMetadata(database, key, "");
                }
                putMetadata(database, key, value);
                putSourceMetadata(database, source, key, value);
            }
            putMetadata(database, "active_source_id", source);
            putMetadata(database, "source_identity", source);
            putMetadata(database, "sync_state", "complete");
            putSourceMetadata(database, source, "sync_state", "complete");
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        activeSource = source;
        CatalogScope.activate(context, source);
        return true;
    }

    boolean isFavorite(String type, String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM favorites WHERE source_id=? AND type=? AND id=?",
                new String[]{activeSource(), type, id})) {
            return cursor.moveToFirst();
        }
    }

    boolean toggleFavorite(String type, String id) {
        String source = activeSource();
        SQLiteDatabase database = getWritableDatabase();
        if (isFavorite(type, id)) {
            database.delete("favorites", "source_id=? AND type=? AND id=?",
                    new String[]{source, type, id});
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("source_id", source);
        values.put("type", type);
        values.put("id", id);
        values.put("created_at", System.currentTimeMillis());
        database.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    void addHistory(String type, String id) {
        ContentValues values = new ContentValues();
        values.put("source_id", activeSource());
        values.put("type", type);
        values.put("id", id);
        values.put("watched_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("history", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    void clearHistoryForActiveSource() {
        String source = activeSource();
        getWritableDatabase().delete("history", "source_id=?", new String[]{source});
        PlaybackProgress.clearScope(context, source);
    }

    void putMetadata(String key, String value) {
        putMetadata(getWritableDatabase(), key, value);
    }

    void clearPersonalState() {
        String source = activeSource();
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("favorites", "source_id=?", new String[]{source});
            database.delete("history", "source_id=?", new String[]{source});
            database.delete("metadata", "key LIKE ?", new String[]{sourceKey(source, "%")});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        PlaybackProgress.clearScope(context, source);
    }

    void deleteSource(String sourceId) {
        String source = cleanSource(sourceId);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("categories", "source_id=?", new String[]{source});
            database.delete("media", "source_id=?", new String[]{source});
            database.delete("media_fts", "source_id=?", new String[]{source});
            database.delete("favorites", "source_id=?", new String[]{source});
            database.delete("history", "source_id=?", new String[]{source});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        PlaybackProgress.clearScope(context, source);
    }

    String metadata(String key, String fallback) {
        return readMetadata(getReadableDatabase(), key, fallback);
    }

    String activeSource() {
        if (activeSource != null && !activeSource.isEmpty()) return activeSource;
        String stored = metadata("active_source_id", "");
        if (stored.isEmpty()) stored = metadata("source_identity", "");
        if (stored.isEmpty()) stored = CatalogScope.active(context);
        activeSource = cleanSource(stored);
        CatalogScope.activate(context, activeSource);
        return activeSource;
    }

    private static void rebuildFts(SQLiteDatabase database, String source) {
        database.delete("media_fts", "source_id=?", new String[]{source});
        database.execSQL("INSERT INTO media_fts(source_id,type,id,search_name) " +
                "SELECT source_id,type,id,search_name FROM media WHERE source_id=?", new Object[]{source});
    }

    private static void resetStagingTables(SQLiteDatabase database) {
        database.execSQL("DROP TABLE IF EXISTS categories_staging");
        database.execSQL("DROP TABLE IF EXISTS media_staging");
        createStagingTables(database);
    }

    private static void putMetadata(SQLiteDatabase database, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value == null ? "" : value);
        database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static void putSourceMetadata(SQLiteDatabase database, String source,
                                          String key, String value) {
        putMetadata(database, sourceKey(source, key), value);
    }

    private static String readSourceMetadata(SQLiteDatabase database, String source,
                                             String key, String fallback) {
        return readMetadata(database, sourceKey(source, key), fallback);
    }

    private static String sourceKey(String source, String key) {
        return "source_v1:" + cleanSource(source) + ":" + (key == null ? "" : key);
    }

    private static String readMetadata(SQLiteDatabase database, String key, String fallback) {
        try (Cursor cursor = database.rawQuery("SELECT value FROM metadata WHERE key=?",
                new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : fallback;
        }
    }

    private static BlofyModels.Media readMedia(Cursor cursor) {
        return new BlofyModels.Media(
                cursor.getString(0), cursor.getString(1), value(cursor, 2), value(cursor, 3),
                value(cursor, 4), value(cursor, 5), value(cursor, 6), value(cursor, 7), value(cursor, 8),
                value(cursor, 9), value(cursor, 10), value(cursor, 11));
    }

    private static String value(Cursor cursor, int column) {
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }

    private static String cleanSource(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty() ? "legacy" : clean;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
