package com.winlator.star.core;

import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps a bounded history of past runs inside a game's log folder.
 *
 * The current run's files keep their plain names — {@code wine_debug.log}, {@code <game>_d3d11.log} —
 * so "the latest log" is always the obvious file, and anyone who already knows where
 * {@code wine_debug.log} lives still finds it. At the START of a launch the previous run's files are
 * moved into {@code previous/<timestamp>/} and the oldest archives beyond the keep-count are deleted.
 *
 * Rotating on launch rather than on exit is deliberate: a crashed or force-killed game never gets to
 * run exit code, and that is exactly the run whose log matters most.
 *
 * Only files we can attribute to a run are moved — the sweep is shallow, so it never touches the
 * {@code previous/} folder itself or anything a user dropped in there.
 */
public final class LogRotation {

    private static final String TAG = "LogRotation";
    public static final String ARCHIVE_DIR = "previous";

    private LogRotation() {}

    /**
     * Archive whatever the last run left in {@code gameDir}, then prune to {@code keepLast} archives.
     * A {@code keepLast} of 0 means "no history": the old files are deleted outright.
     * Never throws — logging must not be able to stop a game launching.
     */
    public static void rotate(File gameDir, int keepLast) {
        if (gameDir == null || !gameDir.isDirectory()) return;
        try {
            File[] stale = gameDir.listFiles(f -> f.isFile() && looksLikeLog(f.getName()));
            if (stale == null || stale.length == 0) {
                pruneArchives(gameDir, keepLast);
                return;
            }

            if (keepLast <= 0) {
                for (File f : stale) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                pruneArchives(gameDir, 0);
                return;
            }

            // Stamp the archive with the newest file it contains, not "now" — the folder name then
            // describes when that run actually happened rather than when the next one started.
            long newest = 0;
            for (File f : stale) newest = Math.max(newest, f.lastModified());
            if (newest == 0) newest = System.currentTimeMillis();

            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(newest));
            File archive = new File(new File(gameDir, ARCHIVE_DIR), stamp);
            if (!archive.mkdirs() && !archive.isDirectory()) {
                Log.w(TAG, "could not create archive dir " + archive);
                return;
            }

            for (File f : stale) {
                File dst = new File(archive, f.getName());
                if (!f.renameTo(dst)) {
                    // Same directory tree, so a rename should always work; if it somehow doesn't,
                    // drop the file rather than leaving last run's output to be appended to.
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            pruneArchives(gameDir, keepLast);
        } catch (Exception e) {
            Log.w(TAG, "rotate failed for " + gameDir, e);
        }
    }

    /** Delete the oldest archives until at most {@code keepLast} remain. */
    private static void pruneArchives(File gameDir, int keepLast) {
        File archiveRoot = new File(gameDir, ARCHIVE_DIR);
        File[] runs = archiveRoot.listFiles(File::isDirectory);
        if (runs == null || runs.length <= Math.max(keepLast, 0)) return;

        // Sort by name: the timestamp format is lexicographically ordered, so this is chronological
        // and does not depend on mtime, which a copy or a file manager can rewrite.
        Arrays.sort(runs, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = Math.max(keepLast, 0); i < runs.length; i++) deleteTree(runs[i]);
    }

    private static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * Whether a file in a game folder is one of ours. Deliberately permissive about the stem (DXVK
     * derives its own names from the executable) but strict about the extension, so an unrelated
     * file a user parked in the folder is left alone.
     */
    private static boolean looksLikeLog(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".log") || n.endsWith(".txt");
    }
}
