package tv.blofy.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small public models returned by the credential-hiding BLOFY device API. */
final class PortalModels {
    private PortalModels() {}

    static final class License {
        final String plan;
        final String status;
        final int remainingDays;
        final String activationUrl;

        License(JSONObject response) {
            JSONObject value = response == null ? new JSONObject() : response;
            plan = clean(value.optString("plan", ""));
            status = clean(value.optString("status", ""));
            remainingDays = Math.max(0, value.optInt("remainingDays", 0));
            activationUrl = clean(value.optString("activationUrl", ""));
        }

        boolean usable() {
            return "trial".equals(plan) || "active".equals(plan);
        }
    }

    static final class Playlist {
        final String id;
        final String name;
        final String kind;
        final String serverName;
        final String status;
        final boolean isDefault;
        // Detail fields are held in memory only while the server-authoritative editor is open.
        // The backend never returns a password and this model deliberately has no password field.
        final String serverUrl;
        final String username;
        final String url;

        Playlist(JSONObject value) {
            this(value, null);
        }

        Playlist(JSONObject value, Playlist fallback) {
            JSONObject row = value == null ? new JSONObject() : value;
            id = clean(row.optString("id", fallback == null ? "" : fallback.id));
            name = clean(row.optString("name", fallback == null ? "" : fallback.name));
            kind = PlaylistEditorContract.normalizeKind(row.optString(
                    "kind", fallback == null ? "xtream" : fallback.kind));
            serverName = clean(row.optString(
                    "serverName", fallback == null ? "" : fallback.serverName));
            status = clean(row.optString(
                    "status", fallback == null ? "unknown" : fallback.status));
            isDefault = row.has("isDefault")
                    ? row.optBoolean("isDefault", false)
                    : fallback != null && fallback.isDefault;
            serverUrl = clean(row.optString(
                    "serverUrl", fallback == null ? "" : fallback.serverUrl));
            username = clean(row.optString(
                    "username", fallback == null ? "" : fallback.username));
            url = clean(row.optString("url", fallback == null ? "" : fallback.url));
        }

        String displayName() {
            return name.isEmpty() ? "قائمة بدون اسم" : name;
        }
    }

    static List<Playlist> playlists(JSONObject response) {
        JSONArray rows = response == null ? null : response.optJSONArray("playlists");
        if (rows == null) return Collections.emptyList();
        List<Playlist> result = new ArrayList<>(rows.length());
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            Playlist playlist = new Playlist(row);
            if (!playlist.id.isEmpty()) result.add(playlist);
        }
        return result;
    }

    static String defaultPlaylistId(JSONObject response) {
        return response == null ? "" : clean(response.optString("defaultPlaylistId", ""));
    }

    static int revision(JSONObject response) {
        return response == null ? 0 : Math.max(0, response.optInt("revision", 0));
    }

    static Playlist playlistDetail(JSONObject response, Playlist fallback) {
        JSONObject row = response == null ? null : response.optJSONObject("playlist");
        if (row == null) row = response == null ? new JSONObject() : response;
        return new Playlist(row, fallback);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
