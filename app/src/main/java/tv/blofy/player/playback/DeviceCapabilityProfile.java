package tv.blofy.player.playback;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Process;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Coarse, non-unique playback capabilities used only to separate learned routes.
 *
 * The token deliberately excludes device identifiers, model, manufacturer, serial,
 * Android ID and build fingerprint. It is safe to persist as part of a route-profile
 * key, but is not an authentication or device-identity value.
 *
 * TODO(native-link capabilityProfile): keep this token local until the backend
 * contract explicitly accepts the bounded cap-v1 schema; never substitute the
 * activation/device identity for it.
 */
public final class DeviceCapabilityProfile {
    private static final Pattern TOKEN = Pattern.compile(
            "cap-v1-(tv|nontv)-api(23_25|26_28|29_32|33plus)-"
                    + "(arm32|arm64|other32|other64)-"
                    + "avc[01]-hevc[01]-ac3[01]-eac3[01]-dts[01]");

    private final String value;

    private DeviceCapabilityProfile(String value) {
        this.value = value;
    }

    /** Detects only bounded compatibility signals; detection failure is conservative. */
    public static DeviceCapabilityProfile detect(Context context) {
        CodecFlags codecs = detectCodecs();
        return fromSignals(Build.VERSION.SDK_INT, isTelevision(context), abiFamily(),
                codecs.avc, codecs.hevc, codecs.ac3, codecs.eac3, codecs.dts);
    }

    /**
     * Accepts only this class's bounded v1 token. Legacy/arbitrary values such as
     * "default" are replaced locally and can never become a tracking identifier.
     */
    public static String resolve(Context context, String supplied) {
        String clean = supplied == null ? "" : supplied.trim().toLowerCase(Locale.US);
        return isRecognized(clean) ? clean : detect(context).value();
    }

    public String value() {
        return value;
    }

    static DeviceCapabilityProfile fromSignals(
            int sdkInt, boolean television, String abiFamily,
            boolean avc, boolean hevc, boolean ac3, boolean eac3, boolean dts) {
        String abi = normalizeAbi(abiFamily);
        String token = "cap-v1-" + (television ? "tv" : "nontv")
                + "-api" + apiBucket(sdkInt)
                + "-" + abi
                + "-avc" + bit(avc)
                + "-hevc" + bit(hevc)
                + "-ac3" + bit(ac3)
                + "-eac3" + bit(eac3)
                + "-dts" + bit(dts);
        if (!isRecognized(token)) {
            throw new IllegalStateException("invalid capability profile");
        }
        return new DeviceCapabilityProfile(token);
    }

    static boolean isRecognized(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    private static String apiBucket(int sdkInt) {
        if (sdkInt >= 33) return "33plus";
        if (sdkInt >= 29) return "29_32";
        if (sdkInt >= 26) return "26_28";
        return "23_25";
    }

    private static String normalizeAbi(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if ("arm64".equals(clean) || "arm32".equals(clean)
                || "other64".equals(clean) || "other32".equals(clean)) return clean;
        return "other32";
    }

    private static String abiFamily() {
        boolean is64Bit = Process.is64Bit();
        String primary = Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0
                ? "" : Build.SUPPORTED_ABIS[0].toLowerCase(Locale.US);
        boolean arm = primary.contains("arm") || primary.contains("aarch64");
        if (arm) return is64Bit ? "arm64" : "arm32";
        return is64Bit ? "other64" : "other32";
    }

    private static boolean isTelevision(Context context) {
        if (context == null) return false;
        try {
            UiModeManager manager = (UiModeManager) context.getSystemService(
                    Context.UI_MODE_SERVICE);
            return manager != null
                    && manager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static CodecFlags detectCodecs() {
        CodecFlags flags = new CodecFlags();
        try {
            MediaCodecInfo[] infos = new MediaCodecList(
                    MediaCodecList.ALL_CODECS).getCodecInfos();
            for (MediaCodecInfo info : infos) {
                if (info == null || info.isEncoder()) continue;
                String[] types;
                try {
                    types = info.getSupportedTypes();
                } catch (RuntimeException ignored) {
                    continue;
                }
                for (String type : types) flags.add(type);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A missing/broken codec registry yields a conservative all-false profile.
        }
        return flags;
    }

    private static char bit(boolean value) {
        return value ? '1' : '0';
    }

    private static final class CodecFlags {
        boolean avc;
        boolean hevc;
        boolean ac3;
        boolean eac3;
        boolean dts;

        void add(String value) {
            String type = value == null ? "" : value.trim().toLowerCase(Locale.US);
            if ("video/avc".equals(type)) avc = true;
            else if ("video/hevc".equals(type)) hevc = true;
            else if ("audio/ac3".equals(type)) ac3 = true;
            else if ("audio/eac3".equals(type) || "audio/eac3-joc".equals(type)) eac3 = true;
            else if ("audio/vnd.dts".equals(type) || "audio/vnd.dts.hd".equals(type)
                    || "audio/dts".equals(type) || "audio/dts-hd".equals(type)) dts = true;
        }
    }
}
