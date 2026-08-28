package tv.blofy.player.remoteconfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Verified and hard-bounded policy. It never contains URLs, cookies or authorization data. */
public final class RemoteConfigPolicy {
    public enum Scope { DEFAULTS, GLOBAL, PROVIDER }

    public final Scope scope;
    public final int schemaVersion;
    public final long revision;
    public final long issuedAtEpochSeconds;
    public final long expiresAtEpochSeconds;
    public final int minimumVersionCode;
    public final String profileId;
    public final int profileRevision;
    public final String keyId;
    public final String compactDigest;
    public final String dnsPolicyId;
    public final String enginePreference;
    public final String transportPolicy;
    public final String userAgent;
    public final Timeouts timeouts;
    public final Map<String, Boolean> features;
    private final Set<String> present;

    private RemoteConfigPolicy(Scope scope, int schemaVersion, long revision,
                               long issuedAtEpochSeconds, long expiresAtEpochSeconds,
                               int minimumVersionCode, String profileId, int profileRevision,
                               String keyId, String compactDigest, String dnsPolicyId,
                               String enginePreference, String transportPolicy, String userAgent,
                               Timeouts timeouts, Map<String, Boolean> features,
                               Set<String> present) {
        this.scope = scope;
        this.schemaVersion = schemaVersion;
        this.revision = revision;
        this.issuedAtEpochSeconds = issuedAtEpochSeconds;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        this.minimumVersionCode = minimumVersionCode;
        this.profileId = clean(profileId);
        this.profileRevision = Math.max(0, profileRevision);
        this.keyId = clean(keyId);
        this.compactDigest = clean(compactDigest);
        this.dnsPolicyId = dnsPolicyId;
        this.enginePreference = enginePreference;
        this.transportPolicy = transportPolicy;
        this.userAgent = userAgent;
        this.timeouts = timeouts;
        this.features = Collections.unmodifiableMap(new HashMap<>(features));
        this.present = Collections.unmodifiableSet(new HashSet<>(present));
    }

