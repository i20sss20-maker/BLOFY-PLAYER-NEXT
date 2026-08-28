package tv.blofy.player.data;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CatalogBatchImporterAtomicTest {
    private CatalogBatchImporter importer;

    @After public void closeImporter() {
        if (importer != null) importer.close();
    }

    @Test public void publishesOnlyAfterEveryPageCompletes() throws Exception {
        FakeStore store = new FakeStore();
        importer = importer(store);
        CountDownLatch complete = new CountDownLatch(1);

        importer.start("p1", "live", new CatalogBatchImporter.PageSource() {
            int page;

            @Override public CatalogBatchImporter.Page fetch(int ignored, int pageSize) {
                if (page++ == 0) {
                    return new CatalogBatchImporter.Page(
                            Collections.singletonList(item("1")), true, 2);
                }
                // Page one is safely staged; readers still see the old generation.
                assertEquals(Collections.singletonList("old"), store.visibleIds());
                return new CatalogBatchImporter.Page(
                        Collections.singletonList(item("2")), false, 2);
            }
        }, 100, listener(complete, null));

        assertTrue(complete.await(3, TimeUnit.SECONDS));
        assertTrue(store.committed);
        assertFalse(store.aborted);
        assertEquals(list("1", "2"), store.visibleIds());
    }

    @Test public void failedPageAbortsAndPreservesOldCatalog() throws Exception {
        FakeStore store = new FakeStore();
        importer = importer(store);
        CountDownLatch error = new CountDownLatch(1);

        importer.start("p1", "live", new CatalogBatchImporter.PageSource() {
            int page;

            @Override public CatalogBatchImporter.Page fetch(int ignored, int pageSize)
                    throws Exception {
                if (page++ == 0) {
                    return new CatalogBatchImporter.Page(
                            Collections.singletonList(item("new")), true, 2);
                }
                throw new IOException("provider failed on page two");
            }
        }, 100, listener(null, error));

        assertTrue(error.await(3, TimeUnit.SECONDS));
        assertTrue(store.abortedLatch.await(3, TimeUnit.SECONDS));
        assertFalse(store.committed);
        assertEquals(Collections.singletonList("old"), store.visibleIds());
    }

    @Test public void cancellationDiscardsStagingAndPreservesOldCatalog() throws Exception {
        FakeStore store = new FakeStore();
        importer = importer(store);
        CountDownLatch secondFetch = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);

        importer.start("p1", "live", new CatalogBatchImporter.PageSource() {
            int page;

            @Override public CatalogBatchImporter.Page fetch(int ignored, int pageSize)
                    throws Exception {
                if (page++ == 0) {
                    return new CatalogBatchImporter.Page(
                            Collections.singletonList(item("new")), true, 2);
                }
                secondFetch.countDown();
                block.await();
                return new CatalogBatchImporter.Page(
                        Collections.singletonList(item("never")), false, 2);
            }

            @Override public void close() {
                block.countDown();
            }
        }, 100, listener(null, null));

        assertTrue(secondFetch.await(3, TimeUnit.SECONDS));
        importer.cancel();
        assertTrue(store.abortedLatch.await(3, TimeUnit.SECONDS));
        assertFalse(store.committed);
        assertEquals(Collections.singletonList("old"), store.visibleIds());
    }

    @Test public void emptySuccessfulResponseDoesNotEraseOldCatalog() throws Exception {
        FakeStore store = new FakeStore();
        importer = importer(store);
        CountDownLatch complete = new CountDownLatch(1);

        importer.start("p1", "live",
                (page, pageSize) -> new CatalogBatchImporter.Page(
                        Collections.emptyList(), false, 0),
                100, listener(complete, null));

        assertTrue(complete.await(3, TimeUnit.SECONDS));
        assertTrue(store.abortedLatch.await(3, TimeUnit.SECONDS));
        assertFalse(store.committed);
        assertEquals(Collections.singletonList("old"), store.visibleIds());
    }

    @Test public void duplicateIdsAcrossPagesCommitAsOneDistinctRow() throws Exception {
        FakeStore store = new FakeStore();
        importer = importer(store);
        CountDownLatch complete = new CountDownLatch(1);

        importer.start("p1", "live", new CatalogBatchImporter.PageSource() {
            int page;

            @Override public CatalogBatchImporter.Page fetch(int ignored, int pageSize) {
                if (page++ == 0) {
                    return new CatalogBatchImporter.Page(
                            Collections.singletonList(item("same")), true, 2);
                }
                return new CatalogBatchImporter.Page(
                        Collections.singletonList(item("same")), false, 2);
            }
        }, 100, listener(complete, null));

        assertTrue(complete.await(3, TimeUnit.SECONDS));
        assertTrue(store.committed);
        assertEquals(Collections.singletonList("same"), store.visibleIds());
    }

    private static CatalogBatchImporter importer(FakeStore store) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return new CatalogBatchImporter(store, executor);
    }

    private static CatalogBatchImporter.Listener listener(
            CountDownLatch complete, CountDownLatch error) {
        return new CatalogBatchImporter.Listener() {
            @Override public void onProgress(int percent, int storedItems) {}

            @Override public void onComplete(int storedItems) {
                if (complete != null) complete.countDown();
            }

            @Override public void onError(Throwable failure) {
                if (error != null) error.countDown();
            }
        };
    }

    private static CatalogItem item(String id) {
        return new CatalogItem("p1", "live", id, "c", id,
                "", "", "ts", 1L, 0L);
    }

    private static List<String> list(String first, String second) {
        List<String> values = new ArrayList<>();
        values.add(first);
        values.add(second);
        return values;
    }

    private static final class FakeStore implements CatalogImportStore {
        private final Map<String, CatalogItem> staging = new LinkedHashMap<>();
        private List<String> visible = Collections.singletonList("old");
        final CountDownLatch abortedLatch = new CountDownLatch(1);
        volatile boolean committed;
        volatile boolean aborted;

        @Override public synchronized void beginImport(long generation, String playlistId,
                                                       String kind) {
            staging.clear();
        }

        @Override public synchronized int stagePage(long generation, String playlistId,
                                                     String kind, List<CatalogItem> items) {
            for (CatalogItem item : items) staging.put(item.id, item);
            return staging.size();
        }

        @Override public synchronized int commitImport(long generation, String playlistId,
                                                       String kind, int expectedItems) {
            assertEquals(expectedItems, staging.size());
            List<String> replacement = new ArrayList<>();
            for (CatalogItem item : staging.values()) replacement.add(item.id);
            visible = replacement;
            committed = true;
            return staging.size();
        }

        @Override public synchronized void abortImport(long generation, String playlistId,
                                                       String kind) {
            staging.clear();
            aborted = true;
            abortedLatch.countDown();
        }

        synchronized List<String> visibleIds() {
            return new ArrayList<>(visible);
        }
    }
}
