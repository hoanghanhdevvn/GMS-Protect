package com.hanh.gmsprotect;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Experimental bridge for the HyperOS runtime-only GMS limiter.
 *
 * The app must declare android.permission.DUMP and the user must grant it once
 * from ADB:
 *   adb shell pm grant com.hanh.gmsprotect android.permission.DUMP
 *
 * No root/Shizuku is used here. /system/bin/dumpsys is executed as this app UID,
 * so success proves that the granted DUMP permission + SELinux policy are enough
 * to reach GreezeManagerService.dump().
 */
public final class GreezerDumpControl {
    private static final String TAG = "GmsProtectDump";
    private static final String DUMP_PERMISSION = "android.permission.DUMP";
    private static final long TIMEOUT_SECONDS = 8L;

    public enum Result {
        DISABLED,
        NO_PERMISSION,
        COMMAND_FAILED,
        VERIFY_FAILED,
        TIMEOUT
    }

    public interface Callback {
        void onDone(Result result, String detail);
    }

    private GreezerDumpControl() {}

    public static boolean hasDumpPermission(Context context) {
        return context.checkSelfPermission(DUMP_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static void disableAsync(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Attempt attempt = disableNow(app);
            Log.i(TAG, "disable result=" + attempt.result + " detail=" + attempt.detail);

            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        callback.onDone(attempt.result, attempt.detail);
                    } catch (Throwable ignored) {
                    }
                });
            }
        }, "GmsProtect-GreezerDump").start();
    }

    private static Attempt disableNow(Context context) {
        if (!hasDumpPermission(context)) {
            return new Attempt(Result.NO_PERMISSION, "android.permission.DUMP not granted");
        }

        File commandOut = new File(context.getCacheDir(), "greezer_disable.txt");
        ExecResult disable = execToFile(
                commandOut,
                "/system/bin/dumpsys", "greezer", "IM", "GMS", "disable"
        );

        if (disable.timedOut) {
            return new Attempt(Result.TIMEOUT, "disable command timeout");
        }
        if (disable.exitCode != 0) {
            return new Attempt(Result.COMMAND_FAILED,
                    "disable exit=" + disable.exitCode + " output=" + compact(disable.output));
        }

        if (containsDisabled(disable.output)) {
            return new Attempt(Result.DISABLED, "verified in disable output");
        }

        // Some builds print little/no state after the command. Verify using a
        // second dumpsys invocation instead of assuming success from exit code.
        File verifyOut = new File(context.getCacheDir(), "greezer_verify.txt");
        ExecResult verify = execToFile(verifyOut, "/system/bin/dumpsys", "greezer");
        if (verify.timedOut) {
            return new Attempt(Result.TIMEOUT, "verify command timeout");
        }
        if (verify.exitCode != 0) {
            return new Attempt(Result.VERIFY_FAILED,
                    "verify exit=" + verify.exitCode + " output=" + compact(verify.output));
        }
        if (containsDisabled(verify.output)) {
            return new Attempt(Result.DISABLED, "verified by second dumpsys");
        }

        return new Attempt(Result.VERIFY_FAILED,
                "flag not false; disableOutput=" + compact(disable.output)
                        + " verifyOutput=" + compact(verify.output));
    }

    private static boolean containsDisabled(String output) {
        if (output == null) return false;
        return output.contains("mGmsLimitEnabled : false")
                || output.contains("mGmsLimitEnabled: false");
    }

    private static ExecResult execToFile(File out, String... command) {
        Process process = null;
        try {
            if (out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(out);
            process = builder.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecResult(-1, readFile(out), true);
            }
            return new ExecResult(process.exitValue(), readFile(out), false);
        } catch (Throwable t) {
            if (process != null) {
                try {
                    process.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
            return new ExecResult(-1,
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()),
                    false);
        }
    }

    private static String readFile(File file) {
        try {
            if (!file.exists()) return "";
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "read-error:" + t.getClass().getSimpleName();
        }
    }

    private static String compact(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (oneLine.length() > 700) {
            return oneLine.substring(0, 700) + "...";
        }
        return oneLine;
    }

    private static final class ExecResult {
        final int exitCode;
        final String output;
        final boolean timedOut;

        ExecResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }

    private static final class Attempt {
        final Result result;
        final String detail;

        Attempt(Result result, String detail) {
            this.result = result;
            this.detail = detail;
        }
    }
}
