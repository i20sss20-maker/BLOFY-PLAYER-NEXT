package tv.blofy.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.media3.common.util.UnstableApi;

/** Small compatibility bridge for actions that used to be temporary toasts. */
@UnstableApi
final class ToastBridge {
    private ToastBridge() {}

    static void show(Context context, String message) {
        if (message != null && message.contains("الإعدادات الجديدة")) {
            context.startActivity(new Intent(context, SettingsActivity.class));
            return;
        }
        if (message != null && message.contains("تحديث الباقة")) {
            new PlaylistSelectionStore(context).requestCatalogRefresh();
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            if (context instanceof Activity) ((Activity) context).finish();
            return;
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
