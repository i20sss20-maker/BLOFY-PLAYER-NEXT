package tv.blofy.player;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * A small, immutable description of the device resources that are safe to use for UI work.
 *
 * <p>The automatic profile intentionally uses conservative limits. IPTV catalog screens may keep
 * several decoded posters alive at once, so the process memory class is a more useful signal than
 * CPU count alone. The explicit modes are a foundation for the performance option in Settings;
 * they never override Android's low-RAM signal with unsafe quality settings.</p>
 */
final class DeviceCapabilityProfile {
    static final String SETTINGS_NAME = "blofy_player_settings";
    static final String KEY_PERFORMANCE_MODE = "performance_mode";

    enum PerformanceMode {
        AUTOMATIC("auto"),
        QUALITY("quality"),
        FAST("fast");

        final String preferenceValue;

        PerformanceMode(String preferenceValue) {
            this.preferenceValue = preferenceValue;
        }

        static PerformanceMode fromPreference(String value) {
            String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (PerformanceMode mode : values()) {
                if (mode.preferenceValue.equals(clean)) return mode;
            }
            return AUTOMATIC;
        }
    }

    private final PerformanceMode requestedMode;
    private final boolean systemLowRam;
    private final boolean constrained;
    private final int memoryClassMb;
    private final int imageWorkerCount;
    private final int imageCacheKilobytes;
    private final int imageTargetWidth;
    private final int imageTargetHeight;

    private DeviceCapabilityProfile(PerformanceMode requestedMode,
                                    boolean systemLowRam,
                                    boolean constrained,
                                    int memoryClassMb,
                                    int imageWorkerCount,
                                    int imageCacheKilobytes,
                                    int imageTargetWidth,
                                    int imageTargetHeight) {
        this.requestedMode = requestedMode;
        this.systemLowRam = systemLowRam;
        this.constrained = constrained;
        this.memoryClassMb = memoryClassMb;
        this.imageWorkerCount = imageWorkerCount;
        this.imageCacheKilobytes = imageCacheKilobytes;
        this.imageTargetWidth = imageTargetWidth;
        this.imageTargetHeight = imageTargetHeight;
    }

    static DeviceCapabilityProfile detect(Context context) {
        Context application = context.getApplicationContext();
        SharedPreferences preferences = application.getSharedPreferences(
                SETTINGS_NAME, Context.MODE_PRIVATE);
        PerformanceMode mode = PerformanceMode.fromPreference(
                preferences.getString(KEY_PERFORMANCE_MODE, PerformanceMode.AUTOMATIC.preferenceValue));

        ActivityManager manager = (ActivityManager) application.getSystemService(
                Context.ACTIVITY_SERVICE);
        boolean lowRam = manager != null && manager.isLowRamDevice();
        int memoryClass = manager == null ? 128 : Math.max(64, manager.getMemoryClass());
        int runtimeLimitMb = (int) Math.max(64L,
                Runtime.getRuntime().maxMemory() / (1024L * 1024L));
        int effectiveMemoryMb = Math.min(memoryClass, runtimeLimitMb);
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());

        boolean hardwareConstrained = lowRam || effectiveMemoryMb <= 128;
        boolean constrained = hardwareConstrained || mode == PerformanceMode.FAST;

        int workers;
        int cacheMb;
        int targetWidth;
        int targetHeight;
        if (constrained) {
            workers = Math.min(2, processors);
            cacheMb = Math.max(6, Math.min(10, effectiveMemoryMb / 16));
            targetWidth = 480;
            targetHeight = 720;
        } else if (mode == PerformanceMode.QUALITY) {
            workers = Math.max(2, Math.min(6, processors));
            cacheMb = Math.max(20, Math.min(40, effectiveMemoryMb / 8));
            targetWidth = 900;
            targetHeight = 1_350;
        } else {
            workers = Math.max(2, Math.min(4, processors));
            cacheMb = Math.max(12, Math.min(28, effectiveMemoryMb / 10));
            targetWidth = 720;
            targetHeight = 1_080;
        }

        return new DeviceCapabilityProfile(mode, lowRam, constrained, effectiveMemoryMb,
                workers, cacheMb * 1024, targetWidth, targetHeight);
    }

    PerformanceMode requestedMode() { return requestedMode; }

    boolean isSystemLowRam() { return systemLowRam; }

    boolean usesReducedPerformance() { return constrained; }

    int memoryClassMb() { return memoryClassMb; }

    int imageWorkerCount() { return imageWorkerCount; }

    int imageCacheKilobytes() { return imageCacheKilobytes; }

    int imageTargetWidth() { return imageTargetWidth; }

    int imageTargetHeight() { return imageTargetHeight; }
}
