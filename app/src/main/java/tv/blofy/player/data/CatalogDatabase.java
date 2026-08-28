package tv.blofy.player.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Cache-first catalog database. All callers should invoke this off the UI thread. */
public final class CatalogDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "blofy_next.db";
    private static final int DB_VERSION = 5;

    public CatalogDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE catalog (" +
                "playlist_id TEXT NOT NULL," +
                "kind TEXT NOT NULL," +
                "item_id TEXT NOT NULL," +
                "category_id TEXT NOT NULL DEFAULT ''," +
                "title TEXT NOT NULL DEFAULT ''," +
                "extension TEXT NOT NULL DEFAULT ''," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "sort_order INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (playlist_id, kind, item_id))");
        db.execSQL("CREATE INDEX idx_catalog_page ON catalog(playlist_id, kind, category_id, updated_at DESC, sort_order, item_id)");
        db.execSQL("CREATE INDEX idx_catalog_all ON catalog(playlist_id, kind, updated_at DESC, sort_order, item_id)");
        createCatalogStaging(db);

        db.execSQL("CREATE TABLE playback_profile (" +
                "profile_key TEXT NOT NULL," +
                "route_id TEXT NOT NULL," +
                "success_count INTEGER NOT NULL DEFAULT 0," +
                "failure_count INTEGER NOT NULL DEFAULT 0," +
                "total_first_frame_ms INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (profile_key, route_id))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE catalog ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0");
            db.execSQL("DROP INDEX IF EXISTS idx_catalog_page");
            db.execSQL("CREATE INDEX idx_catalog_page ON catalog(playlist_id, kind, category_id, updated_at DESC, sort_order, item_id)");
        }
        if (oldVersion < 3) {
            db.execSQL("UPDATE catalog SET stream_url=''");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_all ON catalog(playlist_id, kind, updated_at DESC, sort_order, item_id)");
        }
        if (oldVersion < 4) {
            // Rebuild instead of merely blanking sensitive legacy columns. This
            // removes provider URLs/icons from the schema. Secure delete clears
            // the dropped table pages instead of leaving credential bytes behind.
            db.execSQL("PRAGMA secure_delete=ON");
            db.execSQL("CREATE TABLE catalog_v4 (" +
                    "playlist_id TEXT NOT NULL," +
                    "kind TEXT NOT NULL," +
                    "item_id TEXT NOT NULL," +
                    "category_id TEXT NOT NULL DEFAULT ''," +
                    "title TEXT NOT NULL DEFAULT ''," +
                    "extension TEXT NOT NULL DEFAULT ''," +
                    "updated_at INTEGER NOT NULL DEFAULT 0," +
                    "sort_order INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (playlist_id, kind, item_id))");
            db.execSQL("INSERT OR REPLACE INTO catalog_v4 " +
                    "(playlist_id,kind,item_id,category_id,title,extension,updated_at,sort_order) " +
                    "SELECT playlist_id,kind,item_id,category_id,title,extension,updated_at,sort_order FROM catalog");
            db.execSQL("DROP TABLE catalog");
            db.execSQL("ALTER TABLE catalog_v4 RENAME TO catalog");
            db.execSQL("CREATE INDEX idx_catalog_page ON catalog(playlist_id, kind, category_id, updated_at DESC, sort_order, item_id)");
            db.execSQL("CREATE INDEX idx_catalog_all ON catalog(playlist_id, kind, updated_at DESC, sort_order, item_id)");
        }
        if (oldVersion < 5) {
            createCatalogStaging(db);
        }
    }

    private static void createCatalogStaging(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_staging (" +
                "import_generation INTEGER NOT NULL," +
                "playlist_id TEXT NOT NULL," +
                "kind TEXT NOT NULL," +
                "item_id TEXT NOT NULL," +
                "category_id TEXT NOT NULL DEFAULT ''," +
                "title TEXT NOT NULL DEFAULT ''," +
                "extension TEXT NOT NULL DEFAULT ''," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "sort_order INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (import_generation, playlist_id, kind, item_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_staging_target " +
                "ON catalog_staging(import_generation, playlist_id, kind)");
    }

    /** Starts a private import generation without changing the visible catalog. */
    public void beginStagedImport(long importGeneration, String playlistId, String kind) {
        Target target = target(importGeneration, playlistId, kind);
        // One importer owns a playlist/kind partition. Remove an abandoned
        // generation from a previous process before starting the new one.
        getWritableDatabase().delete("catalog_staging",
                "playlist_id=? AND kind=?",
                new String[]{target.playlistId, target.kind});
    }

    /** Persists one fetched page into staging. Visible catalog readers are unaffected. */
    public int stagePage(long importGeneration, String playlistId, String kind,
                         List<CatalogItem> items) {
        Target target = target(importGeneration, playlistId, kind);
        if (items == null || items.isEmpty()) {
            return countStaged(getReadableDatabase(), target);
        }
        for (CatalogItem item : items) {
            if (item == null || item.id.isEmpty()
                    || !target.playlistId.equals(clean(item.playlistId))
                    || !target.kind.equals(clean(item.kind))) {
                throw new IllegalArgumentException("catalog page contains an item outside its import target");
            }
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (CatalogItem item : items) {
                ContentValues staged = values(item);
                staged.put("import_generation", target.importGeneration);
                long row = db.insertWithOnConflict(
                        "catalog_staging", null, staged, SQLiteDatabase.CONFLICT_REPLACE);
                if (row == -1L) throw new IllegalStateException("failed to stage catalog item");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        // The staging primary key de-duplicates repeated provider rows across
        // pages. Return the database truth so progress and the atomic commit use
        // the same distinct-row count.
        return countStaged(db, target);
    }

    /**
     * Atomically replaces exactly one playlist/kind partition with a complete generation.
     * Deleting the old partition and publishing staging share one SQLite transaction, so
     * readers can observe either the old catalog or the complete new catalog, never a mix.
     */
    public int commitStagedImport(long importGeneration, String playlistId, String kind,
                                  int expectedItems) {
        Target target = target(importGeneration, playlistId, kind);
        if (expectedItems <= 0) {
            throw new IllegalArgumentException("a staged catalog commit must contain rows");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int stagedCount = countStaged(db, target);
            if (stagedCount != expectedItems) {
                throw new IllegalStateException(
                        "staged catalog row count differs from completed import");
            }
            db.delete("catalog", "playlist_id=? AND kind=?",
                    new String[]{target.playlistId, target.kind});
            db.execSQL("INSERT OR REPLACE INTO catalog " +
                            "(playlist_id,kind,item_id,category_id,title,extension,updated_at,sort_order) " +
                            "SELECT playlist_id,kind,item_id,category_id,title,extension,updated_at,sort_order " +
                            "FROM catalog_staging WHERE import_generation=? AND playlist_id=? AND kind=?",
                    new Object[]{target.importGeneration, target.playlistId, target.kind});
            db.delete("catalog_staging",
                    "import_generation=? AND playlist_id=? AND kind=?",
                    target.arguments());
            db.setTransactionSuccessful();
            return stagedCount;
        } finally {
            db.endTransaction();
        }
    }

    /** Discards an incomplete generation. It cannot affect the visible catalog. */
    public void abortStagedImport(long importGeneration, String playlistId, String kind) {
        Target target = target(importGeneration, playlistId, kind);
        getWritableDatabase().delete("catalog_staging",
                "import_generation=? AND playlist_id=? AND kind=?",
                target.arguments());
    }

    private static int countStaged(SQLiteDatabase db, Target target) {
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM catalog_staging " +
                        "WHERE import_generation=? AND playlist_id=? AND kind=?",
                target.arguments());
        try {
            return cursor.moveToFirst() ? Math.max(0, cursor.getInt(0)) : 0;
        } finally {
            cursor.close();
        }
    }

    public List<CatalogItem> page(String playlistId, String kind, String categoryId,
                                  int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 250));
        int safeOffset = Math.max(0, offset);
        String cleanCategory = clean(categoryId);
        String selection = cleanCategory.isEmpty()
                ? "playlist_id=? AND kind=?"
                : "playlist_id=? AND kind=? AND category_id=?";
        String[] arguments = cleanCategory.isEmpty()
                ? new String[]{clean(playlistId), clean(kind)}
                : new String[]{clean(playlistId), clean(kind), cleanCategory};
        Cursor cursor = getReadableDatabase().query(
                "catalog",
                new String[]{"playlist_id","kind","item_id","category_id","title","extension","updated_at","sort_order"},
                selection,
                arguments,
                null, null,
                "updated_at DESC, sort_order, item_id",
                safeOffset + "," + safeLimit);
        try {
            if (!cursor.moveToFirst()) return Collections.emptyList();
            List<CatalogItem> out = new ArrayList<>(cursor.getCount());
            do {
                out.add(new CatalogItem(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), "", "",
                        cursor.getString(5), cursor.getLong(6), cursor.getLong(7)));
            } while (cursor.moveToNext());
            return out;
        } finally {
            cursor.close();
        }
    }

    public int count(String playlistId, String kind) {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM catalog WHERE playlist_id=? AND kind=?",
                new String[]{clean(playlistId), clean(kind)});
        try {
            return cursor.moveToFirst() ? Math.max(0, cursor.getInt(0)) : 0;
        } finally {
            cursor.close();
        }
    }

    public void saveRouteResult(String profileKey, String routeId, boolean success, long firstFrameMs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.query("playback_profile",
                    new String[]{"success_count","failure_count","total_first_frame_ms"},
                    "profile_key=? AND route_id=?",
                    new String[]{clean(profileKey), clean(routeId)}, null, null, null);
            int ok = 0, fail = 0;
            long total = 0;
            try {
                if (c.moveToFirst()) {
                    ok = c.getInt(0);
                    fail = c.getInt(1);
                    total = c.getLong(2);
                }
            } finally { c.close(); }
            if (success) {
                ok++;
                total += Math.max(0L, firstFrameMs);
            } else {
                fail++;
            }
            ContentValues v = new ContentValues();
            v.put("profile_key", clean(profileKey));
            v.put("route_id", clean(routeId));
            v.put("success_count", ok);
            v.put("failure_count", fail);
            v.put("total_first_frame_ms", total);
            db.insertWithOnConflict("playback_profile", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<RouteScore> loadRouteScores(String profileKey) {
        Cursor c = getReadableDatabase().query("playback_profile",
                new String[]{"route_id","success_count","failure_count","total_first_frame_ms"},
                "profile_key=?", new String[]{clean(profileKey)}, null, null, null);
        try {
            List<RouteScore> out = new ArrayList<>();
            while (c.moveToNext()) {
                out.add(new RouteScore(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3)));
            }
            return out;
        } finally { c.close(); }
    }

    private static ContentValues values(CatalogItem item) {
        ContentValues v = new ContentValues();
        v.put("playlist_id", item.playlistId);
        v.put("kind", item.kind);
        v.put("item_id", item.id);
        v.put("category_id", item.categoryId);
        v.put("title", item.title);
        // Catalog storage is ID-only public metadata. Provider URLs and icons can
        // contain credentials, so neither has a column in the active schema.
        v.put("extension", item.extension);
        v.put("updated_at", item.updatedAt);
        v.put("sort_order", item.sortOrder);
        return v;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static Target target(long importGeneration, String playlistId, String kind) {
        String cleanPlaylistId = clean(playlistId);
        String cleanKind = clean(kind);
        if (importGeneration <= 0L || cleanPlaylistId.isEmpty() || cleanKind.isEmpty()) {
            throw new IllegalArgumentException("staged catalog import requires generation, playlist and kind");
        }
        return new Target(importGeneration, cleanPlaylistId, cleanKind);
    }

    private static final class Target {
        final long importGeneration;
        final String playlistId;
        final String kind;

        Target(long importGeneration, String playlistId, String kind) {
            this.importGeneration = importGeneration;
            this.playlistId = playlistId;
            this.kind = kind;
        }

        String[] arguments() {
            return new String[]{String.valueOf(importGeneration), playlistId, kind};
        }
    }

    public static final class RouteScore {
        public final String routeId;
        public final int successCount;
        public final int failureCount;
        public final long totalFirstFrameMs;
        RouteScore(String routeId, int successCount, int failureCount, long totalFirstFrameMs) {
            this.routeId = routeId;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.totalFirstFrameMs = totalFirstFrameMs;
        }
    }
}
