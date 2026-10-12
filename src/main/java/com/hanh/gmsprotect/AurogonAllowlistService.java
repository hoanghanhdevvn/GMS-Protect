package com.hanh.gmsprotect;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

public class AurogonAllowlistService extends Service {
    private static final String TAG = "AurogonAllowBg";
    private static final String PREFS = "aurogon_background_v1";
    private static final String KEY_ENABLED = "enabled";

    private static final String CHANNEL_ID = "aurogon_allowlist_background";
    private static final int NOTIFICATION_ID = 4107;
    private static final int ALARM_REQUEST_CODE = 4108;

    static final String ACTION_REFRESH_ALARM = "com.hanh.gmsprotect.AUROGON_REFRESH_ALARM";
    static final String EXTRA_STAGE = "stage";
    static final int STAGE_FIRST = 1;
    static final int STAGE_SECOND = 2;
    static final int STAGE_PERIODIC = 3;

    // HyperOS GMS limiter first runs roughly 30s after screen-off.
    private static final long FIRST_DELAY_MS = 40_000L;
    // Second refresh lands at roughly 2 minutes after screen-off.
    private static final long SECOND_DELAY_MS = 80_000L;
    // Sparse maintenance while the screen remains off.
    private static final long PERIODIC_DELAY_MS = 10L * 60L * 1000L;

    private boolean receiverRegistered;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.i(TAG, "SCREEN_OFF -> schedule 40s / 2m / 10m");
                cancelRefreshAlarm(context);
                scheduleRefreshAlarm(context, STAGE_FIRST, FIRST_DELAY_MS);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                Log.i(TAG, "SCREEN_ON -> cancel background alarm + restore once");
                cancelRefreshAlarm(context);
                AurogonAllowlistNotifyProbe.notifyReload(context, "screen_on");
            }
        }
    };

    public static boolean isEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_ENABLED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        try {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
            if (enabled) {
                AurogonAllowlistNotifyProbe.notifyReload(context, "switch_on");
                start(context);
            } else {
                cancelRefreshAlarm(context);
                context.getApplicationContext().stopService(
                        new Intent(context.getApplicationContext(), AurogonAllowlistService.class));
            }
            return true;
        } catch (Throwable t) {
            try { prefs(context).edit().putBoolean(KEY_ENABLED, false).apply(); } catch (Throwable ignored) {}
            Log.w(TAG, "setEnabled failed: " + t);
            return false;
        }
    }

    public static void startIfEnabled(Context context) {
        if (!isEnabled(context)) return;
        try {
            start(context);
        } catch (Throwable t) {
            Log.w(TAG, "startIfEnabled failed: " + t);
        }
    }

    private static void start(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, AurogonAllowlistService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app = app.createDeviceProtectedStorageContext();
        }
        return app.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!isEnabled(this)) {
            stopSelf();
            return;
        }

        ensureNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerScreenReceiver();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        if (!interactive) {
            cancelRefreshAlarm(this);
            scheduleRefreshAlarm(this, STAGE_FIRST, FIRST_DELAY_MS);
        }
        Log.i(TAG, "service started interactive=" + interactive);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.aurogon_notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.aurogon_notification_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.aurogon_notification_title))
                .setContentText(getString(R.string.aurogon_notification_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setColor(UiKit.PRIMARY);
        return builder.build();
    }

    static void handleRefreshAlarm(Context context, int stage) {
        if (!isEnabled(context)) {
            cancelRefreshAlarm(context);
            return;
        }

        PowerManager pm = (PowerManager) context.getSystemService(POWER_SERVICE);
        if (pm != null && pm.isInteractive()) {
            cancelRefreshAlarm(context);
            Log.i(TAG, "alarm ignored because screen is on");
            return;
        }

        String reason;
        long nextDelay;
        int nextStage;
        if (stage == STAGE_FIRST) {
            reason = "screen_off_40s";
            nextDelay = SECOND_DELAY_MS;
            nextStage = STAGE_SECOND;
        } else if (stage == STAGE_SECOND) {
            reason = "screen_off_2m";
            nextDelay = PERIODIC_DELAY_MS;
            nextStage = STAGE_PERIODIC;
        } else {
            reason = "screen_off_10m";
            nextDelay = PERIODIC_DELAY_MS;
            nextStage = STAGE_PERIODIC;
        }

        AurogonAllowlistNotifyProbe.notifyReload(context, reason);
        scheduleRefreshAlarm(context, nextStage, nextDelay);
    }

    static void scheduleRefreshAlarm(Context context, int stage, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = refreshPendingIntent(context, stage);
        long when = SystemClock.elapsedRealtime() + Math.max(1_000L, delayMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "schedule alarm failed: " + t);
        }
    }

    static void cancelRefreshAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(refreshPendingIntent(context, STAGE_FIRST));
        } catch (Throwable ignored) {}
    }

    private static PendingIntent refreshPendingIntent(Context context, int stage) {
        Intent intent = new Intent(context.getApplicationContext(), AurogonRefreshReceiver.class);
        intent.setAction(ACTION_REFRESH_ALARM);
        intent.putExtra(EXTRA_STAGE, stage);
        return PendingIntent.getBroadcast(
                context.getApplicationContext(),
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
