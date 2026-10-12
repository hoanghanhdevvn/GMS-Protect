package com.hanh.gmsprotect;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;

/**
 * Lab-only probe for Android SettingsProvider's legacy targetSdk <= 22 behavior.
 * This app intentionally targets API 22 and should not be shipped as the public app.
 */
public final class LegacyMilletProbeActivity extends Activity {
    private static final String TAG = "Millet22Probe";
    private static final String KEY = "MILLET_NO_RESTRICT_APP";
    private static final String GMS = "com.google.android.gms";

    private TextView status;
    private TextView rawValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshStatus("Khởi động probe");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus("onResume");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("MILLET Target 22 Probe");
        title.setTextSize(23f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, lp(0, 14));

        TextView note = new TextView(this);
        note.setText("Bản LAB riêng. Chỉ kiểm tra targetSdk 22 có ghi được MILLET_NO_RESTRICT_APP hay không. Không dùng làm bản public.");
        note.setTextSize(13f);
        note.setTextColor(0xFF555555);
        root.addView(note, lp(0, 18));

        status = new TextView(this);
        status.setTextSize(13f);
        status.setTextColor(Color.BLACK);
        status.setTextIsSelectable(true);
        root.addView(status, lp(0, 12));

        rawValue = new TextView(this);
        rawValue.setTextSize(11f);
        rawValue.setTextColor(0xFF555555);
        rawValue.setTextIsSelectable(true);
        root.addView(rawValue, lp(0, 18));

        Button grant = new Button(this);
        grant.setText("1. MỞ QUYỀN WRITE_SETTINGS");
        grant.setOnClickListener(v -> openWriteSettings());
        root.addView(grant, lp(0, 10));

        Button test = new Button(this);
        test.setText("2. TEST THÊM GMS VÀO MILLET");
        test.setOnClickListener(v -> runWriteProbe());
        root.addView(test, lp(0, 10));

        Button reread = new Button(this);
        reread.setText("ĐỌC LẠI TRẠNG THÁI");
        reread.setOnClickListener(v -> refreshStatus("Đọc lại"));
        root.addView(reread, lp(0, 10));

        Button copyLogHint = new Button(this);
        copyLogHint.setText("HIỆN LỆNH LOGCAT");
        copyLogHint.setOnClickListener(v -> Toast.makeText(this,
                "adb logcat -d -v time | findstr /I Millet22Probe", Toast.LENGTH_LONG).show());
        root.addView(copyLogHint, lp(0, 10));

        setContentView(scroll);
    }

    private void openWriteSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "openWriteSettings FAILED", t);
            Toast.makeText(this, t.getClass().getSimpleName() + ": " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void runWriteProbe() {
        int target = getTargetSdk();
        boolean canWrite = canWrite();
        String before = readRaw();
        LinkedHashSet<String> pkgs = parse(before);
        boolean beforeHasGms = pkgs.contains(GMS);

        Log.i(TAG, "START targetSdk=" + target
                + " canWrite=" + canWrite
                + " beforeHasGms=" + beforeHasGms
                + " before=" + before);

        if (!canWrite) {
            Log.w(TAG, "STOP no WRITE_SETTINGS access");
            Toast.makeText(this, "Chưa có WRITE_SETTINGS. Bấm nút 1 trước.", Toast.LENGTH_LONG).show();
            refreshStatus("Chưa có WRITE_SETTINGS");
            return;
        }

        if (beforeHasGms) {
            Log.w(TAG, "STOP GMS already present; remove it first if you want a clean proof");
            Toast.makeText(this,
                    "GMS đã có sẵn trong MILLET. Muốn test sạch hãy bỏ GMS bằng ADB trước.",
                    Toast.LENGTH_LONG).show();
            refreshStatus("GMS đã có sẵn");
            return;
        }

        pkgs.add(GMS);
        String merged = join(pkgs);

        try {
            boolean accepted = Settings.System.putString(getContentResolver(), KEY, merged);
            String after = readRaw();
            boolean afterHasGms = parse(after).contains(GMS);

            Log.i(TAG, "RESULT putString=" + accepted
                    + " afterHasGms=" + afterHasGms
                    + " after=" + after);

            if (accepted && afterHasGms) {
                Toast.makeText(this, "THÀNH CÔNG: targetSdk 22 đã ghi được GMS vào MILLET!",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        "Ghi không thành công. Xem log Millet22Probe.", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.e(TAG, "FAILED targetSdk=" + target
                    + " canWrite=" + canWrite
                    + " error=" + t.getClass().getSimpleName()
                    + ":" + String.valueOf(t.getMessage()), t);
            Toast.makeText(this,
                    "FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }

        refreshStatus("Sau test ghi");
    }

    private void refreshStatus(String reason) {
        int target = getTargetSdk();
        boolean canWrite = canWrite();
        String raw = readRaw();
        boolean hasGms = parse(raw).contains(GMS);

        String summary = "reason=" + reason
                + "\npackage=" + getPackageName()
                + "\ntargetSdk=" + target
                + "\nWRITE_SETTINGS=" + canWrite
                + "\nGMS trong MILLET=" + hasGms;
        status.setText(summary);
        rawValue.setText("MILLET_NO_RESTRICT_APP:\n" + raw);
        Log.i(TAG, "STATUS " + summary.replace('\n', ' ') + " raw=" + raw);
    }

    private int getTargetSdk() {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), 0);
            return ai.targetSdkVersion;
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean canWrite() {
        try {
            return Settings.System.canWrite(this);
        } catch (Throwable t) {
            Log.e(TAG, "canWrite FAILED", t);
            return false;
        }
    }

    private String readRaw() {
        try {
            String value = Settings.System.getString(getContentResolver(), KEY);
            return value == null ? "" : value;
        } catch (Throwable t) {
            Log.e(TAG, "readRaw FAILED", t);
            return "";
        }
    }

    private static LinkedHashSet<String> parse(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        String normalized = raw.replace(';', ',').replace('\n', ',').replace('\t', ',');
        for (String token : normalized.split(",")) {
            String s = token == null ? "" : token.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String join(LinkedHashSet<String> set) {
        StringBuilder out = new StringBuilder();
        for (String s : set) {
            if (out.length() > 0) out.append(',');
            out.append(s);
        }
        return out.toString();
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
