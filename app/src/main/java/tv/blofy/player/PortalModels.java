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

        Playlist(JSONObject value) {
            JSONObject row = value == null ? new JSONObject() : value;
            id = clean(row.optString("id", ""));
            name = clean(row.optString("name", ""));
            kind = clean(row.optString("kind", "xtream"));
            serverName = clean(row.optString("serverName", ""));
            status = clean(row.optString("status", "unknown"));
            isDefault = row.optBoolean("isDefault", false);
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

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
