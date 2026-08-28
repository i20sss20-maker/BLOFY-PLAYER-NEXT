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
import tv.blofy.player.playback.DeviceCapabilityProfile;
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
    private boolean fullscreenOpening;
    private String deviceProfile;
    private int uiFocusedIndex = -1;
    private int pendingRestoredFocus = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingRestoredFocus = state.getInt("focused_index", -1);
        setContentView(buildUi());

        deviceProfile = DeviceCapabilityProfile.resolve(this, extra("device_profile"));

        repository = new CatalogRepository(new CatalogDatabase(getApplicationContext()), new CatalogMemoryCache(18));
        preview = new LivePreviewController(getApplicationContext());
        preview.attach(previewSurface);
        LiveScreenController.Config config = new LiveScreenController.Config(
                extra("playlist_id"), "", extra("category_id"),
                "", "", deviceProfile, 80);
        controller = new LiveScreenController(repository, preview, config, this);
        controller.start(pendingRestoredFocus);
        pendingRestoredFocus = -1;
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
            if (fullscreenOpening || controller == null) return;
            controller.restoreFocus(position);
            controller.openFocused(SystemClock.elapsedRealtime());
        });
        list.setOnKeyListener((v, keyCode, event) -> {
            if (controller == null) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    controller.openFocused(event.getEventTime());
                }
                // Always consume OK down/up/repeats so ListView cannot emit a second click.
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return controller.move(-1);
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return controller.move(1);
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
            items.clear();
            if (next != null) items.addAll(next);
            uiFocusedIndex = focusedIndex;
            adapter.notifyDataSetChanged();
            if (focusedIndex >= 0 && focusedIndex < items.size()) {
                list.setSelection(focusedIndex);
                list.setItemChecked(focusedIndex, true);
            }
            if (loadingMore && items.isEmpty()) previewState.setText("جاري تحميل القنوات…");
        });
    }

    @Override public void onFocusChanged(int focusedIndex) {
        runOnUiThread(() -> {
            int previous = uiFocusedIndex;
            uiFocusedIndex = focusedIndex;
            list.setItemChecked(focusedIndex, true);
            list.setSelection(focusedIndex);
            refreshVisibleRow(previous);
            refreshVisibleRow(focusedIndex);
        });
    }

    @Override public void onPreviewState(PlaybackSession.State state) {
        runOnUiThread(() -> {
            if (state == PlaybackSession.State.PLAYING
                    || state == PlaybackSession.State.CANCELLED
                    || state == PlaybackSession.State.IDLE) {
                previewState.setVisibility(View.GONE);
            } else {
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

    @Override public void onOpenFullscreen(PlaybackRequest request, long handoffId) {
        if (fullscreenOpening) return;
        fullscreenOpening = true;
        Intent intent = new Intent(this, FullscreenPlayerActivity.class);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_HANDOFF_ID, handoffId);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_PLAYLIST_ID, request.playlistId);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_STREAM_ID, request.streamId);
        // ID-only recovery data. Provider host/source URLs and headers never enter an Intent.
        intent.putExtra(FullscreenPlayerActivity.EXTRA_EXTENSION, request.extension);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_DEVICE_PROFILE, deviceProfile);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_ULTRA_HD, request.ultraHd);
        try {
            startActivity(intent);
        } catch (RuntimeException failure) {
            fullscreenOpening = false;
            if (controller != null) controller.resumePreview();
        }
    }

    @Override public void onError(Throwable error) {
        runOnUiThread(() -> {
            previewState.setVisibility(View.VISIBLE);
            previewState.setText("تعذر تحميل القنوات");
        });
    }

    @Override protected void onResume() {
        super.onResume();
        fullscreenOpening = false;
        if (list != null) list.requestFocus();
        if (controller != null) controller.resumePreview();
    }

    @Override protected void onPause() {
        // Home and Activity transitions begin at onPause; invalidate delayed
        // page/focus work before this screen can become hidden.
        if (controller != null) controller.suspendPreview();
        super.onPause();
    }

    @Override protected void onStop() {
        if (preview != null) preview.detach(isChangingConfigurations());
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (controller != null) {
            state.putInt("focused_index", controller.restorableFocusIndex());
        }
        super.onSaveInstanceState(state);
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
            row.setBackgroundColor(position == uiFocusedIndex
                    ? Color.rgb(72, 42, 120) : Color.TRANSPARENT);
            return row;
        }
    }

    private void refreshVisibleRow(int position) {
        if (position < list.getFirstVisiblePosition() || position > list.getLastVisiblePosition()) return;
        View row = list.getChildAt(position - list.getFirstVisiblePosition());
        if (row != null) row.setBackgroundColor(position == uiFocusedIndex
                ? Color.rgb(72, 42, 120) : Color.TRANSPARENT);
    }

    private String extra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value.trim();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
