package tv.blofy.player.data;

public final class CatalogItem {
    public final String playlistId;
    public final String kind;
    public final String id;
    public final String categoryId;
    public final String title;
    public final String imageUrl;
    public final String streamUrl;
    public final String extension;
    public final long updatedAt;

    public CatalogItem(String playlistId, String kind, String id, String categoryId,
                       String title, String imageUrl, String streamUrl,
                       String extension, long updatedAt) {
        this.playlistId = clean(playlistId);
        this.kind = clean(kind);
        this.id = clean(id);
        this.categoryId = clean(categoryId);
        this.title = clean(title);
        this.imageUrl = clean(imageUrl);
        this.streamUrl = clean(streamUrl);
        this.extension = clean(extension);
        this.updatedAt = Math.max(0L, updatedAt);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
