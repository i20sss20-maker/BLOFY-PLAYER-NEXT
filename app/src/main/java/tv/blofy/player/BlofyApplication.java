package tv.blofy.player;

import android.app.Application;

import tv.blofy.player.diagnostics.DiagnosticsLog;

public final class BlofyApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DiagnosticsLog.init(this);
    }
}
