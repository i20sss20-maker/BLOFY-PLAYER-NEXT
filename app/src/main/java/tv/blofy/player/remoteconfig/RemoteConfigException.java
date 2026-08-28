package tv.blofy.player.remoteconfig;

/** Typed, deliberately non-secret failure used by Remote Config plumbing. */
public final class RemoteConfigException extends Exception {
    public enum Reason {
        MALFORMED, UNTRUSTED, SIGNATURE, CLAIMS, EXPIRED, NOT_YET_VALID,
        AUDIENCE, VERSION, ROLLBACK, SCOPE, STORAGE
    }

    public final Reason reason;

    public RemoteConfigException(Reason reason, String message) {
        this(reason, message, null);
    }

    public RemoteConfigException(Reason reason, String message, Throwable cause) {
        super(message == null ? "remote config rejected" : message, cause);
        this.reason = reason == null ? Reason.MALFORMED : reason;
    }
}
