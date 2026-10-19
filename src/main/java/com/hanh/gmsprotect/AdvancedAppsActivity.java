package com.hanh.gmsprotect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.text.Collator;

/** Optional UI. Does not participate in the mandatory GMS setup flow. */
public final class AdvancedAppsActivity extends Activity {
    private LinearLayout content;
    private ScrollView mainScroll;
    private SettingsMotion.CollapsingHeader collapsingHeader;
    private String detailPkg;
    private int previousScrollY;
    private boolean screenBuilt;
    private boolean lastWritePermission;
    private static final String INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS";
    private static final int REQUEST_INSTALLED_APPS = 1152;
    // Set only when the user manually opens app info for the installed-apps permission.
    private boolean pendingInstalledAppsSettings;
    private boolean infoExpanded = false;
    private String gameRawSnapshot;
    private boolean gameSnapshotReadable;
    private String milletRawSnapshot;
    private boolean milletSnapshotReadable;

    private int dp(int px) { return UiKit.dp(this, px); }
    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams spaceBottom(int dp) {
        LinearLayout.LayoutParams lp = full(); lp.bottomMargin = dp(dp); return lp;
    }
    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_SHORT).show(); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? UiKit.BG : UiKit.PRIMARY_DARK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        getWindow().setNavigationBarColor(UiKit.SURFACE);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        lastWritePermission = GameWatchCore.canWrite(this);
        if (state != null) {
            detailPkg = state.getString("advanced_detail_pkg");
            pendingInstalledAppsSettings = state.getBoolean("advanced_pending_installed_apps", false);
        }
        buildShell();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("advanced_detail_pkg", detailPkg);
        outState.putBoolean("advanced_pending_installed_apps", pendingInstalledAppsSettings);
    }

    @Override public void onBackPressed() {
        if (detailPkg != null) { showList(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean writeAllowed = GameWatchCore.canWrite(this);
        if (writeAllowed && !lastWritePermission) {
            AdvancedAppProtector.maintain(this, "WRITE_SETTINGS_GRANTED");
            if (GameWatchScheduler.shouldWatch(this)) GameWatchScheduler.ensure(this, "ADV_ACCESS_GRANTED");
        }
        lastWritePermission = writeAllowed;
        // Resume Add app only when returning from a user-requested app settings visit.
        // Do not mix this with WRITE_SETTINGS: it is a different Xiaomi permission.
        boolean continueAdd = pendingInstalledAppsSettings && canReadInstalledApps();
        pendingInstalledAppsSettings = false;
        if (screenBuilt) {
            // Returning from Autostart / Xiaomi Battery settings must immediately
            // update the status chips without resetting the user's scroll position.
            final int scrollY = mainScroll.getScrollY();
            render();
            mainScroll.post(() -> mainScroll.scrollTo(0, scrollY));
            if (continueAdd) {
                mainScroll.post(() -> {
                    if (!isFinishing() && detailPkg == null) {
                        selectInstalledApp();
                    }
                });
            }
        }
    }

    private void buildShell() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(UiKit.BG);
        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        UiKit.applyBottomSystemInset(content);
        mainScroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        frame.addView(mainScroll, new FrameLayout.LayoutParams(-1, -1));
        collapsingHeader = SettingsMotion.collapsingBar(this, frame, mainScroll,
                getString(R.string.adv_title), v -> {
                    if (detailPkg != null) showList(); else finish();
                });
        setContentView(frame);
        screenBuilt = true;
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        // Read Game Allowlist once per page refresh, never once per card.
        // A null string is an empty list if the provider read itself succeeded.
        gameRawSnapshot = null;
        gameSnapshotReadable = false;
        try {
            gameRawSnapshot = GameWatchCore.raw(this);
            gameSnapshotReadable = true;
        } catch (Throwable ignored) { }
        // Read MILLET once as well, and don't misreport permission/read failures
        // as an absent package. This check is read-only; no policy changes here.
        milletRawSnapshot = null;
        milletSnapshotReadable = false;
        try {
            milletRawSnapshot = Settings.System.getString(getContentResolver(), MilletNoRestrict.KEY);
            milletSnapshotReadable = true;
        } catch (Throwable ignored) { }
        List<AdvancedAppStore.Entry> selected = AdvancedAppStore.list(this);
        if (detailPkg != null) {
            AdvancedAppStore.Entry current = null;
            for (AdvancedAppStore.Entry e : selected) {
                if (e.pkg.equals(detailPkg)) { current = e; break; }
            }
            if (current != null) {
                collapsingHeader.setTitle(getString(R.string.adv_detail_title));
                collapsingHeader.setCompactOnly(true);
                // The fixed Settings toolbar is the only page title on detail screens.
                // Do not add another giant heading above the app identity row.
                content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(66)));
                content.addView(buildAppDetail(current), spaceBottom(12));
                return;
            }
            detailPkg = null;
        }
        collapsingHeader.setTitle(getString(R.string.adv_title));
        collapsingHeader.setCompactOnly(false);
        SettingsMotion.largeTitle(this, content, getString(R.string.adv_title));

        // Built-in GMS protection is represented here for clarity, but is not
        // stored in AdvancedAppStore and cannot be edited/removed by the user.
        // Always visible even if there are no user-managed applications.
        content.addView(SettingsListUi.heading(this,
                getString(R.string.settings16_special_heading)), full());
        LinearLayout specialGroup = SettingsListUi.group(this);
        specialGroup.addView(buildSpecialGmsRow(), full());
        content.addView(specialGroup, spaceBottom(5));

        content.addView(SettingsListUi.heading(this,
                getString(R.string.settings16_user_apps_heading)), full());
        LinearLayout actionGroup = SettingsListUi.group(this);
        SettingsListUi.row(this, actionGroup, R.drawable.sg_ic_apps, getString(R.string.adv_add),
                getString(R.string.settings_add_desc), null, SettingsListUi.NEUTRAL,
                v -> onAddAppClicked());
        content.addView(actionGroup, spaceBottom(12));

        if (!GameWatchCore.canWrite(this)) {
            LinearLayout warning = SettingsListUi.group(this);
            SettingsListUi.row(this, warning, R.drawable.sg_ic_settings, getString(R.string.adv_open_access),
                    getString(R.string.adv_write_required),
                    getString(R.string.ui_watch_attention), SettingsListUi.BAD,
                    v -> openWriteSettings());
            content.addView(warning, spaceBottom(12));
        }

        if (selected.isEmpty()) {
            TextView empty = UiKit.muted(this, getString(R.string.adv_empty), 14f);
            empty.setPadding(dp(12), dp(25), dp(12), dp(25));
            content.addView(empty, full());
        } else {
            content.addView(SettingsListUi.separator(this),
                    new LinearLayout.LayoutParams(-1, dp(1)));
            LinearLayout appGroup = SettingsListUi.group(this);
            for (AdvancedAppStore.Entry e : selected) {
                appGroup.addView(buildAppSummary(e), full());
            }
            content.addView(appGroup, spaceBottom(12));
        }
        // Collapsed by default: plain Settings rows, not legacy long paragraphs/cards.
        TextView infoToggle = UiKit.muted(this,
                getString(infoExpanded ? R.string.ui_hide_help : R.string.ui_show_help), 13f);
        infoToggle.setTextColor(UiKit.PRIMARY);
        infoToggle.setGravity(Gravity.CENTER_VERTICAL);
        infoToggle.setPadding(dp(18), dp(12), dp(18), dp(12));
        infoToggle.setMinimumHeight(dp(48));
        infoToggle.setBackground(SettingsMotion.ripple(this));
        infoToggle.setClickable(true);
        infoToggle.setFocusable(true);
        content.addView(infoToggle, spaceBottom(4));

        LinearLayout help = new LinearLayout(this);
        help.setOrientation(LinearLayout.VERTICAL);
        help.addView(SettingsListUi.heading(this, getString(R.string.adv_help_status_heading)), full());
        addHelpRow(help, R.drawable.sg_ic_verified_check,
                R.string.adv_help_pass_title, R.string.adv_help_pass_desc);
        addHelpRow(help, R.drawable.sg_ic_status_error,
                R.string.adv_help_attention_title, R.string.adv_help_attention_desc);
        addHelpRow(help, R.drawable.sg_ic_status_pending,
                R.string.adv_help_unknown_title, R.string.adv_help_unknown_desc);
        addHelpRow(help, R.drawable.sg_ic_status_off,
                R.string.adv_help_off_title, R.string.adv_help_off_desc);
        help.addView(SettingsListUi.heading(this, getString(R.string.adv_help_rule_heading)), full());
        addHelpRow(help, R.drawable.sg_ic_status_info,
                R.string.adv_help_five_title, R.string.adv_help_five_desc);
        addHelpRow(help, R.drawable.sg_ic_status_info,
                R.string.adv_help_mipush_title, R.string.adv_help_mipush_desc);
        addHelpRow(help, R.drawable.sg_ic_status_info,
                R.string.adv_help_battery_title, R.string.adv_help_battery_desc);
        addHelpRow(help, R.drawable.sg_ic_status_info,
                R.string.adv_help_watcher_title, R.string.adv_help_watcher_desc);
        TextView jobInfo = UiKit.muted(this, getString(R.string.adv_jobs_status,
                getString(GameWatchScheduler.isPending(this) ? R.string.adv_job_yes : R.string.adv_job_no),
                getString(MilletWatchScheduler.isScheduled(this) ? R.string.adv_job_yes : R.string.adv_job_no)),
                12f);
        jobInfo.setPadding(dp(18), dp(12), dp(18), dp(18));
        help.addView(jobInfo, full());
        help.setVisibility(infoExpanded ? View.VISIBLE : View.GONE);
        content.addView(help, full());
        infoToggle.setOnClickListener(v -> {
            infoExpanded = !infoExpanded;
            help.setVisibility(infoExpanded ? View.VISIBLE : View.GONE);
            infoToggle.setText(infoExpanded ? R.string.ui_hide_help : R.string.ui_show_help);
        });
    }

    /** Non-interactive explanation row, visually consistent with microG Settings. */
    private void addHelpRow(LinearLayout container, int iconRes, int titleRes, int descriptionRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.rightMargin = dp(20);
        row.addView(icon, iconLp);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(14.5f);
        title.setTextColor(UiKit.TEXT);
        text.addView(title, full());
        TextView description = UiKit.muted(this, getString(descriptionRes), 12.5f);
        description.setPadding(0, dp(3), 0, 0);
        text.addView(description, full());
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        container.addView(row, full());
    }

    private void showDetail(String pkg) {
        previousScrollY = mainScroll.getScrollY();
        detailPkg = pkg;
        render();
        mainScroll.scrollTo(0, 0);
        SettingsMotion.enter(content);
    }

    private void showList() {
        detailPkg = null;
        render();
        mainScroll.post(() -> mainScroll.scrollTo(0, previousScrollY));
        SettingsMotion.enter(content);
    }

    private boolean milletInSnapshot(String packageName) {
        if (!milletSnapshotReadable || milletRawSnapshot == null) return false;
        for (String part : milletRawSnapshot.replace(';', ',')
                .replace('\n', ',').replace('\t', ',').split(",")) {
            if (packageName.equals(part.trim())) return true;
        }
        return false;
    }

    /** Read-only built-in protection. Never writes to the user-managed app list. */
    private View buildSpecialGmsRow() {
        int status = SettingsListUi.NEUTRAL;
        int statusRes = R.string.settings16_special_unknown;
        try {
            getPackageManager().getApplicationInfo(GameWatchCore.GMS, 0);
            if (milletSnapshotReadable
                    && (!GameWatchCore.isEnabled(this) || gameSnapshotReadable)) {
                boolean milletOk = milletInSnapshot(GameWatchCore.GMS)
                        && milletInSnapshot(MilletNoRestrict.PLAY_STORE_PACKAGE)
                        && milletInSnapshot(getPackageName());
                boolean watcherOk = !MilletWatchScheduler.isSupported()
                        || MilletWatchScheduler.isScheduled(this);
                boolean gameOk = true;
                if (GameWatchCore.isEnabled(this)) {
                    gameOk = GameWatchCore.contains(gameRawSnapshot, GameWatchCore.GMS)
                            && GameWatchCore.contains(gameRawSnapshot, getPackageName())
                            && GameWatchScheduler.isPending(this)
                            && GameWatchCore.canWrite(this);
                }
                status = milletOk && watcherOk && gameOk
                        ? SettingsListUi.GOOD : SettingsListUi.BAD;
                statusRes = status == SettingsListUi.GOOD
                        ? R.string.settings16_special_verified
                        : R.string.settings16_special_attention;
            }
        } catch (PackageManager.NameNotFoundException missing) {
            status = SettingsListUi.BAD;
            statusRes = R.string.settings16_special_missing;
        } catch (Throwable ignored) {
            // Do not present success when SettingsProvider or PM is unreadable.
        }

        final int markerIcon = status == SettingsListUi.GOOD
                ? R.drawable.sg_ic_verified_check
                : status == SettingsListUi.BAD
                ? R.drawable.sg_ic_status_error
                : R.drawable.sg_ic_status_pending;
        final String verifiedStatus = getString(statusRes);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(78));
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        // Intentionally NO ripple, click listener, switch, chevron, or remove.
        row.setContentDescription(getString(R.string.settings_gms_service_title)
                + ". " + getString(R.string.settings16_special_locked)
                + ". " + verifiedStatus);

        ImageView icon = appIcon(GameWatchCore.GMS);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.settings_gms_service_title);
        title.setTextColor(UiKit.TEXT);
        title.setTextSize(16f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(title, full());
        TextView subtitle = UiKit.muted(this, getString(R.string.settings16_special_locked), 12.5f);
        subtitle.setPadding(0, dp(4), 0, 0);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(subtitle, full());
        TextView evidence = UiKit.muted(this, verifiedStatus, 12f);
        evidence.setTextColor(SettingsListUi.tint(status));
        evidence.setPadding(0, dp(3), 0, 0);
        evidence.setSingleLine(true);
        evidence.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(evidence, full());
        row.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView marker = new ImageView(this);
        marker.setImageResource(markerIcon);
        marker.setContentDescription(verifiedStatus);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(23), dp(23));
        lp.leftMargin = dp(10);
        row.addView(marker, lp);
        return row;
    }

    /** Single compact row per app. Full permission diagnostics remain in detail. */
    private View buildAppSummary(AdvancedAppStore.Entry entry) {
        AdvancedAppStatusReader.Snapshot state = inspect(entry);
        int[] statuses = {state.autostart.kind, state.doze.kind, state.millet.kind,
                state.game.kind, state.mipush.kind};
        int passed = 0;
        int attention = 0;
        int unverified = 0;
        for (int kind : statuses) {
            if (kind == AdvancedAppStatusReader.VERIFIED
                    || kind == AdvancedAppStatusReader.SENT_UNVERIFIED) passed++;
            else if (kind == AdvancedAppStatusReader.ATTENTION) attention++;
            else unverified++;
        }
        final boolean active = entry.enabled;
        final int statusColor;
        final String summary;
        if (!state.installed) {
            summary = getString(R.string.settings_app_missing);
            statusColor = UiKit.ERROR;
        } else if (!active) {
            summary = getString(R.string.settings_app_disabled);
            statusColor = UiKit.MUTED;
        } else if (attention > 0) {
            summary = getString(R.string.settings_app_attention, passed, attention);
            statusColor = UiKit.ERROR;
        } else if (unverified > 0) {
            summary = getString(R.string.settings_app_unknown, passed, unverified);
            statusColor = UiKit.MUTED;
        } else {
            summary = getString(R.string.settings_app_all_ready);
            statusColor = UiKit.SUCCESS;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(73));
        row.setPadding(dp(16), dp(11), dp(18), dp(11));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(SettingsMotion.ripple(this));
        row.setOnClickListener(v -> showDetail(entry.pkg));
        row.setContentDescription(getString(R.string.adv_open_app_details, appLabel(entry.pkg))
                + ", " + summary);

        ImageView icon = appIcon(entry.pkg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(appLabel(entry.pkg));
        name.setTextSize(16f);
        name.setTextColor(UiKit.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name, full());
        TextView note = new TextView(this);
        note.setText(summary);
        note.setTextSize(13f);
        note.setTextColor(statusColor);
        note.setSingleLine(true);
        note.setEllipsize(TextUtils.TruncateAt.END);
        note.setPadding(0, dp(4), 0, 0);
        texts.addView(note, full());
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        // A single colored indicator is easier to scan than five tiny chips.
        TextView indicator = new TextView(this);
        indicator.setText(active && state.installed && attention == 0 && unverified == 0 ? "✓"
                : attention > 0 || !state.installed ? "!" : "•");
        indicator.setTextSize(20f);
        indicator.setTextColor(statusColor);
        indicator.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(25), dp(32));
        markLp.leftMargin = dp(6);
        row.addView(indicator, markLp);
        return row;
    }

    /** Two-column readable status grid, retaining the existing verified states. */


    private ImageView appIcon(String pkg) {
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); }
        catch (Throwable ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
        return icon;
    }

    private TextView bold(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(UiKit.TEXT);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    /** Flat native Settings detail, intentionally without rounded cards or status pills. */
    private View buildAppDetail(AdvancedAppStore.Entry entry) {
        LinearLayout page = SettingsListUi.group(this);
        PackageManager pm = getPackageManager();

        // App identity + existing enable/disable switch, with no repeated page title.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(10));
        ImageView icon = new ImageView(this);
        try { icon.setImageDrawable(pm.getApplicationIcon(entry.pkg)); }
        catch (Throwable ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        iconLp.rightMargin = dp(13);
        header.addView(icon, iconLp);
        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        TextView name = bold(appLabel(entry.pkg), 17f);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        nameBox.addView(name, full());
        TextView packageLine = UiKit.muted(this, entry.pkg, 12.5f);
        packageLine.setSingleLine(true);
        packageLine.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        nameBox.addView(packageLine, full());
        header.addView(nameBox, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch main = new Switch(this);
        main.setChecked(entry.enabled);
        main.setContentDescription(getString(R.string.adv_enable_app, appLabel(entry.pkg)));
        main.setOnCheckedChangeListener((button, checked) -> saveChanged(
                new AdvancedAppStore.Entry(entry.pkg, checked)));
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(-2, -2);
        switchLp.leftMargin = dp(6);
        header.addView(main, switchLp);
        page.addView(header, full());

        AdvancedAppStatusReader.Snapshot state = inspect(entry);
        if (!state.installed) {
            LinearLayout unavailable = SettingsListUi.group(this);
            SettingsListUi.row(this, unavailable, R.drawable.sg_ic_status_error,
                    getString(R.string.adv_app_missing), null, null,
                    SettingsListUi.BAD, null);
            page.addView(unavailable, full());
        }

        page.addView(SettingsListUi.heading(this,
                getString(R.string.ui_adv_permission_section)), full());
        LinearLayout permissions = SettingsListUi.group(this);
        addStatusRow(permissions, getString(R.string.adv_autostart), state.autostart,
                R.string.adv_configure, v -> openAutostartSettings(entry.pkg));
        addStatusRow(permissions, getString(R.string.adv_android_doze), state.doze,
                0, null); // Read only; do not imply a generic system page changes Doze.
        addStatusRow(permissions, getString(R.string.adv_millet), state.millet,
                0, null);
        addStatusRow(permissions, getString(R.string.adv_xiaomi_battery), state.battery,
                R.string.adv_configure, v -> openXiaomiBatterySettings(entry.pkg));
        page.addView(permissions, full());
        addDetailNote(page, R.string.adv_pin_read_note);

        page.addView(SettingsListUi.heading(this, getString(R.string.adv_auto_layers)), full());
        LinearLayout automatic = SettingsListUi.group(this);
        if (entry.enabled && !GameWatchCore.canWrite(this)) {
            addDetailNote(page, R.string.adv_write_required);
        }
        addStatusRow(automatic, getString(R.string.adv_game), state.game,
                !GameWatchCore.canWrite(this) && entry.enabled
                        ? R.string.adv_open_access : 0,
                !GameWatchCore.canWrite(this) && entry.enabled
                        ? v -> openWriteSettings() : null);
        addStatusRow(automatic, getString(R.string.adv_mipush), state.mipush, 0, null);
        page.addView(automatic, full());
        addDetailNote(page, R.string.adv_mipush_legend);

        // Remove is a normal Settings action row, not a bordered card/button.
        LinearLayout actions = SettingsListUi.group(this);
        SettingsListUi.row(this, actions, android.R.drawable.ic_menu_delete,
                getString(R.string.adv_remove), null, null, SettingsListUi.NEUTRAL, v ->
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.adv_remove)
                                .setMessage(R.string.adv_remove_note)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.adv_remove_confirm, (dialog, which) -> {
                                    if (!AdvancedAppStore.remove(this, entry.pkg)) {
                                        toast(R.string.adv_save_failed);
                                        return;
                                    }
                                    detailPkg = null;
                                    afterChange();
                                }).show());
        page.addView(actions, spaceBottom(14));
        return page;
    }

    private void addDetailNote(LinearLayout page, int stringRes) {
        TextView note = UiKit.muted(this, getString(stringRes), 12f);
        note.setPadding(dp(70), dp(7), dp(20), dp(10));
        note.setLineSpacing(dp(2), 1.05f);
        page.addView(note, full());
    }

    private AdvancedAppStatusReader.Snapshot inspect(AdvancedAppStore.Entry entry) {
        return AdvancedAppStatusReader.read(this, entry, gameRawSnapshot, gameSnapshotReadable,
                milletRawSnapshot, milletSnapshotReadable);
    }

    /** MiPush successful send is a green completed step, not an OEM acknowledgement. */
    private void addStatusRow(LinearLayout group, String title,
                              AdvancedAppStatusReader.Status status,
                              int actionLabel, View.OnClickListener click) {
        final int kind = status.kind;
        final int iconRes = kind == AdvancedAppStatusReader.VERIFIED
                || kind == AdvancedAppStatusReader.SENT_UNVERIFIED
                ? R.drawable.sg_ic_verified_check
                : kind == AdvancedAppStatusReader.ATTENTION
                ? R.drawable.sg_ic_status_error
                : R.drawable.sg_ic_status_pending;
        final int tint = kind == AdvancedAppStatusReader.VERIFIED
                || kind == AdvancedAppStatusReader.SENT_UNVERIFIED ? UiKit.SUCCESS
                : kind == AdvancedAppStatusReader.ATTENTION ? UiKit.ERROR
                : UiKit.MUTED;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(18), dp(10));
        row.setMinimumHeight(dp(68));

        ImageView statusIcon = new ImageView(this);
        statusIcon.setImageResource(iconRes);
        statusIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(23), dp(23));
        iconLp.rightMargin = dp(31);
        row.addView(statusIcon, iconLp);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(16f);
        label.setTextColor(UiKit.TEXT);
        textColumn.addView(label, full());
        TextView value = new TextView(this);
        value.setText(status.message);
        value.setTextSize(13f);
        value.setTextColor(tint);
        value.setMaxLines(3);
        value.setPadding(0, dp(3), 0, 0);
        textColumn.addView(value, full());
        row.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1f));

        if (click != null && actionLabel != 0) {
            TextView action = new TextView(this);
            action.setText(getString(actionLabel) + "  ›");
            action.setTextColor(UiKit.PRIMARY);
            action.setTextSize(13f);
            action.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            lp.leftMargin = dp(8);
            row.addView(action, lp);
            row.setBackground(SettingsMotion.ripple(this));
            row.setOnClickListener(click);
            row.setClickable(true);
            row.setFocusable(true);
        }
        row.setContentDescription(title + ". " + getString(status.message)
                + (click != null && actionLabel != 0 ? ". " + getString(actionLabel) : ""));
        group.addView(row, full());
    }

    /** Xiaomi separately controls visibility of all installed applications.
     * Not the same as WRITE_SETTINGS, which is only needed to modify allowlists.
     * ROMs without this OEM-specific permission can use the normal picker. */
    private boolean supportsInstalledAppsPermission() {
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            PermissionInfo permission = getPackageManager().getPermissionInfo(INSTALLED_APPS_PERMISSION, 0);
            return permission != null;
        } catch (PackageManager.NameNotFoundException unsupported) {
            return false;
        } catch (Throwable restricted) {
            // An unknown OEM implementation must not be treated as verified access.
            return true;
        }
    }

    private boolean canReadInstalledApps() {
        if (!supportsInstalledAppsPermission()) return true;
        try {
            return checkSelfPermission(INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void onAddAppClicked() {
        if (canReadInstalledApps()) {
            selectInstalledApp();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.adv_installed_apps_title)
                .setMessage(R.string.adv_installed_apps_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.adv_installed_apps_allow,
                        (dialog, which) -> requestInstalledAppsAccess())
                .show();
    }

    private void requestInstalledAppsAccess() {
        if (canReadInstalledApps()) { selectInstalledApp(); return; }
        if (Build.VERSION.SDK_INT >= 23 && supportsInstalledAppsPermission()) {
            try {
                requestPermissions(new String[]{INSTALLED_APPS_PERMISSION}, REQUEST_INSTALLED_APPS);
                return;
            } catch (Throwable unsupported) {
                android.util.Log.w("AdvancedApps", "Installed apps runtime permission unavailable", unsupported);
            }
        }
        showManualInstalledAppsPermission();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_INSTALLED_APPS) return;
        // Do not assume a positive result when the ROM returns an empty array.
        // The package manager's actual current permission state is authoritative.
        if (canReadInstalledApps()) {
            if (!isFinishing() && detailPkg == null) {
                mainScroll.post(() -> {
                    if (!isFinishing() && canReadInstalledApps()) selectInstalledApp();
                });
            }
        } else {
            showManualInstalledAppsPermission();
        }
    }

    private void showManualInstalledAppsPermission() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.adv_installed_apps_manual_title)
                .setMessage(R.string.adv_installed_apps_manual_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.adv_installed_apps_settings, (dialog, which) -> {
                    pendingInstalledAppsSettings = true;
                    openAppDetails(getPackageName());
                })
                .show();
    }

    private void showInstalledAppsUnavailable() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.adv_installed_apps_title)
                .setMessage(R.string.adv_installed_apps_scan_error)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.adv_installed_apps_settings,
                        (dialog, which) -> openAppDetails(getPackageName()))
                .show();
    }

    private void openWriteSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) { openAppDetails(getPackageName()); }
    }

    private void saveChanged(AdvancedAppStore.Entry entry) {
        if (!AdvancedAppStore.save(this, entry)) { toast(R.string.adv_save_failed); render(); return; }
        afterChange();
    }

    private void afterChange() {
        AdvancedAppProtector.maintain(this, "USER_CHANGE");
        if (GameWatchScheduler.shouldWatch(this)) GameWatchScheduler.ensure(this, "ADV_CHANGE");
        else GameWatchScheduler.cancel(this);
        final int scrollY = mainScroll.getScrollY();
        render();
        mainScroll.post(() -> mainScroll.scrollTo(0, scrollY));
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(info));
        } catch (Throwable ignored) { return pkg; }
    }

    /** HyperOS can return only this APK even when checkSelfPermission() says GRANTED.
     * Inspect the actual PackageManager response; a self-only result means that
     * "no more apps to add" is NOT a truthful diagnosis. */
    private static final class InstalledAppScan {
        final ArrayList<AppChoice> choices = new ArrayList<>();
        int applicationsReturned;
        int packagesReturned;
        boolean primaryFailed;
        boolean limitedLauncherFallback;

        boolean looksRestricted() {
            return Math.max(applicationsReturned, packagesReturned) <= 5
                    && !limitedLauncherFallback;
        }
    }

    /** Called only on user request, never during background protection. */
    private InstalledAppScan scanInstalledApps(java.util.Set<String> existing) {
        final PackageManager pm = getPackageManager();
        InstalledAppScan result = new InstalledAppScan();
        LinkedHashMap<String, ApplicationInfo> found = new LinkedHashMap<>();
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            result.applicationsReturned = apps == null ? 0 : apps.size();
            if (apps != null) {
                for (ApplicationInfo info : apps) {
                    if (info != null && info.packageName != null) found.put(info.packageName, info);
                }
            }
        } catch (Throwable error) {
            result.primaryFailed = true;
            android.util.Log.w("AdvancedApps", "getInstalledApplications failed", error);
        }

        // A second independent PM query helps on vendor builds which expose
        // installed packages but unexpectedly filter installed applications.
        if (result.applicationsReturned <= 5) {
            try {
                List<PackageInfo> packages = pm.getInstalledPackages(0);
                result.packagesReturned = packages == null ? 0 : packages.size();
                if (packages != null) {
                    for (PackageInfo item : packages) {
                        if (item == null || item.packageName == null) continue;
                        ApplicationInfo info = item.applicationInfo;
                        if (info == null) {
                            try { info = pm.getApplicationInfo(item.packageName, 0); }
                            catch (Throwable ignored) { continue; }
                        }
                        found.put(item.packageName, info);
                    }
                }
            } catch (Throwable error) {
                android.util.Log.w("AdvancedApps", "getInstalledPackages failed", error);
            }
        }

        // Non-Xiaomi ROMs without this OEM permission: a minimal launcher-only
        // fallback is preferable to a blank picker. It is explicitly limited.
        if (Math.max(result.applicationsReturned, result.packagesReturned) <= 5
                && !supportsInstalledAppsPermission()) {
            try {
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> launchable = pm.queryIntentActivities(launcher, 0);
                if (launchable != null) {
                    for (ResolveInfo activity : launchable) {
                        if (activity.activityInfo == null) continue;
                        ApplicationInfo info = activity.activityInfo.applicationInfo;
                        if (info != null && info.packageName != null)
                            found.put(info.packageName, info);
                    }
                }
                result.limitedLauncherFallback = found.size() > 5;
            } catch (Throwable error) {
                android.util.Log.w("AdvancedApps", "launcher fallback failed", error);
            }
        }

        for (ApplicationInfo info : found.values()) {
            String pkg = info.packageName;
            if (!AdvancedAppStore.valid(this, pkg) || existing.contains(pkg)) continue;
            String label;
            try { label = String.valueOf(pm.getApplicationLabel(info)); }
            catch (Throwable error) { label = pkg; }
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            result.choices.add(new AppChoice(pkg, label, system));
        }
        android.util.Log.i("AdvancedApps", "Installed app picker scan apps="
                + result.applicationsReturned + " packages=" + result.packagesReturned
                + " eligible=" + result.choices.size()
                + " permission=" + canReadInstalledApps()
                + " limited=" + result.limitedLauncherFallback);
        return result;
    }

    /** Full installed app picker. Unlike the old code, an OEM-filtered list
     * is never reported as if there are genuinely no more applications. */
    private void selectInstalledApp() {
        final PackageManager pm = getPackageManager();
        List<AdvancedAppStore.Entry> current = AdvancedAppStore.list(this);
        if (current.size() >= AdvancedAppStore.MAX_APPS) {
            toast(R.string.adv_save_failed);
            return;
        }
        final java.util.HashSet<String> existing = new java.util.HashSet<>();
        for (AdvancedAppStore.Entry e : current) existing.add(e.pkg);
        InstalledAppScan scan = scanInstalledApps(existing);
        if (scan.looksRestricted()) {
            // Crucial: HyperOS may report a nominal GRANTED permission but still
            // return only our own APK. Guide to Other permissions instead of
            // telling the user that the device contains zero available apps.
            if (supportsInstalledAppsPermission()) showManualInstalledAppsPermission();
            else showInstalledAppsUnavailable();
            return;
        }
        if (scan.choices.isEmpty()) {
            toast(scan.primaryFailed ? R.string.adv_installed_apps_scan_error
                    : R.string.adv_no_available_apps);
            return;
        }
        final ArrayList<AppChoice> all = scan.choices;
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(all, (a, b) -> {
            if (a.system != b.system) return a.system ? 1 : -1; // user-installed first
            int result = collator.compare(a.label, b.label);
            return result == 0 ? a.pkg.compareTo(b.pkg) : result;
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(5), dp(12), dp(8));
        TextView note = UiKit.muted(this, scan.limitedLauncherFallback
                ? getString(R.string.adv_installed_apps_limited_list)
                : getString(R.string.adv_picker_note, all.size()), 12.5f);
        box.addView(note, spaceBottom(5));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint(R.string.adv_search);
        box.addView(search, spaceBottom(5));
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setFastScrollEnabled(true);
        final ArrayList<AppChoice> filtered = new ArrayList<>(all);
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                PickerHolder h;
                if (recycled == null) {
                    LinearLayout row = new LinearLayout(AdvancedAppsActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(9), dp(8), dp(8), dp(8));
                    ImageView icon = new ImageView(AdvancedAppsActivity.this);
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(42), dp(42));
                    imageParams.rightMargin = dp(12);
                    row.addView(icon, imageParams);
                    LinearLayout names = new LinearLayout(AdvancedAppsActivity.this);
                    names.setOrientation(LinearLayout.VERTICAL);
                    TextView name = bold("", 15.5f);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    names.addView(name, full());
                    TextView pkg = UiKit.muted(AdvancedAppsActivity.this, "", 12.5f);
                    pkg.setSingleLine(true);
                    pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    names.addView(pkg, full());
                    row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
                    h = new PickerHolder(icon, name, pkg);
                    row.setTag(h);
                    recycled = row;
                } else h = (PickerHolder) recycled.getTag();
                AppChoice item = filtered.get(position);
                h.name.setText(item.label);
                h.pkg.setText(item.pkg + (item.system ? "  ·  " + getString(R.string.adv_system_app) : ""));
                try { h.icon.setImageDrawable(pm.getApplicationIcon(item.pkg)); }
                catch (Throwable ignored) { h.icon.setImageResource(android.R.drawable.sym_def_app_icon); }
                return recycled;
            }
        };
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(430)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.adv_choose_app)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) return;
            String pkg = filtered.get(position).pkg;
            if (!AdvancedAppStore.save(this, new AdvancedAppStore.Entry(pkg, true))) {
                toast(R.string.adv_save_failed);
                return;
            }
            dialog.dismiss();
            afterChange();
            showDetail(pkg);
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String filter = s.toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                for (AppChoice app : all) {
                    if (app.label.toLowerCase(Locale.ROOT).contains(filter)
                            || app.pkg.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(app);
                }
                adapter.notifyDataSetChanged();
                note.setText(filtered.isEmpty() ? getString(R.string.adv_no_results)
                        : scan.limitedLauncherFallback
                        ? getString(R.string.adv_installed_apps_limited_list)
                        : getString(R.string.adv_picker_note, filtered.size()));
                list.setSelection(0);
            }
            public void afterTextChanged(Editable e) { }
        });
        dialog.show();
    }

    private static final class AppChoice {
        final String pkg, label;
        final boolean system;
        AppChoice(String pkg, String label, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
        }
    }
    private static final class PickerHolder {
        final ImageView icon;
        final TextView name, pkg;
        PickerHolder(ImageView icon, TextView name, TextView pkg) {
            this.icon = icon;
            this.name = name;
            this.pkg = pkg;
        }
    }

    /**
     * HyperOS 4 (myron): user-verified PowerKeeper battery configuration for
     * the selected package. This is Xiaomi's app-specific battery screen,
     * NOT Android's ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog.
     *
     * A missing/blocked OEM activity must never crash the advanced apps UI.
     * The stock Android app-details page is a safe cross-ROM fallback.
     */
    private void openXiaomiBatterySettings(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            toast(R.string.adv_no_settings);
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.legacypowerrank.PowerDetailActivity"));
            intent.putExtra("package_name", pkg);
            intent.putExtra("package_label", appLabel(pkg));
            startActivity(intent);
        } catch (Throwable ignored) {
            openAppDetails(pkg);
        }
    }

    private void openAutostartSettings(String pkg) {
        try {
            Intent action = new Intent("miui.intent.action.OP_AUTO_START");
            action.setPackage("com.miui.securitycenter");
            action.putExtra("package_name", pkg);
            startActivity(action);
        } catch (Throwable error) {
            try {
                Intent direct = new Intent().setComponent(new ComponentName(
                        "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                startActivity(direct);
            } catch (Throwable ignored) { openAppDetails(pkg); }
        }
    }

    private void openAppDetails(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (Throwable error) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Throwable ignored) { toast(R.string.adv_no_settings); }
        }
    }
}
