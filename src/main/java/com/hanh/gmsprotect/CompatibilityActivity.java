package com.hanh.gmsprotect;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

/** User-facing compatibility/evidence screen. */
public final class CompatibilityActivity extends Activity {
    private static final int WARNING = 0xFFE49A23;
    private static final int WARNING_SOFT = 0xFFFFF4DE;

    private enum RowState { OK, PENDING, ERROR }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(UiKit.PRIMARY_DARK);
        getWindow().setNavigationBarColor(UiKit.SURFACE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh in case a real background verification happened while this
        // activity was not visible.
        if (getWindow().getDecorView() != null) render();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        UiKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

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

        LinearLayout summary = UiKit.card(this);
        TextView device = UiKit.muted(this,
                Build.MANUFACTURER + " " + Build.MODEL + " • Android " + Build.VERSION.RELEASE,
                11.5f);
        summary.addView(device, bottom(dp(9)));

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

        TextView overallPill = pill(overall, overallState);
        LinearLayout.LayoutParams overallLp = new LinearLayout.LayoutParams(-2, -2);
        overallLp.gravity = Gravity.START;
        overallLp.bottomMargin = dp(8);
        summary.addView(overallPill, overallLp);

        TextView summaryText = UiKit.muted(this, getString(R.string.compat_summary_desc), 11.5f);
        summaryText.setLineSpacing(0f, 1.15f);
        summary.addView(summaryText);
        page.addView(summary, cardMargin());

        LinearLayout hyper = sectionCard(R.string.compat_hyperos_section);
        addRow(hyper, R.string.compat_powerkeeper,
                powerKeeper ? R.string.compat_detected : R.string.compat_not_detected,
                powerKeeper ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_millet_access,
                !milletReadable ? R.string.compat_not_supported
                        : canWrite ? R.string.compat_read_write_ok : R.string.compat_permission_needed,
                !milletReadable ? RowState.ERROR : canWrite ? RowState.OK : RowState.PENDING);
        addRow(hyper, R.string.compat_gms,
                gmsOk ? R.string.compat_applied : R.string.compat_not_applied,
                gmsOk ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_play_store,
                playStoreOk ? R.string.compat_applied : R.string.compat_not_applied,
                playStoreOk ? RowState.OK : RowState.ERROR);
        addRow(hyper, R.string.compat_self_no_restrict,
                protectorOk ? R.string.compat_verified : R.string.compat_self_needed,
                protectorOk ? RowState.OK : RowState.PENDING);
        boolean androidBatteryExempt = BatterySetupState.isIgnoringBatteryOptimizations(this);
        addRow(hyper, R.string.compat_android_battery_exemption,
                androidBatteryExempt ? R.string.compat_verified : R.string.compat_not_applied,
                androidBatteryExempt ? RowState.OK : RowState.PENDING);
        addRow(hyper, R.string.compat_hyperos_protection,
                !powerKeeper ? R.string.compat_not_supported
                        : hyperOsProtectionSent ? R.string.compat_request_sent : R.string.compat_not_checked,
                !powerKeeper ? RowState.ERROR : hyperOsProtectionSent ? RowState.OK : RowState.PENDING);
        TextView hyperProtectionNote = UiKit.muted(
                this, getString(R.string.compat_hyperos_protection_note), 10.8f);
        hyperProtectionNote.setLineSpacing(0f, 1.12f);
        hyper.addView(hyperProtectionNote, bottom(dp(3)));
        page.addView(hyper, cardMargin());

        LinearLayout recovery = sectionCard(R.string.compat_recovery_section);
        addRow(recovery, R.string.compat_watcher_registration,
                !watcherSupported ? R.string.compat_not_supported
                        : watcherScheduled ? R.string.compat_registered : R.string.compat_not_registered,
                !watcherSupported ? RowState.ERROR : watcherScheduled ? RowState.OK : RowState.PENDING);
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
        page.addView(recovery, cardMargin());

        LinearLayout actions = UiKit.card(this);
        TextView note = UiKit.muted(this, getString(R.string.compat_passive_note), 11.2f);
        note.setLineSpacing(0f, 1.15f);
        actions.addView(note, bottom(dp(12)));
        actions.addView(UiKit.primaryButton(this, getString(R.string.compat_run_check), v -> runCheck()),
                bottom(dp(9)));
        actions.addView(UiKit.secondaryButton(this, getString(R.string.compat_back), v -> finish()));
        page.addView(actions, cardMargin());
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

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(18));
        header.setBackground(UiKit.verticalGradient());
        UiKit.applyTopSystemInset(header);

        TextView title = new TextView(this);
        title.setText(R.string.compat_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(23f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.compat_subtitle);
        sub.setTextColor(0xFFDAD9FF);
        sub.setTextSize(12f);
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        return header;
    }

    private LinearLayout sectionCard(int titleRes) {
        LinearLayout card = UiKit.card(this);
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(15.5f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title, bottom(dp(7)));
        return card;
    }

    private void addRow(LinearLayout parent, int titleRes, int statusRes, RowState state) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(12.5f);
        title.setTextColor(UiKit.TEXT);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(pill(getString(statusRes), state), new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addTextRow(LinearLayout parent, String titleText, String valueText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(5));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(12.5f);
        title.setTextColor(UiKit.TEXT);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView value = UiKit.muted(this, valueText, 11f);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row);
    }

    private TextView pill(String text, RowState state) {
        if (state == RowState.OK) {
            return UiKit.pill(this, text, UiKit.SUCCESS_SOFT, UiKit.SUCCESS);
        }
        if (state == RowState.ERROR) {
            return UiKit.pill(this, text, UiKit.ERROR_SOFT, UiKit.ERROR);
        }
        return UiKit.pill(this, text, WARNING_SOFT, WARNING);
    }

    private boolean packageExists(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(int value) { return UiKit.dp(this, value); }

    private LinearLayout.LayoutParams bottom(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = margin;
        return lp;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        return lp;
    }
}
