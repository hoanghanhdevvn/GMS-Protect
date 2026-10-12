package com.hanh.gmsprotect;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/** Public Android evidence only; it cannot read Xiaomi PowerKeeper bgControl. */
final class BatterySetupState {
    private static final String TAG = "BatterySetupState";
    private BatterySetupState() {}

    static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "isIgnoringBatteryOptimizations unavailable", t);
            return false;
        }
    }

    static boolean isBackgroundRestricted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.isBackgroundRestricted();
        } catch (Throwable t) {
            Log.w(TAG, "isBackgroundRestricted unavailable", t);
            return false;
        }
    }
}
