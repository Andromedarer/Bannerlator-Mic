/*
 * Denuvo anti-tamper detection for Epic EOS Phase 2 (ownership-token launch).
 *
 * Companion to EpicLaunchArgs / EpicSidecar / EpicEosDetector. EOS games wrapped
 * in Denuvo reject Phase 1's exchange-code auth alone and additionally demand a
 * signed ownership verification token (-epicovt). This class decides, per game,
 * whether that Denuvo path must run — it is a detector only, not a bypass; the
 * ownership token is still minted by real Epic against the user's genuine
 * account + ownership.
 *
 * Modeled on {@link EpicEosDetector}: bounded install-dir scan → cached flag on
 * the shortcut (and bh_epic_prefs), computed once, fast read at launch.
 *
 * Credits: the -epicovt / Denuvo-requires-ownership-token flow follows The
 * GameNative Team's Epic/Legendary launcher work
 * (https://github.com/utkarshdalal/GameNative) — EpicGameLauncher +
 * EpicAuthManager (requiresOwnershipToken path). Massive thanks to utkarshdalal
 * and the GameNative contributors.
 *
 * Licensed under GPL-3.0, matching the GameNative-derived Epic sources.
 */
package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.winlator.star.container.Shortcut;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Detects whether an installed game is protected by Denuvo anti-tamper.
 *
 * The reliable heuristic is the presence of the literal ASCII string {@code Denuvo}
 * embedded inside the game's main {@code .exe} and/or its DLLs — Denuvo's
 * anti-tamper runtime carries the string in the binary. We stream the candidate
 * binaries in bounded chunks and search for the byte pattern; the first hit marks
 * the game.
 *
 * Result caching mirrors {@link EpicEosDetector#scanShortcutIfNeeded}: the boolean
 * is persisted on the shortcut as the {@code denuvo} extra (1/0) and computed
 * exactly once. {@link #isDenuvo(Shortcut)} is the fast read used at launch.
 */
public final class DenuvoDetector {

    private static final String TAG = "BH_DENUVO";

    /** The ASCII marker embedded in Denuvo-protected binaries. */
    private static final byte[] MARKER = "Denuvo".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Only binaries can carry the anti-tamper stub — skip everything else. */
    private static final String[] BINARY_EXTS = {".exe", ".dll"};

    /** Depth cap for the install-dir walk (game dir → subfolders). */
    private static final int SCAN_DEPTH = 3;

    /** Cap the number of binaries opened so a pathological tree can't stall a scan. */
    private static final int MAX_BINARIES_SCANNED = 400;

    /**
     * Per-binary byte budget. Denuvo's marker lives in the code/import sections and
     * is reached well within this; capping avoids streaming an entire multi-GB pak
     * that happens to carry a .dll extension.
     */
    private static final long MAX_BYTES_PER_BINARY = 256L * 1024 * 1024;

    private static final int READ_CHUNK = 1 << 16; // 64 KiB

    private DenuvoDetector() {}

    /**
     * Fast read: has this shortcut been resolved as Denuvo-protected? Returns false
     * when the flag is absent (never scanned) or scanned-and-negative. Pair with
     * {@link #hasScanned(Shortcut)} to distinguish the two.
     */
    public static boolean isDenuvo(Shortcut shortcut) {
        if (shortcut == null) return false;
        return "1".equals(shortcut.getExtra("denuvo"));
    }

    /** Has a Denuvo scan been recorded on this shortcut yet? */
    public static boolean hasScanned(Shortcut shortcut) {
        return shortcut != null && shortcut.hasExtra("denuvo");
    }

    /**
     * Resolve the game's install directory: prefer the shortcut exe's parent
     * (source-agnostic), fall back to the Epic {@code epic_dir_<appName>} pref.
     */
    private static File resolveInstallDir(Context ctx, Shortcut shortcut) {
        try {
            if (shortcut.path != null && !shortcut.path.isEmpty()) {
                File parent = new File(shortcut.path).getParentFile();
                if (parent != null && parent.isDirectory()) return parent;
            }
        } catch (Throwable ignored) {}
        if (ctx != null) {
            String appName = shortcut.getExtra("epicAppName", "");
            if (!appName.isEmpty()) {
                String dir = ctx.getSharedPreferences("bh_epic_prefs", 0)
                        .getString("epic_dir_" + appName, null);
                if (dir != null && !dir.isEmpty()) {
                    File f = new File(dir);
                    if (f.isDirectory()) return f;
                }
            }
        }
        return null;
    }

    /**
     * Synchronously scan (bounded) and persist the {@code denuvo} extra on the
     * shortcut. Runs even if a result is already cached — use {@link #scanIfNeededSync}
     * for the compute-once path. Returns the detected flag.
     *
     * Must be called off the main thread (streams binaries).
     */
    public static boolean scanSync(Context ctx, Shortcut shortcut) {
        if (shortcut == null) return false;
        boolean isDenuvo = false;
        try {
            File installDir = resolveInstallDir(ctx, shortcut);
            if (installDir != null) {
                int[] budget = {MAX_BINARIES_SCANNED};
                isDenuvo = walk(installDir, budget, SCAN_DEPTH);
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanSync walk failed for " + shortcut.path, t);
        }
        try {
            shortcut.putExtra("denuvo", isDenuvo ? "1" : "0");
            shortcut.saveData();
        } catch (Throwable t) {
            Log.w(TAG, "persist denuvo extra failed for " + shortcut.path, t);
        }
        // Mirror onto bh_epic_prefs keyed by appName (parity with EOS cache), best-effort.
        try {
            if (ctx != null) {
                String appName = shortcut.getExtra("epicAppName", "");
                if (!appName.isEmpty()) {
                    ctx.getSharedPreferences("bh_epic_prefs", 0).edit()
                            .putBoolean("denuvo_" + appName, isDenuvo)
                            .apply();
                }
            }
        } catch (Throwable ignored) {}
        Log.i(TAG, "scan(" + shortcut.path + ") → denuvo=" + isDenuvo);
        return isDenuvo;
    }

    /**
     * Compute-once synchronous variant: if the {@code denuvo} extra already exists
     * this returns the cached value without touching the filesystem; otherwise it
     * runs {@link #scanSync}. Intended for the background launch worker.
     */
    public static boolean scanIfNeededSync(Context ctx, Shortcut shortcut) {
        if (shortcut == null) return false;
        if (hasScanned(shortcut)) return isDenuvo(shortcut);
        return scanSync(ctx, shortcut);
    }

    /**
     * Background compute-once. Mirrors {@link EpicEosDetector#scanShortcutIfNeeded}:
     * returns immediately (invoking {@code onResolved}) on cache hit, otherwise
     * scans + persists off-thread.
     */
    public static void scanIfNeededAsync(Context ctx, Shortcut shortcut, Runnable onResolved) {
        if (shortcut == null) { if (onResolved != null) onResolved.run(); return; }
        if (hasScanned(shortcut)) { if (onResolved != null) onResolved.run(); return; }
        new Thread(() -> {
            try {
                scanSync(ctx, shortcut);
            } catch (Throwable t) {
                Log.w(TAG, "scanIfNeededAsync failed for " + shortcut.path, t);
            }
            if (onResolved != null) {
                try { onResolved.run(); } catch (Throwable ignored) {}
            }
        }, "bh-denuvo-scan").start();
    }

    private static boolean walk(File dir, int[] budget, int depth) {
        File[] entries = dir.listFiles();
        if (entries == null) return false;
        for (File f : entries) {
            if (budget[0] <= 0) return false;
            if (f.isDirectory()) {
                if (depth > 0 && walk(f, budget, depth - 1)) return true;
            } else if (isBinary(f.getName())) {
                budget[0]--;
                if (fileContainsMarker(f)) return true;
            }
        }
        return false;
    }

    private static boolean isBinary(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : BINARY_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Stream {@code file} in 64 KiB chunks (bounded by {@link #MAX_BYTES_PER_BINARY})
     * searching for the ASCII {@code Denuvo} marker. Keeps a small tail overlap so a
     * marker straddling a chunk boundary is still found.
     */
    private static boolean fileContainsMarker(File file) {
        int overlap = MARKER.length - 1;
        byte[] buf = new byte[READ_CHUNK + overlap];
        try (InputStream in = new FileInputStream(file)) {
            long scanned = 0;
            int carried = 0; // bytes retained from previous chunk at buf[0..carried)
            while (scanned < MAX_BYTES_PER_BINARY) {
                int read = in.read(buf, carried, READ_CHUNK);
                if (read < 0) break;
                int valid = carried + read;
                int idx = indexOf(buf, valid, MARKER);
                if (idx >= 0) return true;
                scanned += read;
                // Carry the last (MARKER.length-1) bytes forward to catch a boundary-spanning match.
                carried = Math.min(overlap, valid);
                if (carried > 0) System.arraycopy(buf, valid - carried, buf, 0, carried);
            }
        } catch (Throwable t) {
            // Unreadable file → not a positive; keep scanning others.
            Log.d(TAG, "read failed for " + file.getName() + ": " + t.getClass().getSimpleName());
        }
        return false;
    }

    /** Naive byte search of {@code pattern} within {@code data[0..len)}. */
    private static int indexOf(byte[] data, int len, byte[] pattern) {
        final int n = len - pattern.length;
        outer:
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
