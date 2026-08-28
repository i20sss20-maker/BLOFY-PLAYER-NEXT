package tv.blofy.player.remoteconfig;

import org.junit.Test;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RemoteConfigManagerTest {
    @Test public void absentOptionalConfigKeepsCompiledDefaults() {
        MutableClock clock = new MutableClock(1_700_000_000L);
        RemoteConfigManager manager = new RemoteConfigManager(
                new RemoteConfigStore(new MemoryBackend()),
                new RemoteConfigVerifier(RemoteConfigVerifier.NONE), clock, 1001001);

        RemoteConfigManager.UpdateResult global = manager.acceptGlobal(null);
        RemoteConfigManager.UpdateResult provider = manager.acceptProvider(
                null, "pp_abcdefghijklmnopqrstuvwx", 1);

        assertFalse(global.present);
        assertFalse(provider.present);
        assertEquals(0L, global.snapshot.effective.revision);
        assertEquals("adaptive", provider.snapshot.effective.enginePreference);
    }

    @Test public void cachesVerifiedRevisionAndRejectsRollbackAndEquivocation() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        MutableClock clock = new MutableClock(1_700_000_000L);
        MemoryBackend backend = new MemoryBackend();
        RemoteConfigStore store = new RemoteConfigStore(backend);
        RemoteConfigManager manager = manager(store, pair, clock);
        RemoteConfigEnvelope revisionTwo = RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.global(2, clock.now, clock.now + 3600,
                        "{\"playback\":{\"enginePreference\":\"media3_first\"}}"), pair);

        RemoteConfigManager.UpdateResult accepted = manager.acceptGlobal(revisionTwo);

        assertTrue(accepted.accepted);
        assertEquals(2L, accepted.snapshot.effective.revision);
        assertEquals("media3_first", accepted.snapshot.effective.enginePreference);
        RemoteConfigManager restarted = manager(store, pair, clock);
        assertEquals(2L, restarted.current("", 0).effective.revision);

        RemoteConfigEnvelope rollback = RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.global(1, clock.now, clock.now + 3600, "{}"), pair);
        RemoteConfigManager.UpdateResult rejected = restarted.acceptGlobal(rollback);
        assertEquals(RemoteConfigException.Reason.ROLLBACK, rejected.rejection);
        assertEquals(2L, rejected.snapshot.effective.revision);

        RemoteConfigEnvelope equivocal = RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.global(2, clock.now, clock.now + 3600,
                        "{\"features\":{\"telemetry\":true}}"), pair);
        rejected = restarted.acceptGlobal(equivocal);
        assertEquals(RemoteConfigException.Reason.ROLLBACK, rejected.rejection);
        assertEquals(2L, rejected.snapshot.effective.revision);
    }

    @Test public void expiryOrMissingBuildKeyFallsBackToCompiledDefaults() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        MutableClock clock = new MutableClock(1_700_000_000L);
        RemoteConfigStore store = new RemoteConfigStore(new MemoryBackend());
        RemoteConfigManager trusted = manager(store, pair, clock);
        trusted.acceptGlobal(RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.global(5, clock.now, clock.now + 600,
                        "{\"features\":{\"telemetry\":true}}"), pair));
        clock.now += 601;

        RemoteConfigSnapshot expired = trusted.current("", 0);

        assertEquals(0L, expired.effective.revision);
        assertFalse(expired.effective.feature("telemetry"));
        assertNull(store.global());

        RemoteConfigManager untrusted = new RemoteConfigManager(
                new RemoteConfigStore(new MemoryBackend()),
                new RemoteConfigVerifier(RemoteConfigVerifier.NONE), clock, 1001001);
        RemoteConfigManager.UpdateResult rejected = untrusted.acceptGlobal(
                RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.global(
                        6, clock.now, clock.now + 600, "{}"), pair));
        assertEquals(RemoteConfigException.Reason.UNTRUSTED, rejected.rejection);
        assertEquals(0L, rejected.snapshot.effective.revision);
    }

    @Test public void providerCacheIsBoundToNativeLinkProfileRevision() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        MutableClock clock = new MutableClock(1_700_000_000L);
        RemoteConfigStore store = new RemoteConfigStore(new MemoryBackend());
        RemoteConfigManager manager = manager(store, pair, clock);
        manager.acceptGlobal(RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.global(10, clock.now, clock.now + 3600,
                        "{\"playback\":{\"enginePreference\":\"media3_first\"}}"), pair));
        String profile = "pp_abcdefghijklmnopqrstuvwx";
        RemoteConfigEnvelope provider = RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.provider(11, clock.now, clock.now + 3600,
                        profile, 3,
                        "{\"playback\":{\"transportPolicy\":\"prefer_hls\"}}"), pair);

        RemoteConfigManager.UpdateResult result = manager.acceptProvider(provider, profile, 3);

        assertTrue(result.accepted);
        assertEquals("media3_first", result.snapshot.effective.enginePreference);
        assertEquals("prefer_hls", result.snapshot.effective.transportPolicy);
        assertEquals(3, result.snapshot.provider.profileRevision);
        assertNull(manager.current(profile, 4).provider);
        RemoteConfigManager.UpdateResult mismatch = manager.acceptProvider(provider, profile, 4);
        assertEquals(RemoteConfigException.Reason.SCOPE, mismatch.rejection);
    }

    @Test public void cacheCommitFailureIsFailSafeAndDoesNotReportAcceptance() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        MutableClock clock = new MutableClock(1_700_000_000L);
        MemoryBackend backend = new MemoryBackend();
        backend.failWrites = true;
        RemoteConfigManager manager = manager(new RemoteConfigStore(backend), pair, clock);

        RemoteConfigManager.UpdateResult result = manager.acceptGlobal(
                RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.global(
                        1, clock.now, clock.now + 600, "{}"), pair));

        assertFalse(result.accepted);
        assertEquals(RemoteConfigException.Reason.STORAGE, result.rejection);
        assertEquals(0L, result.snapshot.effective.revision);
    }

    private static RemoteConfigManager manager(RemoteConfigStore store, KeyPair pair,
                                               MutableClock clock) {
        return new RemoteConfigManager(store, new RemoteConfigVerifier(
                RemoteConfigVerifier.oneEs256Key(RemoteConfigTestSupport.KID,
                        RemoteConfigTestSupport.publicKey(pair))), clock, 1001001);
    }

    private static final class MutableClock implements RemoteConfigManager.Clock {
        long now;
        MutableClock(long now) { this.now = now; }
        @Override public long epochSeconds() { return now; }
    }

    private static final class MemoryBackend implements RemoteConfigStore.Backend {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Long> numbers = new HashMap<>();
        boolean failWrites;

        @Override public String string(String key) { return strings.getOrDefault(key, ""); }
        @Override public long number(String key) { return numbers.getOrDefault(key, 0L); }

        @Override public boolean atomic(Map<String, String> nextStrings,
                                        Map<String, Long> nextNumbers,
                                        Set<String> removals) {
            if (failWrites) return false;
            for (String key : removals) { strings.remove(key); numbers.remove(key); }
            strings.putAll(nextStrings);
            numbers.putAll(nextNumbers);
            return true;
        }

        @Override public void removePrefix(String prefix) {
            for (String key : new HashSet<>(strings.keySet())) {
                if (key.startsWith(prefix)) strings.remove(key);
            }
            for (String key : new HashSet<>(numbers.keySet())) {
                if (key.startsWith(prefix)) numbers.remove(key);
            }
        }
    }
}
