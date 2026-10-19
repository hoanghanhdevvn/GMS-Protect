package com.hanh.gmsprotect;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * A one-shot, UI-only verification animation for the Google Play Services row.
 * This class never edits settings, sends MiPush, schedules work, or checks FCM
 * connectivity. The actual protect/ensure work remains in the existing core.
 */
public final class GoogleServiceCheckUi {
    private static final long MIN_VISIBLE_SPINNER_MS = 900L;

    private GoogleServiceCheckUi() {}

    /** Runs once when the main Settings screen is constructed. */
    public static void checkOnce(Activity activity, LinearLayout row,
                                 GmsProtector.Result requestResult) {
        // row(icon, textColumn) is built in SettingsListUi with nonempty subtitle.
        LinearLayout textColumn = (LinearLayout) row.getChildAt(1);
        TextView subtitle = (TextView) textColumn.getChildAt(1);
        subtitle.setText(R.string.settings14_checking);
        subtitle.setTextColor(UiKit.MUTED);

        // Replays must replace the previous indicator rather than append a new one.
        View previous = row.getTag() instanceof View ? (View) row.getTag() : null;
        if (previous != null && previous.getParent() == row) row.removeView(previous);
        int size = UiKit.dp(activity, 24);
        ProgressBar spinner = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(UiKit.PRIMARY));
        spinner.setContentDescription(activity.getString(R.string.settings14_checking));
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(size, size);
        spinnerLp.leftMargin = UiKit.dp(activity, 12);
        spinnerLp.rightMargin = UiKit.dp(activity, 8);
        row.addView(spinner, spinnerLp);
        // Per-run token: a stale verification result must not replace a newer one.
        row.setTag(spinner);

        Context app = activity.getApplicationContext();
        long started = SystemClock.uptimeMillis();
        Handler main = new Handler(Looper.getMainLooper());
        // Read Settings.System and scheduled-job state off the UI thread so
        // animations do not block when system_server is busy.
        new Thread(() -> {
            Outcome outcome = readOutcome(app, requestResult);
            long wait = Math.max(0L, MIN_VISIBLE_SPINNER_MS
                    - (SystemClock.uptimeMillis() - started));
            main.postDelayed(() -> {
                // A navigation or Activity recreation can detach the old row.
                if (activity.isFinishing()
                        || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())
                        || !row.isAttachedToWindow() || row.getTag() != spinner) return;
                render(activity, row, spinner, subtitle, outcome);
            }, wait);
        }, "GMS-UI-Check").start();
    }

    private static Outcome readOutcome(Context app, GmsProtector.Result requestResult) {
        if (requestResult == GmsProtector.Result.GMS_MISSING) return Outcome.GMS_MISSING;
        try {
            // Every green indicator is grounded in a directly readable system value.
            boolean millet = MilletNoRestrict.containsGms(app)
                    && MilletNoRestrict.containsPlayStore(app)
                    && MilletNoRestrict.containsSelf(app);
            boolean watch = !MilletWatchScheduler.isSupported()
                    || MilletWatchScheduler.isScheduled(app);
            boolean game = true;
            if (GameWatchCore.isEnabled(app)) {
                String raw = GameWatchCore.raw(app);
                game = GameWatchCore.contains(raw, GameWatchCore.GMS)
                        && GameWatchCore.contains(raw, app.getPackageName())
                        && GameWatchScheduler.isPending(app);
            }
            if (!millet || !watch || !game) return Outcome.ATTENTION;
            // SENT only means the MiPush protection request was dispatched.
            return requestResult == GmsProtector.Result.SENT
                    ? Outcome.VERIFIED_REQUEST_SENT : Outcome.REQUEST_FAILED;
        } catch (Throwable ignored) {
            // Unknown readings are not treated as success.
            return Outcome.ATTENTION;
        }
    }

    private static void render(Activity activity, LinearLayout row, View spinner,
                               TextView subtitle, Outcome result) {
        row.removeView(spinner);
        boolean ok = result == Outcome.VERIFIED_REQUEST_SENT;
        int textRes;
        switch (result) {
            case VERIFIED_REQUEST_SENT: textRes = R.string.settings14_verified; break;
            case GMS_MISSING: textRes = R.string.gms_missing; break;
            case REQUEST_FAILED: textRes = R.string.settings14_request_failed; break;
            default: textRes = R.string.settings14_attention;
        }
        subtitle.setText(textRes);
        subtitle.setTextColor(ok ? UiKit.MUTED : UiKit.ERROR);
        ImageView image = new ImageView(activity);
        image.setImageResource(ok ? R.drawable.sg_ic_verified_check
                : R.drawable.sg_ic_check_attention);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(activity.getString(ok
                ? R.string.settings14_check_verified_a11y
                : R.string.settings14_check_attention_a11y));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                UiKit.dp(activity, 24), UiKit.dp(activity, 24));
        lp.leftMargin = UiKit.dp(activity, 12);
        lp.rightMargin = UiKit.dp(activity, 8);
        row.addView(image, lp);
        row.setTag(image);
        image.setAlpha(0f);
        image.setScaleX(0.65f);
        image.setScaleY(0.65f);
        image.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
    }

    private enum Outcome { VERIFIED_REQUEST_SENT, REQUEST_FAILED, ATTENTION, GMS_MISSING }
}
