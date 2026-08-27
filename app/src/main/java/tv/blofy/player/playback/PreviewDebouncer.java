package tv.blofy.player.playback;

import android.os.Handler;
import android.os.Looper;

/** Prevents rapid D-pad movement from starting a resolve/player prepare for every focused row. */
public final class PreviewDebouncer implements AutoCloseable {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long delayMs;
    private long generation;
    private Runnable pending;

    public PreviewDebouncer(long delayMs) {
        this.delayMs = Math.max(100L, Math.min(delayMs, 500L));
    }

    public synchronized void submit(Runnable action) {
        cancel();
        if (action == null) return;
        final long token = ++generation;
        pending = () -> {
            synchronized (PreviewDebouncer.this) {
                if (token != generation) return;
                pending = null;
            }
            action.run();
        };
        handler.postDelayed(pending, delayMs);
    }

    public synchronized void cancel() {
        generation++;
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
    }

    @Override public void close() { cancel(); }
}
