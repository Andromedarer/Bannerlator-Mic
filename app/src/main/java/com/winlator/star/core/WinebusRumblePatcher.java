package com.winlator.star.core;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Idempotent runtime byte-patch for winebus.so ("TideGear #91 / preload-free winebus
 * duration patch").
 *
 * <p>SDL2's rumble APIs auto-expire the effect after the caller-supplied duration_ms.
 * winebus' {@code sdl_device_haptics_start} passes a real (short) duration when it drives
 * {@code SDL_JoystickRumble} / {@code SDL_JoystickRumbleTriggers} through the SDL2 pointers
 * it {@code dlsym}'d, so a held rumble dies after ~1s. This forces the 4th integer arg
 * (duration_ms) of those two indirect calls to 0xffffffff (-1) so SDL2 never auto-stops it.
 *
 * <p>Derivation (CODE-DERIVED against our Proton 9.0 arm64ec winebus.so, aarch64-unix,
 * 220176 bytes). Inside {@code sdl_device_haptics_start} the two rumble call sites are:
 * <pre>
 *   ; SDL_JoystickRumble          |  ; SDL_JoystickRumbleTriggers
 *   ldr  x8,[x8,#0x878]           |  ldr  x8,[x8,#0x880]
 *   mov  w2,w21                   |  mov  w2,w23
 *   mov  w3,w20   ; <-- duration  |  mov  w3,w20   ; <-- duration
 *   blr  x8                       |  blr  x8
 * </pre>
 * Both set the 4th arg with {@code mov w3,w20} (0x2a1403e3) immediately before the
 * indirect {@code blr x8} (0xd63f0100). The 8-byte window {mov w3,w20 ; blr x8} matches
 * EXACTLY these 2 sites; {@code mov w3,w20} alone matches 4 (the blr suffix disambiguates
 * it from unrelated argument moves, and keeps the zero-duration haptics-stop path
 * untouched). We rewrite {@code mov w3,w20} to {@code mov w3,#-1} (movn w3,#0 = 0x12800003)
 * while leaving {@code blr x8} intact.
 */
public final class WinebusRumblePatcher {
    private static final String TAG = "Evshim";

    /** One duration-load rewrite window plus how many are expected across the file. */
    private static final class Sig {
        final byte[] original;   // "set duration_ms = real ; indirect-call"
        final byte[] patched;    // "set duration_ms = 0xffffffff ; indirect-call"
        final int expected;      // exact-count guard: only patch when this many ORIGINALs match
        Sig(int[] original, int[] patched, int expected) {
            this.original = bytes(original);
            this.patched = bytes(patched);
            this.expected = expected;
        }
    }

    private static byte[] bytes(int[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    /**
     * Per-arch signature keyed by the wine arch dir ("aarch64-unix" / "x86_64-unix").
     * Only patterns actually derived from a real winebus.so are registered — an
     * unregistered arch is skipped rather than guessed.
     */
    private static Sig signatureFor(String archDir) {
        if ("aarch64-unix".equals(archDir)) {
            // mov w3,w20 ; blr x8   ->   mov w3,#-1 ; blr x8
            return new Sig(
                    new int[]{0xe3, 0x03, 0x14, 0x2a, 0x00, 0x01, 0x3f, 0xd6},
                    new int[]{0x03, 0x00, 0x80, 0x12, 0x00, 0x01, 0x3f, 0xd6},
                    2);
        }
        // No x86_64-unix winebus.so ships in our assets, so no verified pattern exists.
        return null;
    }

    private WinebusRumblePatcher() {}

    /**
     * Scan {@code winebus} for the derived ORIGINAL duration-load pattern and force the
     * rumble duration to never-expire. Idempotent and non-destructive:
     * <ul>
     *   <li>exactly {@code expected} ORIGINAL matches -> patch all of them,</li>
     *   <li>already patched ({@code expected} PATCHED matches, 0 ORIGINAL) -> no-op,</li>
     *   <li>any other count (unknown build / ambiguous) -> log and SKIP, never partial.</li>
     * </ul>
     */
    public static void patchDuration(File winebus, String archDir) {
        Sig sig = signatureFor(archDir);
        if (sig == null) {
            Log.i(TAG, "rumble: no verified duration pattern for " + archDir + ", skipping");
            return;
        }
        if (winebus == null || !winebus.exists() || winebus.length() <= 0) {
            Log.w(TAG, "rumble: winebus missing, skipping: " + winebus);
            return;
        }

        final byte[] data;
        try {
            data = readAll(winebus);
        } catch (IOException e) {
            Log.w(TAG, "rumble: read failed, skipping: " + e);
            return;
        }

        int origCount = count(data, sig.original);
        int patchedCount = count(data, sig.patched);

        if (origCount == 0 && patchedCount == sig.expected) {
            Log.i(TAG, "rumble: already patched (" + patchedCount + " sites), no-op");
            return;
        }
        if (origCount != sig.expected) {
            Log.w(TAG, "rumble: expected " + sig.expected + " duration sites but found "
                    + origCount + " (patched=" + patchedCount + ") in " + archDir
                    + " winebus - ambiguous/unknown build, SKIP");
            return;
        }

        // Exactly the expected number of original sites -> patch every one.
        int patched = replaceAll(data, sig.original, sig.patched);
        try {
            writeAll(winebus, data);
        } catch (IOException e) {
            Log.w(TAG, "rumble: write failed, winebus left unchanged: " + e);
            return;
        }
        Log.i(TAG, "rumble: forced never-expire duration on " + patched
                + " site(s) in " + archDir + " winebus");
    }

    private static int count(byte[] hay, byte[] needle) {
        int n = 0;
        for (int i = indexOf(hay, needle, 0); i >= 0; i = indexOf(hay, needle, i + 1)) n++;
        return n;
    }

    private static int replaceAll(byte[] hay, byte[] needle, byte[] repl) {
        int n = 0;
        for (int i = indexOf(hay, needle, 0); i >= 0; i = indexOf(hay, needle, i + needle.length)) {
            System.arraycopy(repl, 0, hay, i, repl.length);
            n++;
        }
        return n;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        final int last = hay.length - needle.length;
        outer:
        for (int i = Math.max(0, from); i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > Integer.MAX_VALUE) throw new IOException("bad size " + len);
        byte[] buf = new byte[(int) len];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(buf);
        }
        return buf;
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
    }
}
