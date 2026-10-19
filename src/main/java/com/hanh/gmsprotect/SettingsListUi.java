package com.hanh.gmsprotect;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.TextUtils;

/** microG-inspired native Settings list. View-only; no background worker. */
public final class SettingsListUi {
    public static final int GOOD = 1, BAD = -1, NEUTRAL = 0, INFO = 2;
    private SettingsListUi() {}
    private static int dp(Context c, int x) { return UiKit.dp(c, x); }

    public static TextView heading(Context c, String value) {
        TextView label = new TextView(c);
        label.setText(value);
        label.setTextColor(UiKit.MUTED);
        label.setTextSize(14.5f);
        label.setPadding(dp(c, 70), dp(c, 24), dp(c, 12), dp(c, 7));
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    public static LinearLayout group(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(UiKit.BG);
        return box;
    }

    public static void divider(Context c, LinearLayout box) {
        // A separator only where explicitly requested. microG does not draw borders around each item.
        if (box.getChildCount() == 0) return;
        View line = new View(c);
        line.setBackgroundColor(UiKit.BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 1));
        lp.topMargin = dp(c, 1);
        box.addView(line, lp);
    }

    public static LinearLayout row(Context c, LinearLayout group, String title,
                                   String subtitle, String status, int kind, View.OnClickListener action) {
        return row(c, group, 0, title, subtitle, status, kind, action);
    }

    public static LinearLayout row(Context c, LinearLayout group, int iconRes, String title,
                                   String subtitle, String status, int kind, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 12), dp(c, 10), dp(c, 14), dp(c, 10));
        row.setMinimumHeight(dp(c, subtitle == null && status == null ? 58 : 72));
        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageResource(iconRes);
            icon.setColorFilter(UiKit.MUTED);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(c, 25), dp(c, 25));
            ilp.leftMargin = dp(c, 4);
            ilp.rightMargin = dp(c, 27);
            row.addView(icon, ilp);
        } else {
            View spacer = new View(c);
            row.addView(spacer, new LinearLayout.LayoutParams(dp(c, 56), 1));
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(c);
        name.setText(title);
        name.setTextColor(UiKit.TEXT);
        name.setTextSize(16f);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView note = new TextView(c);
            note.setText(subtitle);
            note.setTextColor(kind == BAD ? UiKit.ERROR : UiKit.MUTED);
            note.setTextSize(13.5f);
            note.setMaxLines(2);
            note.setEllipsize(TextUtils.TruncateAt.END);
            note.setPadding(0, dp(c, 3), 0, 0);
            texts.addView(note, new LinearLayout.LayoutParams(-1, -2));
        }
        // On diagnostics, put the result beneath its label (not in a row of tiny badges).
        if (status != null && !status.isEmpty() && subtitle == null) {
            TextView value = new TextView(c);
            value.setText(status);
            value.setTextSize(13.5f);
            value.setTextColor(tint(kind));
            value.setPadding(0, dp(c, 3), 0, 0);
            value.setMaxLines(3);
            texts.addView(value, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (status != null && !status.isEmpty() && subtitle != null) {
            TextView value = new TextView(c);
            value.setText(status);
            value.setTextSize(12.5f);
            value.setTextColor(tint(kind));
            value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            value.setMaxLines(2);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = dp(c, 8);
            row.addView(value, vlp);
        }
        if (action != null) {
            row.setBackground(SettingsMotion.ripple(c));
            row.setOnClickListener(action);
            row.setClickable(true);
            row.setFocusable(true);
        }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    public static int tint(int kind) {
        return kind == GOOD ? UiKit.SUCCESS : kind == BAD ? UiKit.ERROR :
                kind == INFO ? UiKit.PRIMARY : UiKit.MUTED;
    }

    public static View separator(Context c) {
        View line = new View(c);
        line.setBackgroundColor(UiKit.BORDER);
        return line;
    }
}
