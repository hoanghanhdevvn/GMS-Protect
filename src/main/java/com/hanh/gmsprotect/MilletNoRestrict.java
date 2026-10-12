package com.hanh.gmsprotect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedHashSet;

/**
 * Best-effort HyperOS no-restrict helper.
 *
 * This uses the user-grantable WRITE_SETTINGS special access. It never replaces
 * the existing list blindly: current packages are read, normalized, preserved,
 * and GMS, Google Play Store, and GMS Protect itself are added idempotently. Every write is
 * verified by reading the setting back. Detailed logs are kept intentionally
 * small so failures on HyperOS can be diagnosed without guessing.
 */
public final class MilletNoRestrict {
    private static final String TAG = "MilletNoRestrict";

    public static final String KEY = "MILLET_NO_RESTRICT_APP";
    public static final String GMS_PACKAGE = GmsProtector.GMS_PACKAGE;
    public static final String PLAY_STORE_PACKAGE = "com.android.vending";

    private static final String PREFS = "millet_no_restrict_state";
    private static final String PREF_LAST_GOOD = "last_good_" + KEY;

    public enum Result {
        ALREADY_PRESENT,
        ADDED,
        NO_PERMISSION,
        VERIFY_FAILED,
        FAILED
    }

    private MilletNoRestrict() {}

    public static boolean canWrite(Context context) {
        try {
            return Settings.System.canWrite(context.getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "canWrite FAILED error=" + errorOf(t));
            return false;
        }
    }

