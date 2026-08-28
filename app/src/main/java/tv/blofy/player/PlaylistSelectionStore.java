package tv.blofy.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/** Reads the same active-playlist keys used by the production playlist hub. */
final class PlaylistSelectionStore {
    private static final String PREFS = "blofy_playlist_hub";
    private static final String KEY_ACTIVE = "active_playlist_id";
    private static final String KEY_REVISION = "remote_revision";
    private static final String KEY_REFRESH_REQUESTED = "refresh_catalog_requested";

    private final SharedPreferences preferences;

    PlaylistSelectionStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String activeId() {
        String value = preferences.getString(KEY_ACTIVE, "");
        return value == null ? "" : value.trim();
    }

    void setActive(String playlistId) {
        preferences.edit().putString(
                KEY_ACTIVE, playlistId == null ? "" : playlistId.trim()).apply();
    }

    int revision() {
        return Math.max(0, preferences.getInt(KEY_REVISION, 0));
    }

    void setRevision(int revision) {
        preferences.edit().putInt(KEY_REVISION, Math.max(0, revision)).apply();
    }

    @SuppressLint("ApplySharedPref")
    void requestCatalogRefresh() {
        preferences.edit().putBoolean(KEY_REFRESH_REQUESTED, true).commit();
    }

    @SuppressLint("ApplySharedPref")
    boolean consumeCatalogRefresh() {
        boolean requested = preferences.getBoolean(KEY_REFRESH_REQUESTED, false);
        if (requested) preferences.edit().remove(KEY_REFRESH_REQUESTED).commit();
        return requested;
    }
}
