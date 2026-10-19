package com.hanh.gmsprotect;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Event-only, bounded, on-device journal. No worker, timer, receiver, network,
 * database or system-setting access. Logging failures MUST NOT affect protection.
 *
 * v1 had a 16K truncated text history. v2 migrates meaningful entries once,
 * retaining the old preference unchanged in case a diagnostic needs it later.
 */
public final class GameWatchJournal {
    private static final String TAG = "GAME_WATCH";
    private static final String PREFS = "protect_event_journal_v2";
    private static final String HISTORY = "events";
    private static final String INIT = "initialized";
    private static final String LEGACY_PREFS = "game_watch_full_journal_v1";
    private static final String LEGACY_HISTORY = "history";
    private static final int LIMIT = 200;
    private static final int MAX_DETAIL = 360;
    private static final long MERGE_WINDOW_MS = 5L * 60_000L;
    private static final Pattern OLD_LINE = Pattern.compile(
            "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[([^]]+)\\]\\s*(.*)$");

    private GameWatchJournal() { }

    public static final class Event {
        public final long timeMs;
        public final long lastMs;
        public final int count;
        public final String kind; // success / info / change / warning / error
        public final String code;
        public final String key;
        public final String detail; // technical payload, never a claimed verified state

        private Event(long timeMs, long lastMs, int count, String kind,
                      String code, String key, String detail) {
            this.timeMs = timeMs;
            this.lastMs = lastMs;
            this.count = count;
            this.kind = kind;
            this.code = code;
            this.key = key;
            this.detail = detail;
        }

        private JSONObject json() throws org.json.JSONException {
            JSONObject result = new JSONObject();
            result.put("t", timeMs);
            result.put("last", lastMs);
            result.put("n", count);
            result.put("kind", kind);
            result.put("code", code);
            result.put("key", key);
            result.put("detail", detail);
            return result;
        }

        private static Event parse(JSONObject o) {
            long time = o.optLong("t", 0L);
            return new Event(time, o.optLong("last", time),
                    Math.max(1, o.optInt("n", 1)), o.optString("kind", "info"),
                    o.optString("code", "other"), o.optString("key", ""),
                    o.optString("detail", ""));
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Existing call sites remain API compatible; routine checks are never saved. */
    public static void add(Context c, String category, String message) {
        // Preserve the original Logcat diagnostics independently from user-facing history.
        Log.i(TAG, "[" + category + "] " + message);
        String raw = message == null ? "" : message;
        String cat = category == null ? "" : category;
        if ("CHECK".equals(cat) || "BASELINE".equals(cat) || "JOB".equals(cat)
                && !raw.startsWith("END rearm=false")) return;
        if ("COMPAT".equals(cat)) {
            if (raw.startsWith("real URI dual-entry repair verified"))
                event(c, "watch_verified", "GameWatchEvidence: " + raw);
            else if (raw.contains("failed")) event(c, "game_error", "COMPAT " + raw);
            return; // URI callback alone is not proof of repair.
        }
        if ("CHANGE".equals(cat)) {
            if (!raw.startsWith("from=SELF_WRITE")) event(c, "game_changed", raw);
            return;
        }
        if ("REPAIR".equals(cat)) {
            if (raw.contains("concurrent_settings_update")) event(c, "game_deferred", raw);
            else if (raw.contains("verified=true")) event(c, "game_repaired", raw);
            // Failed write paths already invoke recordError(), so avoid a double entry.
            return;
        }
        if ("WATCH".equals(cat)) {
            if (raw.startsWith("schedule=true") && !raw.contains("reason=JOB_REARM"))
                event(c, "watch_registered", raw);
            else if (raw.contains("error") || raw.startsWith("schedule=false"))
                event(c, "watch_error", raw);
            return;
        }
        if ("ERROR".equals(cat)) { event(c, "game_error", raw); return; }
        if ("ADV".equals(cat)) { event(c, "app_error", raw); return; }
        if ("JOB".equals(cat)) { event(c, "watch_error", raw); }
    }

    /** Called ONLY at existing state transitions, not by an automatic polling loop. */
    public static synchronized void event(Context c, String code, String technicalDetail) {
        if (c == null || code == null || code.isEmpty()) return;
        try {
            String detail = sanitize(technicalDetail);
            String kind = kindFor(code);
            String key = fingerprint(code, detail);
            long now = System.currentTimeMillis();
            SharedPreferences p = prefs(c);
            ArrayList<Event> all = read(p);
            if (!p.getBoolean(INIT, false)) migrateLegacy(c, all);
            boolean merged = false;
            for (int i = 0; i < all.size(); i++) {
                Event e = all.get(i);
                if (key.equals(e.key) && code.equals(e.code)
                        && now >= e.lastMs && now - e.lastMs <= MERGE_WINDOW_MS) {
                    all.remove(i);
                    all.add(0, new Event(e.timeMs, now, e.count + 1,
                            kind, code, key, detail));
                    merged = true;
                    break;
                }
            }
            if (!merged) all.add(0, new Event(now, now, 1, kind, code, key, detail));
            persist(p, all);
        } catch (RuntimeException failure) {
            // Journaling must never break a Xiaomi settings write or a JobService.
            Log.w(TAG, "Optional event storage unavailable", failure);
        }
    }

    /** Returns an immutable-snapshot-like copy; the caller cannot edit stored data. */
    public static synchronized List<Event> entries(Context c) {
        ArrayList<Event> result = new ArrayList<>();
        try {
            SharedPreferences p = prefs(c);
            result = read(p);
            if (!p.getBoolean(INIT, false)) {
                migrateLegacy(c, result);
                persist(p, result);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to read optional journal", failure);
        }
        return result;
    }

    /** No locale-specific strings are persisted. Raw export is suitable for bug reports. */
    public static String technicalHistory(Context c) {
        StringBuilder out = new StringBuilder();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        for (Event e : entries(c)) {
            out.append(iso.format(new Date(e.lastMs)))
                    .append(" [").append(e.kind).append("] ")
                    .append(e.code);
            if (e.count > 1) out.append(" count=").append(e.count)
                    .append(" first=").append(iso.format(new Date(e.timeMs)));
            out.append("\n");
            if (!e.detail.isEmpty()) out.append("  ").append(e.detail).append("\n");
        }
        return out.toString();
    }

    /** Backward-compatible helper for any older UI/diagnostic call sites. */
    public static String history(Context c) { return technicalHistory(c); }

    private static String kindFor(String code) {
        if (code.endsWith("_error")) return "error";
        if ("game_deferred".equals(code)) return "warning";
        if (code.endsWith("_repaired") || "watch_verified".equals(code)) return "success";
        if (code.startsWith("app_") || "game_changed".equals(code)) return "change";
        return "info";
    }

    private static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL) + "…";
    }

