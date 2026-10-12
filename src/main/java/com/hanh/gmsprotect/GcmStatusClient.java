package com.hanh.gmsprotect;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight copy of FCM Auto's GMS local/status probe. */
final class GcmStatusClient {
    interface Callback {
        void onResult(String status, String error);
    }

    private static final String GMS = "com.google.android.gms";
    private static final String ACTION_SEND = "com.google.android.gcm.intent.SEND";
    private static final String GTALK_PERMISSION =
            "com.google.android.gtalkservice.permission.GTALK_SERVICE";
    private static final String LOCAL_STATUS =
            "https://gcm.googleapis.com/local/status";
    private static final long TIMEOUT_MS = 3_000L;
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PendingIntent appIdentity;

    private Callback callback;
    private String messageId;
    private boolean finished;

    GcmStatusClient(Context context) {
        this.context = context.getApplicationContext();
        Intent invalid = new Intent();
        invalid.setPackage("com.google.example.invalidpackage");
        appIdentity = PendingIntent.getBroadcast(
                this.context,
                0,
                invalid,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    void request(Callback cb) {
        if (callback != null) {
            cb.onResult(null, "BUSY");
            return;
        }

        callback = cb;
        finished = false;
        messageId = "gmsprotect-status-"
                + SEQ.getAndIncrement()
                + "-"
                + SystemClock.elapsedRealtime();

        Handler replyHandler = new Handler(Looper.getMainLooper(), this::handleReply);
        Messenger replyMessenger = new Messenger(replyHandler);

        Intent intent = new Intent(ACTION_SEND).setPackage(GMS);
        intent.putExtra("app", appIdentity);
        intent.putExtra("google.to", LOCAL_STATUS);
        intent.putExtra("google.message_id", messageId);
        intent.putExtra("google.ttl", "0");
        intent.putExtra("google.messenger", replyMessenger);

        try {
            context.sendOrderedBroadcast(
                    intent,
                    GTALK_PERMISSION,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            // The Messenger response below carries local/status.
                        }
                    },
                    main,
                    Activity.RESULT_OK,
                    null,
                    null
            );
            main.postDelayed(() -> finish(null, "NO_RESPONSE"), TIMEOUT_MS);
        } catch (Throwable t) {
            finish(null, t.getClass().getSimpleName());
        }
    }

    private boolean handleReply(Message msg) {
        if (!(msg.obj instanceof Intent)) return true;

        Intent response = (Intent) msg.obj;
        String replyTo = response.getStringExtra("In-Reply-To");
        if (replyTo != null && messageId != null && !messageId.equals(replyTo)) {
            return true;
        }

        finish(response.getStringExtra("status"), null);
        return true;
    }

    private void finish(String status, String error) {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);

        Callback cb = callback;
        callback = null;
        if (cb != null) cb.onResult(status, error);
    }
}
