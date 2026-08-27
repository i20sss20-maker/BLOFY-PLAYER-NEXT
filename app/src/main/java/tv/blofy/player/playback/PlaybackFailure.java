package tv.blofy.player.playback;

public final class PlaybackFailure extends Exception {
    public enum Type {
        NETWORK, AUTH, HTTP, TIMEOUT, SOURCE_EXPIRED, CONTAINER, CODEC,
        PLAYER, STALL, CANCELLED, STALE, UNKNOWN
    }

    public final Type type;
    public final String code;
    public final int httpStatus;
    public final boolean retryable;

    public PlaybackFailure(Type type, String code, String message, int httpStatus,
                           boolean retryable, Throwable cause) {
        super(message == null ? "" : message, cause);
        this.type = type == null ? Type.UNKNOWN : type;
        this.code = code == null ? "UNKNOWN" : code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public static PlaybackFailure cancelled() {
        return new PlaybackFailure(Type.CANCELLED, "PLAYBACK-CANCELLED", "cancelled", 0, false, null);
    }

    public static PlaybackFailure timeout(String stage) {
        String clean = stage == null ? "UNKNOWN" : stage.toUpperCase();
        return new PlaybackFailure(Type.TIMEOUT, clean + "-TIMEOUT", "timeout", 0, true, null);
    }
}
