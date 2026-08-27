package tv.blofy.player.live;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.List;

import tv.blofy.player.data.CatalogDatabase;
import tv.blofy.player.data.CatalogItem;
import tv.blofy.player.data.CatalogMemoryCache;
import tv.blofy.player.data.CatalogRepository;
import tv.blofy.player.playback.FullscreenPlayerActivity;
import tv.blofy.player.playback.LivePreviewController;
import tv.blofy.player.playback.PlaybackFailure;
import tv.blofy.player.playback.PlaybackRequest;
import tv.blofy.player.playback.PlaybackRoute;
import tv.blofy.player.playback.PlaybackSession;

/** First real TV Live screen: cache-first list + stable D-pad focus + bounded preview. */
@UnstableApi
public final class LiveActivity extends Activity implements LiveScreenController.Listener {
    private final List<CatalogItem> items = new ArrayList<>();
    private CatalogRepository repository;
    private LivePreviewController preview;
    private LiveScreenController controller;
    private ChannelAdapter adapter;
    private ListView list;
    private SurfaceView previewSurface;
    private TextView previewState;
    private boolean rendering;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());

        repository = new CatalogRepository(new CatalogDatabase(getApplicationContext()), new CatalogMemoryCache(18));
        preview = new LivePreviewController(getApplicationContext());
        preview.attach(previewSurface);
        LiveScreenController.Config config = new LiveScreenController.Config(
                extra("playlist_id"), extra("provider_host"), extra("category_id"),
                extra("user_agent"), extra("referer"), defaultExtra("device_profile", "default"), 80);
        controller = new LiveScreenController(repository, preview, config, this);
        controller.start();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(9, 11, 18));

        list = new ListView(this);
        list.setDividerHeight(1);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setFocusable(true);
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            controller.restoreFocus(position);
            controller.openFocused(SystemClock.elapsedRealtime());
        });
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || controller == null) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return controller.move(-1);
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return controller.move(1);
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return controller.openFocused(event.getEventTime());
            }
            return false;
        });
        root.addView(list, new LinearLayout.LayoutParams(0, -1, 0.38f));

        FrameLayout right = new FrameLayout(this);
        previewSurface = new SurfaceView(this);
        right.addView(previewSurface, new FrameLayout.LayoutParams(-1, -1));
        previewState = new TextView(this);
        previewState.setTextColor(Color.WHITE);
        previewState.setTextSize(16f);
        previewState.setGravity(Gravity.CENTER);
        previewState.setBackgroundColor(0x66000000);
        right.addView(previewState, new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM));
        root.addView(right, new LinearLayout.LayoutParams(0, -1, 0.62f));
        return root;
    }

    @Override public void onItems(List<CatalogItem> next, int focusedIndex, boolean loadingMore) {
        runOnUiThread(() -> {
            rendering = true;
            items.clear();
            if (next != null) items.addAll(next);
            adapter.notifyDataSetChanged();
            if (focusedIndex >= 0 && focusedIndex < items.size()) {
                list.setSelection(focusedIndex);
                list.setItemChecked(focusedIndex, true);
            }
            if (loadingMore && items.isEmpty()) previewState.setText("جاري تحميل القنوات…");
            rendering = false;
        });
    }

    @Override public void onPreviewState(PlaybackSession.State state) {
        runOnUiThread(() -> {
            if (state == PlaybackSession.State.PLAYING) previewState.setVisibility(View.GONE);
            else {
                previewState.setVisibility(View.VISIBLE);
                previewState.setText(state == PlaybackSession.State.RECOVERING ? "جاري تجربة مسار آخر…" : "جاري تشغيل المعاينة…");
            }
        });
    }

    @Override public void onPreviewFirstFrame(PlaybackRoute route, long elapsedMs) {
        runOnUiThread(() -> previewState.setVisibility(View.GONE));
    }

    @Override public void onPreviewFailure(PlaybackFailure failure, String diagnostics) {
        runOnUiThread(() -> {
            previewState.setVisibility(View.VISIBLE);
            previewState.setText("تعذرت المعاينة");
        });
    }

    @Override public void onOpenFullscreen(PlaybackRequest request) {
        Intent intent = new Intent(this, FullscreenPlayerActivity.class);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_PLAYLIST_ID, request.playlistId);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_PROVIDER_HOST, request.providerHost);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_STREAM_ID, request.streamId);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_STREAM_URL, request.sourceUrl);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_EXTENSION, request.extension);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_USER_AGENT, request.userAgent);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_REFERER, request.referer);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_DEVICE_PROFILE, defaultExtra("device_profile", "default"));
        intent.putExtra(FullscreenPlayerActivity.EXTRA_ULTRA_HD, request.ultraHd);
        startActivity(intent);
    }

    @Override public void onError(Throwable error) {
        runOnUiThread(() -> {
            previewState.setVisibility(View.VISIBLE);
            previewState.setText("تعذر تحميل القنوات");
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) list.requestFocus();
    }

    @Override protected void onDestroy() {
        if (controller != null) controller.close();
        if (preview != null) preview.close();
        if (repository != null) repository.close();
        controller = null;
        preview = null;
        repository = null;
        super.onDestroy();
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(LiveActivity.this);
            row.setText(items.get(position).title);
            row.setTextColor(Color.WHITE);
            row.setTextSize(18f);
            row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            row.setPadding(dp(18), dp(10), dp(18), dp(10));
            row.setFocusable(false);
            row.setBackgroundColor(list.isItemChecked(position) ? Color.rgb(72, 42, 120) : Color.TRANSPARENT);
            return row;
        }
    }

    private String extra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value.trim();
    }
    private String defaultExtra(String key, String fallback) {
        String value = extra(key);
        return value.isEmpty() ? fallback : value;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
