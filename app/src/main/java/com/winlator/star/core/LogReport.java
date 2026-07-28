package com.winlator.star.core;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a bug report out of one game's logs: a redacted zip the user can attach, plus the issue
 * body text that goes with it.
 *
 * Why this is two steps rather than one. GitHub has no API for attaching a file to an issue — the
 * only upload path is the web UI's own, tied to a signed-in session. A URL can prefill the title
 * and body and nothing else. So the app writes the zip somewhere the browser's file picker can
 * reach, prefills everything it knows, and the attach itself is one tap in the GitHub form.
 *
 * Everything in the zip goes through {@link LogcatCapture#redact} first. That matters more here
 * than anywhere else in the app: {@code wine_debug.log} is full of Windows paths, and those paths
 * routinely contain the user's account name.
 */
public final class LogReport {

    private static final String TAG = "LogReport";
    private static final String REPO = "The412Banner/Bannerlator";
    /** Per-file cap. A seh-enabled Wine log can be tens of MB; the tail is the part that matters. */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** GitHub prefills through a GET; a body far past this starts getting refused by browsers. */
    private static final int MAX_BODY_CHARS = 6000;

    private LogReport() {}

    /** What was built: the zip on disk, and what went into it. */
    public static final class Bundle {
        public final File zip;
        public final List<String> included;
        public final String facts;   // markdown block: device, app, and whatever the logs revealed

        Bundle(File zip, List<String> included, String facts) {
            this.zip = zip;
            this.included = included;
            this.facts = facts;
        }
    }

    /**
     * Zip up a run's logs, redacted, into public Downloads so the browser's picker can see them.
     *
     * @param runDir     the run to report — current, or one of the archived launches
     * @param includeApp also attach the app logcat and any crash reports
     */
    public static Bundle build(Context context, LogInventory.Entry entry, File runDir, boolean includeApp) {
        File reports = new File(Environment.getExternalStorageDirectory(), "Download/bannerlator/reports");
        //noinspection ResultOfMethodCallIgnored
        reports.mkdirs();

        String stem = LogLocation.sanitizeFolderName(entry.isAppBucket ? "app" : entry.name);
        File zip = new File(reports, stem + "-" + LogcatCapture.timestamp() + ".zip");

        List<String> included = new ArrayList<>();
        StringBuilder scanned = new StringBuilder();

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (File f : LogInventory.filesIn(runDir)) {
                String text = readTailRedacted(f);
                addEntry(out, stem + "/" + f.getName(), text);
                included.add(f.getName());
                scanned.append(text.length() > 8000 ? text.substring(0, 8000) : text).append('\n');
            }
            if (includeApp) {
                File appDir = LogLocation.resolveAppLogDir(context);
                if (appDir != null && !appDir.equals(runDir)) {
                    File[] appFiles = appDir.listFiles(f -> f.isFile()
                            && (f.getName().equals("logcat.log") || f.getName().startsWith("crash_")));
                    if (appFiles != null) {
                        for (File f : appFiles) {
                            addEntry(out, "_app/" + f.getName(), readTailRedacted(f));
                            included.add(f.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not build report bundle", e);
            return null;
        }

        return new Bundle(zip, included, facts(context, scanned.toString(), included, zip));
    }

    /** The markdown block that goes into the issue body. */
    private static String facts(Context context, String scanned, List<String> included, File zip) {
        StringBuilder b = new StringBuilder();
        b.append("### System\n\n```\n").append(LogcatCapture.deviceHeader(context)).append("```\n\n");

        String gpu = firstMatch(scanned, "Device *: *(.+)");
        String driver = firstMatch(scanned, "Driver *: *(.+)");
        String dxvk = firstMatch(scanned, "DXVK: *v?([\\w.\\-]+)");
        String vkd3d = firstMatch(scanned, "vkd3d-proton *v?([\\w.\\-]+)");
        if (gpu != null || driver != null || dxvk != null || vkd3d != null) {
            b.append("### From the logs\n\n");
            if (gpu != null) b.append("- GPU: `").append(gpu).append("`\n");
            if (driver != null) b.append("- Driver: `").append(driver).append("`\n");
            if (dxvk != null) b.append("- DXVK: `").append(dxvk).append("`\n");
            if (vkd3d != null) b.append("- VKD3D: `").append(vkd3d).append("`\n");
            b.append('\n');
        }

        b.append("### Attached\n\n");
        for (String name : included) b.append("- `").append(name).append("`\n");
        b.append("\n_Logs are redacted of usernames, e-mail addresses and tokens before they are " +
                "written. Attach the zip below — it is at `Download/bannerlator/reports/")
         .append(zip.getName()).append("`._\n");
        return b.toString();
    }

    /** github.com/…/issues/new with the title and body prefilled. */
    public static String issueUrl(String title, String description, String facts) {
        StringBuilder body = new StringBuilder();
        if (description != null && !description.trim().isEmpty()) {
            body.append("### What happened\n\n").append(description.trim()).append("\n\n");
        }
        body.append(facts);
        String text = body.toString();
        if (text.length() > MAX_BODY_CHARS) text = text.substring(0, MAX_BODY_CHARS) + "\n…";

        return "https://github.com/" + REPO + "/issues/new"
                + "?title=" + Uri.encode(title == null || title.trim().isEmpty() ? "Bug report" : title.trim())
                + "&body=" + Uri.encode(text);
    }

    private static void addEntry(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes());
        out.closeEntry();
    }

    /** Last {@link #MAX_FILE_BYTES} of a file, redacted, with a note when it was truncated. */
    private static String readTailRedacted(File f) {
        try {
            long len = f.length();
            long from = len > MAX_FILE_BYTES ? len - MAX_FILE_BYTES : 0;
            byte[] bytes = new byte[(int) (len - from)];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(from);
                raf.readFully(bytes);
            }
            String text = new String(bytes);
            if (from > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0) text = text.substring(nl + 1);
                text = String.format(Locale.US,
                        "[truncated — showing the last %d KB of %d KB]\n", MAX_FILE_BYTES / 1024, len / 1024)
                        + text;
            }
            return LogcatCapture.redact(text);
        } catch (Exception e) {
            return "(could not read " + f.getName() + ": " + e.getMessage() + ")";
        }
    }

    private static String firstMatch(String haystack, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(haystack);
            if (m.find()) {
                String s = m.group(1).trim();
                return s.length() > 80 ? s.substring(0, 80) : s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