    private static String fingerprint(String code, String detail) {
        // Most protection events are keyed by effect, not volatile pid/reason.
        // User-app events must remain distinct per package.
        if (code.startsWith("app_added") || code.startsWith("app_enabled")
                || code.startsWith("app_disabled") || code.startsWith("app_removed")
                || "mipush_requested_app".equals(code)) return code + ":" + detail;
        if (code.endsWith("_error")) {
            // Do not merge unrelated failures merely because both say ERROR.
            String signature = detail.replaceAll("(?i)(reason|cause|pid)=[^ ]+", "$1=*")
                    .replaceAll("\\b[0-9]{4,}\\b", "#");
            return code + ":" + (signature.length() > 100 ? signature.substring(0, 100) : signature);
        }
        return code;
    }

    private static ArrayList<Event> read(SharedPreferences p) {
        ArrayList<Event> list = new ArrayList<>();
        String json = p.getString(HISTORY, "[]");
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && list.size() < LIMIT; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optLong("t", 0L) > 0) list.add(Event.parse(o));
            }
        } catch (org.json.JSONException invalid) {
            // Preserve the unreadable original for diagnostic recovery.
            if (!p.contains("corrupt_backup"))
                p.edit().putString("corrupt_backup", json).apply();
            Log.w(TAG, "Unreadable journal JSON; original backed up", invalid);
        }
        return list;
    }

    private static void persist(SharedPreferences p, List<Event> all) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < all.size() && i < LIMIT; i++) {
            try { a.put(all.get(i).json()); }
            catch (org.json.JSONException invalid) { Log.w(TAG, "Skipping invalid event", invalid); }
        }
        p.edit().putString(HISTORY, a.toString()).putBoolean(INIT, true).apply();
    }

    private static void migrateLegacy(Context c, ArrayList<Event> into) {
        String old = c.getApplicationContext().getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(LEGACY_HISTORY, "");
        if (old == null || old.isEmpty()) return;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        int year = Calendar.getInstance().get(Calendar.YEAR);
        long now = System.currentTimeMillis();
        String[] lines = old.split("\r?\n");
        for (String line : lines) {
            // Legacy order: newest -> oldest; preserve that order.
            if (into.size() >= LIMIT) break;
            Matcher match = OLD_LINE.matcher(line);
            if (!match.matches()) continue;
            String cat = match.group(2);
            String msg = match.group(3);
            String code = legacyCode(cat, msg);
            if (code == null) continue;
            long when = now;
            try {
                when = format.parse(year + "-" + match.group(1)).getTime();
                if (when > now + 12L * 3600_000L) {
                    when = format.parse((year - 1) + "-" + match.group(1)).getTime();
                }
            } catch (ParseException ignored) { }
            String detail = sanitize(msg);
            into.add(new Event(when, when, 1, kindFor(code), code,
                    fingerprint(code, detail), detail));
        }
    }

    private static String legacyCode(String cat, String msg) {
        if ("ERROR".equals(cat)) return "game_error";
        if ("ADV".equals(cat)) return "app_error";
        if ("CHANGE".equals(cat) && !msg.startsWith("from=SELF_WRITE")) return "game_changed";
        if ("REPAIR".equals(cat)) return msg.contains("concurrent_settings_update")
                ? "game_deferred" : msg.contains("verified=true") ? "game_repaired" : "game_error";
        if ("COMPAT".equals(cat) && msg.contains("dual-entry repair verified")) return "watch_verified";
        if ("WATCH".equals(cat)) {
            if (msg.startsWith("schedule=true") && !msg.contains("reason=JOB_REARM"))
                return "watch_registered";
            if (msg.contains("error") || msg.startsWith("schedule=false")) return "watch_error";
        }
        return null;
    }
}
