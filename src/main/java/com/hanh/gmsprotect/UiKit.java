package com.hanh.gmsprotect;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
    public static final int PRIMARY = 0xFF3F3D9B;
    public static final int PRIMARY_DARK = 0xFF302E78;
    public static final int PRIMARY_SOFT = 0xFFECEBFF;
    public static final int BG = 0xFFF6F6FB;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int TEXT = 0xFF202033;
    public static final int MUTED = 0xFF77778B;
    public static final int BORDER = 0xFFE7E7F0;
    public static final int SUCCESS = 0xFF28A96B;
    public static final int SUCCESS_SOFT = 0xFFE7F7EF;
    public static final int ERROR = 0xFFE45A62;
    public static final int ERROR_SOFT = 0xFFFFECEE;

    private UiKit() {}

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void applyTopSystemInset(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetTop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.statusBars()).top
                    : insets.getSystemWindowInsetTop();
            v.setPadding(left, top + insetTop, right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    public static void applyBottomSystemInset(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetBottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            v.setPadding(left, top, right, bottom + insetBottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    public static GradientDrawable rounded(int color, int radiusDp, Context context) {
        return rounded(color, radiusDp, context, null, 1);
    }

    public static GradientDrawable rounded(int color, int radiusDp, Context context, Integer strokeColor, int strokeDp) {
        GradientDrawable out = new GradientDrawable();
        out.setShape(GradientDrawable.RECTANGLE);
        out.setColor(color);
        out.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != null) out.setStroke(dp(context, strokeDp), strokeColor);
        return out;
    }

    public static GradientDrawable verticalGradient() {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PRIMARY_DARK, PRIMARY}
        );
    }

    public static TextView muted(Context context, String text, float size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(MUTED);
        return view;
    }

    public static TextView pill(Context context, String text, int bg, int fg) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(11f);
        view.setTextColor(fg);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(dp(context, 11), dp(context, 6), dp(context, 11), dp(context, 6));
        view.setBackground(rounded(bg, 99, context));
        return view;
    }

    public static Button primaryButton(Context context, String text, View.OnClickListener action) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(PRIMARY, 14, context));
        button.setMinHeight(dp(context, 50));
        button.setMinimumHeight(dp(context, 50));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setOnClickListener(action);
        return button;
    }

    public static Button secondaryButton(Context context, String text, View.OnClickListener action) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(PRIMARY);
        button.setBackground(rounded(PRIMARY_SOFT, 12, context, 0xFFD8D5F2, 1));
        button.setMinHeight(dp(context, 46));
        button.setMinimumHeight(dp(context, 46));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setOnClickListener(action);
        return button;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        card.setBackground(rounded(SURFACE, 20, context, BORDER, 1));
        card.setElevation(dp(context, 1));
        return card;
    }
}
