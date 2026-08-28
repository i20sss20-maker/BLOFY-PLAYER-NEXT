package tv.blofy.player.remoteconfig;

import org.json.JSONObject;

/** Optional transport wrapper embedded in bootstrap or native-link JSON. */
public final class RemoteConfigEnvelope {
    public static final String FORMAT = "jws-compact";

    public final String compactJws;

    public RemoteConfigEnvelope(String compactJws) throws RemoteConfigException {
        String clean = compactJws == null ? "" : compactJws.trim();
        if (clean.isEmpty() || clean.length() > 96_000
                || clean.indexOf('\r') >= 0 || clean.indexOf('\n') >= 0) {
            throw new RemoteConfigException(RemoteConfigException.Reason.MALFORMED,
                    "invalid remote config envelope");
        }
        this.compactJws = clean;
    }

    /** Returns null when the additive field is absent, preserving old contracts. */
    public static RemoteConfigEnvelope optional(JSONObject parent, String field)
            throws RemoteConfigException {
        if (parent == null || field == null || !parent.has(field) || parent.isNull(field)) {
            return null;
        }
        JSONObject value = parent.optJSONObject(field);
        if (value == null || !FORMAT.equals(value.optString("format", ""))) {
            throw new RemoteConfigException(RemoteConfigException.Reason.MALFORMED,
                    "unsupported remote config envelope");
        }
        return new RemoteConfigEnvelope(value.optString("value", ""));
    }
}
