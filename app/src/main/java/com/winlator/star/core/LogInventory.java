package com.winlator.star.core;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads what is actually on disk under the chosen log location and groups it by game, so the Log
 * Manager can list "God of War — 4 files, 6.2 MB, 12 min ago" without the UI knowing anything about
 * folder layout.
 *
 * Everything here is derived from the filesystem rather than tracked in a database: logs are written
 * by DXVK and Wine as much as by us, a user can delete or copy them from a file manager at any time,
 * and a stored index would drift from reality the moment they did.
 */
public final class LogInventory {

    private LogInventory() {}

    /** One game's worth of logs (or the {@code _app} bucket). */
    public static final class Entry {
        public final String name;          // display name = folder name
        public final File dir;
        public final int fileCount;        // current-run files only, archives excluded
        public final long totalBytes;      // including archived runs
        public final long lastModified;
        public final boolean isAppBucket;  // the "_app" folder: logcat + crash reports
        public final int archivedRuns;

        Entry(String name, File dir, int fileCount, long totalBytes, long lastModified,
              boolean isAppBucket, int archivedRuns) {
            this.name = name;
            this.dir = dir;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.lastModified = lastModified;
            this.isAppBucket = isAppBucket;
            this.archivedRuns = archivedRuns;
        }
    }

    /**
     * All log groups, newest activity first. When per-game folders are off there is a single
     * "All logs" entry pointing at the flat directory, so the same list UI works either way.
     */
    public static List<Entry> scan(Context context) {
        List<Entry> out = new ArrayList<>();
        File base = LogLocation.resolveLogDir(context);
        if (base == null || !base.isDirectory()) return out;

        File[] kids = base.listFiles();
        if (kids == null) return out;

        boolean sawFolder = false;
        for (File f : kids) {
            if (!f.isDirectory()) continue;
            sawFolder = true;
            out.add(describe(f, LogLocation.APP_FOLDER.equals(f.getName())));
        }

        // Loose files sitting directly in the log dir: either per-game folders are off, or these are
        // logs from before the folders existed. Either way they are real and must be listable — we
        // deliberately do not migrate or delete anything a previous version wrote.
        List<File> loose = new ArrayList<>();
        for (File f : kids) if (f.isFile() && isLog(f.getName())) loose.add(f);
        if (!loose.isEmpty()) {
            long bytes = 0, newest = 0;
            for (File f : loose) { bytes += f.length(); newest = Math.max(newest, f.lastModified()); }
            out.add(new Entry(sawFolder ? "Older logs" : "All logs", base,
                    loose.size(), bytes, newest, false, 0));
        }

        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return Long.compare(b.lastModified, a.lastModified);
            }
        });
        return out;
    }

    private static Entry describe(File dir, boolean appBucket) {
        File[] files = dir.listFiles();
        int count = 0;
        long bytes = 0, newest = dir.lastModified();
        int archives = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    if (LogRotation.ARCHIVE_DIR.equals(f.getName())) {
                        File[] runs = f.listFiles(File::isDirectory);
                        archives = runs == null ? 0 : runs.length;
                        bytes += sizeOfTree(f);
                    }
                    continue;
                }
                if (!isLog(f.getName())) continue;
                count++;
                bytes += f.length();
                newest = Math.max(newest, f.lastModified());
            }
        }
        return new Entry(dir.getName(), dir, count, bytes, newest, appBucket, archives);
    }

    /** Current-run log files in a group, newest first. Archives are not included. */
    public static List<File> filesIn(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) for (File f : files) if (f.isFile() && isLog(f.getName())) out.add(f);
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** Delete one group's logs, archives included. Returns true when the folder is gone or emptied. */
    public static boolean delete(Entry entry) {
        if (entry == null || entry.dir == null) return false;
        // A flat "All logs"/"Older logs" entry points at the log ROOT — deleting the directory itself
        // would take every game folder with it, so only the loose files are removed in that case.
        boolean isRoot = entry.fileCount > 0 && !entry.isAppBucket
                && (entry.name.equals("All logs") || entry.name.equals("Older logs"));
        if (isRoot) {
            File[] files = entry.dir.listFiles();
            if (files != null) for (File f : files) if (f.isFile() && isLog(f.getName())) //noinspection ResultOfMethodCallIgnored
                f.delete();
            return true;
        }
        return deleteTree(entry.dir);
    }

    public static long totalBytes(List<Entry> entries) {
        long t = 0;
        for (Entry e : entries) t += e.totalBytes;
        return t;
    }

    public static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024f);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024f * 1024f));
        return String.format(Locale.US, "%.2f GB", b / (1024f * 1024f * 1024f));
    }

    private static long sizeOfTree(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        long t = 0;
        if (kids != null) for (File k : kids) t += sizeOfTree(k);
        return t;
    }

    private static boolean deleteTree(File f) {
        if (f == null) return false;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        return f.delete();
    }

    private static boolean isLog(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".log") || n.endsWith(".txt");
    }
}
