package tv.blofy.player.remoteconfig;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Atomic cache for signed compact JWS values. It stores no provider URL or header. */
public final class RemoteConfigStore {
    private static final String PREFS = "blofy_remote_config_v1";
    private static final String GLOBAL = "global";
    private final Backend backend;

    public RemoteConfigStore(Context context) {
        this(new PreferencesBackend(context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)));
    }

    RemoteConfigStore(Backend backend) {
        this.backend = backend;
    }

    public synchronized CachedEntry global() {
        return read(GLOBAL);
    }

    public synchronized CachedEntry provider(String profileId) {
        String clean = profile(profileId);
        return clean.isEmpty() ? null : read("provider." + clean);
    }

    public synchronized void saveGlobal(CachedEntry entry) throws RemoteConfigException {
        save(GLOBAL, entry);
    }

    public synchronized void saveProvider(String profileId, CachedEntry entry)
            throws RemoteConfigException {
        String clean = profile(profileId);
        if (clean.isEmpty()) throw new RemoteConfigException(
                RemoteConfigException.Reason.STORAGE, "provider cache key is invalid");
        save("provider." + clean, entry);
    }

    public synchronized void clearGlobal() { backend.removePrefix(GLOBAL + "."); }

    public synchronized void clearProvider(String profileId) {
        String clean = profile(profileId);
        if (!clean.isEmpty()) backend.removePrefix("provider." + clean + ".");
    }

    private CachedEntry read(String slot) {
        String compact = backend.string(slot + ".jws");
        String digest = backend.string(slot + ".digest");
        long revision = backend.number(slot + ".revision");
        long expires = backend.number(slot + ".expires");
        int profileRevision = (int) Math.max(0L,
                Math.min(Integer.MAX_VALUE, backend.number(slot + ".profileRevision")));
        if (compact.isEmpty() || digest.isEmpty() || revision <= 0L || expires <= 0L) return null;
        try {
            return new CachedEntry(compact, digest, revision, expires, profileRevision);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void save(String slot, CachedEntry entry) throws RemoteConfigException {
        if (entry == null) throw new RemoteConfigException(
                RemoteConfigException.Reason.STORAGE, "remote config cache entry is missing");
        Map<String, String> strings = new HashMap<>();
        strings.put(slot + ".jws", entry.compactJws);
        strings.put(slot + ".digest", entry.compactDigest);
        Map<String, Long> numbers = new HashMap<>();
        numbers.put(slot + ".revision", entry.revision);
        numbers.put(slot + ".expires", entry.expiresAtEpochSeconds);
        numbers.put(slot + ".profileRevision", (long) entry.profileRevision);
        if (!backend.atomic(strings, numbers, Collections.emptySet())) {
            throw new RemoteConfigException(RemoteConfigException.Reason.STORAGE,
                    "remote config cache commit failed");
        }
    }

    private static String profile(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("pp_[A-Za-z0-9_-]{8,80}") ? clean : "";
    }

    public static final class CachedEntry {
        public final String compactJws;
        public final String compactDigest;
        public final long revision;
        public final long expiresAtEpochSeconds;
        public final int profileRevision;

        public CachedEntry(String compactJws, String compactDigest, long revision,
                           long expiresAtEpochSeconds, int profileRevision) {
            this.compactJws = clean(compactJws);
            this.compactDigest = clean(compactDigest);
            this.revision = revision;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
            this.profileRevision = Math.max(0, profileRevision);
            if (this.compactJws.isEmpty() || this.compactDigest.isEmpty()
                    || revision <= 0L || expiresAtEpochSeconds <= 0L) {
                throw new IllegalArgumentException("invalid remote config cache entry");
            }
        }

        private static String clean(String value) { return value == null ? "" : value.trim(); }
    }

    interface Backend {
        String string(String key);
        long number(String key);
        boolean atomic(Map<String, String> strings, Map<String, Long> numbers,
                       Set<String> removals);
        void removePrefix(String prefix);
    }

    private static final class PreferencesBackend implements Backend {
        private final SharedPreferences preferences;

        PreferencesBackend(SharedPreferences preferences) { this.preferences = preferences; }

        @Override public String string(String key) {
            String value = preferences.getString(key, "");
            return value == null ? "" : value;
        }

        @Override public long number(String key) { return preferences.getLong(key, 0L); }

        @Override public boolean atomic(Map<String, String> strings, Map<String, Long> numbers,
                                        Set<String> removals) {
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, String> entry : strings.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Long> entry : numbers.entrySet()) {
                editor.putLong(entry.getKey(), entry.getValue());
            }
            for (String key : removals) editor.remove(key);
            return editor.commit();
        }

        @Override public void removePrefix(String prefix) {
            Set<String> keys = new HashSet<>(preferences.getAll().keySet());
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : keys) if (key.startsWith(prefix)) editor.remove(key);
            editor.commit();
        }
    }
}
