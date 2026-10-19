package com.hanh.gmsprotect;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Arrays;

/** Short lived; started by system_server on the Settings content-URI event. */
public final class GameWatchJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!GameWatchScheduler.shouldWatch(this)) return false;
        boolean triggered = false;
        String urisText = "[]";
        if (Build.VERSION.SDK_INT >= 24 && params != null) {
            Uri[] uris = params.getTriggeredContentUris();
            urisText = uris == null ? "[]" : Arrays.toString(uris);
            if (uris != null) {
                Uri expected = Settings.System.getUriFor(GameWatchCore.KEY);
                for (Uri uri : uris) if (expected.equals(uri)) triggered = true;
            }
        }
        final boolean realUri = triggered;
        final boolean coreEnabled = GameWatchCore.isEnabled(this);
        if (coreEnabled) {
            GameWatchCore.onJobStart(this, realUri);
            GameWatchEvidence.noteUriJob(this, realUri);
        }
        GameWatchJournal.add(this, "JOB", "START pid=" + android.os.Process.myPid()
                + " uriTrigger=" + realUri + " uris=" + urisText);

        // Schedule work AFTER returning true. The rearm replaces the one-shot job
        // and Android may then call onStopJob for that replaced instance.
        main.post(() -> {
            if (!GameWatchScheduler.shouldWatch(this)) {
                jobFinished(params, false);
                return;
            }
            if (coreEnabled) {
            GameWatchCore.observe(this, "JOB");
            // Capture the state BEFORE repairing: a mere read-back or a forced
            // job must not earn the passive repair PASS.
            boolean missing = false;
            boolean beforeReadable = false;
            try {
                String before = GameWatchCore.raw(this);
                missing = !GameWatchCore.contains(before, GameWatchCore.GMS)
                        || !GameWatchCore.contains(before, getPackageName());
                beforeReadable = true;
            } catch (Throwable err) {
                GameWatchJournal.add(this, "COMPAT", "read before job failed: " + err);
            }
            // Even a system-forced callback may repair; only real content URI
            // events count toward compatibility evidence.
            long writesBefore = GameWatchCore.repairCount(this);
            boolean repairOk = GameWatchCore.ensureTargets(this,
                    realUri ? "URI_TRIGGER" : "JOB_OTHER");
            // A successful read alone must never be treated as a performed repair.
            boolean written = repairOk && GameWatchCore.repairCount(this) > writesBefore;
            boolean bothAfter = false;
            try {
                String after = GameWatchCore.raw(this);
                bothAfter = GameWatchCore.contains(after, GameWatchCore.GMS)
                        && GameWatchCore.contains(after, getPackageName());
            } catch (Throwable err) {
                GameWatchJournal.add(this, "COMPAT", "read after job failed: " + err);
            }
            GameWatchEvidence.noteRepair(this, realUri, beforeReadable && missing,
                    written, bothAfter);
            } // Core GMS verification must not be awarded for advanced-only jobs.
            try {
                AdvancedAppProtector.maintain(this, realUri ? "GAME_URI_JOB" : "GAME_JOB");
            } catch (Throwable error) {
                GameWatchJournal.add(this, "ADV", "Job exception: " + error);
            }
            boolean rearmed = GameWatchScheduler.rearm(this);
            GameWatchJournal.add(this, "JOB", "END rearm=" + rearmed);
            if (!rearmed) jobFinished(params, true);
            // When successful, re-scheduling same ID replaces this job.
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        GameWatchJournal.add(this, "JOB", "STOP id=" + (params == null ? -1 : params.getJobId()));
        return false;
    }
}
