package com.hanh.gmsprotect;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
    public static final int PRIMARY = 0xFF50647F;
    public static final int PRIMARY_DARK = 0xFF35465E;
    public static final int PRIMARY_SOFT = 0xFFEEEFF7;
    public static final int BG = 0xFFF7F5FA;
    public static final int SURFACE = 0xFFF7F5FA;
    public static final int TEXT = 0xFF262832;
    public static final int MUTED = 0xFF686A76;
    public static final int BORDER = 0xFFE1DFE7;
    public static final int SUCCESS = 0xFF16875E;
    public static final int ERROR = 0xFFCA4A59;

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



    public static TextView muted(Context context, String text, float size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(Math.max(size, 12.5f));
        view.setTextColor(MUTED);
        return view;
    }



    public static Button primaryButton(Context context, String text, View.OnClickListener action) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15f);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(PRIMARY, 14, context));
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setOnClickListener(action);
        return button;
    }

    public static Button secondaryButton(Context context, String text, View.OnClickListener action) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(PRIMARY);
        button.setBackground(rounded(PRIMARY_SOFT, 12, context, 0xFFD8D5F2, 1));
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setOnClickListener(action);
        return button;
    }


}