    public static boolean canRead(Context context) {
        try {
            Settings.System.getString(context.getApplicationContext().getContentResolver(), KEY);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "canRead FAILED error=" + errorOf(t));
            return false;
        }
    }

    public static boolean containsGms(Context context) {
        return containsPackage(context, GMS_PACKAGE, "containsGms");
    }

    public static boolean containsPlayStore(Context context) {
        return containsPackage(context, PLAY_STORE_PACKAGE, "containsPlayStore");
    }

    public static boolean containsSelf(Context context) {
        return containsPackage(context, context.getApplicationContext().getPackageName(), "containsSelf");
    }

    public static boolean containsPackage(Context context, String packageName) {
        return containsPackage(context, packageName, "containsPackage");
    }

    private static boolean containsPackage(Context context, String packageName, String logName) {
        try {
            String raw = Settings.System.getString(
                    context.getApplicationContext().getContentResolver(), KEY);
            return parse(raw).contains(packageName);
        } catch (Throwable t) {
            Log.w(TAG, logName + " FAILED error=" + errorOf(t));
            return false;
        }
    }

    public static Result ensureGms(Context context) {
        Context app = context.getApplicationContext();
        boolean writeAllowed = canWrite(app);
        Log.i(TAG, "ensureGms canWrite=" + writeAllowed);

        if (!writeAllowed) {
            return Result.NO_PERMISSION;
        }

        try {
            String before = Settings.System.getString(app.getContentResolver(), KEY);
            LinkedHashSet<String> packages = parse(before);
            boolean hasGmsBefore = packages.contains(GMS_PACKAGE);
            boolean hasPlayStoreBefore = packages.contains(PLAY_STORE_PACKAGE);
            boolean hasSelfBefore = packages.contains(app.getPackageName());
            Log.i(TAG, "before containsGms=" + hasGmsBefore
                    + " containsPlayStore=" + hasPlayStoreBefore
                    + " containsSelf=" + hasSelfBefore
                    + " count=" + packages.size()
                    + " value=" + safeValue(before));

            if (hasGmsBefore && hasPlayStoreBefore && hasSelfBefore) {
                rememberLastGood(app, packages);
                Log.i(TAG, "result=ALREADY_PRESENT");
                return Result.ALREADY_PRESENT;
            }

            // HyperOS can occasionally expose a transient empty value while its
            // power policy database is being rebuilt. Do not turn that temporary
            // empty state into a new one-item list and accidentally discard the
            // user's other no-restrict packages. Restore the last successfully
            // observed list first, then add GMS.
            if (packages.isEmpty()) {
                LinkedHashSet<String> cached = parse(readLastGood(app));
                if (!cached.isEmpty()) {
                    packages.addAll(cached);
                    Log.i(TAG, "restored lastGood count=" + cached.size());
                } else {
                    Log.i(TAG, "lastGood unavailable; starting from empty list");
                }
            }

            boolean addedGms = packages.add(GMS_PACKAGE);
            boolean addedPlayStore = packages.add(PLAY_STORE_PACKAGE);
            boolean addedSelf = packages.add(app.getPackageName());
            String merged = TextUtils.join(",", packages);
            Log.i(TAG, "addingGms=" + addedGms
                    + " addingPlayStore=" + addedPlayStore
                    + " addingSelf=" + addedSelf
                    + " newCount=" + packages.size());

            boolean writeAccepted = Settings.System.putString(
                    app.getContentResolver(), KEY, merged);
            Log.i(TAG, "putString accepted=" + writeAccepted);

            if (!writeAccepted) {
                Log.w(TAG, "result=FAILED reason=putString_false");
                return Result.FAILED;
            }

            String after = Settings.System.getString(app.getContentResolver(), KEY);
            LinkedHashSet<String> verifiedPackages = parse(after);
            boolean gmsVerified = verifiedPackages.contains(GMS_PACKAGE);
            boolean playStoreVerified = verifiedPackages.contains(PLAY_STORE_PACKAGE);
            boolean selfVerified = verifiedPackages.contains(app.getPackageName());
            Log.i(TAG, "after containsGms=" + gmsVerified
                    + " containsPlayStore=" + playStoreVerified
                    + " containsSelf=" + selfVerified
                    + " value=" + safeValue(after));

            if (!gmsVerified || !playStoreVerified || !selfVerified) {
                Log.w(TAG, "result=VERIFY_FAILED gms=" + gmsVerified
                        + " playStore=" + playStoreVerified
                        + " self=" + selfVerified);
                return Result.VERIFY_FAILED;
            }

            rememberLastGood(app, verifiedPackages);

            // Settings.System.putString() already notifies SettingsProvider observers.
            // Do not send a second manual notifyChange(): Aurogon and the JobScheduler
            // watcher will receive the provider notification from this successful write.
            Log.i(TAG, "result=ADDED");
            return Result.ADDED;
        } catch (Throwable t) {
            Log.e(TAG, "ensureGms FAILED error=" + errorOf(t), t);
            return Result.FAILED;
        }
    }

    public static String readRaw(Context context) {
        try {
            String raw = Settings.System.getString(
                    context.getApplicationContext().getContentResolver(), KEY);
            return raw == null ? "" : raw;
        } catch (Throwable t) {
            Log.w(TAG, "readRaw FAILED error=" + errorOf(t));
            return "";
        }
    }


    private static Context prefsContext(Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return app.createDeviceProtectedStorageContext();
            } catch (Throwable ignored) {
                // Fall back to normal app storage below.
            }
        }
        return app;
    }

    private static String readLastGood(Context context) {
        try {
            SharedPreferences prefs = prefsContext(context)
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return prefs.getString(PREF_LAST_GOOD, null);
        } catch (Throwable t) {
            Log.w(TAG, "readLastGood FAILED error=" + errorOf(t));
            return null;
        }
    }

    private static void rememberLastGood(Context context, LinkedHashSet<String> packages) {
        if (packages == null || packages.isEmpty()) return;

        String normalized = TextUtils.join(",", packages);
        try {
            SharedPreferences prefs = prefsContext(context)
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String old = prefs.getString(PREF_LAST_GOOD, null);
            if (!normalized.equals(old)) {
                prefs.edit().putString(PREF_LAST_GOOD, normalized).apply();
                Log.i(TAG, "lastGood saved count=" + packages.size());
            }
        } catch (Throwable t) {
            Log.w(TAG, "rememberLastGood FAILED error=" + errorOf(t));
        }
    }

    private static LinkedHashSet<String> parse(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return out;

        // ROM dumps have been observed with commas plus optional spaces. Accept
        // additional separators defensively without disturbing package names.
        String normalized = raw.replace(';', ',')
                .replace('\n', ',')
                .replace('\t', ',');

        for (String token : normalized.split(",")) {
            String value = token == null ? "" : token.trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static String safeValue(String value) {
        if (value == null) return "<null>";
        // This list is normally short, but avoid flooding logcat on unusual ROMs.
        if (value.length() <= 1200) return value;
        return value.substring(0, 1200) + "...(" + value.length() + " chars)";
    }

    private static String errorOf(Throwable t) {
        if (t == null) return "unknown";
        return t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
    }
}
