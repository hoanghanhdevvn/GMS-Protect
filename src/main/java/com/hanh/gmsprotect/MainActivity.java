package com.hanh.gmsprotect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private static final String PREFS = "setup_v1";
    private static final String KEY_SETUP_DONE = "setup_done";
    private static final String KEY_BATTERY_USER_CONFIRMED = "battery_user_confirmed_v1";
    private static final String KEY_DONATE_START_MS = "donate_start_ms";
    private static final String KEY_DONATE_LAUNCH_COUNT = "donate_launch_count";
    private static final String KEY_DONATE_LAST_PROMPT_MS = "donate_last_prompt_ms";
    private static final String KEY_DONATE_PROMPT_COUNT = "donate_prompt_count";

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long DONATE_FIRST_DELAY_MS = 7L * DAY_MS;
    private static final long DONATE_REPEAT_DELAY_MS = 30L * DAY_MS;
    private static final int DONATE_MIN_LAUNCHES = 3;
    private static final int DONATE_MAX_AUTO_PROMPTS = 3;

    private GmsProtector.Result protectResult = GmsProtector.Result.FAILED;
    private int onboardingStep = 0;
    private boolean waitingAutostartSettingsReturn = false;
    private boolean waitingWriteSettingsReturn = false;
    private boolean waitingBatterySettingsReturn = false;
    private boolean donatePromptCheckedThisSession = false;
    private boolean waitingAdvancedScreenReturn = false;
    // UI only: replay the check animation when the app returns from background.
    private LinearLayout googleServiceCheckRow;
    private boolean recheckGoogleRowAfterPause = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? UiKit.BG : UiKit.PRIMARY_DARK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        getWindow().setNavigationBarColor(UiKit.SURFACE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        // User requirement: refresh MiPush protection every time the app is opened.
        protectResult = GmsProtector.protect(this);
        // One-time migration: cancel any alarms/notification left by the removed
        // optional background service. Does not schedule ongoing work.
        LegacyBackgroundCleanup.runOnce(this);

        // Ask JobScheduler/system_server to watch the exact MILLET Settings.System
        // URI. The process does not need to stay alive; the job repairs GMS/Play
        // Store only when PowerKeeper later removes either entry.
        MilletWatchScheduler.schedule(this);
        // Mandatory event-driven Game Allowlist watcher on supported Android versions.
        if (GameWatchCore.isEnabled(this)) {
            GameWatchCore.ensureTargets(this, "APP_OPEN");
        }
        // User-selected packages reuse the same core Game Allowlist maintenance.
        AdvancedAppProtector.maintain(this, "APP_OPEN");
        if (GameWatchScheduler.shouldWatch(this)) GameWatchScheduler.ensure(this, "APP_OPEN");

        if (isSetupDone()) {
            recordReturningLaunchForDonate();
            showMainScreen();
        } else {
            continueSetupFlow();
        }
    }


    @Override
    protected void onPause() {
        GameWatchEvidence.noteUi(this, false);
        recheckGoogleRowAfterPause = googleServiceCheckRow != null;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GameWatchEvidence.noteUi(this, true);
        if (GameWatchCore.isEnabled(this)) {
            GameWatchCore.ensureTargets(this, "APP_RESUME");
        }
        if (GameWatchScheduler.shouldWatch(this)) GameWatchScheduler.ensure(this, "APP_RESUME");


        if (waitingAdvancedScreenReturn) {
            waitingAdvancedScreenReturn = false;
            if (isSetupDone()) showMainScreen();
            return;
        }

        if (waitingAutostartSettingsReturn) {
            waitingAutostartSettingsReturn = false;
            // HyperOS may update the AppOp a moment after the settings screen closes.
            checkAutostartAfterReturn(0);
            return;
        }

        if (waitingWriteSettingsReturn) {
            waitingWriteSettingsReturn = false;
            // HyperOS can apply WRITE_SETTINGS a fraction after this Activity resumes.
            checkWriteSettingsAfterReturn(0);
            return;
        }

        if (waitingBatterySettingsReturn) {
            waitingBatterySettingsReturn = false;
            // Read Android's observable battery-exemption flags again. Xiaomi's
            // private PowerKeeper mode is not exposed via a public read API.
            if (isSetupDone()) showMainScreen();
            else showOnboarding(2);
            return;
        }
        // Returning from Home/task switcher doesn't recreate this Activity.
        // Re-run the UI-only status snapshot without rebuilding the scrolled page.
        if (recheckGoogleRowAfterPause && isSetupDone()
                && googleServiceCheckRow != null && googleServiceCheckRow.isAttachedToWindow()) {
            GoogleServiceCheckUi.checkOnce(this, googleServiceCheckRow, protectResult);
        }
        recheckGoogleRowAfterPause = false;
    }

    private void continueSetupFlow() {
        XiaomiSetupState.AutostartState autostart = XiaomiSetupState.getAutostartState(this);
        boolean autostartManuallyConfirmed = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("autostart_manual_confirmed", false);

        if (autostart == XiaomiSetupState.AutostartState.DENIED
                || (autostart == XiaomiSetupState.AutostartState.UNKNOWN
                && !autostartManuallyConfirmed
                && !isSetupDone())) {
            showOnboarding(0);
            return;
        }

        if (!MilletNoRestrict.canWrite(this)) {
            showOnboarding(1);
            return;
        }

        if (!isBatteryUserConfirmed()) {
            showOnboarding(2);
            return;
        }

        finishSetup();
    }

    private void checkAutostartAfterReturn(int attempt) {
        XiaomiSetupState.AutostartState state = XiaomiSetupState.getAutostartState(this);
        if (state == XiaomiSetupState.AutostartState.ALLOWED) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("autostart_manual_confirmed", true).apply();
            continueSetupFlow();
            return;
        }

        if (state == XiaomiSetupState.AutostartState.UNKNOWN) {
            // Some HyperOS versions hide the vendor AppOp from third-party apps.
            // Stay on this step and expose a manual confirmation fallback.
            showOnboarding(0);
            return;
        }

        if (attempt < 2) {
            long delayMs = attempt == 0 ? 450L : 900L;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) checkAutostartAfterReturn(attempt + 1);
            }, delayMs);
            return;
        }

        showOnboarding(0);
        toast(getString(R.string.autostart_not_enabled));
    }

    private void checkWriteSettingsAfterReturn(int attempt) {
        if (MilletNoRestrict.canWrite(this)) {
            MilletNoRestrict.ensureGms(this);
            continueSetupFlow();
            return;
        }

        if (attempt < 2) {
            long delayMs = attempt == 0 ? 500L : 1000L;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) checkWriteSettingsAfterReturn(attempt + 1);
            }, delayMs);
            return;
        }

        showOnboarding(1);
        toast(getString(R.string.system_access_not_granted));
    }

    private void finishSetup() {
        MilletNoRestrict.ensureGms(this);
        protectResult = GmsProtector.protect(this);
        setSetupDone(true);
        showMainScreen();
    }

    private boolean isSetupDone() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false);
    }

    /** This flag records the USER'S confirmation, not a claim that Xiaomi's
     * private PowerKeeper database could be read or changed silently. */
    private boolean isBatteryUserConfirmed() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_BATTERY_USER_CONFIRMED, false);
    }

    private void setSetupDone(boolean done) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_SETUP_DONE, done);
        if (done && prefs.getLong(KEY_DONATE_START_MS, 0L) == 0L) {
            editor.putLong(KEY_DONATE_START_MS, System.currentTimeMillis());
            editor.putInt(KEY_DONATE_LAUNCH_COUNT, Math.max(1, prefs.getInt(KEY_DONATE_LAUNCH_COUNT, 0)));
        }
        editor.apply();
    }

    private void recordReturningLaunchForDonate() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int launches = prefs.getInt(KEY_DONATE_LAUNCH_COUNT, 0);
        prefs.edit().putInt(KEY_DONATE_LAUNCH_COUNT, launches + 1).apply();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(16));
        header.setBackgroundColor(UiKit.BG);
        UiKit.applyTopSystemInset(header);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(UiKit.TEXT);
        title.setTextSize(23f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.ui_short_header);
        sub.setTextColor(UiKit.MUTED);
        sub.setTextSize(13f);
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        return header;
    }

    private LinearLayout shell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.addView(buildHeader(), matchWidth());
        return root;
    }

    /** Single-screen setup overview. Only UI/navigation lives here; the existing
     * Xiaomi AppOp, WRITE_SETTINGS and battery confirmation rules stay unchanged. */
    private void showOnboarding(int step) {
        onboardingStep = Math.max(0, Math.min(2, step));
        XiaomiSetupState.AutostartState autostart = XiaomiSetupState.getAutostartState(this);
        boolean autostartManual = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("autostart_manual_confirmed", false);
        boolean[] complete = {
                autostart == XiaomiSetupState.AutostartState.ALLOWED
                        || (autostart == XiaomiSetupState.AutostartState.UNKNOWN && autostartManual),
                MilletNoRestrict.canWrite(this),
                isBatteryUserConfirmed()
        };
        int completed = (complete[0] ? 1 : 0) + (complete[1] ? 1 : 0)
                + (complete[2] ? 1 : 0);

        LinearLayout root = shell();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(10), dp(18), dp(14));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Overview heading: a centered vector icon, never font-dependent glyphs.
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView shield = onboardingIcon(R.drawable.sg_ic_shield, 46, 24, true);
        heading.addView(shield, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout headingCopy = new LinearLayout(this);
        headingCopy.setOrientation(LinearLayout.VERTICAL);
        headingCopy.setPadding(dp(12), 0, 0, 0);
        headingCopy.addView(onboardingText(R.string.onboarding_overview_title, 18f,
                UiKit.TEXT, true), matchWidth());
        TextView summary = onboardingText(R.string.onboarding_overview_subtitle,
                12.5f, UiKit.MUTED, false);
        summary.setPadding(0, dp(3), 0, 0);
        headingCopy.addView(summary, matchWidth());
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(heading, matchWidthWithBottom(dp(22)));

        LinearLayout progress = new LinearLayout(this);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            View segment = new View(this);
            int color = complete[i] ? UiKit.SUCCESS
                    : (i == onboardingStep ? UiKit.PRIMARY : UiKit.BORDER);
            segment.setBackground(UiKit.rounded(color, 4, this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(5), 1f);
            if (i > 0) lp.leftMargin = dp(7);
            progress.addView(segment, lp);
        }
        page.addView(progress, matchWidthWithBottom(dp(13)));

        LinearLayout counter = new LinearLayout(this);
        counter.setOrientation(LinearLayout.HORIZONTAL);
        counter.setGravity(Gravity.CENTER_VERTICAL);
        counter.addView(onboardingText(R.string.onboarding_section_label,
                13f, UiKit.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = new TextView(this);
        count.setText(getString(R.string.onboarding_completed_format, completed, 3));
        count.setTextColor(UiKit.MUTED);
        count.setTextSize(12.5f);
        counter.addView(count);
        page.addView(counter, matchWidthWithBottom(dp(7)));

        LinearLayout permissionRows = new LinearLayout(this);
        permissionRows.setOrientation(LinearLayout.VERTICAL);
        permissionRows.setBackground(UiKit.rounded(UiKit.BG, 16, this));
        addOnboardingRow(permissionRows, 0, R.drawable.sg_ic_power,
                R.string.onboarding_autostart_name, R.string.onboarding_autostart_desc,
                complete[0], autostart == XiaomiSetupState.AutostartState.ALLOWED);
        onboardingDivider(permissionRows);
        addOnboardingRow(permissionRows, 1, R.drawable.sg_ic_settings,
                R.string.onboarding_system_name, R.string.onboarding_system_desc,
                complete[1], true);
        onboardingDivider(permissionRows);
        addOnboardingRow(permissionRows, 2, R.drawable.sg_ic_battery,
                R.string.onboarding_battery_name, R.string.onboarding_battery_desc,
                complete[2], false);
        page.addView(permissionRows, matchWidthWithBottom(dp(15)));

        // Explain just the selected permission, not three large separate dialogs.
        TextView stepLabel = onboardingText(R.string.onboarding_instruction_label,
                12.5f, UiKit.PRIMARY, true);
        page.addView(stepLabel, matchWidthWithBottom(dp(6)));
        TextView explanation = onboardingText(onboardingStep == 0
                ? R.string.autostart_body_auto : onboardingStep == 1
                ? R.string.system_access_message_auto : R.string.battery_hyperos_check_limited,
                12.5f, UiKit.MUTED, false);
        explanation.setLineSpacing(dp(2), 1f);
        page.addView(explanation, matchWidthWithBottom(dp(12)));
        if (onboardingStep == 2) {
            boolean exempt = BatterySetupState.isIgnoringBatteryOptimizations(this);
            TextView androidBattery = onboardingText(exempt
                    ? R.string.battery_doze_granted : R.string.battery_doze_missing,
                    12f, exempt ? UiKit.SUCCESS : UiKit.MUTED, false);
            page.addView(androidBattery, matchWidthWithBottom(dp(10)));
        }
        LinearLayout hint = new LinearLayout(this);
        hint.setGravity(Gravity.TOP);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setPadding(dp(11), dp(10), dp(11), dp(10));
        hint.setBackground(UiKit.rounded(UiKit.PRIMARY_SOFT, 12, this));
        ImageView hintIcon = new ImageView(this);
        hintIcon.setImageResource(R.drawable.sg_ic_info);
        hintIcon.setColorFilter(UiKit.PRIMARY);
        hint.addView(hintIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView hintText = onboardingText(R.string.onboarding_info_note,
                11.5f, UiKit.MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(0, -2, 1f);
        hintLp.leftMargin = dp(8);
        hint.addView(hintText, hintLp);
        page.addView(hint, matchWidth());

        // Action area sticks to the bottom; permission rows remain scrollable.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(18), dp(10), dp(18), dp(14));
        actions.setBackgroundColor(UiKit.BG);
        UiKit.applyBottomSystemInset(actions);
        if (onboardingStep == 0) {
            if (complete[0]) {
                actions.addView(UiKit.primaryButton(this,
                        getString(R.string.onboarding_next_pending), v -> continueSetupFlow()),
                        matchWidth());
            } else {
                actions.addView(UiKit.primaryButton(this, getString(R.string.autostart_button), v -> {
                    waitingAutostartSettingsReturn = true;
                    openAutostartSettings();
                }), matchWidth());
                if (autostart == XiaomiSetupState.AutostartState.UNKNOWN) {
                    actions.addView(UiKit.secondaryButton(this,
                            getString(R.string.autostart_manual_continue), v -> {
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .putBoolean("autostart_manual_confirmed", true).apply();
                                continueSetupFlow();
                            }), matchWidthWithTop(dp(9)));
                }
            }
        } else if (onboardingStep == 1) {
            if (complete[1]) {
                actions.addView(UiKit.primaryButton(this,
                        getString(R.string.onboarding_next_pending), v -> continueSetupFlow()),
                        matchWidth());
            } else {
                actions.addView(UiKit.primaryButton(this,
                        getString(R.string.system_access_button), v -> openWriteSettingsAccess()),
                        matchWidth());
            }
        } else {
            boolean ignoringDoze = BatterySetupState.isIgnoringBatteryOptimizations(this);
            boolean canRequestExemption = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ignoringDoze;
            if (canRequestExemption) {
                actions.addView(UiKit.primaryButton(this, getString(R.string.battery_button),
                        v -> requestAndroidBatteryExemption()), matchWidth());
            }
            if (!complete[2]) {
                Button confirm = canRequestExemption
                        ? UiKit.secondaryButton(this, getString(R.string.battery_continue),
                        v -> confirmOnboardingBattery())
                        : UiKit.primaryButton(this, getString(R.string.battery_continue),
                        v -> confirmOnboardingBattery());
                actions.addView(confirm, canRequestExemption ? matchWidthWithTop(dp(9)) : matchWidth());
            } else {
                actions.addView(UiKit.primaryButton(this,
                        getString(R.string.onboarding_next_pending), v -> continueSetupFlow()),
                        matchWidthWithTop(canRequestExemption ? dp(9) : 0));
            }
        }
        root.addView(actions, matchWidth());
        setContentView(root);
    }

    private void confirmOnboardingBattery() {
        // This is only the user's confirmation of Xiaomi PowerKeeper choice.
        // Do not claim the vendor-private setting was verified by Android.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_BATTERY_USER_CONFIRMED, true).apply();
        continueSetupFlow();
    }

    private TextView onboardingText(int res, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(res);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private ImageView onboardingIcon(int iconRes, int boxDp, int glyphDp, boolean hero) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(hero ? UiKit.PRIMARY : UiKit.MUTED);
        icon.setPadding(dp((boxDp - glyphDp) / 2), dp((boxDp - glyphDp) / 2),
                dp((boxDp - glyphDp) / 2), dp((boxDp - glyphDp) / 2));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setBackground(UiKit.rounded(UiKit.PRIMARY_SOFT, 13, this));
        return icon;
    }

    private void onboardingDivider(LinearLayout parent) {
        View separator = new View(this);
        separator.setBackgroundColor(UiKit.BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.leftMargin = dp(60);
        parent.addView(separator, lp);
    }

    private void addOnboardingRow(LinearLayout parent, int index, int iconRes,
                                  int titleRes, int descriptionRes,
                                  boolean done, boolean independentlyVerified) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(5), dp(10), dp(5), dp(10));
        row.setMinimumHeight(dp(76));
        if (onboardingStep == index) {
            row.setBackground(UiKit.rounded(0xFFEBEDF4, 13, this));
        } else {
            row.setBackground(SettingsMotion.ripple(this));
        }
        ImageView icon = onboardingIcon(iconRes, 42, 22, false);
        row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.VERTICAL);
        label.setPadding(dp(12), 0, 0, 0);
        label.addView(onboardingText(titleRes, 14.5f, UiKit.TEXT, true), matchWidth());
        TextView description = onboardingText(descriptionRes, 12f, UiKit.MUTED, false);
        description.setMaxLines(2);
        description.setPadding(0, dp(3), 0, 0);
        label.addView(description, matchWidth());
        int labelRes = done ? (independentlyVerified
                ? R.string.onboarding_verified : R.string.onboarding_confirmed)
                : R.string.onboarding_pending;
        TextView statusLabel = onboardingText(labelRes, 11.5f,
                done ? (independentlyVerified ? UiKit.SUCCESS : UiKit.MUTED)
                        : UiKit.MUTED, false);
        statusLabel.setPadding(0, dp(3), 0, 0);
        label.addView(statusLabel, matchWidth());
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView status = new ImageView(this);
        status.setImageResource(done ? R.drawable.sg_ic_verified_check
                : R.drawable.sg_ic_status_pending);
        status.setContentDescription(getString(done ? (independentlyVerified
                ? R.string.onboarding_verified : R.string.onboarding_confirmed)
                : R.string.onboarding_pending));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(dp(21), dp(21));
        statusLp.leftMargin = dp(9);
        statusLp.rightMargin = dp(6);
        row.addView(status, statusLp);
        row.setContentDescription(getString(titleRes) + ", " + status.getContentDescription());
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> showOnboarding(index));
        parent.addView(row, matchWidth());
    }

    /** microG-inspired settings screen: large title collapses as list scrolls. */
    private void showMainScreen() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(UiKit.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        UiKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        SettingsMotion.collapsingBar(this, frame, scroll, getString(R.string.app_name), null);
        setContentView(frame);

        SettingsMotion.largeTitle(this, page, getString(R.string.app_name));
        // The visible Google Play Services row runs a one-shot status read after
        // protection setup. Animation never implies MiPush acceptance or FCM uptime.
        // microG first row: explicit diagnostic action; no passive row secretly changes settings.
        LinearLayout verify = SettingsListUi.group(this);
        SettingsListUi.row(this, verify, R.drawable.sg_ic_check,
                getString(R.string.settings13_self_check),
                getString(R.string.settings13_self_check_desc), null, SettingsListUi.NEUTRAL,
                v -> startActivity(new Intent(this, CompatibilityActivity.class)));
        page.addView(verify, matchWidth());
        addMicrogSeparator(page);

        page.addView(SettingsListUi.heading(this, getString(R.string.settings13_google_section)), matchWidth());
        LinearLayout protection = SettingsListUi.group(this);
        LinearLayout googleServiceRow = SettingsListUi.row(this, protection,
                R.drawable.sg_ic_shield,
                getString(R.string.settings_gms_service_title),
                getString(R.string.settings14_checking),
                null, SettingsListUi.NEUTRAL,
                v -> showProtectionDetailsDialog());
        // The GMS row is an interactive entry: tap it to inspect the live
        // protection state. GMS remains mandatory and is not user-toggleable.
        googleServiceRow.setContentDescription(getString(R.string.settings_gms_service_title)
                + ". " + getString(R.string.settings_protection_details_desc));
        // Spinner -> verified tick / attention icon. No polling, no background service.
        googleServiceCheckRow = googleServiceRow;
        recheckGoogleRowAfterPause = false;
        GoogleServiceCheckUi.checkOnce(this, googleServiceRow, protectResult);
        SettingsListUi.row(this, protection, R.drawable.sg_ic_apps,
                getString(R.string.ui_section_apps),
                // The built-in, read-only GMS protection is the first protected app.
                // User entries remain the only editable apps and enabled=false is excluded.
                getString(R.string.adv_main_desc, 1 + AdvancedAppStore.activeCount(this)),
                null, SettingsListUi.NEUTRAL, v -> openManagedApps());
        page.addView(protection, matchWidth());

        View needed = buildSetupCardIfNeeded();
        if (needed != null) {
            addMicrogSeparator(page);
            page.addView(SettingsListUi.heading(this, getString(R.string.ui_section_attention)), matchWidth());
            LinearLayout wrapper = new LinearLayout(this);
            wrapper.setPadding(dp(20), 0, dp(20), dp(8));
            wrapper.addView(needed, matchWidth());
            page.addView(wrapper, matchWidth());
        }

        addMicrogSeparator(page);
        page.addView(SettingsListUi.heading(this, getString(R.string.settings_tools)), matchWidth());
        LinearLayout tools = SettingsListUi.group(this);
        boolean doze = BatterySetupState.isIgnoringBatteryOptimizations(this);
        SettingsListUi.row(this, tools, R.drawable.sg_ic_battery,
                getString(R.string.settings_battery_short),
                getString(doze ? R.string.ui_status_verified : R.string.ui_status_missing),
                null, doze ? SettingsListUi.GOOD : SettingsListUi.BAD,
                v -> { if (!BatterySetupState.isIgnoringBatteryOptimizations(this))
                           requestAndroidBatteryExemption();
                       else showAndroidBatteryInfo(); });
        SettingsListUi.row(this, tools, R.drawable.sg_ic_history,
                getString(R.string.gamewatch_logs), getString(R.string.settings_history_desc),
                null, SettingsListUi.NEUTRAL, v -> startActivity(new Intent(this, ActivityLogActivity.class)));
        SettingsListUi.row(this, tools, R.drawable.sg_ic_heart,
                getString(R.string.donate_title), null, null,
                SettingsListUi.NEUTRAL, v -> showDonateDialog());
        SettingsListUi.row(this, tools, R.drawable.sg_ic_info,
                getString(R.string.app_info), null, null,
                SettingsListUi.NEUTRAL, v -> showAboutDialog());
        page.addView(tools, matchWidth());
        // Existing donation rules untouched, and shown only on normal app launch.
        page.post(this::maybeShowDonatePrompt);
    }

    private void addMicrogSeparator(LinearLayout page) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(9);
        page.addView(SettingsListUi.separator(this), lp);
    }

    

    /** Single flat Settings dialog; verification is read-only until Retry is tapped. */
    private void showProtectionDetailsDialog() {
        LinearLayout details = SettingsListUi.group(this);
        details.setPadding(dp(2), dp(8), dp(2), dp(6));
        boolean gmsInMillet = MilletNoRestrict.containsGms(this);
        boolean playInMillet = MilletNoRestrict.containsPlayStore(this);
        addProtectionStatusRow(details, R.string.dashboard_gms, gmsInMillet, false);
        // A successful broadcast confirms dispatch, not Xiaomi's acceptance.
        addProtectionStatusRow(details, R.string.dashboard_greezer,
                protectResult == GmsProtector.Result.SENT, true);
        addProtectionStatusRow(details, R.string.dashboard_play_store, playInMillet, false);
        TextView explanation = UiKit.muted(this,
                getString(R.string.settings105_mipush_note), 12.5f);
        explanation.setPadding(dp(20), dp(10), dp(20), dp(8));
        details.addView(explanation, matchWidth());
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_protection)
                .setView(details)
                .setNeutralButton(R.string.retry, (dialog, which) -> {
                    protectResult = GmsProtector.protect(this);
                    showMainScreen();
                })
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showAndroidBatteryInfo() {
        new AlertDialog.Builder(this).setTitle(R.string.settings_battery_short)
                .setMessage(R.string.battery_doze_granted)
                .setPositiveButton(R.string.close, null).show();
    }

    private void showAboutDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.app_info)
                .setView(buildAppInfoCard())
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.setOnShowListener(unused -> {
            // Match the flat Settings background: rounded corners, no card border.
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(UiKit.rounded(UiKit.BG, 20, this));
            }
            Button close = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (close != null) {
                close.setAllCaps(false);
                close.setTextColor(UiKit.PRIMARY);
            }
        });
        dialog.show();
    }

    

    private void addProtectionStatusRow(LinearLayout group, int labelRes,
                                        boolean success, boolean requestOnly) {
        int statusRes = requestOnly
                ? (success ? R.string.ui_status_sent : R.string.settings105_mipush_not_sent)
                : success ? R.string.ui_status_verified : R.string.ui_status_missing;
        int iconRes = success ? R.drawable.sg_ic_verified_check
                : R.drawable.sg_ic_status_error;
        LinearLayout row = SettingsListUi.row(this, group, iconRes,
                getString(labelRes), getString(statusRes),
                null, success ? SettingsListUi.GOOD : SettingsListUi.BAD, null);
        // Status vectors already encode verified / error colors; do not tint both
        // the colored circle and white symbol with the generic grey icon filter.
        ((ImageView) row.getChildAt(0)).clearColorFilter();
    }

    private View buildSetupCardIfNeeded() {
        XiaomiSetupState.AutostartState autostart = XiaomiSetupState.getAutostartState(this);
        boolean writeAllowed = MilletNoRestrict.canWrite(this);
        boolean gmsOk = MilletNoRestrict.containsGms(this);
        boolean playStoreOk = MilletNoRestrict.containsPlayStore(this);
        boolean selfOk = MilletNoRestrict.containsSelf(this);
        boolean batteryPending = !isBatteryUserConfirmed()
                || BatterySetupState.isBackgroundRestricted(this);

        boolean autostartMissing = autostart == XiaomiSetupState.AutostartState.DENIED;
        boolean writeMissing = !writeAllowed;
        boolean milletMissing = writeAllowed && (!gmsOk || !playStoreOk || !selfOk);

        if (!autostartMissing && !writeMissing && !milletMissing && !batteryPending) {
            return null;
        }

        LinearLayout setup = SettingsListUi.group(this);
        setup.setPadding(dp(16), dp(12), dp(16), dp(15));
        TextView setupTitle = new TextView(this);
        setupTitle.setText(R.string.setup);
        setupTitle.setTextSize(15f);
        setupTitle.setTextColor(UiKit.TEXT);
        setupTitle.setTypeface(setupTitle.getTypeface(), Typeface.BOLD);
        setup.addView(setupTitle, matchWidthWithBottom(dp(8)));

        if (autostartMissing) {
            TextView line = UiKit.muted(this, getString(R.string.autostart_setup_missing), 11.5f);
            setup.addView(line, matchWidthWithBottom(dp(8)));
            setup.addView(UiKit.primaryButton(this, getString(R.string.autostart_button), v -> {
                waitingAutostartSettingsReturn = true;
                openAutostartSettings();
            }), matchWidthWithBottom(dp(9)));
        }

        if (writeMissing) {
            TextView line = UiKit.muted(this, getString(R.string.system_access_setup_line), 11.5f);
            setup.addView(line, matchWidthWithBottom(dp(8)));
            setup.addView(UiKit.primaryButton(
                    this, getString(R.string.system_access_button), v -> openWriteSettingsAccess()),
                    matchWidthWithBottom(dp(9)));
        } else if (milletMissing) {
            setup.addView(UiKit.primaryButton(
                    this, getString(R.string.system_access_apply_now), v -> {
                        MilletNoRestrict.Result result = MilletNoRestrict.ensureGms(this);
                        protectResult = GmsProtector.protect(this);
                        toast(getString((result == MilletNoRestrict.Result.ADDED
                                || result == MilletNoRestrict.Result.ALREADY_PRESENT)
                                ? R.string.system_access_applied
                                : R.string.system_access_apply_failed));
                        showMainScreen();
                    }), matchWidthWithBottom(dp(9)));
        }

        if (batteryPending) {
            TextView line = UiKit.muted(this,
                    getString(R.string.battery_setup_missing), 11.5f);
            setup.addView(line, matchWidthWithBottom(dp(8)));
            setup.addView(UiKit.secondaryButton(this, getString(R.string.battery_review),
                    v -> showOnboarding(2)), matchWidthWithBottom(dp(9)));
        }
        return setup;
    }

    private void maybeShowDonatePrompt() {
        if (donatePromptCheckedThisSession || !isSetupDone() || isFinishing()) return;
        donatePromptCheckedThisSession = true;

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long start = prefs.getLong(KEY_DONATE_START_MS, 0L);
        int launches = prefs.getInt(KEY_DONATE_LAUNCH_COUNT, 0);
        long lastPrompt = prefs.getLong(KEY_DONATE_LAST_PROMPT_MS, 0L);
        int promptCount = prefs.getInt(KEY_DONATE_PROMPT_COUNT, 0);

        if (start <= 0L) {
            prefs.edit().putLong(KEY_DONATE_START_MS, now).apply();
            return;
        }
        if (now - start < DONATE_FIRST_DELAY_MS) return;
        if (launches < DONATE_MIN_LAUNCHES) return;
        if (promptCount >= DONATE_MAX_AUTO_PROMPTS) return;
        if (lastPrompt > 0L && now - lastPrompt < DONATE_REPEAT_DELAY_MS) return;

        long days = Math.max(7L, (now - start) / DAY_MS);
        prefs.edit()
                .putLong(KEY_DONATE_LAST_PROMPT_MS, now)
                .putInt(KEY_DONATE_PROMPT_COUNT, promptCount + 1)
                .apply();

        new AlertDialog.Builder(this)
                .setTitle(R.string.donate_reminder_title)
                .setMessage(getString(R.string.donate_reminder_message, days))
                .setPositiveButton(R.string.donate_button, (dialog, which) -> showDonateDialog())
                .setNegativeButton(R.string.donate_later, null)
                .show();
    }

    

    


    

    private void openManagedApps() {
        waitingAdvancedScreenReturn = true;
        startActivity(new Intent(this, AdvancedAppsActivity.class));
    }

    /** Secondary features remain available without stacking four cards on the dashboard. */
    

    

    


    /**
     * Collapsed status/diagnostics for mandatory Game Allowlist maintenance.
     * Embedded in the existing GMS card with MILLET/MiPush, not a second card.
     */
    

    /** One flat Settings-style dialog body. The dialog itself owns the only About title. */
    private View buildAppInfoCard() {
        LinearLayout content = SettingsListUi.group(this);
        content.setPadding(0, dp(4), 0, dp(8));

        // Plain information: do not render a second heading/card inside the dialog.
        SettingsListUi.row(this, content, R.drawable.sg_ic_info,
                getString(R.string.about_developer_label), getString(R.string.developer),
                null, SettingsListUi.NEUTRAL, null);

        // Keep the original Facebook action, now as a native ripple list item.
        SettingsListUi.row(this, content, R.drawable.ic_facebook,
                getString(R.string.facebook), null,
                null, SettingsListUi.NEUTRAL, v -> openFacebookProfile());

        TextView note = UiKit.muted(this, getString(R.string.about_note), 13f);
        note.setLineSpacing(dp(2), 1.02f);
        note.setPadding(dp(20), dp(12), dp(20), dp(6));
        content.addView(note, matchWidth());
        return content;
    }

    private void showDonateDialog() {
        final String usdtEvm = "0x4cC35bE54c358146E7b71E58f965532193848FDd";
        final String usdtTrc20 = "TD8fkqHy5cGQU3tFf7Qi7AeeqrAWi6e9Rm";

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(22), dp(10), dp(22), dp(12));

        TextView thanks = new TextView(this);
        thanks.setText(R.string.donate_thanks);
        thanks.setTextSize(14f);
        thanks.setTextColor(UiKit.TEXT);
        thanks.setGravity(Gravity.CENTER);
        content.addView(thanks, matchWidthWithBottom(dp(14)));

        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.donate_qr);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setAdjustViewBounds(true);
        qr.setBackground(UiKit.rounded(Color.WHITE, 16, this));
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        content.addView(qr, new LinearLayout.LayoutParams(dp(240), dp(240)));

        TextView hint = UiKit.muted(this, getString(R.string.donate_qr_hint), 12f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, dp(12));
        content.addView(hint, matchWidth());

        TextView cryptoTitle = new TextView(this);
        cryptoTitle.setText(R.string.donate_usdt);
        cryptoTitle.setTextSize(13f);
        cryptoTitle.setTextColor(UiKit.TEXT);
        cryptoTitle.setTypeface(cryptoTitle.getTypeface(), Typeface.BOLD);
        content.addView(cryptoTitle, matchWidth());
        content.addView(buildCryptoAddressRow("USDT - EVM", usdtEvm), matchWidthWithTop(dp(8)));
        content.addView(buildCryptoAddressRow("USDT - TRC20", usdtTrc20), matchWidthWithTop(dp(8)));

        TextView warning = UiKit.muted(this, getString(R.string.donate_network_warning), 10.5f);
        warning.setPadding(0, dp(9), 0, 0);
        content.addView(warning, matchWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(R.string.donate_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private View buildCryptoAddressRow(String network, String address) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11), dp(9), dp(9), dp(9));
        box.setBackground(UiKit.rounded(0xFFF8F7FF, 12, this, UiKit.BORDER, 1));

        TextView label = new TextView(this);
        label.setText(network);
        label.setTextSize(12.5f);
        label.setTextColor(UiKit.TEXT);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        box.addView(label, matchWidth());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, 0);

        TextView value = new TextView(this);
        value.setText(address);
        value.setTextSize(12.5f);
        value.setTextColor(UiKit.MUTED);
        value.setTextIsSelectable(true);
        value.setTypeface(Typeface.MONOSPACE);
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));

        Button copy = UiKit.secondaryButton(this, getString(R.string.copy), v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(network, address));
            toast(getString(R.string.copied_address, network));
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(dp(82), dp(42));
        copyLp.leftMargin = dp(8);
        row.addView(copy, copyLp);
        box.addView(row, matchWidth());
        return box;
    }

    private void openWriteSettingsAccess() {
        waitingWriteSettingsReturn = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }

        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
        } catch (Throwable ignored) {
            waitingWriteSettingsReturn = false;
            openAppDetails(getString(R.string.system_access_settings_fallback));
        }
    }

    private void requestAndroidBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (BatterySetupState.isIgnoringBatteryOptimizations(this)) {
            showOnboarding(2);
            return;
        }
        waitingBatterySettingsReturn = true;
        try {
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            request.setData(Uri.parse("package:" + getPackageName()));
            startActivity(request); // Android always asks the USER for consent.
        } catch (Throwable ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignoredAgain) {
                waitingBatterySettingsReturn = false;
                toast(getString(R.string.battery_request_unavailable));
            }
        }
    }

    private void openAutostartSettings() {
        // Open only the dedicated HyperOS Autostart manager. Do NOT fall back to
        // PermissionsEditorActivity: that is the long "Other permissions" screen
        // with unrelated toggles the app does not need.
        Intent action = new Intent("miui.intent.action.OP_AUTO_START");
        action.setPackage("com.miui.securitycenter");

        Intent direct = componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
        );

        if (!tryIntents(new Intent[]{action, direct})) {
            waitingAutostartSettingsReturn = false;
            openAppDetails(getString(R.string.settings_hint_autostart));
        }
    }

    private Intent componentIntent(String packageName, String className) {
        return new Intent().setComponent(new ComponentName(packageName, className));
    }

    private boolean tryIntents(Intent[] intents) {
        for (Intent intent : intents) {
            try {
                startActivity(intent);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void openAppDetails(String hint) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            toast(hint);
        } catch (Throwable ignored) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignoredAgain) {}
        }
    }

    private void openFacebookProfile() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/mrhankytb")));
        } catch (Throwable t) {
            toast(getString(R.string.facebook_failed));
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) { return UiKit.dp(this, value); }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams matchWidthWithBottom(int bottom) {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.bottomMargin = bottom;
        return lp;
    }

    private LinearLayout.LayoutParams matchWidthWithTop(int top) {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.topMargin = top;
        return lp;
    }

    
}
