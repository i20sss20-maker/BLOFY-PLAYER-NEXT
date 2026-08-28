package tv.blofy.player.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class DeviceCapabilityProfileTest {
    @Test public void tokenContainsOnlyCoarseBoundedSignals() {
        DeviceCapabilityProfile profile = DeviceCapabilityProfile.fromSignals(
                24, true, "arm32", true, false, true, false, true);

        assertEquals("cap-v1-tv-api23_25-arm32-avc1-hevc0-ac31-eac30-dts1",
                profile.value());
        assertTrue(DeviceCapabilityProfile.isRecognized(profile.value()));
        assertTrue(profile.value().matches("[a-z0-9_-]+"));
        assertTrue(profile.value().length() < 96);
    }

    @Test public void apiVersionsAreBucketedRatherThanFingerprintingExactBuild() {
        String api23 = profile(23);
        String api25 = profile(25);
        String api26 = profile(26);
        String api32 = profile(32);
        String api33 = profile(33);
        String future = profile(40);

        assertEquals(api23, api25);
        assertNotEquals(api25, api26);
        assertNotEquals(api26, api32);
        assertNotEquals(api32, api33);
        assertEquals(api33, future);
    }

    @Test public void arbitraryOrIdentityShapedValuesAreNotCapabilityTokens() {
        assertFalse(DeviceCapabilityProfile.isRecognized("default"));
        assertFalse(DeviceCapabilityProfile.isRecognized("BLOFY-ABCD-1234"));
        assertFalse(DeviceCapabilityProfile.isRecognized(
                "cap-v1-tv-api33plus-arm64-avc1-hevc1-ac31-eac31-dts1-serial123"));
    }

    private static String profile(int sdk) {
        return DeviceCapabilityProfile.fromSignals(
                sdk, false, "arm64", true, true, false, false, false).value();
    }
}
