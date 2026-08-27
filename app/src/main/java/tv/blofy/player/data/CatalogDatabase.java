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
    private static final int DB_VERSION = 1;

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
                "image_url TEXT NOT NULL DEFAULT ''," +
                "stream_url TEXT NOT NULL DEFAULT ''," +
                "extension TEXT NOT NULL DEFAULT ''," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (playlist_id, kind, item_id))");
        db.execSQL("CREATE INDEX idx_catalog_page ON catalog(playlist_id, kind, category_id, updated_at DESC, item_id)");

        db.execSQL("CREATE TABLE playback_profile (" +
                "profile_key TEXT NOT NULL," +
                "route_id TEXT NOT NULL," +
                "success_count INTEGER NOT NULL DEFAULT 0," +
                "failure_count INTEGER NOT NULL DEFAULT 0," +
                "total_first_frame_ms INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (profile_key, route_id))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No migration path from " + oldVersion + " to " + newVersion);
    }

    public void replacePage(List<CatalogItem> items) {
        if (items == null || items.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (CatalogItem item : items) {
                ContentValues values = values(item);
                db.insertWithOnConflict("catalog", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<CatalogItem> page(String playlistId, String kind, String categoryId,
                                  int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 250));
        int safeOffset = Math.max(0, offset);
        Cursor cursor = getReadableDatabase().query(
                "catalog",
                new String[]{"playlist_id","kind","item_id","category_id","title","image_url","stream_url","extension","updated_at"},
                "playlist_id=? AND kind=? AND category_id=?",
                new String[]{clean(playlistId), clean(kind), clean(categoryId)},
                null, null,
                "updated_at DESC, item_id",
                safeOffset + "," + safeLimit);
        try {
            if (!cursor.moveToFirst()) return Collections.emptyList();
            List<CatalogItem> out = new ArrayList<>(cursor.getCount());
            do {
                out.add(new CatalogItem(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getString(5),
                        cursor.getString(6), cursor.getString(7), cursor.getLong(8)));
            } while (cursor.moveToNext());
            return out;
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
        v.put("image_url", item.imageUrl);
        v.put("stream_url", item.streamUrl);
        v.put("extension", item.extension);
        v.put("updated_at", item.updatedAt);
        return v;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

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
