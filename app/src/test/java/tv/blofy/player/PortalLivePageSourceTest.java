package tv.blofy.player;

import org.junit.Test;

import tv.blofy.player.data.CatalogItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalLivePageSourceTest {
    @Test public void mapsPublicFieldsWithoutProviderSource() {
        CatalogItem item = PortalLivePageSource.mapItem(
                " playlist-1 ", "77", "5", "News",
                "https://images.example/live/user/password/77.png?token=secret",
                ".M3U8", 1234L, 50L);

        assertEquals("playlist-1", item.playlistId);
        assertEquals("live", item.kind);
        assertEquals("77", item.id);
        assertEquals("5", item.categoryId);
        assertEquals("News", item.title);
        assertEquals("m3u8", item.extension);
        assertEquals("", item.imageUrl);
        assertEquals("", item.streamUrl);
        assertEquals(50L, item.sortOrder);
        assertEquals(1234L, item.updatedAt);
    }

    @Test public void pagingStopsAtTotal() {
        assertTrue(PortalLivePageSource.hasMore(1, 50, 51));
        assertFalse(PortalLivePageSource.hasMore(2, 50, 51));
    }
}
