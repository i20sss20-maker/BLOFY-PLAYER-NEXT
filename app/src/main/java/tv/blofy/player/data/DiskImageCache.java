package tv.blofy.player.data;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Small bounded disk cache for poster bytes. Network/decode policy stays outside this class. */
public final class DiskImageCache {
    private final File directory;
    private final long maxBytes;

    public DiskImageCache(Context context, long maxBytes) {
        directory = new File(context.getCacheDir(), "poster-cache-v1");
        this.maxBytes = Math.max(8L * 1024L * 1024L, maxBytes);
        if (!directory.exists()) directory.mkdirs();
    }

    public synchronized byte[] get(String url) throws IOException {
        File file = fileFor(url);
        if (!file.isFile()) return null;
        file.setLastModified(System.currentTimeMillis());
        long size = file.length();
        if (size <= 0 || size > Integer.MAX_VALUE) {
            file.delete();
            return null;
        }
        byte[] data = new byte[(int) size];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != data.length) {
                file.delete();
                return null;
            }
        }
        return data;
    }

    public synchronized void put(String url, byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > maxBytes / 2) return;
        if (!directory.exists() && !directory.mkdirs()) return;
        File target = fileFor(url);
        File temp = new File(directory, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            if (target.exists()) target.delete();
            temp.renameTo(target);
        }
        trim();
    }

    public synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) file.delete();
    }

    private void trim() {
        File[] files = directory.listFiles(file -> file.isFile() && !file.getName().endsWith(".tmp"));
        if (files == null || files.length == 0) return;
        long total = 0L;
        for (File file : files) total += file.length();
        if (total <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            long length = file.length();
            if (file.delete()) total -= length;
            if (total <= maxBytes) break;
        }
    }

    private File fileFor(String url) {
        return new File(directory, sha256(url == null ? "" : url));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(result.length * 2);
            for (byte b : result) out.append(String.format(java.util.Locale.US, "%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
