package tv.blofy.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Stable BLOFY device identity shared with the production v324+ application.
 *
 * Keep the preference names and registration rules byte-for-byte compatible: an
 * in-place NEXT upgrade must continue using the already activated device record.
 */
@SuppressLint("HardwareIds")
final class DeviceIdentity {
    private static final String PREFS = "blofy_native_identity";
    private static final String KEY_SECRET = "device_secret";
    private static final String KEY_PAIR_TOKEN = "pair_token";
    private static final String KEY_DISPLAY_ID = "display_id";
    private static final String KEY_PAIRING_CODE = "pairing_code";
    private static final String KEY_PUBLIC_REGISTERED = "public_identity_registered";
    private static final String KEY_PRIVATE_ID = "private_device_id";
    private static final String KEY_RECOVERY_PENDING = "fresh_identity_recovery_pending";
    private static final String KEY_PREVIOUS_PRIVATE_ID = "previous_private_device_id";
    private static final String KEY_PREVIOUS_SECRET = "previous_device_secret";

    private DeviceIdentity() {}

    static String id(Context context) {
        String persisted = preferences(context).getString(KEY_PRIVATE_ID, "");
        if (isPrivateId(persisted)) return persisted;
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("tv.blofy.player:" + androidId)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                value.append(String.format(Locale.US, "%02X", hash[index]));
            }
            return "BLOFY-" + value.substring(0, 4) + "-" + value.substring(4, 8)
                    + "-" + value.substring(8, 12) + "-" + value.substring(12, 16);
        } catch (Exception ignored) {
            return "BLOFY-ANDROID-DEVICE";
        }
    }

    static String startFreshPrivateIdentity(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getBoolean(KEY_RECOVERY_PENDING, false)) {
            String pendingId = preferences.getString(KEY_PRIVATE_ID, "");
            String pendingSecret = preferences.getString(KEY_SECRET, "");
            if (isPrivateId(pendingId)
                    && pendingSecret != null
                    && pendingSecret.matches("[A-F0-9]{64}")) {
                return pendingId;
            }
            throw new IllegalStateException("هوية استعادة الجهاز غير مكتملة.");
        }

        String previousId = id(context);
        String previousSecret = secret(context);
        byte[] idRandom = new byte[8];
        byte[] keyRandom = new byte[32];
        SecureRandom random = new SecureRandom();
        random.nextBytes(idRandom);
        random.nextBytes(keyRandom);
        String privateId = formatPrivateId(idRandom);
        String privateKey = hex(keyRandom);
        boolean saved = preferences.edit()
                .putString(KEY_PREVIOUS_PRIVATE_ID, previousId)
                .putString(KEY_PREVIOUS_SECRET, previousSecret)
                .putString(KEY_PRIVATE_ID, privateId)
                .putString(KEY_SECRET, privateKey)
                .putBoolean(KEY_RECOVERY_PENDING, true)
                .remove(KEY_DISPLAY_ID)
                .remove(KEY_PAIRING_CODE)
                .remove(KEY_PAIR_TOKEN)
                .remove(KEY_PUBLIC_REGISTERED)
                .commit();
        if (!saved) throw new IllegalStateException("تعذر حفظ هوية الجهاز الجديدة.");
        return privateId;
    }

    static boolean isFreshPrivateIdentityPending(Context context) {
        return preferences(context).getBoolean(KEY_RECOVERY_PENDING, false);
    }

    static boolean isPrivateId(String value) {
        return value != null
                && value.matches("BLOFY-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}");
    }

    static String displayId(Context context) {
        return hasRegisteredPublicIdentity(context)
                ? preferences(context).getString(KEY_DISPLAY_ID, "") : "";
    }

    static String activationCode(Context context) {
        return hasRegisteredPublicIdentity(context)
                ? preferences(context).getString(KEY_PAIRING_CODE, "") : "";
    }

    static String proposedDisplayId(Context context) {
        String saved = preferences(context).getString(KEY_DISPLAY_ID, "");
        String generated = stableCode(context, "display", 8);
        return registrationDisplayId(saved,
                "BLOFY-" + generated.substring(0, 4) + "-" + generated.substring(4, 8));
    }

    static String proposedActivationCode(Context context) {
        return registrationPairingCode(
                preferences(context).getString(KEY_PAIRING_CODE, ""),
                stableDigits(context, "activation", 6));
    }

    static String registrationDisplayId(String saved, String generated) {
        String value = saved == null ? "" : saved.trim().toUpperCase(Locale.US);
        if (value.matches("BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}")
                || value.matches("BLOFY-[A-Z0-9]{2}")) return value;
        return generated;
    }

    static String registrationPairingCode(String saved, String generated) {
        String value = saved == null ? "" : saved.trim();
        return value.matches("[0-9]{6}") ? value : generated;
    }

    static boolean updatePublicIdentity(Context context, JSONObject response) {
        if (response == null) return false;
        String displayId = response.optString("displayId", "").trim().toUpperCase(Locale.US);
        String pairingCode = response.optString("pairingCode", "").trim();
        if (!displayId.matches("BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}")
                || !pairingCode.matches("[0-9]{6}")) return false;
        return preferences(context).edit()
                .putString(KEY_DISPLAY_ID, displayId)
                .putString(KEY_PAIRING_CODE, pairingCode)
                .putBoolean(KEY_PUBLIC_REGISTERED, true)
                .remove(KEY_RECOVERY_PENDING)
                .remove(KEY_PREVIOUS_PRIVATE_ID)
                .remove(KEY_PREVIOUS_SECRET)
                .commit();
    }

    static boolean hasRegisteredPublicIdentity(Context context) {
        SharedPreferences preferences = preferences(context);
        if (!preferences.getBoolean(KEY_PUBLIC_REGISTERED, false)) return false;
        String displayId = preferences.getString(KEY_DISPLAY_ID, "");
        String pairingCode = preferences.getString(KEY_PAIRING_CODE, "");
        return displayId != null && displayId.matches("BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}")
                && pairingCode != null && pairingCode.matches("[0-9]{6}");
    }

    static String secret(Context context) {
        SharedPreferences preferences = preferences(context);
        String saved = preferences.getString(KEY_SECRET, "");
        if (saved != null && saved.matches("[A-F0-9]{64}")) return saved;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String created = hex(random);
        preferences.edit().putString(KEY_SECRET, created).apply();
        return created;
    }

    static void pairToken(Context context, String value) {
        preferences(context).edit().putString(
                KEY_PAIR_TOKEN, value == null ? "" : value).apply();
    }

    static String pairToken(Context context) {
        return preferences(context).getString(KEY_PAIR_TOKEN, "");
    }

    private static String formatPrivateId(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new IllegalArgumentException("private id requires eight bytes");
        }
        String value = hex(bytes);
        return "BLOFY-" + value.substring(0, 4) + "-" + value.substring(4, 8)
                + "-" + value.substring(8, 12) + "-" + value.substring(12, 16);
    }

    private static String stableCode(Context context, String purpose, int length) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
        try {
            byte[] hash = stableHash(context, purpose);
            StringBuilder value = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                value.append(alphabet[(hash[index] & 0xff) % alphabet.length]);
            }
            return value.toString();
        } catch (Exception ignored) {
            return "00000000";
        }
    }

    private static String stableDigits(Context context, String purpose, int length) {
        try {
            byte[] hash = stableHash(context, purpose);
            StringBuilder value = new StringBuilder(length);
            for (int index = 0; index < length; index++) value.append((hash[index] & 0xff) % 10);
            return value.toString();
        } catch (Exception ignored) {
            return "000000";
        }
    }

    private static byte[] stableHash(Context context, String purpose) throws Exception {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return MessageDigest.getInstance("SHA-256").digest(
                ("tv.blofy.player:" + purpose + ":" + androidId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte part : bytes) value.append(String.format(Locale.US, "%02X", part));
        return value.toString();
    }
}
