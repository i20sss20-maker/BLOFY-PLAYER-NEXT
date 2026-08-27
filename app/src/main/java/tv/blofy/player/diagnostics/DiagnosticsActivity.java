package tv.blofy.player.diagnostics;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class DiagnosticsActivity extends Activity {
    private DiagnosticsStore store;
    private TextView report;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DiagnosticsStore(this);
        setContentView(buildUi());
        refresh();
    }

    private ScrollView buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(13, 10, 24));

        TextView title = new TextView(this);
        title.setText("معلومات التشخيص");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setGravity(Gravity.END);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        report = new TextView(this);
        report.setTextColor(Color.LTGRAY);
        report.setTextSize(14f);
        report.setTextIsSelectable(true);
        report.setGravity(Gravity.START);
        LinearLayout.LayoutParams reportParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        reportParams.topMargin = dp(16);
        root.addView(report, reportParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        Button copy = new Button(this);
        copy.setText("نسخ التقرير");
        copy.setOnClickListener(v -> copy());
        actions.addView(copy);

        Button clear = new Button(this);
        clear.setText("مسح السجل");
        clear.setOnClickListener(v -> { store.clear(); refresh(); Toast.makeText(this, "تم مسح سجل التشخيص", Toast.LENGTH_SHORT).show(); });
        actions.addView(clear);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void refresh() { report.setText(store.report()); }

    private void copy() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("BLOFY diagnostics", store.report()));
        Toast.makeText(this, "تم نسخ تقرير التشخيص", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
