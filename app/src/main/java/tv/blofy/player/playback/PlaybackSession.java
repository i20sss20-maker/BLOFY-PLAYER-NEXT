package tv.blofy.player.playback;

import android.os.SystemClock;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlaybackSession {
    public enum State {
        IDLE, RESOLVING, PREPARING, BUFFERING, PLAYING, RECOVERING, ENDED, FAILED, CANCELLED
    }

    public final long epoch;
    public final PlaybackRequest request;
    public final long createdAtMs = SystemClock.elapsedRealtime();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile State state = State.IDLE;
    private volatile Future<?> pendingTask;
    private volatile AutoCloseable activeCancellation;

    PlaybackSession(long epoch, PlaybackRequest request) {
        this.epoch = epoch;
        this.request = request;
    }

    public State state() { return state; }
    public boolean isCancelled() { return cancelled.get(); }

    public synchronized void state(State value) {
        if (cancelled.get() && value != State.CANCELLED) return;
        state = value;
    }

    public synchronized void pendingTask(Future<?> task) {
        if (cancelled.get()) {
            if (task != null) task.cancel(true);
            return;
        }
        pendingTask = task;
    }

    public synchronized void cancellation(AutoCloseable cancellation) {
        if (cancelled.get()) {
            closeQuietly(cancellation);
            return;
        }
        closeQuietly(activeCancellation);
        activeCancellation = cancellation;
    }

    public synchronized void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        state = State.CANCELLED;
        if (pendingTask != null) pendingTask.cancel(true);
        pendingTask = null;
        closeQuietly(activeCancellation);
        activeCancellation = null;
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) {}
    }
}
