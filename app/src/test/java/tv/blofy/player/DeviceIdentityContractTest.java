package tv.blofy.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeviceIdentityContractTest {
    @Test public void preservesRegisteredLegacyPublicIdsDuringMigration() {
        assertEquals("BLOFY-ABCD-1234",
                DeviceIdentity.registrationDisplayId(
                        " blofy-abcd-1234 ", "BLOFY-NEW1-NEW2"));
        assertEquals("BLOFY-A1",
                DeviceIdentity.registrationDisplayId("BLOFY-A1", "BLOFY-NEW1-NEW2"));
    }

    @Test public void preservesOnlySixDigitPairingCodes() {
        assertEquals("012345",
                DeviceIdentity.registrationPairingCode("012345", "999999"));
        assertEquals("999999",
                DeviceIdentity.registrationPairingCode("12345", "999999"));
    }
}
