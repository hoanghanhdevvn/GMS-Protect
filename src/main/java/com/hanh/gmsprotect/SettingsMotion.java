package com.hanh.gmsprotect;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.animation.DecelerateInterpolator;

/** Pure Android 5.1+ UI transitions, never a background timer or service. */
public final class SettingsMotion {
    private SettingsMotion() {}
    private static int dp(Context c, int x) { return UiKit.dp(c, x); }

    public static RippleDrawable ripple(Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x1B666577),
                new ColorDrawable(Color.TRANSPARENT), null);
    }

    public static void largeTitle(Context c, LinearLayout page, String title) {
        TextView label = new TextView(c);
        label.setText(title);
        label.setTextColor(UiKit.TEXT);
        label.setTextSize(30f);
        label.setTypeface(null, android.graphics.Typeface.NORMAL);
        label.setGravity(Gravity.BOTTOM | Gravity.START);
        label.setPadding(dp(c, 20), dp(c, 103), dp(c, 20), dp(c, 22));
        page.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Mutable title for screens that switch between an overview and an app detail.
     *  Detail pages have only a compact toolbar; overview pages keep the large-title collapse.
     *  This affects presentation only and never schedules work or changes settings. */
    public static final class CollapsingHeader {
        private final TextView title;
        private final LinearLayout bar;
        private final ScrollView scroll;
        private final boolean hasBack;
        private final Context context;
        private boolean compactOnly;

        private CollapsingHeader(Context context, TextView title, LinearLayout bar,
                                 ScrollView scroll, boolean hasBack) {
            this.context = context;
            this.title = title;
            this.bar = bar;
            this.scroll = scroll;
            this.hasBack = hasBack;
        }

        public void setTitle(String value) { title.setText(value); }

        public void setCompactOnly(boolean compact) {
            compactOnly = compact;
            update();
        }

        private void update() {
            if (compactOnly) {
                bar.setVisibility(View.VISIBLE);
                bar.setAlpha(1f);
                bar.setBackgroundColor(UiKit.BG);
                title.setAlpha(1f);
                return;
            }
            float fraction = Math.max(0f, Math.min(1f,
                    (scroll.getScrollY() - dp(context, 65)) / (float) dp(context, 58)));
            if (hasBack) {
                bar.setVisibility(View.VISIBLE);
                bar.setAlpha(1f);
                title.setAlpha(fraction);
                bar.setBackgroundColor(Color.argb(Math.round(255 * fraction),
                        Color.red(UiKit.BG), Color.green(UiKit.BG), Color.blue(UiKit.BG)));
            } else {
                bar.setAlpha(fraction);
                bar.setVisibility(fraction > 0.01f ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    /** Floating small title fades in as the large title scrolls away. The status bar remains system-owned. */
    public static CollapsingHeader collapsingBar(Activity activity, FrameLayout frame, ScrollView scroll,
                                     String title, View.OnClickListener back) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(UiKit.BG);
        if (back != null) {
            TextView arrow = new TextView(activity);
            arrow.setText("‹");
            arrow.setTextSize(32f);
            arrow.setTextColor(UiKit.TEXT);
            arrow.setGravity(Gravity.CENTER);
            arrow.setBackground(ripple(activity));
            arrow.setOnClickListener(back);
            arrow.setContentDescription("Back");
            bar.addView(arrow, new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)));
        }
        TextView small = new TextView(activity);
        small.setText(title);
        small.setTextColor(UiKit.TEXT);
        small.setTextSize(20f);
        small.setGravity(Gravity.CENTER_VERTICAL);
        small.setPadding(back == null ? dp(activity, 20) : dp(activity, 4), 0, dp(activity, 16), 0);
        bar.addView(small, new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, dp(activity, 56), Gravity.TOP);
        frame.addView(bar, bp);
        // The back arrow remains accessible while the large overview title scrolls.
        CollapsingHeader header = new CollapsingHeader(activity, small, bar, scroll, back != null);
        header.update();
        scroll.getViewTreeObserver().addOnScrollChangedListener(header::update);
        scroll.setVerticalScrollBarEnabled(false);
        return header;
    }

    public static void enter(View target) {
        if (target == null || !target.isAttachedToWindow()) return;
        target.setAlpha(0.75f);
        target.setTranslationY(dp(target.getContext(), 8));
        target.animate().alpha(1f).translationY(0f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
}
