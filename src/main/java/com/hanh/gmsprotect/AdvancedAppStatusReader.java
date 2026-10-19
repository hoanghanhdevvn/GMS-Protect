package com.hanh.gmsprotect;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.PowerManager;

import java.lang.reflect.Method;

/**
 * Read-only, fail-closed indicators for the selected app list and detail page.
 * UNKNOWN must never be shown as permission granted, especially for Xiaomi OEM
 * battery mode and restricted MIUI AppOps reads. This class never changes settings,
 * grants permissions, or runs maintenance: the existing JobServices own that work.
 */
final class AdvancedAppStatusReader {
    static final int UNKNOWN = 0;
    static final int VERIFIED = 1;
    static final int ATTENTION = 2;
    static final int OFF = 3;
    static final int SENT_UNVERIFIED = 4;

    static final class Status {
        final int kind;
        final int message;
        Status(int kind, int message) { this.kind = kind; this.message = message; }
    }

    static final class Snapshot {
        final boolean installed;
        final Status autostart, doze, battery, millet, game, mipush;
        Snapshot(boolean installed, Status a, Status d, Status b, Status ml, Status g, Status m) {
            this.installed = installed;
            autostart = a; doze = d; battery = b; millet = ml; game = g; mipush = m;
        }
    }

    private AdvancedAppStatusReader() { }

    static Snapshot read(Context context, AdvancedAppStore.Entry entry,
                         String gameRaw, boolean gameReadOk, String milletRaw, boolean milletReadOk) {
        boolean installed = AdvancedAppProtector.isInstalled(context, entry.pkg);
        Status autostart = installed ? autostart(context, entry.pkg) : unknown();
        Status doze = installed ? doze(context, entry.pkg) : unknown();
        // Xiaomi's selected Battery Saver/No restrictions mode has no verified
        // unprivileged read API on the tested ROM. Never infer it from Android Doze.
        Status battery = unknownBattery();
        Status millet = installed ? millet(entry.pkg, milletRaw, milletReadOk) : unknown();
        Status game = game(context, entry, installed, gameRaw, gameReadOk);
        Status mipush = mipush(context, entry, installed);
        return new Snapshot(installed, autostart, doze, battery, millet, game, mipush);
    }

    private static Status unknown() { return new Status(UNKNOWN, R.string.adv_unknown); }
    private static Status unknownBattery() {
        return new Status(UNKNOWN, R.string.adv_xiaomi_battery_unverified);
    }

    /** Xiaomi-private operation 10008; non-allow or blocked reads aren't assumed OK. */
    private static Status autostart(Context c, String pkg) {
        try {
            ApplicationInfo info = c.getPackageManager().getApplicationInfo(pkg, 0);
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return unknown();
            Method method = AppOpsManager.class.getDeclaredMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            method.setAccessible(true);
            Object result = method.invoke(ops, 10008, info.uid, pkg);
            if (!(result instanceof Integer)) return unknown();
            int mode = (Integer) result;
            if (mode == AppOpsManager.MODE_ALLOWED)
                return new Status(VERIFIED, R.string.adv_auto_allowed);
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED)
                return new Status(ATTENTION, R.string.adv_denied);
        } catch (Throwable unavailable) {
            // OEM reflection/access restriction is UNKNOWN, never a negative result.
        }
        return unknown();
    }

    /**
     * MILLET_NO_RESTRICT_APP is Xiaomi's separate no-freeze list, not the
     * SecurityCenter battery mode shown in PowerDetailActivity. A verified
     * membership is green; it NEVER claims 'No restrictions' was selected.
     * Settings read errors/unsupported keys stay UNKNOWN rather than red.
     */
    private static Status millet(String pkg, String raw, boolean readable) {
        if (!readable || raw == null) return new Status(UNKNOWN, R.string.adv_millet_unknown);
        // Exact token matching only: substring matching could mark wrong packages.
        for (String token : raw.split("[,;\n\t]")) {
            if (pkg.equals(token.trim())) return new Status(VERIFIED, R.string.adv_millet_present);
        }
        return new Status(ATTENTION, R.string.adv_millet_absent);
    }

    /** Android's public Doze exemption, explicitly NOT Xiaomi battery mode. */
    private static Status doze(Context c, String pkg) {
        if (Build.VERSION.SDK_INT < 23) return unknown();
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return unknown();
            return pm.isIgnoringBatteryOptimizations(pkg)
                    ? new Status(VERIFIED, R.string.adv_doze_exempt)
                    : new Status(ATTENTION, R.string.adv_not_exempt);
        } catch (Throwable unavailable) { return unknown(); }
    }

    private static Status game(Context c, AdvancedAppStore.Entry entry, boolean installed,
                               String raw, boolean readable) {
        if (!entry.enabled) return new Status(OFF, R.string.adv_off);
        if (!installed) return new Status(ATTENTION, R.string.adv_app_missing);
        if (entry.pkg.indexOf('_') >= 0)
            return new Status(ATTENTION, R.string.adv_game_unsupported);
        if (!readable) return unknown();
        // Read state independently of WRITE_SETTINGS permission: existing entries
        // may be verified even when permission to repair has been revoked.
        if (GameWatchCore.contains(raw, entry.pkg))
            return new Status(VERIFIED, R.string.adv_game_present);
        if (!GameWatchCore.canWrite(c))
            return new Status(ATTENTION, R.string.adv_game_permission);
        return new Status(ATTENTION, R.string.adv_game_absent);
    }

    private static Status mipush(Context c, AdvancedAppStore.Entry entry, boolean installed) {
        if (!entry.enabled) return new Status(OFF, R.string.adv_off);
        if (!installed) return new Status(ATTENTION, R.string.adv_app_missing);
        if (AdvancedAppStore.lastMiPushAttempt(c, entry.pkg) == 0)
            return new Status(UNKNOWN, R.string.adv_mipush_waiting);
        // MiPush has no acknowledgement API here: sent != verified.
        // Preserve distinct evidence: UI may count the *send step* as completed,
        // but must not claim Xiaomi acknowledged or applied the request.
        return AdvancedAppStore.lastMiPushSent(c, entry.pkg)
                ? new Status(SENT_UNVERIFIED, R.string.adv_mipush_sent)
                : new Status(ATTENTION, R.string.adv_mipush_failed);
    }
}
