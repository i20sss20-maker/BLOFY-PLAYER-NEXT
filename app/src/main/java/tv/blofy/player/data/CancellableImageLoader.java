package tv.blofy.player.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cancellable image pipeline for list/poster cells. Requests are owned by a caller key;
 * reusing or cancelling that key aborts the old HTTP connection and prevents stale delivery.
 */
public final class CancellableImageLoader implements AutoCloseable {
    public interface Callback {
        void onImage(Bitmap bitmap, boolean fromDisk);
        void onError(Throwable error);
    }

    private static final int MAX_DOWNLOAD_BYTES = 3 * 1024 * 1024;
    private final DiskImageCache disk;
    private final ExecutorService io = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "blofy-image-io");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Request> active = new ConcurrentHashMap<>();

    public CancellableImageLoader(DiskImageCache disk) {
        this.disk = disk;
    }

    public long load(String ownerKey, String url, int targetWidth, int targetHeight, Callback callback) {
        String owner = clean(ownerKey);
        cancel(owner);
        if (owner.isEmpty() || clean(url).isEmpty()) return -1L;
        long token = sequence.incrementAndGet();
        Request request = new Request(token, owner, clean(url));
        active.put(owner, request);
        request.future = io.submit(() -> run(request, targetWidth, targetHeight, callback));
        return token;
    }

    public void cancel(String ownerKey) {
        Request request = active.remove(clean(ownerKey));
        if (request == null) return;
        request.cancelled = true;
        HttpURLConnection connection = request.connection;
        if (connection != null) connection.disconnect();
        Future<?> future = request.future;
        if (future != null) future.cancel(true);
    }

    private void run(Request request, int targetWidth, int targetHeight, Callback callback) {
        try {
            byte[] bytes = disk == null ? null : disk.get(request.url);
            boolean fromDisk = bytes != null;
            if (bytes == null) {
                bytes = download(request);
                if (isCurrent(request) && disk != null) disk.put(request.url, bytes);
            }
            if (!isCurrent(request)) return;
            Bitmap bitmap = decode(bytes, targetWidth, targetHeight);
            if (bitmap == null) throw new IllegalStateException("image decode failed");
            deliver(request, () -> callback.onImage(bitmap, fromDisk), callback);
        } catch (Throwable error) {
            if (isCurrent(request) && callback != null) deliver(request, () -> callback.onError(error), callback);
        } finally {
            active.remove(request.owner, request);
            HttpURLConnection connection = request.connection;
            request.connection = null;
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] download(Request request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url).openConnection();
        request.connection = connection;
        connection.setConnectTimeout(4500);
        connection.setReadTimeout(6500);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.5");
        connection.setRequestProperty("User-Agent", "BLOFY-PLAYER-NEXT/1");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("image HTTP " + status);
        int declared = connection.getContentLength();
        if (declared > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("image too large");
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, declared))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (!isCurrent(request) || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("image too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    static Bitmap decode(byte[] data, int targetWidth, int targetHeight) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        int width = Math.max(1, targetWidth);
        int height = Math.max(1, targetHeight);
        while ((bounds.outWidth / sample) > width * 2 || (bounds.outHeight / sample) > height * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private void deliver(Request request, Runnable action, Callback callback) {
        if (callback == null) return;
        main.post(() -> {
            if (isCurrent(request)) action.run();
        });
    }

    private boolean isCurrent(Request request) {
        return request != null && !request.cancelled && active.get(request.owner) == request;
    }

    @Override public void close() {
        for (String owner : active.keySet()) cancel(owner);
        io.shutdownNow();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static final class Request {
        final long token;
        final String owner;
        final String url;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile Future<?> future;
        Request(long token, String owner, String url) {
            this.token = token;
            this.owner = owner;
            this.url = url;
        }
    }
}
