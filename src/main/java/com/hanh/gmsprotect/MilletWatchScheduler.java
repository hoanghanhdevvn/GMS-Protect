package com.hanh.gmsprotect;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Event-driven watcher registration for MILLET_NO_RESTRICT_APP.
 *
 * No polling, alarm, foreground service or wake lock is used. On Android N+
 * JobScheduler asks system_server to watch the Settings.System content URI and
 * start MilletWatchJobService only after that URI is notified as changed.
 */
public final class MilletWatchScheduler {
    static final String TAG = "MILLET_WATCH";
    private static final int JOB_ID = 0x47534D57; // "GSMW"; stable per package.

    private MilletWatchScheduler() {}

    public static boolean schedule(Context context) {
        Context app = context.getApplicationContext();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.i(TAG, "SCHEDULE unsupported sdk=" + Build.VERSION.SDK_INT);
            return false;
        }

        try {
            JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) {
                Log.w(TAG, "SCHEDULE failed reason=no_JobScheduler");
                return false;
            }

            Uri uri = Settings.System.getUriFor(MilletNoRestrict.KEY);
            JobInfo job = new JobInfo.Builder(
                    JOB_ID,
                    new ComponentName(app, MilletWatchJobService.class))
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(uri, 0))
                    // Small batching window. Android/HyperOS may
                    // still clamp/delay execution according to JobScheduler policy.
                    .setTriggerContentUpdateDelay(1_000L)
                    .setTriggerContentMaxDelay(5_000L)
                    .build();

            int result = scheduler.schedule(job);
            boolean ok = result == JobScheduler.RESULT_SUCCESS;
            Log.i(TAG, "SCHEDULE result=" + (ok ? "SUCCESS" : "FAILURE")
                    + " jobId=" + JOB_ID
                    + " uri=" + uri);
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "SCHEDULE exception=" + errorOf(t), t);
            return false;
        }
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static boolean isScheduled(Context context) {
        if (!isSupported()) return false;
        try {
            JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                    .getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return false;
            JobInfo job = scheduler.getPendingJob(JOB_ID);
            return job != null;
        } catch (Throwable t) {
            Log.w(TAG, "isScheduled failed error=" + errorOf(t));
            return false;
        }
    }

    static int jobId() {
        return JOB_ID;
    }

    private static String errorOf(Throwable t) {
        if (t == null) return "unknown";
        return t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
    }
}
