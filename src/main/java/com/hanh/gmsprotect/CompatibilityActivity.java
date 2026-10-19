package com.hanh.gmsprotect;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.FrameLayout;

import java.text.DateFormat;
import java.util.Date;

/** Compatibility evidence screen, styled like the native Settings UI. Read-only
 * until the user explicitly taps "Check current status". */
public final class CompatibilityActivity extends Activity {
    private enum RowState { OK, PENDING, ERROR, INFO }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? UiKit.BG : UiKit.PRIMARY_DARK);
        getWindow().setNavigationBarColor(UiKit.SURFACE);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GameWatchEvidence.noteUi(this, true);
        if (getWindow().getDecorView() != null) render();
    }

    @Override
    protected void onPause() {
        GameWatchEvidence.noteUi(this, false);
        super.onPause();
    }

    private void render() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(UiKit.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        UiKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        SettingsMotion.collapsingBar(this, frame, scroll,
                getString(R.string.compat_title), v -> finish());
        setContentView(frame);
        SettingsMotion.largeTitle(this, page, getString(R.string.compat_title));

        // Read the same device signals and stored evidence as beta11.
        // None of these checks changes system settings or invokes background jobs.
        boolean powerKeeper = packageExists("com.miui.powerkeeper");
        boolean milletReadable = MilletNoRestrict.canRead(this);
        boolean canWrite = MilletNoRestrict.canWrite(this);
        boolean gmsOk = MilletNoRestrict.containsGms(this);
        boolean playStoreOk = MilletNoRestrict.containsPlayStore(this);
        boolean protectorOk = MilletNoRestrict.containsPackage(this, getPackageName());
        boolean hyperOsProtectionSent = CompatibilityVerification.hyperOsProtectionRequestSent(this);
        boolean watcherSupported = MilletWatchScheduler.isSupported();
        boolean watcherScheduled = MilletWatchScheduler.isScheduled(this);
        boolean watcherVerified = CompatibilityVerification.watcherVerified(this);
        boolean repairVerified = CompatibilityVerification.autoRepairVerified(this);
        boolean staleAfterUpdate = CompatibilityVerification.needsReverificationAfterSystemUpdate(this);

        boolean coreReady = powerKeeper && milletReadable && canWrite
                && gmsOk && playStoreOk && protectorOk && hyperOsProtectionSent
                && watcherSupported && watcherScheduled;
        boolean full = coreReady && watcherVerified && repairVerified && !staleAfterUpdate;

        String overall;
        RowState overallState;
        if (!powerKeeper || !milletReadable) {
            overall = getString(R.string.compat_result_unsupported);
            overallState = RowState.ERROR;
        } else if (full) {
            overall = getString(R.string.compat_result_full);
            overallState = RowState.OK;
        } else if (coreReady) {
            overall = staleAfterUpdate
                    ? getString(R.string.compat_result_reverify)
                    : getString(R.string.compat_result_pending);
            overallState = RowState.PENDING;
        } else {
            overall = getString(R.string.compat_result_partial);
            overallState = RowState.PENDING;
        }

        // Single clean summary, not a prominent gradient/badge card.
        page.addView(SettingsListUi.heading(this, getString(R.string.compat_summary_section)), full());
        LinearLayout summary = SettingsListUi.group(this);
        summary.setPadding(dp(16), dp(15), dp(16), dp(15));
        TextView headline = new TextView(this);
        headline.setText(overall);
        headline.setTextSize(18f);
        headline.setTypeface(null, Typeface.BOLD);
        headline.setTextColor(overallState == RowState.OK ? UiKit.SUCCESS
                : overallState == RowState.ERROR ? UiKit.ERROR : UiKit.TEXT);
        LinearLayout overviewLine = new LinearLayout(this);
        overviewLine.setOrientation(LinearLayout.HORIZONTAL);
        overviewLine.setGravity(Gravity.CENTER_VERTICAL);
        overviewLine.addView(makeStatusIcon(overallState), statusIconParams(12));
        overviewLine.addView(headline, new LinearLayout.LayoutParams(0, -2, 1f));
        summary.addView(overviewLine, bottom(dp(7)));
        TextView device = UiKit.muted(this,
                Build.MANUFACTURER + " " + Build.MODEL + " • Android " + Build.VERSION.RELEASE,
                13f);
        summary.addView(device, bottom(dp(9)));
        TextView explanation = UiKit.muted(this, getString(R.string.compat_summary_desc), 13f);
        explanation.setLineSpacing(dp(2), 1.03f);
        summary.addView(explanation, full());
        page.addView(summary, full());

        page.addView(SettingsListUi.heading(this, getString(R.string.compat_hyperos_section)), full());
        LinearLayout hyper = SettingsListUi.group(this);
        addRow(hyper, R.string.compat_powerkeeper,
                powerKeeper ? R.string.compat_detected : R.string.compat_not_detected,
                powerKeeper ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_millet_access,
                !milletReadable ? R.string.compat_not_supported
                        : canWrite ? R.string.compat_read_write_ok : R.string.compat_permission_needed,
                !milletReadable ? RowState.ERROR : canWrite ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_gms,
                gmsOk ? R.string.compat_applied : R.string.compat_not_applied,
                gmsOk ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_play_store,
                playStoreOk ? R.string.compat_applied : R.string.compat_not_applied,
                playStoreOk ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_self_no_restrict,
                protectorOk ? R.string.compat_verified : R.string.compat_self_needed,
                protectorOk ? RowState.OK : RowState.ERROR);
        boolean androidBatteryExempt = BatterySetupState.isIgnoringBatteryOptimizations(this);
        addRow(hyper, R.string.compat_android_battery_exemption,
                androidBatteryExempt ? R.string.compat_verified : R.string.compat_not_applied,
                androidBatteryExempt ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_hyperos_protection,
                !powerKeeper ? R.string.compat_not_supported
                        : hyperOsProtectionSent ? R.string.compat_request_sent : R.string.compat_not_checked,
                // SENT is an acknowledged dispatch, not proof that Xiaomi applied it.
                !powerKeeper ? RowState.ERROR : hyperOsProtectionSent ? RowState.INFO : RowState.PENDING);
        page.addView(hyper, full());
        addNote(page, R.string.compat_hyperos_protection_note);

        page.addView(SettingsListUi.heading(this, getString(R.string.compat_recovery_section)), full());
        LinearLayout recovery = SettingsListUi.group(this);
        addRow(recovery, R.string.compat_watcher_registration,
                !watcherSupported ? R.string.compat_not_supported
                        : watcherScheduled ? R.string.compat_registered : R.string.compat_not_registered,
                !watcherSupported ? RowState.ERROR : watcherScheduled ? RowState.OK : RowState.ERROR);
        addRow(recovery, R.string.compat_system_wake,
                staleAfterUpdate ? R.string.compat_need_reverify
                        : watcherVerified ? R.string.compat_verified : R.string.compat_not_verified_yet,
                staleAfterUpdate || !watcherVerified ? RowState.PENDING : RowState.OK);
        addRow(recovery, R.string.compat_auto_repair,
                staleAfterUpdate ? R.string.compat_need_reverify
                        : repairVerified ? R.string.compat_verified : R.string.compat_not_verified_yet,
                staleAfterUpdate || !repairVerified ? RowState.PENDING : RowState.OK);
        long last = CompatibilityVerification.lastVerifiedTime(this);
        String lastText = last > 0L
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(last))
                : getString(R.string.compat_never);
        addTextRow(recovery, getString(R.string.compat_last_verified), lastText);
        page.addView(recovery, full());

        // Preserve passive Game Watch evidence, but match the normal Settings rows.
        page.addView(SettingsListUi.heading(this, getString(R.string.compat_game_section)), full());
        LinearLayout game = SettingsListUi.group(this);
        boolean gwEnabled = GameWatchCore.isEnabled(this);
        boolean gwPlatform = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
        boolean gwWrite = GameWatchCore.canWrite(this);
        boolean gwBoth = false;
        boolean gwReadOk = false;
        try {
            String current = GameWatchCore.raw(this);
            gwReadOk = true;
            gwBoth = GameWatchCore.contains(current, GameWatchCore.GMS)
                    && GameWatchCore.contains(current, getPackageName());
        } catch (Throwable ignored) { }
        boolean gwPending = gwEnabled && GameWatchScheduler.isPending(this);
        addRow(game, R.string.compat_game_enabled,
                gwEnabled ? R.string.compat_game_enabled_yes : R.string.compat_game_enabled_no,
                gwEnabled ? RowState.OK : RowState.ERROR);
        addRow(game, R.string.compat_game_platform,
                gwPlatform ? R.string.compat_verified : R.string.compat_not_supported,
                gwPlatform ? RowState.OK : RowState.ERROR);
        addRow(game, R.string.compat_game_settings,
                gwWrite ? R.string.compat_verified : R.string.compat_permission_needed,
                gwWrite ? RowState.OK : RowState.ERROR);
        addRow(game, R.string.compat_game_targets,
                !gwReadOk ? R.string.compat_not_verified_yet
                        : gwBoth ? R.string.compat_verified : R.string.compat_not_applied,
                !gwReadOk ? RowState.PENDING : gwBoth ? RowState.OK : RowState.ERROR);
        addRow(game, R.string.compat_game_job,
                gwPending ? R.string.compat_registered : R.string.compat_not_registered,
                gwPending ? RowState.OK : RowState.ERROR);
        addSubheading(game, getString(R.string.compat_game_passive_title));
        addRow(game, R.string.compat_game_real_uri,
                GameWatchEvidence.uriVerified(this) ? R.string.compat_verified
                        : R.string.compat_not_verified_yet,
                GameWatchEvidence.uriVerified(this) ? RowState.OK : RowState.PENDING);
        addRow(game, R.string.compat_game_background,
                GameWatchEvidence.backgroundVerified(this) ? R.string.compat_verified
                        : R.string.compat_not_verified_yet,
                GameWatchEvidence.backgroundVerified(this) ? RowState.OK : RowState.PENDING);
        addRow(game, R.string.compat_game_process,
                GameWatchEvidence.newPidVerified(this) ? R.string.compat_verified
                        : R.string.compat_not_verified_yet,
                GameWatchEvidence.newPidVerified(this) ? RowState.OK : RowState.PENDING);
        addRow(game, R.string.compat_game_repair,
                GameWatchEvidence.repairVerified(this) ? R.string.compat_verified
                        : R.string.compat_not_verified_yet,
                GameWatchEvidence.repairVerified(this) ? RowState.OK : RowState.PENDING);
        addRow(game, R.string.compat_game_boot,
                GameWatchEvidence.bootVerified(this) ? R.string.compat_verified
                        : R.string.compat_not_verified_yet,
                GameWatchEvidence.bootVerified(this) ? RowState.OK : RowState.PENDING);
        String when = GameWatchEvidence.lastUri(this) == 0L
                ? getString(R.string.compat_never)
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(GameWatchEvidence.lastUri(this)));
        addTextRow(game, getString(R.string.compat_game_last_uri), when);
        page.addView(game, full());
        addNote(page, R.string.compat_game_note);

        page.addView(SettingsListUi.heading(this, getString(R.string.compat_actions_section)), full());
        LinearLayout actions = SettingsListUi.group(this);
        SettingsListUi.row(this, actions, R.drawable.sg_ic_check,
                getString(R.string.compat_run_check),
                null, null, SettingsListUi.NEUTRAL, v -> runCheck());
        SettingsListUi.row(this, actions, R.drawable.sg_ic_arrow_back,
                getString(R.string.compat_back),
                null, null, SettingsListUi.NEUTRAL, v -> finish());
        page.addView(actions, full());
        addNote(page, R.string.compat_passive_note);
    }

    private void runCheck() {
        MilletNoRestrict.ensureGms(this);
        GmsProtector.HyperOsProtectionResult protection =
                GmsProtector.sendHyperOsProtectionRequest(this);
        MilletWatchScheduler.schedule(this);

        int message = protection == GmsProtector.HyperOsProtectionResult.SENT
                ? R.string.compat_check_done
                : protection == GmsProtector.HyperOsProtectionResult.GMS_MISSING
                ? R.string.compat_check_gms_missing
                : R.string.compat_check_hyperos_failed;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        render();
    }



    private void addRow(LinearLayout group, int titleRes, int statusRes, RowState state) {
        String title = getString(titleRes);
        String result = getString(statusRes);
        int kind = state == RowState.OK ? SettingsListUi.GOOD
                : state == RowState.ERROR ? SettingsListUi.BAD
                : state == RowState.INFO ? SettingsListUi.INFO : SettingsListUi.NEUTRAL;
        LinearLayout row = SettingsListUi.row(this, group, title, null, result, kind, null);
        insertStatusIcon(row, state);
        row.setContentDescription(title + ". " + result);
    }

    private void addTextRow(LinearLayout group, String title, String value) {
        // A timestamp is information, not a compatibility pass or failure.
        LinearLayout row = SettingsListUi.row(this, group, title, null, value,
                SettingsListUi.NEUTRAL, null);
        insertStatusIcon(row, RowState.INFO);
        row.setContentDescription(title + ". " + value);
    }

    /** Replace the blank microG indent with a meaningful result icon. */
    private void insertStatusIcon(LinearLayout row, RowState state) {
        // SettingsListUi.row without an icon starts with a 56dp spacer.
        row.removeViewAt(0);
        row.addView(makeStatusIcon(state), 0, statusIconParams(27));
    }

    private LinearLayout.LayoutParams statusIconParams(int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(rightMargin);
        return lp;
    }

    private ImageView makeStatusIcon(RowState state) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(state == RowState.OK ? R.drawable.sg_ic_verified_check
                : state == RowState.ERROR ? R.drawable.sg_ic_status_error
                : state == RowState.INFO ? R.drawable.sg_ic_status_info
                : R.drawable.sg_ic_status_pending);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int label = state == RowState.OK ? R.string.settings16_status_ok
                : state == RowState.ERROR ? R.string.settings16_status_error
                : state == RowState.INFO ? R.string.settings16_status_info
                : R.string.settings16_status_pending;
        icon.setContentDescription(getString(label));
        return icon;
    }

    private void addSubheading(LinearLayout group, String text) {
        SettingsListUi.divider(this, group);
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13f);
        label.setTextColor(UiKit.MUTED);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(dp(16), dp(15), dp(16), dp(3));
        group.addView(label, full());
    }

    private void addNote(LinearLayout page, int textRes) {
        TextView note = UiKit.muted(this, getString(textRes), 13f);
        note.setPadding(dp(12), dp(10), dp(12), dp(3));
        note.setLineSpacing(dp(2), 1.02f);
        page.addView(note, full());
    }

    private boolean packageExists(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(int value) { return UiKit.dp(this, value); }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams bottom(int margin) {
        LinearLayout.LayoutParams lp = full();
        lp.bottomMargin = margin;
        return lp;
    }
}
