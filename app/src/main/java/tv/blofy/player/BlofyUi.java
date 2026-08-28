package tv.blofy.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared BLOFY cinematic television design system. */
final class BlofyUi {
    static final int BLACK = Color.rgb(5, 5, 12);
    static final int NAVY = Color.rgb(9, 9, 20);
    static final int PANEL = Color.rgb(17, 16, 30);
    static final int PANEL_ALT = Color.rgb(24, 20, 42);
    static final int PANEL_SOFT = Color.rgb(38, 25, 68);
    static final int PURPLE = Color.rgb(124, 43, 255);
    static final int PURPLE_DARK = Color.rgb(72, 12, 171);
    static final int PURPLE_LIGHT = Color.rgb(188, 132, 255);
    static final int CYAN = Color.rgb(77, 212, 224);
    static final int TEXT = Color.rgb(249, 248, 252);
    static final int MUTED = Color.rgb(169, 166, 181);
    static final int SUCCESS = Color.rgb(66, 221, 157);
    static final int ERROR = Color.rgb(255, 105, 137);
    static final int STROKE = Color.rgb(48, 39, 76);
    static final int DIVIDER = Color.rgb(34, 28, 52);

    // Kept byte-compatible with the v340 settings screen without requiring the
    // legacy SettingsActivity to be present while the NEXT interface is restored.
    private static final String MOTION_PREFS = "blofy_player_settings";
    private static final String KEY_MOTION = "motion_mode";

    private BlofyUi() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static boolean isTv(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    static TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        view.setIncludeFontPadding(false);
        view.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
        return view;
    }

    static TextView title(Context context, String value, int sp) {
        TextView view = text(context, value, sp, TEXT);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setLetterSpacing(0.005f);
        return view;
    }

    static TextView chip(Context context, String value) {
        TextView chip = text(context, value, 11, TEXT);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setBackground(panel(context, Color.argb(205, 21, 19, 34), 8, STROKE));
        chip.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        return chip;
    }

    static EditText input(Context context, String hint, boolean numeric) {
        EditText view = new EditText(context);
        view.setSingleLine(true);
        view.setHint(hint);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setTextSize(14);
        view.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        view.setBackground(focusDrawable(context, Color.argb(220, 16, 15, 28),
                PANEL_SOFT, PURPLE_LIGHT));
        view.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        view.setFocusable(true);
        return view;
    }

