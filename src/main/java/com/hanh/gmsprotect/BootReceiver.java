package com.hanh.gmsprotect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            BroadcastReceiver.PendingResult pending = goAsync();
            try {
                // GAME_WATCH is deliberately independent of FCM recovery callbacks.
                // Even if MiPush/FCM is unavailable, this watcher still rearms.
                if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                        && GameWatchCore.isEnabled(context)) {
                    boolean targetsReady = GameWatchCore.ensureTargets(context,
                            "BOOT_" + action);
                    boolean jobReady = GameWatchScheduler.ensure(context,
                            "BOOT_" + action);
                    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                            || Intent.ACTION_USER_UNLOCKED.equals(action)) {
                        GameWatchEvidence.noteBoot(context, targetsReady && jobReady);
                    }
                }
                if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                    try {
                        AdvancedAppProtector.maintain(context, "BOOT_" + action);
                        if (GameWatchScheduler.shouldWatch(context)) {
                            GameWatchScheduler.ensure(context, "BOOT_ADV_" + action);
                        }
                    } catch (Throwable ignored) { }
                }
                // Clear obsolete background alarms on upgrade / first regular boot.
                // No new alarm, service or polling task is created.
                if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                    LegacyBackgroundCleanup.runOnce(context);
                }
                // Arm the event-driven MILLET watcher after the normal boot protection
                // pass. No polling or long-running process is required.
                GmsProtector.protect(context, () -> {
                    MilletWatchScheduler.schedule(context);
                    pending.finish();
                });
            } catch (Throwable t) {
                pending.finish();
            }
        }
    }
}
