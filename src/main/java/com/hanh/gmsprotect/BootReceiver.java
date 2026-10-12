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
                // The switch state lives in device-protected storage, so the
                // background Aurogon monitor can be restored after reboot.
                AurogonAllowlistService.startIfEnabled(context);
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
