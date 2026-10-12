package com.hanh.gmsprotect;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot FCM recovery derived from FCM Auto.
 * It never runs a scheduler: after MiPush Protect, check local/status once.
 * Only status=3 (disconnected) is actively recovered.
 */
final class FcmReconnect {
    private static final String GMS = "com.google.android.gms";
    private static final String ACTION_HEARTBEAT_NOW =
            "com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW";
    private static final String ACTION_RECONNECT =
            "com.google.android.intent.action.GCM_RECONNECT";
    private static final long HEARTBEAT_RECHECK_MS = 2_000L;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private FcmReconnect() {}

    static void recoverIfDisconnected(Context context, Runnable completion) {
        Context app = context.getApplicationContext();
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            runCompletion(completion);
            return;
        }

        Handler main = new Handler(Looper.getMainLooper());
        GcmStatusClient client = new GcmStatusClient(app);

        Runnable finish = () -> {
            IN_FLIGHT.set(false);
            runCompletion(completion);
        };

        client.request((status, error) -> {
            // FCM Auto rule: unknown is not treated as disconnected.
            if (error != null || !"3".equals(status)) {
                finish.run();
                return;
            }

            // Recovery step 1 from FCM Auto: request an immediate MCS heartbeat.
            try {
                app.sendBroadcast(new Intent(ACTION_HEARTBEAT_NOW).setPackage(GMS));
            } catch (Throwable ignored) {
                // If heartbeat cannot be delivered, use the fallback reconnect below.
                trySendReconnect(app);
                finish.run();
                return;
            }

            main.postDelayed(() -> {
                client.request((afterHeartbeat, afterError) -> {
                    // Recovery step 2: only force GCM_RECONNECT if GMS still reports disconnected.
                    if (afterError == null && "3".equals(afterHeartbeat)) {
                        trySendReconnect(app);
                    }
                    finish.run();
                });
            }, HEARTBEAT_RECHECK_MS);
        });
    }

    private static void trySendReconnect(Context app) {
        try {
            app.sendBroadcast(new Intent(ACTION_RECONNECT).setPackage(GMS));
        } catch (Throwable ignored) {
            // Best effort. USER_UNLOCKED/app open will retry protection/recovery later.
        }
    }

    private static void runCompletion(Runnable completion) {
        if (completion == null) return;
        try {
            completion.run();
        } catch (Throwable ignored) {
        }
    }
}
