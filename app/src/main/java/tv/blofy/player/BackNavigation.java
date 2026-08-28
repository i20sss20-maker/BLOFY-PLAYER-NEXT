package tv.blofy.player;

import android.app.Activity;
import android.os.Build;

import androidx.annotation.RequiresApi;

/** Bridges the existing TV Back action to Android 13+ predictive-back dispatch. */
final class BackNavigation {
    private BackNavigation() {}

    static void register(Activity activity, Runnable action) {
        if (activity == null || action == null || Build.VERSION.SDK_INT < 33) return;
        Api33.register(activity, action);
    }

    @RequiresApi(33)
    private static final class Api33 {
        private Api33() {}

        static void register(Activity activity, Runnable action) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, action::run);
        }
    }
}
