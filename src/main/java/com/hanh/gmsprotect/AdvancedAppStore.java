package com.hanh.gmsprotect;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** User-managed packages: independent from mandatory GMS/Play Store/MILLET core.
 * v1.0.5-beta4 migration: legacy mipush/game flags are ignored intentionally.
 * Saved enabled=false remains disabled; enabled=true always manages both layers.
 */
public final class AdvancedAppStore {
    private static final String PREFS = "advanced_apps_v1";
    private static final String LIST = "entries_json";
    public static final int MAX_APPS = 50;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+");

    public static final class Entry {
        public final String pkg;
        public final boolean enabled;
        // One master switch. Both MiPush and Game Allowlist are maintained when enabled.
        public Entry(String pkg, boolean enabled) {
            this.pkg = pkg;
            this.enabled = enabled;
        }
    }

    private AdvancedAppStore() { }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean valid(Context c, String pkg) {
        return !TextUtils.isEmpty(pkg) && pkg.length() <= 255 && PACKAGE.matcher(pkg).matches()
                && !pkg.equals(c.getPackageName()) && !pkg.equals(GameWatchCore.GMS)
                && !pkg.equals(MilletNoRestrict.PLAY_STORE_PACKAGE);
    }

    public static synchronized List<Entry> list(Context c) {
        ArrayList<Entry> result = new ArrayList<>();
        String text = prefs(c).getString(LIST, "[]");
        try {
            JSONArray a = new JSONArray(text);
            for (int i = 0; i < a.length() && result.size() < MAX_APPS; i++) {
                JSONObject obj = a.optJSONObject(i);
                if (obj == null) continue;
                String pkg = obj.optString("pkg", "");
                if (!valid(c, pkg)) continue;
                boolean duplicate = false;
                for (Entry e : result) if (e.pkg.equals(pkg)) { duplicate = true; break; }
                if (!duplicate) result.add(new Entry(pkg, obj.optBoolean("enabled", true)));
            }
        } catch (Throwable error) {
            // Do not treat a corrupt file as an instruction to overwrite system lists.
            android.util.Log.e("AdvancedAppStore", "Unable to parse stored package list", error);
        }
        return result;
    }

    public static synchronized boolean save(Context c, Entry changed) {
        if (changed == null || !valid(c, changed.pkg)) return false;
        List<Entry> rows = list(c);
        boolean replaced = false;
        boolean stateChanged = false;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).pkg.equals(changed.pkg)) {
                stateChanged = rows.get(i).enabled != changed.enabled;
                rows.set(i, changed);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (rows.size() >= MAX_APPS) return false;
            rows.add(changed);
        }
        boolean saved = write(c, rows);
        if (saved && (!replaced || stateChanged)) {
            String action = !replaced ? "app_added" : changed.enabled ? "app_enabled" : "app_disabled";
            GameWatchJournal.event(c, action, changed.pkg);
        }
        return saved;
    }

    public static synchronized boolean remove(Context c, String pkg) {
        List<Entry> rows = list(c);
        boolean removed = false;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).pkg.equals(pkg)) { rows.remove(i); removed = true; }
        }
        boolean saved = removed && write(c, rows);
        if (saved) GameWatchJournal.event(c, "app_removed", pkg);
        return saved;
    }

    private static boolean write(Context c, List<Entry> rows) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : rows) {
                JSONObject o = new JSONObject();
                o.put("pkg", e.pkg);
                o.put("enabled", e.enabled);
                a.put(o);
            }
            // commit (not apply) so a reboot/job cannot see half-finished changes.
            return prefs(c).edit().putString(LIST, a.toString()).commit();
        } catch (Throwable error) {
            android.util.Log.e("AdvancedAppStore", "Could not save package list", error);
            return false;
        }
    }

    public static boolean hasEnabledEntries(Context c) {
        for (Entry e : list(c)) if (e.enabled) return true;
        return false;
    }

    public static int activeCount(Context c) {
        int count = 0;
        for (Entry e : list(c)) if (e.enabled) count++;
        return count;
    }

    static void noteMiPush(Context c, String pkg, boolean sent, String error) {
        SharedPreferences.Editor edit = prefs(c).edit();
        edit.putLong("mipush_attempt:" + pkg, System.currentTimeMillis());
        edit.putBoolean("mipush_sent:" + pkg, sent);
        edit.putString("mipush_error:" + pkg, error == null ? "" : error);
        edit.apply();
    }

    public static long lastMiPushAttempt(Context c, String pkg) {
        return prefs(c).getLong("mipush_attempt:" + pkg, 0);
    }

    public static boolean lastMiPushSent(Context c, String pkg) {
        return prefs(c).getBoolean("mipush_sent:" + pkg, false);
    }
}
