package tv.blofy.player.data;

import java.util.List;

/** Persistence boundary used by the importer and its deterministic unit tests. */
interface CatalogImportStore {
    void beginImport(long importGeneration, String playlistId, String kind);
    /** Stages a page and returns the total distinct rows currently staged. */
    int stagePage(long importGeneration, String playlistId, String kind,
                  List<CatalogItem> items);
    int commitImport(long importGeneration, String playlistId, String kind, int expectedItems);
    void abortImport(long importGeneration, String playlistId, String kind);
}
