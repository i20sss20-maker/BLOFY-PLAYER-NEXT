package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import tv.blofy.player.diagnostics.DiagnosticsActivity;

/** Temporary clean shell. Existing BLOFY TV screens are migrated after the new playback core is proven. */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(9, 11, 18));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("BLOFY PLAYER NEXT");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button diagnostics = new Button(this);
        diagnostics.setText("معلومات التشخيص");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(24);
        root.addView(diagnostics, buttonParams);
        diagnostics.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        diagnostics.requestFocus();

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
