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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(UiKit.PRIMARY_DARK);
        getWindow().setNavigationBarColor(UiKit.SURFACE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        // User requirement: refresh MiPush protection every time the app is opened.
        protectResult = GmsProtector.protect(this);
        // If the optional Aurogon background switch was already enabled, make
        // sure its monitor service is alive after a process restart.
        AurogonAllowlistService.startIfEnabled(this);

        // Ask JobScheduler/system_server to watch the exact MILLET Settings.System
        // URI. The process does not need to stay alive; the job repairs GMS/Play
        // Store only when PowerKeeper later removes either entry.
        MilletWatchScheduler.schedule(this);

        if (isSetupDone()) {
            recordReturningLaunchForDonate();
            showMainScreen();
        } else {
            continueSetupFlow();
        }
    }


    @Override
    protected void onResume() {
        super.onResume();

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
        }
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
        header.setPadding(dp(20), dp(20), dp(20), dp(18));
        header.setBackground(UiKit.verticalGradient());
        UiKit.applyTopSystemInset(header);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(25f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.header_subtitle);
        sub.setTextColor(0xFFDAD9FF);
        sub.setTextSize(12f);
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

    private void showOnboarding(int step) {
        onboardingStep = Math.max(0, Math.min(2, step));
        LinearLayout root = shell();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(28));
        UiKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        TextView stepText = UiKit.muted(this, getString(R.string.step_format, onboardingStep + 1, 3), 12f);
        stepText.setGravity(Gravity.CENTER);
        page.addView(stepText, matchWidthWithBottom(dp(10)));

        LinearLayout card = UiKit.card(this);
        TextView icon = new TextView(this);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(46f);
        icon.setTextColor(UiKit.PRIMARY);
        icon.setText(onboardingStep == 0 ? "↻" : onboardingStep == 1 ? "⚙" : "☼");
        card.addView(icon, matchWidthWithBottom(dp(10)));

        TextView title = new TextView(this);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(22f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setText(onboardingStep == 0 ? R.string.autostart_title
                : onboardingStep == 1 ? R.string.system_access_title : R.string.battery_title);
        card.addView(title, matchWidthWithBottom(dp(9)));

        TextView body = UiKit.muted(this,
                getString(onboardingStep == 0 ? R.string.autostart_body_auto
                        : onboardingStep == 1 ? R.string.system_access_message_auto
                        : R.string.battery_body),
                13f);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0f, 1.2f);
        card.addView(body, matchWidthWithBottom(dp(20)));

        if (onboardingStep == 0) {
            XiaomiSetupState.AutostartState state = XiaomiSetupState.getAutostartState(this);
            card.addView(UiKit.primaryButton(this, getString(R.string.autostart_button), v -> {
                waitingAutostartSettingsReturn = true;
                openAutostartSettings();
            }), matchWidthWithBottom(dp(9)));

            if (state == XiaomiSetupState.AutostartState.UNKNOWN) {
                card.addView(UiKit.secondaryButton(this, getString(R.string.autostart_manual_continue), v -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("autostart_manual_confirmed", true).apply();
                    continueSetupFlow();
                }), matchWidth());
            }
        } else if (onboardingStep == 1) {
            card.addView(UiKit.primaryButton(this, getString(R.string.system_access_button), v ->
                    openWriteSettingsAccess()), matchWidth());
        } else {
            // Native HyperOS mode requires a real user choice. Public Android
            // DeviceIdle exemption is useful telemetry, but is NOT the same as
            // Xiaomi's PowerKeeper "noRestrict" database row.
            boolean ignoringDoze = BatterySetupState.isIgnoringBatteryOptimizations(this);
            int dozeText = ignoringDoze ? R.string.battery_doze_granted : R.string.battery_doze_missing;
            TextView doze = UiKit.muted(this, getString(dozeText), 12f);
            doze.setGravity(Gravity.CENTER);
            card.addView(doze, matchWidthWithBottom(dp(10)));
            TextView warning = UiKit.muted(this, getString(R.string.battery_hyperos_check_limited), 11f);
            warning.setGravity(Gravity.CENTER);
            card.addView(warning, matchWidthWithBottom(dp(14)));

            // Keep a single battery button: open the Android exemption request,
            // which is the action known to work on the target device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ignoringDoze) {
                card.addView(UiKit.primaryButton(this, getString(R.string.battery_button),
                        v -> requestAndroidBatteryExemption()), matchWidthWithBottom(dp(9)));
            }
            card.addView(UiKit.secondaryButton(this, getString(R.string.battery_continue), v -> {
                // User confirms having selected Xiaomi No restrictions manually.
                // Never mislabel this as a vendor-verified permission.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_BATTERY_USER_CONFIRMED, true).apply();
                continueSetupFlow();
            }), matchWidth());
        }

        page.addView(card, matchWidth());
    }

    private void showMainScreen() {
        LinearLayout root = shell();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        UiKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        LinearLayout success = UiKit.card(this);
        success.setGravity(Gravity.CENTER_HORIZONTAL);
        success.setPadding(dp(20), dp(25), dp(20), dp(22));

        boolean ok = protectResult == GmsProtector.Result.SENT;
        TextView check = new TextView(this);
        check.setText(ok ? "✓" : "!");
        check.setTextSize(76f);
        check.setGravity(Gravity.CENTER);
        check.setTypeface(check.getTypeface(), Typeface.BOLD);
        check.setTextColor(ok ? UiKit.SUCCESS : UiKit.ERROR);
        success.addView(check, matchWidth());

        TextView status = UiKit.pill(this,
                getString(ok ? R.string.status_protected
                        : protectResult == GmsProtector.Result.GMS_MISSING ? R.string.status_unavailable : R.string.status_failed),
                ok ? UiKit.SUCCESS_SOFT : UiKit.ERROR_SOFT,
                ok ? UiKit.SUCCESS : UiKit.ERROR);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.gravity = Gravity.CENTER_HORIZONTAL;
        pillLp.bottomMargin = dp(12);
        success.addView(status, pillLp);

        TextView title = new TextView(this);
        title.setText(ok ? R.string.success_title
                : protectResult == GmsProtector.Result.GMS_MISSING ? R.string.gms_missing : R.string.protect_failed);
        title.setTextSize(ok ? 23f : 19f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        success.addView(title, matchWidthWithBottom(dp(9)));

        if (ok) {
            TextView body = UiKit.muted(this, getString(R.string.success_body), 13f);
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(0f, 1.2f);
            success.addView(body, matchWidthWithBottom(dp(9)));

            TextView detail = UiKit.muted(this, getString(R.string.success_detail), 11.5f);
            detail.setGravity(Gravity.CENTER);
            success.addView(detail, matchWidthWithBottom(dp(10)));

            boolean writeAllowed = MilletNoRestrict.canWrite(this);
            boolean gmsNoRestrict = MilletNoRestrict.containsGms(this);
            boolean playStoreNoRestrict = MilletNoRestrict.containsPlayStore(this);

            LinearLayout protectionNotes = new LinearLayout(this);
            protectionNotes.setOrientation(LinearLayout.VERTICAL);
            protectionNotes.setGravity(Gravity.START);

            TextView limiterStatus = UiKit.pill(
                    this,
                    getString(gmsNoRestrict
                            ? R.string.gms_no_restrict_active
                            : writeAllowed
                            ? R.string.gms_no_restrict_missing
                            : R.string.gms_no_restrict_permission_needed),
                    gmsNoRestrict ? UiKit.SUCCESS_SOFT : UiKit.ERROR_SOFT,
                    gmsNoRestrict ? UiKit.SUCCESS : UiKit.ERROR
            );
            limiterStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams limiterLp = new LinearLayout.LayoutParams(-2, -2);
            limiterLp.gravity = Gravity.START;
            limiterLp.bottomMargin = dp(6);
            protectionNotes.addView(limiterStatus, limiterLp);

            TextView greezerStatus = UiKit.pill(
                    this,
                    getString(R.string.greezer_protection_active),
                    UiKit.SUCCESS_SOFT,
                    UiKit.SUCCESS
            );
            greezerStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams greezerLp = new LinearLayout.LayoutParams(-2, -2);
            greezerLp.gravity = Gravity.START;
            greezerLp.bottomMargin = dp(6);
            protectionNotes.addView(greezerStatus, greezerLp);

            TextView playStoreStatus = UiKit.pill(
                    this,
                    getString(playStoreNoRestrict
                            ? R.string.play_store_no_restrict_active
                            : writeAllowed
                            ? R.string.play_store_no_restrict_missing
                            : R.string.play_store_no_restrict_permission_needed),
                    playStoreNoRestrict ? UiKit.SUCCESS_SOFT : UiKit.ERROR_SOFT,
                    playStoreNoRestrict ? UiKit.SUCCESS : UiKit.ERROR
            );
            playStoreStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams playStoreLp = new LinearLayout.LayoutParams(-2, -2);
            playStoreLp.gravity = Gravity.START;
            protectionNotes.addView(playStoreStatus, playStoreLp);

            LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(-1, -2);
            notesLp.gravity = Gravity.START;
            success.addView(protectionNotes, notesLp);
        } else {
            success.addView(UiKit.primaryButton(this, getString(R.string.retry), v -> {
                protectResult = GmsProtector.protect(this);
                showMainScreen();
            }), matchWidthWithTop(dp(14)));
        }
        page.addView(success, cardMargin());

        // Keep the top bar clean. Compatibility check lives below Donate.
        page.addView(buildAdvancedCard(), cardMargin());
        page.addView(buildDonateCard(), cardMargin());
        page.addView(buildCompatibilityCard(), cardMargin());

        View setupCard = buildSetupCardIfNeeded();
        if (setupCard != null) {
            page.addView(setupCard, cardMargin());
        }
        page.addView(buildAppInfoCard(), cardMargin());

        // Setup actions are shown only when something is actually missing.
        // Do not stack permission dialogs over the main screen.
        page.post(this::maybeShowDonatePrompt);
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

        LinearLayout setup = UiKit.card(this);
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

    private View buildCompatibilityCard() {
        LinearLayout card = UiKit.card(this);

        TextView title = new TextView(this);
        title.setText(R.string.compat_title);
        title.setTextSize(15f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title, matchWidthWithBottom(dp(6)));

        TextView desc = UiKit.muted(this, getString(R.string.compat_card_desc), 11f);
        desc.setLineSpacing(0f, 1.15f);
        card.addView(desc, matchWidthWithBottom(dp(11)));

        card.addView(UiKit.secondaryButton(this, getString(R.string.compat_open), v ->
                startActivity(new Intent(this, CompatibilityActivity.class))), matchWidth());
        return card;
    }

    private View buildDonateCard() {
        LinearLayout card = UiKit.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.donate_title);
        title.setTextSize(15f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_donate_heart, 0, 0, 0);
        title.setCompoundDrawablePadding(dp(5));
        texts.addView(title);
        TextView desc = UiKit.muted(this, getString(R.string.donate_desc), 11f);
        desc.setPadding(0, dp(4), dp(8), 0);
        texts.addView(desc);

        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(UiKit.secondaryButton(this, getString(R.string.donate_button), v -> showDonateDialog()),
                new LinearLayout.LayoutParams(dp(90), dp(46)));
        card.addView(row);
        return card;
    }


    private View buildAdvancedCard() {
        LinearLayout card = UiKit.card(this);

        TextView title = new TextView(this);
        title.setText(R.string.advanced);
        title.setTextSize(15f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title, matchWidthWithBottom(dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView itemTitle = new TextView(this);
        itemTitle.setText(R.string.aurogon_allowlist_title);
        itemTitle.setTextSize(13.5f);
        itemTitle.setTextColor(UiKit.TEXT);
        itemTitle.setTypeface(itemTitle.getTypeface(), Typeface.BOLD);
        texts.addView(itemTitle, matchWidth());

        TextView desc = UiKit.muted(this, getString(R.string.aurogon_allowlist_desc), 11f);
        desc.setLineSpacing(0f, 1.15f);
        desc.setPadding(0, dp(4), dp(10), 0);
        texts.addView(desc, matchWidth());

        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(AurogonAllowlistService.isEnabled(this));
        toggle.setContentDescription(getString(R.string.aurogon_allowlist_title));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean applied = AurogonAllowlistService.setEnabled(this, isChecked);
            if (!applied) {
                toast(getString(R.string.aurogon_allowlist_failed));
                showMainScreen();
                return;
            }
            toast(getString(isChecked
                    ? R.string.aurogon_allowlist_enabled
                    : R.string.aurogon_allowlist_disabled));
        });
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));

        card.addView(row, matchWidth());

        TextView timing = UiKit.muted(this, getString(R.string.aurogon_allowlist_timing), 10.5f);
        timing.setPadding(0, dp(10), 0, 0);
        card.addView(timing, matchWidthWithBottom(dp(12)));

        TextView batteryStatus = UiKit.muted(this,
                getString(BatterySetupState.isIgnoringBatteryOptimizations(this)
                        ? R.string.battery_doze_granted : R.string.battery_doze_missing), 11f);
        card.addView(batteryStatus, matchWidthWithBottom(dp(7)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !BatterySetupState.isIgnoringBatteryOptimizations(this)) {
            card.addView(UiKit.secondaryButton(this, getString(R.string.battery_button),
                    v -> requestAndroidBatteryExemption()), matchWidth());
        }
        return card;
    }

    private View buildAppInfoCard() {
        LinearLayout card = UiKit.card(this);
        TextView title = new TextView(this);
        title.setText(R.string.app_info);
        title.setTextSize(15f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title);

        TextView dev = UiKit.muted(this, getString(R.string.developer), 12f);
        dev.setPadding(0, dp(7), 0, 0);
        card.addView(dev);
        TextView note = UiKit.muted(this, getString(R.string.about_note), 10.8f);
        note.setPadding(0, dp(6), 0, dp(9));
        card.addView(note);

        Button fb = UiKit.secondaryButton(this, getString(R.string.facebook), v -> openFacebookProfile());
        fb.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_facebook, 0, 0, 0);
        fb.setCompoundDrawablePadding(dp(7));
        card.addView(fb, matchWidth());
        return card;
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
        label.setTextSize(11.5f);
        label.setTextColor(UiKit.TEXT);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        box.addView(label, matchWidth());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, 0);

        TextView value = new TextView(this);
        value.setText(address);
        value.setTextSize(10.5f);
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

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.bottomMargin = dp(14);
        return lp;
    }
}
