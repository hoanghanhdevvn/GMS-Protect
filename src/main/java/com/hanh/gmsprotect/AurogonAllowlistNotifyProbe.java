package com.hanh.gmsprotect;

import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/**
 * HyperOS Aurogon runtime allowlist refresh helper.
 *
 * It does NOT write Settings.Secure. It only notifies the existing
 * Settings.Secure URI so the system_server ContentObserver re-reads
 * immobulus_mode_switch_restrict and rebuilds the runtime mAllowList.
 */
public final class AurogonAllowlistNotifyProbe {
    private static final String TAG = "AurogonAllowProbe";
    private static final String KEY = "immobulus_mode_switch_restrict";

    private AurogonAllowlistNotifyProbe() {}

    public static boolean notifyReload(Context context) {
        return notifyReload(context, "manual");
    }

    public static boolean notifyReload(Context context, String reason) {
        Context app = context.getApplicationContext();
        Uri uri = Settings.Secure.getUriFor(KEY);

        try {
            app.getContentResolver().notifyChange(uri, null);
            Log.i(TAG, "notifyChange SENT reason=" + reason + " uri=" + uri);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "notifyChange FAILED reason=" + reason + " error="
                    + t.getClass().getSimpleName()
                    + ":" + String.valueOf(t.getMessage()));
            return false;
        }
    }
}
