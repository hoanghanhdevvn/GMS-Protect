package com.hanh.gmsprotect;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Independent of MILLET_WATCH; one content-trigger job, no periodic polling. */
public final class GameWatchScheduler {
    public static final int JOB_ID = 0x47414D45; // GAME; distinct from MILLET job
    private GameWatchScheduler() { }

    private static JobScheduler get(Context c) {
        return (JobScheduler) c.getApplicationContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    /** Automatic watcher for core GMS on supported Android versions.
     * Also watches user-selected apps; no preference toggle can cancel it.
     */
    public static boolean shouldWatch(Context c) { return GameWatchCore.isEnabled(c); }

    public static boolean isPending(Context c) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            JobScheduler s = get(c);
            return s != null && s.getPendingJob(JOB_ID) != null;
        } catch (Throwable ex) {
            GameWatchJournal.add(c, "WATCH", "isPending error: " + ex);
            return false;
        }
    }

    /** Compatibility helper: never cancel mandatory monitoring on supported ROMs. */
    public static void keepOrCancel(Context c) {
        if (shouldWatch(c)) ensure(c, "KEEP_ADVANCED");
        else cancel(c);
    }

    public static void cancel(Context c) {
        try { JobScheduler s = get(c); if (s != null) s.cancel(JOB_ID); }
        catch (Throwable ex) { GameWatchJournal.add(c, "WATCH", "cancel error: " + ex); }
    }

    public static boolean ensure(Context c, String reason) {
        return schedule(c, reason, false);
    }

    /** Content-trigger jobs are one-shot: after processing, re-register same ID. */
    public static boolean rearm(Context c) {
        return schedule(c, "JOB_REARM", true);
    }

    private static synchronized boolean schedule(Context c, String reason, boolean replace) {
        if (Build.VERSION.SDK_INT < 24 || !shouldWatch(c)) return false;
        JobScheduler js = get(c);
        if (js == null) return false;
        if (!replace && isPending(c)) return true;
        try {
            Uri uri = Settings.System.getUriFor(GameWatchCore.KEY);
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, GameWatchJobService.class))
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(uri, 0))
                    .setTriggerContentUpdateDelay(1000L)
                    .setTriggerContentMaxDelay(5000L)
                    .build();
            boolean ok = js.schedule(job) == JobScheduler.RESULT_SUCCESS;
            GameWatchJournal.add(c, "WATCH", "schedule=" + ok + " reason=" + reason
                    + " id=" + JOB_ID);
            return ok;
        } catch (Throwable ex) {
            GameWatchJournal.add(c, "WATCH", "schedule error=" + ex);
            return false;
        }
    }
}
