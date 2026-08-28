package tv.blofy.player.playback;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

import tv.blofy.player.MainActivity;
import tv.blofy.player.R;

/**
 * Lifecycle guard for one application-owned fullscreen playback session.
 *
 * This service deliberately owns no player, resolver, URL, credentials, or network client. The
 * process-wide PlaybackSessionHost remains the only playback owner; the service only supplies the
 * required foreground notification and one non-reference-counted partial WakeLock.
 */
@UnstableApi
public final class PlaybackKeepAliveService extends Service {
    private static final String ACTION_START = "tv.blofy.player.playback.KEEP_ALIVE_START";
    private static final String ACTION_STOP = "tv.blofy.player.playback.KEEP_ALIVE_STOP";
    private static final String EXTRA_SESSION_ID = "session_id";
    private static final String CHANNEL_ID = "blofy_live_playback";
    private static final int NOTIFICATION_ID = 20260828;

    private PowerManager.WakeLock wakeLock;
    private long activeSessionId;

    static void start(Context context, long sessionId) {
        if (context == null || sessionId <= 0L) return;
        Intent intent = intent(context, ACTION_START, sessionId);
        try {
            ContextCompat.startForegroundService(context.getApplicationContext(), intent);
        } catch (RuntimeException ignored) {
            // Playback remains usable if a device policy refuses foreground-service startup.
        }
    }

    static void stop(Context context, long sessionId) {
        if (context == null || sessionId <= 0L) return;
        Intent intent = intent(context, ACTION_STOP, sessionId);
        Context app = context.getApplicationContext();
        try {
            app.startService(intent);
        } catch (RuntimeException ignored) {
            // A direct stop is safe as a fallback; the Host serializes stop-before-next-start.
            try { app.stopService(intent); } catch (RuntimeException ignoredAgain) {}
        }
    }

    private static Intent intent(Context context, String action, long sessionId) {
        return new Intent(context, PlaybackKeepAliveService.class)
                .setAction(action)
                .putExtra(EXTRA_SESSION_ID, sessionId);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        long sessionId = intent == null ? 0L : intent.getLongExtra(EXTRA_SESSION_ID, 0L);
        if (ACTION_START.equals(action) && sessionId > 0L) {
            activeSessionId = sessionId;
            try {
                startInForeground();
                acquireWakeLock();
            } catch (RuntimeException denied) {
                activeSessionId = 0L;
                releaseWakeLock();
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action) && sessionId == activeSessionId) {
            activeSessionId = 0L;
            releaseWakeLock();
            removeForegroundNotification();
            stopSelfResult(startId);
        } else if (activeSessionId == 0L) {
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_blofy_playback)
                .setContentTitle("BLOFY PLAYER")
                .setContentText("البث المباشر قيد التشغيل")
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "تشغيل البث المباشر", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("يحافظ على استقرار جلسة البث أثناء المشاهدة");
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setLightColor(Color.TRANSPARENT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "BLOFY PLAYER:LivePlayback");
            wakeLock.setReferenceCounted(false);
        }
        try {
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (RuntimeException ignored) {
            // Some vendor ROMs deny WakeLocks despite the manifest permission.
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
            // Never let a vendor PowerManager failure keep the service alive.
        }
        wakeLock = null;
    }

    private void removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        activeSessionId = 0L;
        releaseWakeLock();
        removeForegroundNotification();
        super.onDestroy();
    }
}