    public static RemoteConfigPolicy defaults() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("livePreview", true);
        features.put("vlcFallback", true);
        features.put("ffmpegAudio", true);
        features.put("telemetry", false);
        features.put("tmdb", true);
        return new RemoteConfigPolicy(Scope.DEFAULTS, 1, 0L, 0L, Long.MAX_VALUE,
                0, "", 0, "", "", "system", "adaptive", "adaptive",
                RemoteConfigDefaults.USER_AGENT, Timeouts.defaults(), features,
                allPresent());
    }

    static RemoteConfigPolicy parse(RemoteConfigVerifier.VerifiedPayload verified,
                                    long nowEpochSeconds, int appVersionCode)
            throws RemoteConfigException {
        Map<String, Object> claims = verified.claims;
        int schema = integer(claims.get("schemaVersion"), 0);
        if (schema != 1) throw reject(RemoteConfigException.Reason.VERSION,
                "unsupported remote config schema");
        if (!RemoteConfigDefaults.AUDIENCE.equals(text(claims.get("aud")))) {
            throw reject(RemoteConfigException.Reason.AUDIENCE,
                    "remote config audience mismatch");
        }
        Scope scope;
        String rawScope = text(claims.get("scope"));
        if ("global".equals(rawScope)) scope = Scope.GLOBAL;
        else if ("provider".equals(rawScope)) scope = Scope.PROVIDER;
        else throw reject(RemoteConfigException.Reason.SCOPE,
                    "remote config scope is invalid");

        long revision = whole(claims.get("revision"), 0L);
        long issued = whole(claims.get("iat"), 0L);
        long notBefore = whole(claims.get("nbf"), issued);
        long expires = whole(claims.get("exp"), 0L);
        if (revision <= 0L || issued <= 0L || notBefore <= 0L
                || notBefore > expires || expires <= issued
                || expires - issued > RemoteConfigDefaults.MAX_VALIDITY_SECONDS) {
            throw reject(RemoteConfigException.Reason.CLAIMS,
                    "remote config lifetime or revision is invalid");
        }
        if (issued > nowEpochSeconds + RemoteConfigDefaults.CLOCK_SKEW_SECONDS
                || notBefore > nowEpochSeconds + RemoteConfigDefaults.CLOCK_SKEW_SECONDS) {
            throw reject(RemoteConfigException.Reason.NOT_YET_VALID,
                    "remote config is not active yet");
        }
        if (expires <= nowEpochSeconds) {
            throw reject(RemoteConfigException.Reason.EXPIRED,
                    "remote config has expired");
        }
        long minimumVersionValue = whole(claims.get("minVersionCode"), -1L);
        if (minimumVersionValue < 0L || minimumVersionValue > Integer.MAX_VALUE) {
            throw reject(RemoteConfigException.Reason.CLAIMS,
                    "minimum application version is invalid");
        }
        int minimumVersion = (int) minimumVersionValue;
        if (minimumVersion > appVersionCode) {
            throw reject(RemoteConfigException.Reason.VERSION,
                    "remote config requires a newer application");
        }

        String profileId = text(claims.get("profileId"));
        int profileRevision = integer(claims.get("profileRevision"), 0);
        if (scope == Scope.PROVIDER
                && (!profileId.matches("pp_[A-Za-z0-9_-]{8,80}")
                || profileRevision <= 0)) {
            throw reject(RemoteConfigException.Reason.CLAIMS,
                    "provider config identity is invalid");
        }
        if (scope == Scope.GLOBAL && (!profileId.isEmpty() || profileRevision != 0)) {
            throw reject(RemoteConfigException.Reason.CLAIMS,
                    "global config contains provider identity");
        }

        Map<String, Object> policy = object(claims.get("policy"));
        Map<String, Object> network = object(policy.get("network"));
        Map<String, Object> playback = object(policy.get("playback"));
        Map<String, Object> timeoutValues = object(playback.get("timeoutsMs"));
        Map<String, Object> featureValues = object(policy.get("features"));
        Set<String> present = new HashSet<>();

        String dns = option(network, "providerDnsPolicyId", RemoteConfigDefaults.DNS_POLICIES,
                "system", "dns", present);
        String engine = option(playback, "enginePreference", RemoteConfigDefaults.ENGINES,
                "adaptive", "engine", present);
        String transport = option(playback, "transportPolicy", RemoteConfigDefaults.TRANSPORTS,
                "adaptive", "transport", present);
        String agent = RemoteConfigDefaults.USER_AGENT;
        if (playback.containsKey("userAgent")) {
            present.add("userAgent");
            agent = RemoteConfigDefaults.userAgent(text(playback.get("userAgent")));
        }
        Timeouts timeouts = Timeouts.parse(timeoutValues, present);
        Map<String, Boolean> features = new HashMap<>();
        for (String name : RemoteConfigDefaults.FEATURES) {
            Object value = featureValues.get(name);
            if (value instanceof Boolean) {
                present.add("feature." + name);
                features.put(name, (Boolean) value);
            }
        }
        return new RemoteConfigPolicy(scope, schema, revision, issued, expires,
                minimumVersion, profileId, profileRevision, verified.keyId,
                verified.compactDigest, dns, engine, transport, agent, timeouts,
                features, present);
    }

    /** Provider or global fields override only keys explicitly present in the signed payload. */
    public RemoteConfigPolicy mergedOver(RemoteConfigPolicy base) {
        RemoteConfigPolicy fallback = base == null ? defaults() : base;
        Map<String, Boolean> mergedFeatures = new HashMap<>(fallback.features);
        for (String name : RemoteConfigDefaults.FEATURES) {
            if (present.contains("feature." + name)) mergedFeatures.put(name, features.get(name));
        }
        return new RemoteConfigPolicy(scope, schemaVersion, revision,
                issuedAtEpochSeconds, expiresAtEpochSeconds, minimumVersionCode,
                profileId, profileRevision, keyId, compactDigest,
                pick("dns", dnsPolicyId, fallback.dnsPolicyId),
                pick("engine", enginePreference, fallback.enginePreference),
                pick("transport", transportPolicy, fallback.transportPolicy),
                pick("userAgent", userAgent, fallback.userAgent),
                timeouts.mergedOver(fallback.timeouts, present), mergedFeatures,
                allPresent());
    }

    public boolean feature(String name) {
        return Boolean.TRUE.equals(features.get(name));
    }

    boolean declares(String name) {
        return present.contains(name);
    }

    public boolean isExpired(long nowEpochSeconds) {
        return expiresAtEpochSeconds <= nowEpochSeconds;
    }

    private String pick(String key, String value, String fallback) {
        return present.contains(key) ? value : fallback;
    }

    private static String option(Map<String, Object> source, String key, Set<String> allowed,
                                 String fallback, String presence, Set<String> present) {
        if (!source.containsKey(key)) return fallback;
        present.add(presence);
        return RemoteConfigDefaults.allowlisted(text(source.get(key)), allowed, fallback);
    }

    private static Set<String> allPresent() {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, "dns", "engine", "transport", "userAgent");
        for (String timeout : Timeouts.NAMES) result.add("timeout." + timeout);
        for (String feature : RemoteConfigDefaults.FEATURES) result.add("feature." + feature);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) throws RemoteConfigException {
        if (value == null) return Collections.emptyMap();
        if (!(value instanceof Map)) throw reject(RemoteConfigException.Reason.CLAIMS,
                "remote config policy object is invalid");
        return (Map<String, Object>) value;
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static int integer(Object value, int fallback) {
        long number = whole(value, fallback);
        return number > Integer.MAX_VALUE || number < Integer.MIN_VALUE
                ? fallback : (int) number;
    }

    private static long whole(Object value, long fallback) {
        if (!(value instanceof Number)) return fallback;
        Number number = (Number) value;
        double decimal = number.doubleValue();
        long result = number.longValue();
        return Double.isFinite(decimal) && decimal == result ? result : fallback;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static RemoteConfigException reject(RemoteConfigException.Reason reason,
                                                String message) {
        return new RemoteConfigException(reason, message);
    }

    public static final class Timeouts {
        static final String[] NAMES = {
                "nativeLinkTotal", "providerConnect", "providerRead",
                "previewFirstFrame", "liveFirstFrame", "uhdFirstFrame",
                "stall", "previewTotalStartup", "liveTotalStartup", "uhdTotalStartup"
        };

        public final long nativeLinkTotalMs;
        public final long providerConnectMs;
        public final long providerReadMs;
        public final long previewFirstFrameMs;
        public final long liveFirstFrameMs;
        public final long uhdFirstFrameMs;
        public final long stallMs;
        public final long previewTotalStartupMs;
        public final long liveTotalStartupMs;
        public final long uhdTotalStartupMs;

        Timeouts(long nativeLinkTotalMs, long providerConnectMs, long providerReadMs,
                 long previewFirstFrameMs, long liveFirstFrameMs, long uhdFirstFrameMs,
                 long stallMs, long previewTotalStartupMs, long liveTotalStartupMs,
                 long uhdTotalStartupMs) {
            this.nativeLinkTotalMs = nativeLinkTotalMs;
            this.providerConnectMs = providerConnectMs;
            this.providerReadMs = providerReadMs;
            this.previewFirstFrameMs = previewFirstFrameMs;
            this.liveFirstFrameMs = liveFirstFrameMs;
            this.uhdFirstFrameMs = uhdFirstFrameMs;
            this.stallMs = stallMs;
            this.previewTotalStartupMs = previewTotalStartupMs;
            this.liveTotalStartupMs = liveTotalStartupMs;
            this.uhdTotalStartupMs = uhdTotalStartupMs;
        }

        static Timeouts defaults() {
            return new Timeouts(4000, 8000, 12000, 4500, 10000, 14000,
                    4000, 8000, 15000, 20000);
        }

        static Timeouts parse(Map<String, Object> source, Set<String> present) {
            Timeouts d = defaults();
            return new Timeouts(
                    bounded(source, "nativeLinkTotal", 1500, 8000, d.nativeLinkTotalMs, present),
                    bounded(source, "providerConnect", 2000, 12000, d.providerConnectMs, present),
                    bounded(source, "providerRead", 5000, 30000, d.providerReadMs, present),
                    bounded(source, "previewFirstFrame", 2500, 8000, d.previewFirstFrameMs, present),
                    bounded(source, "liveFirstFrame", 4000, 15000, d.liveFirstFrameMs, present),
                    bounded(source, "uhdFirstFrame", 6000, 20000, d.uhdFirstFrameMs, present),
                    bounded(source, "stall", 2500, 10000, d.stallMs, present),
                    bounded(source, "previewTotalStartup", 5000, 10000, d.previewTotalStartupMs, present),
                    bounded(source, "liveTotalStartup", 8000, 20000, d.liveTotalStartupMs, present),
                    bounded(source, "uhdTotalStartup", 12000, 25000, d.uhdTotalStartupMs, present));
        }

        Timeouts mergedOver(Timeouts base, Set<String> present) {
            Timeouts fallback = base == null ? defaults() : base;
            return new Timeouts(
                    pick("nativeLinkTotal", nativeLinkTotalMs, fallback.nativeLinkTotalMs, present),
                    pick("providerConnect", providerConnectMs, fallback.providerConnectMs, present),
                    pick("providerRead", providerReadMs, fallback.providerReadMs, present),
                    pick("previewFirstFrame", previewFirstFrameMs, fallback.previewFirstFrameMs, present),
                    pick("liveFirstFrame", liveFirstFrameMs, fallback.liveFirstFrameMs, present),
                    pick("uhdFirstFrame", uhdFirstFrameMs, fallback.uhdFirstFrameMs, present),
                    pick("stall", stallMs, fallback.stallMs, present),
                    pick("previewTotalStartup", previewTotalStartupMs, fallback.previewTotalStartupMs, present),
                    pick("liveTotalStartup", liveTotalStartupMs, fallback.liveTotalStartupMs, present),
                    pick("uhdTotalStartup", uhdTotalStartupMs, fallback.uhdTotalStartupMs, present));
        }

        private static long bounded(Map<String, Object> source, String key,
                                    long minimum, long maximum, long fallback,
                                    Set<String> present) {
            if (!source.containsKey(key)) return fallback;
            present.add("timeout." + key);
            return RemoteConfigDefaults.clamp(whole(source.get(key), fallback),
                    minimum, maximum, fallback);
        }

        private static long pick(String key, long value, long fallback, Set<String> present) {
            return present.contains("timeout." + key) ? value : fallback;
        }
    }
}
