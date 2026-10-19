package com.hanh.gmsprotect;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Read-only log viewer. No permission, system write, watcher or background work. */
public final class ActivityLogActivity extends Activity {
    private LinearLayout entries;
    private LinearLayout filters;
    private ScrollView scroll;
    private int selectedFilter = 0; // all / important / errors
    private int dp(int value) { return UiKit.dp(this, value); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? UiKit.BG : UiKit.PRIMARY_DARK);
        getWindow().setNavigationBarColor(UiKit.SURFACE);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GameWatchEvidence.noteUi(this, true);
        if (entries != null) refresh(true);
    }

    @Override
    protected void onPause() {
        GameWatchEvidence.noteUi(this, false);
        super.onPause();
    }

    private void buildScreen() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(UiKit.BG);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        UiKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        SettingsMotion.collapsingBar(this, frame, scroll,
                getString(R.string.gamewatch_logs), v -> finish());
        setContentView(frame);

        SettingsMotion.largeTitle(this, page, getString(R.string.gamewatch_logs));
        TextView intro = UiKit.muted(this, getString(R.string.activity_log_intro_v2), 13.5f);
        intro.setPadding(dp(20), 0, dp(20), dp(14));
        page.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(dp(20), dp(1), dp(12), dp(8));
        page.addView(filters, new LinearLayout.LayoutParams(-1, -2));
        updateFilters();

        entries = SettingsListUi.group(this);
        page.addView(entries, new LinearLayout.LayoutParams(-1, -2));

        page.addView(SettingsListUi.heading(this, getString(R.string.activity_log_actions)),
                new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = SettingsListUi.group(this);
        SettingsListUi.row(this, actions, R.drawable.sg_ic_history,
                getString(R.string.activity_log_copy_technical), null, null,
                SettingsListUi.NEUTRAL, v -> copyTechnical());
        SettingsListUi.row(this, actions, R.drawable.sg_ic_check,
                getString(R.string.activity_log_refresh), null, null,
                SettingsListUi.NEUTRAL, v -> refresh(false));
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        refresh(false);
        page.post(() -> SettingsMotion.enter(entries));
    }

    private GradientDrawable pill(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFFE4EAEE : UiKit.BG);
        bg.setCornerRadius(dp(18));
        if (!selected) bg.setStroke(dp(1), UiKit.BORDER);
        return bg;
    }

    private void updateFilters() {
        filters.removeAllViews();
        final int[] labels = {
                R.string.activity_log_all, R.string.activity_log_important, R.string.activity_log_errors
        };
        for (int i = 0; i < labels.length; i++) {
            final int filter = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(13f);
            chip.setTypeface(null, i == selectedFilter ? Typeface.BOLD : Typeface.NORMAL);
            chip.setTextColor(i == selectedFilter ? UiKit.TEXT : UiKit.MUTED);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            chip.setBackground(pill(i == selectedFilter));
            chip.setOnClickListener(v -> {
                if (selectedFilter == filter) return;
                selectedFilter = filter;
                updateFilters();
                refresh(false);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(35));
            p.rightMargin = dp(8);
            filters.addView(chip, p);
        }
    }

    private boolean visible(GameWatchJournal.Event e) {
        if (selectedFilter == 0) return true;
        if (selectedFilter == 2) return "error".equals(e.kind) || "warning".equals(e.kind);
        return !"info".equals(e.kind);
    }

    private void refresh(boolean preserveScroll) {
        if (entries == null) return;
        final int offset = preserveScroll && scroll != null ? scroll.getScrollY() : 0;
        entries.removeAllViews();
        List<GameWatchJournal.Event> data = GameWatchJournal.entries(this);
        SimpleDateFormat dayFmt = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String lastDay = null;
        int shown = 0;
        for (GameWatchJournal.Event e : data) {
            if (!visible(e)) continue;
            String day = dayFmt.format(new Date(e.lastMs));
            if (!day.equals(lastDay)) {
                lastDay = day;
                entries.addView(SettingsListUi.heading(this, day),
                        new LinearLayout.LayoutParams(-1, -2));
            }
            appendEvent(e, timeFmt.format(new Date(e.lastMs)));
            shown++;
        }
        if (shown == 0) {
            TextView empty = UiKit.muted(this, getString(R.string.activity_log_empty), 14f);
            empty.setPadding(dp(70), dp(18), dp(20), dp(22));
            entries.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, offset));
    }

    private void appendEvent(GameWatchJournal.Event e, String time) {
        int color = "success".equals(e.kind) ? UiKit.SUCCESS
                : "error".equals(e.kind) || "warning".equals(e.kind) ? UiKit.ERROR
                : "info".equals(e.kind) ? UiKit.PRIMARY : UiKit.MUTED;
        String symbol = "success".equals(e.kind) ? "✓"
                : "error".equals(e.kind) ? "!"
                : "warning".equals(e.kind) ? "!"
                : "change".equals(e.kind) ? "↻" : "i";
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(12), dp(12), dp(18), dp(12));
        row.setBackground(SettingsMotion.ripple(this));
        row.setClickable(true);
        row.setFocusable(true);

        TextView icon = new TextView(this);
        icon.setText(symbol);
        icon.setTextColor(color);
        icon.setTextSize(21f);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(32));
        ip.leftMargin = dp(5);
        ip.rightMargin = dp(17);
        row.addView(icon, ip);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleFor(e.code));
        title.setTextSize(15.5f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        headline.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView stamp = new TextView(this);
        stamp.setText(time);
        stamp.setTextSize(12f);
        stamp.setTextColor(UiKit.MUTED);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(8);
        headline.addView(stamp, tp);
        content.addView(headline);

        TextView sub = new TextView(this);
        sub.setText(subtitleFor(e));
        sub.setTextSize(13f);
        sub.setTextColor(UiKit.MUTED);
        sub.setPadding(0, dp(4), 0, 0);
        content.addView(sub);
        if (e.count > 1) {
            TextView repeated = new TextView(this);
            repeated.setText(getString(R.string.activity_log_repeated, e.count));
            repeated.setTextSize(12f);
            repeated.setTextColor(UiKit.MUTED);
            repeated.setPadding(0, dp(3), 0, 0);
            content.addView(repeated);
        }

        TextView technical = new TextView(this);
        technical.setText(getString(R.string.activity_log_technical_item,
                e.code, e.kind, e.detail, e.count));
        technical.setTextSize(12f);
        technical.setTextColor(UiKit.MUTED);
        technical.setTextIsSelectable(true);
        technical.setPadding(0, dp(8), 0, 0);
        technical.setVisibility(View.GONE);
        content.addView(technical);
        row.setOnClickListener(v -> technical.setVisibility(
                technical.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1f));
        entries.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = SettingsListUi.separator(this);
        LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(-1, dp(1));
        d.leftMargin = dp(70);
        entries.addView(divider, d);
    }

    private String subtitleFor(GameWatchJournal.Event e) {
        if (e.code.startsWith("app_added") || e.code.startsWith("app_enabled")
                || e.code.startsWith("app_disabled") || e.code.startsWith("app_removed"))
            return e.detail; // package name, not raw debug data
        switch (e.code) {
            case "millet_repaired": return getString(R.string.activity_log_desc_millet_repaired);
            case "millet_error": return getString(R.string.activity_log_desc_millet_error);
            case "mipush_requested": return getString(R.string.activity_log_desc_mipush_requested);
            case "mipush_error": return getString(R.string.activity_log_desc_mipush_error);
            case "game_repaired": return getString(R.string.activity_log_desc_game_repaired);
            case "game_changed": return getString(R.string.activity_log_desc_game_changed);
            case "game_deferred": return getString(R.string.activity_log_desc_game_deferred);
            case "game_error": return getString(R.string.activity_log_desc_game_error);
            case "watch_registered": return getString(R.string.activity_log_desc_watch_registered);
            case "watch_verified": return getString(R.string.activity_log_desc_watch_verified);
            case "watch_error": return getString(R.string.activity_log_desc_watch_error);
            case "app_error": return getString(R.string.activity_log_desc_app_error);
            default: return getString(R.string.activity_log_desc_other);
        }
    }

    private String titleFor(String code) {
        switch (code) {
            case "millet_repaired": return getString(R.string.activity_log_title_millet_repaired);
            case "millet_error": return getString(R.string.activity_log_title_millet_error);
            case "mipush_requested": return getString(R.string.activity_log_title_mipush_requested);
            case "mipush_error": return getString(R.string.activity_log_title_mipush_error);
            case "game_repaired": return getString(R.string.activity_log_title_game_repaired);
            case "game_changed": return getString(R.string.activity_log_title_game_changed);
            case "game_deferred": return getString(R.string.activity_log_title_game_deferred);
            case "game_error": return getString(R.string.activity_log_title_game_error);
            case "watch_registered": return getString(R.string.activity_log_title_watch_registered);
            case "watch_verified": return getString(R.string.activity_log_title_watch_verified);
            case "watch_error": return getString(R.string.activity_log_title_watch_error);
            case "app_added": return getString(R.string.activity_log_title_app_added);
            case "app_enabled": return getString(R.string.activity_log_title_app_enabled);
            case "app_disabled": return getString(R.string.activity_log_title_app_disabled);
            case "app_removed": return getString(R.string.activity_log_title_app_removed);
            case "app_error": return getString(R.string.activity_log_title_app_error);
            default: return getString(R.string.activity_log_title_other);
        }
    }

    private void copyTechnical() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.gamewatch_logs), GameWatchJournal.technicalHistory(this)));
        Toast.makeText(this, R.string.activity_log_copied, Toast.LENGTH_SHORT).show();
    }
}
