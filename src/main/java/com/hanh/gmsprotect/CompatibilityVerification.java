package com.hanh.gmsprotect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Stores only evidence that has actually happened on this device/ROM build.
 *
 * A successful schedule is not treated as proof that HyperOS can wake the app.
 * The stronger flags below are written only from MilletWatchJobService after
 * system_server has delivered the MILLET content trigger, and after a real
 * repair when GMS was missing.
 */
public final class CompatibilityVerification {
    private static final String PREFS = "compatibility_verification";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_WATCHER_TRIGGERED = "watcher_triggered";
    private static final String KEY_WATCHER_LAST_MS = "watcher_last_ms";
    private static final String KEY_AUTO_REPAIR_VERIFIED = "auto_repair_verified";
    private static final String KEY_AUTO_REPAIR_LAST_MS = "auto_repair_last_ms";
    private static final String KEY_HYPEROS_PROTECTION_SENT = "hyperos_protection_sent";
    private static final String KEY_HYPEROS_PROTECTION_LAST_MS = "hyperos_protection_last_ms";

    private CompatibilityVerification() {}

    public static void markWatcherTriggered(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = editorForCurrentBuild(prefs);
        editor.putBoolean(KEY_WATCHER_TRIGGERED, true)
                .putLong(KEY_WATCHER_LAST_MS, System.currentTimeMillis())
                .apply();
    }

    public static void markAutoRepairVerified(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = editorForCurrentBuild(prefs);
        long now = System.currentTimeMillis();
        editor.putBoolean(KEY_WATCHER_TRIGGERED, true)
                .putLong(KEY_WATCHER_LAST_MS, now)
                .putBoolean(KEY_AUTO_REPAIR_VERIFIED, true)
                .putLong(KEY_AUTO_REPAIR_LAST_MS, now)
                .apply();
    }

    public static void markHyperOsProtectionRequestSent(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = editorForCurrentBuild(prefs);
        editor.putBoolean(KEY_HYPEROS_PROTECTION_SENT, true)
                .putLong(KEY_HYPEROS_PROTECTION_LAST_MS, System.currentTimeMillis())
                .apply();
    }

    public static boolean hyperOsProtectionRequestSent(Context context) {
        SharedPreferences prefs = prefs(context);
        return isCurrentBuild(prefs) && prefs.getBoolean(KEY_HYPEROS_PROTECTION_SENT, false);
    }

    public static long hyperOsProtectionRequestLastTime(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!isCurrentBuild(prefs)) return 0L;
        return prefs.getLong(KEY_HYPEROS_PROTECTION_LAST_MS, 0L);
    }

    public static boolean watcherVerified(Context context) {
        SharedPreferences prefs = prefs(context);
        return isCurrentBuild(prefs) && prefs.getBoolean(KEY_WATCHER_TRIGGERED, false);
    }

    public static boolean autoRepairVerified(Context context) {
        SharedPreferences prefs = prefs(context);
        return isCurrentBuild(prefs) && prefs.getBoolean(KEY_AUTO_REPAIR_VERIFIED, false);
    }

    public static long lastVerifiedTime(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!isCurrentBuild(prefs)) return 0L;
        return Math.max(
                prefs.getLong(KEY_WATCHER_LAST_MS, 0L),
                prefs.getLong(KEY_AUTO_REPAIR_LAST_MS, 0L));
    }

    public static boolean needsReverificationAfterSystemUpdate(Context context) {
        SharedPreferences prefs = prefs(context);
        String stored = prefs.getString(KEY_FINGERPRINT, "");
        return !stored.isEmpty() && !stored.equals(currentFingerprint());
    }

    private static SharedPreferences.Editor editorForCurrentBuild(SharedPreferences prefs) {
        String current = currentFingerprint();
        String stored = prefs.getString(KEY_FINGERPRINT, "");
        SharedPreferences.Editor editor = prefs.edit();
        if (!current.equals(stored)) {
            editor.remove(KEY_HYPEROS_PROTECTION_SENT)
                    .remove(KEY_HYPEROS_PROTECTION_LAST_MS)
                    .remove(KEY_WATCHER_TRIGGERED)
                    .remove(KEY_WATCHER_LAST_MS)
                    .remove(KEY_AUTO_REPAIR_VERIFIED)
                    .remove(KEY_AUTO_REPAIR_LAST_MS)
                    .putString(KEY_FINGERPRINT, current);
        }
        return editor;
    }

    private static boolean isCurrentBuild(SharedPreferences prefs) {
        return currentFingerprint().equals(prefs.getString(KEY_FINGERPRINT, ""));
    }

    private static String currentFingerprint() {
        String fingerprint = Build.FINGERPRINT;
        return fingerprint == null ? "" : fingerprint;
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                app = app.createDeviceProtectedStorageContext();
            } catch (Throwable ignored) {
                // Fall back to credential-protected storage below.
            }
        }
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
