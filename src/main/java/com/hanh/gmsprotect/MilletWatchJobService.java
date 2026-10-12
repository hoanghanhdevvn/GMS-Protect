package com.hanh.gmsprotect;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;

/**
 * Event-driven MILLET repair service.
 *
 * JobScheduler/system_server watches MILLET_NO_RESTRICT_APP. When PowerKeeper
 * rebuilds that setting, this job is started without any polling loop. The job
 * writes only when GMS, Play Store, or GMS Protect itself is missing, then
 * immediately returns and rearms the one-shot content watcher.
 */
public final class MilletWatchJobService extends JobService {
    private static final String TAG = MilletWatchScheduler.TAG;
    private static final long REARM_DELAY_MS = 500L;

    @Override
    public boolean onStartJob(JobParameters params) {
        MilletNoRestrict.Result repairResult = null;

        try {
            String before = MilletNoRestrict.readRaw(this);
            boolean hasGmsBefore = containsPackage(before, MilletNoRestrict.GMS_PACKAGE);
            boolean hasPlayStoreBefore = containsPackage(before, MilletNoRestrict.PLAY_STORE_PACKAGE);
            boolean hasProtectorBefore = containsPackage(before, getPackageName());

            boolean milletTriggered = isMilletTrigger(params);

            Log.i(TAG, "JOB TRIGGERED"
                    + " jobId=" + (params == null ? -1 : params.getJobId())
                    + " containsGms=" + hasGmsBefore
                    + " containsPlayStore=" + hasPlayStoreBefore
                    + " containsProtector=" + hasProtectorBefore
                    + " milletTriggered=" + milletTriggered
                    + " triggeredUris=" + triggeredUris(params)
                    + " current=" + safeValue(before));

            // This is the strongest passive compatibility proof: system_server
            // actually delivered the MILLET content change to our dead/background app.
            if (milletTriggered) {
                CompatibilityVerification.markWatcherTriggered(this);
            }

            if (!hasGmsBefore || !hasPlayStoreBefore || !hasProtectorBefore) {
                repairResult = MilletNoRestrict.ensureGms(this);

                String after = MilletNoRestrict.readRaw(this);
                boolean hasGmsAfter = containsPackage(after, MilletNoRestrict.GMS_PACKAGE);
                boolean hasPlayStoreAfter = containsPackage(after, MilletNoRestrict.PLAY_STORE_PACKAGE);
                boolean hasProtectorAfter = containsPackage(after, getPackageName());

                Log.i(TAG, "AUTO_REPAIR result=" + repairResult
                        + " containsGms=" + hasGmsAfter
                        + " containsPlayStore=" + hasPlayStoreAfter
                        + " containsProtector=" + hasProtectorAfter
                        + " current=" + safeValue(after));

                // Do not mark a mere scheduled/forced job as verified repair.
                // Require a real MILLET trigger, GMS missing before, and GMS
                // present after our repair on this exact ROM fingerprint.
                if (milletTriggered
                        && !hasGmsBefore
                        && hasGmsAfter
                        && hasPlayStoreAfter
                        && (repairResult == MilletNoRestrict.Result.ADDED
                        || repairResult == MilletNoRestrict.Result.ALREADY_PRESENT)) {
                    CompatibilityVerification.markAutoRepairVerified(this);
                }
            } else {
                Log.i(TAG, "AUTO_REPAIR skipped=already_present");
            }
        } catch (Throwable t) {
            Log.e(TAG, "JOB exception=" + errorOf(t), t);
        }

        // TriggerContentUri jobs are one-shot after execution. Rearm after this
        // run. If our repair generated another Settings notification, the next
        // run sees both required packages already present and performs no write.
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> MilletWatchScheduler.schedule(getApplicationContext()),
                REARM_DELAY_MS);

        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "JOB STOPPED jobId=" + (params == null ? -1 : params.getJobId()));
        return false;
    }

    private static boolean isMilletTrigger(JobParameters params) {
        if (params == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            Uri[] uris = params.getTriggeredContentUris();
            if (uris == null) return false;
            Uri expected = android.provider.Settings.System.getUriFor(MilletNoRestrict.KEY);
            for (Uri uri : uris) {
                if (expected.equals(uri)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String triggeredUris(JobParameters params) {
        if (params == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "[]";
        try {
            Uri[] uris = params.getTriggeredContentUris();
            return uris == null ? "[]" : Arrays.toString(uris);
        } catch (Throwable t) {
            return "<unavailable:" + errorOf(t) + ">";
        }
    }

    private static boolean containsPackage(String raw, String packageName) {
        if (raw == null || raw.trim().isEmpty() || packageName == null) return false;
        String normalized = raw.replace(';', ',')
                .replace('\n', ',')
                .replace('\t', ',');
        for (String token : normalized.split(",")) {
            if (packageName.equals(token.trim())) return true;
        }
        return false;
    }

    private static String safeValue(String value) {
        if (value == null || value.isEmpty()) return "<empty>";
        if (value.length() <= 1200) return value;
        return value.substring(0, 1200) + "...(len=" + value.length() + ")";
    }

    private static String errorOf(Throwable t) {
        if (t == null) return "unknown";
        return t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
    }
}
