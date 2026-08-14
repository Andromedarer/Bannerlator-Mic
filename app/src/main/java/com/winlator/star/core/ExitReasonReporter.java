package com.winlator.star.core;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Post-mortem crash capture WITHOUT root, via {@link ActivityManager#getHistoricalProcessExitReasons}.
 *
 * <p>The Android system records why every app process last died — including <b>native</b> crashes
 * (SIGSEGV/SIGABRT/…) that die in a separate {@code crash_dump} process and so never appear in the
 * app's own logcat, and that the Java {@link CrashReporter} (a {@code UncaughtExceptionHandler})
 * cannot see either. On the NEXT launch we can read that record and, for a native crash, pull the
 * tombstone-style backtrace from {@link ApplicationExitInfo#getTraceInputStream()}. No permission,
 * no su — this is the one way to record a native crash on an unrooted device.
 *
 * <p>The history is <b>system-retained</b> across the crash+restart, so this still works when reading
 * is triggered only AFTER the crash happened — the reason is still there to read.
 *
 * <p>Needs Android 11 (API 30 / R). Adapted from WinNative's {@code LogManager.logLastExitReasons}
 * (both apps GPL-3.0).
 */
public final class ExitReasonReporter {

    private static final String TAG = "ExitReasonReporter";
    /** Own subfolder next to the other logs, so these never mix with per-game or app logcat files. */
    public static final String FOLDER = "exit-reasons";
    /** Auto-write the report on launch. OFF by default — the data is system-retained, so opt-in
     *  after a crash still surfaces it; the toggle only controls whether we file it automatically. */
    public static final String PREF_AUTOSAVE = "exit_reasons_autosave";

    private static final int MAX_RECORDS = 8;
    private static final int MAX_TRACE_LINES = 40;

    private ExitReasonReporter() {}

    /** True when the API this relies on exists (Android 11 / R). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * Build a human-readable report of the most recent process exits. Never throws; returns a readable
     * message on failure / when unsupported so a saved file always explains itself.
     */
    @SuppressLint("NewApi") // every ApplicationExitInfo use is behind the isSupported() gate below
    public static String capture(Context context) {
        if (!isSupported())
            return "Exit-reason capture needs Android 11 (SDK 30) or newer — this device is SDK "
                    + Build.VERSION.SDK_INT + ".";
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> infos =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, MAX_RECORDS);
            if (infos == null || infos.isEmpty())
                return "No exit records yet — the system has not recorded a previous exit for this app.";

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (ApplicationExitInfo info : infos) {
                sb.append("--- exit #").append(i++).append(" ---\n");
                sb.append("when      : ").append(fmt.format(new Date(info.getTimestamp()))).append('\n');
                sb.append("reason    : ").append(reasonName(info.getReason()))
                  .append(" (").append(info.getReason()).append(")\n");
                sb.append("desc      : ").append(String.valueOf(info.getDescription())).append('\n');
                sb.append("importance: ").append(info.getImportance()).append('\n');
                sb.append("memory    : pss ").append(info.getPss())
                  .append(" KB / rss ").append(info.getRss()).append(" KB\n");
                if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE)
                    sb.append(traceExcerpt(info));
                sb.append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "capture failed", t);
            return "Failed to read exit reasons: " + t.getMessage();
        }
    }

    @SuppressLint("NewApi")
    private static String traceExcerpt(ApplicationExitInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("native trace (first ").append(MAX_TRACE_LINES).append(" lines):\n");
        InputStream in = null;
        try {
            in = info.getTraceInputStream();
            if (in == null) return sb.append("  (no trace stream available)\n").toString();
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < MAX_TRACE_LINES) {
                sb.append("  ").append(LogcatCapture.redact(line)).append('\n');
                n++;
            }
        } catch (Throwable t) {
            sb.append("  (could not read trace: ").append(t.getMessage()).append(")\n");
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    @SuppressLint("NewApi")
    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:             return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:      return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR:               return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY:        return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_SIGNALED:          return "SIGNALED";
            case ApplicationExitInfo.REASON_USER_REQUESTED:    return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:      return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:   return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_EXIT_SELF:         return "EXIT_SELF";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_OTHER:             return "OTHER";
            default:                                           return "UNKNOWN";
        }
    }

    /** Where the exit-reason reports are filed: {@code <log dir>/exit-reasons/}. */
    public static File folder(Context context) {
        File dir = new File(LogLocation.resolveLogDir(context), FOLDER);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Write a timestamped exit-reason report. Returns the file, or null on failure / unsupported.
     * Call off the main thread — it reads a system stream and writes a file.
     */
    public static File captureToFile(Context context) {
        if (!isSupported()) return null;
        try {
            File out = new File(folder(context), "exit-reasons-" + LogcatCapture.timestamp() + ".log");
            FileUtils.writeString(out, LogcatCapture.deviceHeader(context) + capture(context));
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "could not write exit-reasons file", t);
            return null;
        }
    }
}