    static Button button(Context context, String label, boolean primary) {
        Button view = new Button(context);
        view.setText(label);
        view.setTextColor(TEXT);
        view.setTextSize(14);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        view.setBackground(primary ? primaryButtonDrawable(context)
                : focusDrawable(context, Color.argb(210, 23, 21, 36),
                PANEL_SOFT, PURPLE_LIGHT));
        view.setFocusable(true);
        view.setStateListAnimator(null);
        attachScaleFocus(view, 1.008f);
        // Some Android TV firmwares ignore nextFocusRight/Left on RTL GridLayout.
        // Fall back to Android's geometric focus search so all four DPAD arrows
        // remain responsive. Screens that install their own key listener override this.
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int direction;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) direction = View.FOCUS_LEFT;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) direction = View.FOCUS_RIGHT;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) direction = View.FOCUS_UP;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) direction = View.FOCUS_DOWN;
            else return false;
            View next = v.focusSearch(direction);
            if (next == null || next == v) return false;
            next.requestFocus();
            return true;
        });
        return view;
    }

    static TextView navChip(Context context, String label) {
        TextView chip = title(context, label, 14);
        chip.setGravity(Gravity.CENTER);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setBackground(focusDrawable(context, Color.TRANSPARENT, PANEL_SOFT, PURPLE_LIGHT));
        chip.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        attachScaleFocus(chip, 1.006f);
        return chip;
    }

    static TextView sidebarItem(Context context, String icon, String label, boolean selected) {
        TextView item = title(context, icon + "    " + label, 14);
        item.setTextDirection(View.TEXT_DIRECTION_RTL);
        item.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setSingleLine(true);
        item.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        item.setBackground(selected ? selectedDrawable(context)
                : focusDrawable(context, Color.TRANSPARENT, PANEL_SOFT, PURPLE_LIGHT));
        if (!selected) item.setTextColor(Color.rgb(213, 210, 221));
        attachScaleFocus(item, 1.006f);
        return item;
    }

    static Drawable panel(Context context, int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, Math.max(0, radiusDp)));
        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(context, 1), strokeColor);
        }
        return drawable;
    }

    static Drawable gradientPanel(Context context, int start, int end,
                                  int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start, end});
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(context, 1), strokeColor);
        }
        return drawable;
    }

    static Drawable focusDrawable(Context context, int normal, int focused, int focusStroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(context, PURPLE_DARK, 13, PURPLE_LIGHT, 2));
        states.addState(new int[]{android.R.attr.state_focused},
                rounded(context, focused, 13, focusStroke, 2));
        states.addState(new int[]{}, rounded(context, normal, 13,
                normal == Color.TRANSPARENT ? Color.TRANSPARENT : STROKE, 1));
        return states;
    }

    private static Drawable primaryButtonDrawable(Context context) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(151, 70, 255), Color.rgb(102, 27, 224)});
        focused.setCornerRadius(dp(context, 13));
        focused.setStroke(dp(context, 2), Color.WHITE);
        GradientDrawable idle = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(128, 44, 255), Color.rgb(91, 20, 206)});
        idle.setCornerRadius(dp(context, 13));
        idle.setStroke(dp(context, 1), PURPLE_LIGHT);
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, idle);
        return states;
    }

    private static Drawable selectedDrawable(Context context) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(84, 25, 160), Color.rgb(37, 18, 76)});
        focused.setCornerRadius(dp(context, 14));
        focused.setStroke(dp(context, 2), PURPLE_LIGHT);
        GradientDrawable idle = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(62, 19, 124), Color.rgb(31, 15, 62)});
        idle.setCornerRadius(dp(context, 14));
        idle.setStroke(dp(context, 1), Color.rgb(112, 53, 196));
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{}, idle);
        return states;
    }

    private static GradientDrawable rounded(Context context, int color, int radiusDp,
                                             int stroke, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(context, strokeDp), stroke);
        return drawable;
    }

    static Drawable screenGradient() {
        return new Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(android.graphics.Canvas canvas) {
                paint.setShader(new LinearGradient(0, 0, canvas.getWidth(), canvas.getHeight(),
                        new int[]{Color.rgb(4, 4, 10), Color.rgb(7, 6, 15),
                                Color.rgb(11, 8, 25), Color.rgb(5, 5, 12)},
                        new float[]{0f, 0.42f, 0.78f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(getBounds(), paint);
                paint.setShader(null);
                paint.setColor(Color.argb(16, 124, 43, 255));
                canvas.drawCircle(canvas.getWidth() * .78f, canvas.getHeight() * .86f,
                        canvas.getWidth() * .34f, paint);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) {
                paint.setColorFilter(filter);
            }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }

    static Drawable heroScrim() {
        return new Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(android.graphics.Canvas canvas) {
                paint.setShader(new LinearGradient(0, 0, canvas.getWidth(), 0,
                        new int[]{Color.argb(248, 6, 6, 13), Color.argb(218, 7, 7, 15),
                                Color.argb(74, 7, 6, 15), Color.argb(8, 7, 6, 15)},
                        new float[]{0f, .34f, .72f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(getBounds(), paint);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) {
                paint.setColorFilter(filter);
            }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    static LinearLayout brand(Context context, String subtitle) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(logo, new LinearLayout.LayoutParams(dp(context, 50), dp(context, 50)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(context, 9), 0, 0, 0);
        TextView name = title(context, "BLOFY", 18);
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        TextView player = text(context, subtitle == null ? "P L A Y E R" : subtitle,
                9, PURPLE_LIGHT);
        player.setTextDirection(View.TEXT_DIRECTION_LTR);
        player.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        labels.addView(name);
        labels.addView(player);
        row.addView(labels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    static void attachScaleFocus(View view, float scale) {
        view.setOnFocusChangeListener((v, focused) -> {
            String motion = v.getContext().getSharedPreferences(MOTION_PREFS,
                    Context.MODE_PRIVATE).getString(KEY_MOTION, "smooth");
            float effectiveScale = "reduced".equals(motion) ? 1f : Math.min(scale, 1.008f);
            float target = focused ? effectiveScale : 1f;
            v.animate().cancel();
            v.animate().scaleX(target).scaleY(target)
                    .setDuration("reduced".equals(motion) ? 55 : 90).start();
            v.setElevation(focused ? dp(v.getContext(), 8) : 0);
        });
    }

    static ColorStateList progressColors() {
        return ColorStateList.valueOf(PURPLE);
    }
}
