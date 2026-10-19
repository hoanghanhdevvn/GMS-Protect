package com.hanh.gmsprotect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import java.util.List;
import java.util.ArrayList;

/**
 * Automatic Game Allowlist protection for GMS, this app and enabled managed apps.
 * Never replaces unrelated packages in Xiaomi's underscore-delimited list.
 * Uses an event-triggered job; no persistent background service or polling.
 */
public final class GameWatchCore {
    public static final String KEY = "com.miui.game.allowlist";
    public static final String GMS = "com.google.android.gms";
    private static final String PREFS = "game_watch_full_state_v1";
    private static final String RUNS = "job_runs";
    private static final String URI_EVENTS = "uri_events";
    private static final String WRITES = "repair_writes";
    private static final String VALUE_CHANGES = "value_changes";
    private static final String LAST_VALUE = "last_value";
    private static final String VALUE_KNOWN = "value_known";
    private static final String LAST_JOB = "last_job";
    private static final String LAST_REPAIR = "last_repair";
    private static final String LAST_ERROR = "last_error";

    private GameWatchCore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Always requested on supported Android versions, even if legacy preference was off.
     * Write permissions are evaluated separately; missing permission never appears as PASS.
     */
    public static boolean isEnabled(Context c) { return Build.VERSION.SDK_INT >= 24; }
    public static long count(Context c, String key) { return prefs(c).getLong(key, 0); }
    public static long jobCount(Context c) { return count(c, RUNS); }
    public static long uriCount(Context c) { return count(c, URI_EVENTS); }
    public static long repairCount(Context c) { return count(c, WRITES); }
    public static long changesCount(Context c) { return count(c, VALUE_CHANGES); }
    public static long lastJob(Context c) { return count(c, LAST_JOB); }
    public static long lastRepair(Context c) { return count(c, LAST_REPAIR); }
    public static String lastError(Context c) { return prefs(c).getString(LAST_ERROR, ""); }

    public static boolean canWrite(Context c) {
        try {
            return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(c);
        } catch (RuntimeException ex) { return false; }
    }

    /** Read errors are not conflated with a truly empty list. */
    public static String raw(Context c) {
        return Settings.System.getString(c.getContentResolver(), KEY);
    }

    public static boolean contains(String raw, String pkg) {
        if (TextUtils.isEmpty(raw)) return false;
        for (String part : raw.split("_", -1)) {
            if (pkg.equals(part.trim())) return true;
        }
        return false;
    }

    public static boolean containsGms(Context c) { return contains(raw(c), GMS); }
    public static boolean containsSelf(Context c) { return contains(raw(c), c.getPackageName()); }

    private static String append(String raw, String pkg) {
        if (contains(raw, pkg)) return raw;
        if (TextUtils.isEmpty(raw)) return pkg;
        return raw + (raw.endsWith("_") ? "" : "_") + pkg;
    }

    public static synchronized void observe(Context c, String from) {
        try {
            String value = raw(c);
            SharedPreferences p = prefs(c);
            boolean known = p.getBoolean(VALUE_KNOWN, false);
            String previous = p.getString(LAST_VALUE, null);
            boolean changed = known && !(previous == null ? value == null : previous.equals(value));
            SharedPreferences.Editor e = p.edit().putBoolean(VALUE_KNOWN, true)
                    .putString(LAST_VALUE, value);
            if (changed) e.putLong(VALUE_CHANGES, p.getLong(VALUE_CHANGES, 0) + 1);
            e.commit();
            if (changed) {
                GameWatchJournal.add(c, "CHANGE", "from=" + from + " old=" + brief(previous)
                        + " new=" + brief(value));
            } else if (!known) {
                GameWatchJournal.add(c, "BASELINE", "from=" + from + " value=" + brief(value));
            }
        } catch (Throwable ex) {
            recordError(c, "OBSERVE: " + ex);
        }
    }

    private static String brief(String raw) {
        if (raw == null) return "<null>";
        return raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
    }

    private static synchronized void recordError(Context c, String message) {
        prefs(c).edit().putString(LAST_ERROR, message).commit();
        GameWatchJournal.add(c, "ERROR", message);
    }

    public static synchronized void onJobStart(Context c, boolean uriTriggered) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit().putLong(RUNS, p.getLong(RUNS, 0) + 1)
                .putLong(LAST_JOB, System.currentTimeMillis());
        if (uriTriggered) e.putLong(URI_EVENTS, p.getLong(URI_EVENTS, 0) + 1);
        e.commit();
    }

    /** Entry point for automatic core and enabled user-app protection. */
    public static boolean ensureTargets(Context c, String reason) {
        ArrayList<String> managed = new ArrayList<>();
        for (AdvancedAppStore.Entry e : AdvancedAppStore.list(c)) {
            if (e.enabled && e.pkg.indexOf('_') < 0 && AdvancedAppProtector.isInstalled(c, e.pkg)) {
                managed.add(e.pkg);
            }
        }
        return ensurePackages(c, managed, reason);
    }

    /**
     * Single synchronized read/append/re-read shared by core and advanced paths.
     * A Xiaomi concurrent writer can still race after the second read; the URI
     * watcher repairs later changes. No unrelated token is deleted.
     */
    public static synchronized boolean ensurePackages(Context c, List<String> extra, String reason) {
        if (!isEnabled(c)) return false;
        if (!canWrite(c)) {
            recordError(c, "WRITE_SETTINGS missing; cause=" + reason);
            return false;
        }
        try {
            ArrayList<String> wanted = new ArrayList<>();
            wanted.add(GMS);
            wanted.add(c.getPackageName());
            if (extra != null) for (String pkg : extra) {
                if (AdvancedAppStore.valid(c, pkg) && pkg.indexOf('_') < 0
                        && !wanted.contains(pkg)) wanted.add(pkg);
            }
            String previous = raw(c);
            boolean hadGms = contains(previous, GMS);
            boolean hadSelf = contains(previous, c.getPackageName());
            String candidate = previous;
            int missing = 0;
            for (String pkg : wanted) {
                if (!contains(candidate, pkg)) {
                    candidate = append(candidate, pkg);
                    missing++;
                }
            }
            if (missing == 0) {
                // A prior permission/write error is obsolete once every target is verified.
                if (!lastError(c).isEmpty()) prefs(c).edit().putString(LAST_ERROR, "").commit();
                GameWatchJournal.add(c, "CHECK", reason + " already_present targets=" + wanted.size());
                return true;
            }
            String justBefore = raw(c);
            if (!(previous == null ? justBefore == null : previous.equals(justBefore))) {
                GameWatchJournal.add(c, "REPAIR", reason + " concurrent_settings_update, defer");
                return false;
            }
            boolean put = Settings.System.putString(c.getContentResolver(), KEY, candidate);
            String actual = raw(c);
            boolean verified = put;
            for (String pkg : wanted) verified &= contains(actual, pkg);
            GameWatchJournal.add(c, "REPAIR", reason + " missingGMS=" + !hadGms
                    + " missingAPP=" + !hadSelf + " missingTotal=" + missing
                    + " targets=" + wanted.size() + " put=" + put + " verified=" + verified);
            observe(c, "SELF_WRITE");
            if (verified) {
                SharedPreferences p = prefs(c);
                p.edit().putLong(WRITES, p.getLong(WRITES, 0) + 1)
                        .putLong(LAST_REPAIR, System.currentTimeMillis())
                        .putString(LAST_ERROR, "").commit();
            } else recordError(c, "Repair unverified; reason=" + reason);
            return verified;
        } catch (Throwable ex) {
            recordError(c, "REPAIR " + reason + ": " + ex);
            return false;
        }
    }
}
