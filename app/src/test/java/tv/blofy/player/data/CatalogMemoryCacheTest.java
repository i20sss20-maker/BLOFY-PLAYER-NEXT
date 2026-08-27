package tv.blofy.player.data;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public final class CatalogMemoryCacheTest {
    @Test public void returnsDefensiveCopyAndInvalidatesPlaylist() {
        CatalogMemoryCache cache = new CatalogMemoryCache(4);
        String key = CatalogMemoryCache.key("p1", "live", "c1", 0, 50);
        CatalogItem item = new CatalogItem("p1", "live", "1", "c1", "Channel", "", "u", "ts", 1);
        cache.put(key, Collections.singletonList(item));
        assertNotNull(cache.get(key));
        assertEquals(1, cache.get(key).size());
        cache.invalidatePlaylist("p1");
        assertNull(cache.get(key));
    }
}
