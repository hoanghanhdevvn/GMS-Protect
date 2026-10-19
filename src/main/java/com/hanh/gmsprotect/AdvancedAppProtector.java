package com.hanh.gmsprotect;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort maintenance for EXPLICITLY selected third-party packages.
 * Never changes GMS/Play Store/core MILLET behavior; never removes vendor entries.
 * MiPush uses an unacknowledged broadcast: "sent" is NOT "Xiaomi accepted".
 */
public final class AdvancedAppProtector {
    private static final String TAG = "AdvancedApps";
    private AdvancedAppProtector() { }

    public static synchronized void maintain(Context context, String reason) {
        Context app = context.getApplicationContext();
        List<AdvancedAppStore.Entry> rows = AdvancedAppStore.list(app);
        ArrayList<String> gamePackages = new ArrayList<>();
        int requests = 0;
        for (AdvancedAppStore.Entry e : rows) {
            if (!e.enabled) continue;
            if (!isInstalled(app, e.pkg)) {
                Log.i(TAG, "SKIP missing app=" + e.pkg);
                continue;
            }
            // Xiaomi stores entries separated by underscores. Package identifiers
            // containing underscores cannot be represented unambiguously there.
            if (e.pkg.indexOf('_') < 0) gamePackages.add(e.pkg);
            // Same action/payload as GmsProtector, without FCM reconnect or
            // GMS compatibility evidence changes. Broadcast is not an ack.
            try {
                    Intent request = new Intent(GmsProtector.ACTION_REFRESH_MIPUSH);
                    request.putExtra("pkgName", e.pkg);
                    request.putExtra("option", 1);
                    app.sendBroadcast(request);
                    AdvancedAppStore.noteMiPush(app, e.pkg, true, null);
                    requests++;
                } catch (Throwable error) {
                    AdvancedAppStore.noteMiPush(app, e.pkg, false, error.toString());
                    Log.w(TAG, "MiPush request failed pkg=" + e.pkg, error);
            }
        }
        // Always ensure the mandatory core too, even when the user's list is empty.
        // Both paths now use one writer and one verification read.
        if (GameWatchCore.isEnabled(app)) ensureGame(app, gamePackages, reason);
        Log.i(TAG, "MAINTAIN " + reason + " gameTargets=" + gamePackages.size()
                + " mipushRequestsSent=" + requests);
    }

    public static boolean isInstalled(Context c, String pkg) {
        try {
            // Target SDK 22 retains legacy visibility; resolve only explicit user selection.
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) { return false; }
        catch (Throwable error) { Log.w(TAG, "Package check unavailable: " + pkg, error); return false; }
    }

    /** Shared, single-writer Game Allowlist maintenance (core + user apps). */
    static boolean ensureGame(Context c, List<String> targets, String reason) {
        return GameWatchCore.ensurePackages(c, targets, reason);
    }

    public static String gameStatus(Context c, String pkg) {
        if (pkg.indexOf('_') >= 0) return "unsupported";
        if (!GameWatchCore.canWrite(c)) return "permission";
        if (!isInstalled(c, pkg)) return "missing";
        try { return GameWatchCore.contains(GameWatchCore.raw(c), pkg) ? "present" : "absent"; }
        catch (Throwable error) { return "unknown"; }
    }
}
