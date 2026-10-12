package com.hanh.gmsprotect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Experimental HyperOS GMS-limit bypass probe.
 *
 * MIUI tracks clientUid -> serviceUid for bindService() connections. The
 * Greezer quick-freeze condition checks holdConnectionUids(mTopAppUid) and
 * skips a target UID when the current top app is holding a connection to it.
 *
 * This probe intentionally binds while GMS Protect is foreground and keeps the
 * ServiceConnection alive for the lifetime of this process. It uses real
 * PersistentApiService actions observed on this device from ordinary apps.
 */
public final class GmsBindingProbe {
    private static final String TAG = "GmsBindProbe";
    private static final String GMS = "com.google.android.gms";
    private static final String PERSISTENT_API_SERVICE =
            "com.google.android.gms.chimera.PersistentApiService";
    private static final String CHIMERA_BIND_ACTION =
            "com.google.android.chimera.BoundService.START";

    // First candidate is confirmed in the device dump with com.mbmobile as
    // the third-party client. The others are fallbacks that resolve to the
    // same PersistentApiService on this GMS build.
    private static final String[] TARGET_ACTIONS = new String[] {
            "com.google.android.gms.common.telemetry.service.START",
            "com.google.android.gms.appusage.service.START",
            "com.google.android.gms.pseudonymous.service.START"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static Context appContext;
    private static ServiceConnection activeConnection;
    private static boolean bindRequested;
    private static boolean connected;
    private static int activeCandidate = -1;

    private GmsBindingProbe() {}

    public static void ensureBound(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            appContext = app;
            if (connected || bindRequested) {
                Log.i(TAG, "ensureBound skip connected=" + connected
                        + " requested=" + bindRequested
                        + " candidate=" + candidateName(activeCandidate));
                return;
            }
            bindRequested = true;
        }

        MAIN.post(() -> tryCandidate(0));
    }

    public static boolean isConnected() {
        synchronized (LOCK) {
            return connected;
        }
    }

    public static String state() {
        synchronized (LOCK) {
            return "connected=" + connected
                    + " requested=" + bindRequested
                    + " candidate=" + candidateName(activeCandidate);
        }
    }

    private static void tryCandidate(int index) {
        final Context app;
        synchronized (LOCK) {
            app = appContext;
            if (app == null || connected) return;
            if (index >= TARGET_ACTIONS.length) {
                bindRequested = false;
                activeCandidate = -1;
                Log.w(TAG, "all candidates failed");
                return;
            }
            activeCandidate = index;
        }

        final String targetAction = TARGET_ACTIONS[index];
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (LOCK) {
                    activeConnection = this;
                    connected = true;
                    bindRequested = true;
                    activeCandidate = index;
                }
                Log.i(TAG, "CONNECTED candidate=" + targetAction
                        + " component=" + name
                        + " binder=" + (service != null));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                synchronized (LOCK) {
                    if (activeConnection == this) {
                        connected = false;
                    }
                }
                Log.w(TAG, "DISCONNECTED candidate=" + targetAction
                        + " component=" + name);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                synchronized (LOCK) {
                    if (activeConnection == this) {
                        connected = false;
                        activeConnection = null;
                        bindRequested = false;
                    }
                }
                Log.w(TAG, "BINDING_DIED candidate=" + targetAction
                        + " component=" + name);
                MAIN.postDelayed(() -> ensureBound(app), 1500L);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.w(TAG, "NULL_BINDING candidate=" + targetAction
                        + " component=" + name);
                safeUnbind(app, this);
                synchronized (LOCK) {
                    if (activeConnection == this) activeConnection = null;
                    connected = false;
                }
                MAIN.postDelayed(() -> tryCandidate(index + 1), 250L);
            }
        };

        Intent intent = new Intent(CHIMERA_BIND_ACTION);
        intent.setComponent(new ComponentName(GMS, PERSISTENT_API_SERVICE));
        intent.setData(Uri.parse("chimera-action:" + targetAction));

        boolean accepted = false;
        try {
            // AUTO_CREATE is enough for the MIUI clientUid -> serviceUid
            // connection map used by holdConnectionUids().
            accepted = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService candidate=" + targetAction
                    + " accepted=" + accepted);
        } catch (Throwable t) {
            Log.w(TAG, "bindService exception candidate=" + targetAction
                    + " error=" + t.getClass().getSimpleName()
                    + ":" + String.valueOf(t.getMessage()));
        }

        if (!accepted) {
            MAIN.postDelayed(() -> tryCandidate(index + 1), 250L);
            return;
        }

        synchronized (LOCK) {
            activeConnection = connection;
        }

        // If GMS accepted the bind request but never connects, move on. This is
        // only a probe; a real successful candidate should connect quickly.
        MAIN.postDelayed(() -> {
            boolean stillWaiting;
            synchronized (LOCK) {
                stillWaiting = !connected && activeConnection == connection;
            }
            if (!stillWaiting) return;

            Log.w(TAG, "CONNECT_TIMEOUT candidate=" + targetAction);
            safeUnbind(app, connection);
            synchronized (LOCK) {
                if (activeConnection == connection) activeConnection = null;
            }
            tryCandidate(index + 1);
        }, 3000L);
    }

    private static void safeUnbind(Context context, ServiceConnection connection) {
        try {
            context.unbindService(connection);
        } catch (Throwable ignored) {
        }
    }

    private static String candidateName(int index) {
        if (index < 0 || index >= TARGET_ACTIONS.length) return "none";
        return TARGET_ACTIONS[index];
    }
}
