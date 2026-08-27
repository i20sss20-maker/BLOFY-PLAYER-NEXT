package tv.blofy.player.playback;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single owner for fullscreen/preview playback sessions.
 * A new request always invalidates and cancels the previous epoch first.
 */
public final class PlaybackCoordinator implements AutoCloseable {
    private final AtomicLong epochs = new AtomicLong();
    private PlaybackSession active;

    public synchronized PlaybackSession begin(PlaybackRequest request) {
        if (active != null) active.cancel();
        active = new PlaybackSession(epochs.incrementAndGet(), request);
        return active;
    }

    public synchronized PlaybackSession active() { return active; }

    public synchronized boolean isCurrent(long epoch) {
        return active != null && !active.isCancelled() && active.epoch == epoch;
    }

    /** Execute a callback only when it belongs to the current session. */
    public synchronized boolean ifCurrent(long epoch, Runnable action) {
        if (!isCurrent(epoch)) return false;
        action.run();
        return true;
    }

    public synchronized void cancelCurrent() {
        if (active != null) active.cancel();
        active = null;
    }

    @Override public void close() { cancelCurrent(); }
}
