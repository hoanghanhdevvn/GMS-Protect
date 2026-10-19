package com.hanh.gmsprotect;

import android.app.AppOpsManager;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * Best-effort reader for Xiaomi/HyperOS setup state.
 *
 * OP_AUTO_START (10008) is Xiaomi's AppOps entry for Autostart on this ROM
 * family. We only read it; the user still changes the value in SecurityCenter.
 * If a future HyperOS build blocks this hidden read, callers receive UNKNOWN
 * and the UI falls back to a manual confirmation instead of pretending success.
 */
final class XiaomiSetupState {
    private static final int OP_AUTO_START = 10008;

    enum AutostartState {
        ALLOWED,
        DENIED,
        UNKNOWN
    }

    private XiaomiSetupState() {}

    static AutostartState getAutostartState(Context context) {
        Context app = context.getApplicationContext();
        AppOpsManager manager = (AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
        if (manager == null) return AutostartState.UNKNOWN;

        try {
            Method method = AppOpsManager.class.getDeclaredMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            method.setAccessible(true);
            Object value = method.invoke(
                    manager,
                    OP_AUTO_START,
                    app.getApplicationInfo().uid,
                    app.getPackageName()
            );
            if (!(value instanceof Integer)) return AutostartState.UNKNOWN;

            int mode = (Integer) value;
            if (mode == AppOpsManager.MODE_ALLOWED) return AutostartState.ALLOWED;
            return AutostartState.DENIED;
        } catch (Throwable ignored) {
            return AutostartState.UNKNOWN;
        }
    }
}
