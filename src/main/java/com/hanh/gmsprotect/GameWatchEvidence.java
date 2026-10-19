package com.hanh.gmsprotect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

/**
 * Evidence gathered from actual GAME_WATCH callbacks, never by a synthetic UI test.
 * PASS denotes observed scheduling/repair behavior, not guaranteed freeze immunity.
 * All ROM-sensitive evidence is invalidated when the system fingerprint changes.
 */
public final class GameWatchEvidence {
    private static final String PREFS = "game_watch_compat_evidence_v1";
    private static final String ROM = "rom_fingerprint";
    private static final String UI_VISIBLE = "ui_visible";
    private static final String UI_PID = "last_ui_pid";
    private static final String URI = "real_uri";
    private static final String BACKGROUND = "background_uri";
    private static final String NEW_PID = "new_pid_uri";
    private static final String DUAL_REPAIR = "dual_repair";
    private static final String BOOT = "boot_rearmed";
    private static final String LAST_URI = "last_uri_ms";
    private static final String LAST_REPAIR = "last_repair_ms";
    private static final String LAST_WAKE = "last_background_ms";
    private static final String LAST_BOOT = "last_boot_ms";
    private static final String EVENT_COUNT = "uri_count";

    private GameWatchEvidence() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fingerprint() {
        return Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
    }

    private static boolean current(SharedPreferences p) {
        return fingerprint().equals(p.getString(ROM, ""));
    }

    private static SharedPreferences.Editor freshEditor(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        if (!current(p)) {
            e.clear();
            e.putString(ROM, fingerprint());
        }
        return e;
    }

    /** Foreground state is best-effort; PID is the previously seen UI process. */
    public static synchronized void noteUi(Context c, boolean visible) {
        SharedPreferences p = prefs(c);
        freshEditor(p).putBoolean(UI_VISIBLE, visible)
                .putInt(UI_PID, Process.myPid()).commit();
    }

    /** Invoked only by the system-delivered JobService with a real matching URI. */
    public static synchronized void noteUriJob(Context c, boolean realUri) {
        if (!realUri || !GameWatchCore.isEnabled(c)) return;
        SharedPreferences p = prefs(c);
        boolean sameRom = current(p);
        boolean wasBackground = sameRom && !p.getBoolean(UI_VISIBLE, true)
                && p.getInt(UI_PID, 0) > 0;
        int earlierPid = sameRom ? p.getInt(UI_PID, 0) : 0;
        boolean newPid = wasBackground && earlierPid != Process.myPid();
        SharedPreferences.Editor e = freshEditor(p)
                .putBoolean(URI, true)
                .putLong(LAST_URI, System.currentTimeMillis())
                .putLong(EVENT_COUNT, (sameRom ? p.getLong(EVENT_COUNT, 0) : 0) + 1);
        if (wasBackground) {
            e.putBoolean(BACKGROUND, true).putLong(LAST_WAKE, System.currentTimeMillis());
        }
        if (newPid) e.putBoolean(NEW_PID, true);
        e.commit();
        GameWatchJournal.add(c, "COMPAT", "real URI callback; uiBackground=" + wasBackground
                + " previousUiPid=" + earlierPid + " jobPid=" + Process.myPid()
                + " newPid=" + newPid);
    }

    /** Marks ONLY a genuine URI-triggered missing -> restored dual entry transition. */
    public static synchronized void noteRepair(Context c, boolean realUri,
                                                boolean missingBefore, boolean written,
                                                boolean bothPresentAfter) {
        if (!GameWatchCore.isEnabled(c) || !realUri || !missingBefore
                || !written || !bothPresentAfter) return;
        SharedPreferences p = prefs(c);
        freshEditor(p).putBoolean(DUAL_REPAIR, true)
                .putLong(LAST_REPAIR, System.currentTimeMillis()).commit();
        GameWatchJournal.add(c, "COMPAT", "real URI dual-entry repair verified");
    }

    /** A reboot event (not package-update) re-registered the job and checked both entries. */
    public static synchronized void noteBoot(Context c, boolean successful) {
        if (!successful || !GameWatchCore.isEnabled(c)) return;
        SharedPreferences p = prefs(c);
        freshEditor(p).putBoolean(BOOT, true)
                .putLong(LAST_BOOT, System.currentTimeMillis()).commit();
    }

    public static boolean uriVerified(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) && p.getBoolean(URI, false);
    }
    public static boolean backgroundVerified(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) && p.getBoolean(BACKGROUND, false);
    }
    public static boolean newPidVerified(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) && p.getBoolean(NEW_PID, false);
    }
    public static boolean repairVerified(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) && p.getBoolean(DUAL_REPAIR, false);
    }
    public static boolean bootVerified(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) && p.getBoolean(BOOT, false);
    }
    public static long lastUri(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) ? p.getLong(LAST_URI, 0L) : 0L;
    }
    public static long lastRepair(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) ? p.getLong(LAST_REPAIR, 0L) : 0L;
    }
    public static long realUriCount(Context c) {
        SharedPreferences p = prefs(c);
        return current(p) ? p.getLong(EVENT_COUNT, 0L) : 0L;
    }
    public static boolean needsReverification(Context c) {
        String old = prefs(c).getString(ROM, "");
        return !old.isEmpty() && !fingerprint().equals(old);
    }
}
