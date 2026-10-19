package com.hanh.gmsprotect;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * One-time upgrade cleanup of the discontinued optional background monitor.
 * This class never starts a service, registers a receiver or schedules an alarm.
 */
final class LegacyBackgroundCleanup {
    private static final String TAG = "LegacyBgCleanup";
    private static final String PREFS = "removed_background_monitor_v1";
    private static final int ALARM_REQUEST_CODE = 4108;
    private static final int NOTIFICATION_ID = 4107;

    private LegacyBackgroundCleanup() { }

    static void runOnce(Context context) {
        Context app = context.getApplicationContext();
        Context storage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? app.createDeviceProtectedStorageContext() : app;
        SharedPreferences migration = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (migration.getBoolean("complete", false)) return;
        try {
            // Disable the old user preference before cleaning historical artifacts.
            storage.getSharedPreferences("aurogon_background_v1", Context.MODE_PRIVATE)
                    .edit().remove("enabled").commit();

            Intent obsoleteService = new Intent();
            obsoleteService.setComponent(new ComponentName(app.getPackageName(),
                    app.getPackageName() + ".AurogonAllowlistService"));
            try { app.stopService(obsoleteService); } catch (Throwable ignored) { }

            Intent obsoleteAlarm = new Intent("com.hanh.gmsprotect.AUROGON_REFRESH_ALARM");
            obsoleteAlarm.setComponent(new ComponentName(app.getPackageName(),
                    app.getPackageName() + ".AurogonRefreshReceiver"));
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(app, ALARM_REQUEST_CODE, obsoleteAlarm, flags);
            if (pending != null) {
                AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) alarmManager.cancel(pending);
                pending.cancel();
            }

            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.deleteNotificationChannel("aurogon_allowlist_background");
                }
            }
            migration.edit().putBoolean("complete", true).apply();
            Log.i(TAG, "Legacy background artifacts cleaned");
        } catch (Throwable error) {
            // Keep the migration pending so it can retry next launch or boot.
            Log.w(TAG, "Legacy cleanup deferred", error);
        }
    }
}
