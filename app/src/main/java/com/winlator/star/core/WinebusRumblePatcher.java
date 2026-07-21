package com.winlator.star.core;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent runtime byte-patch for winebus.so ("TideGear #91 / preload-free winebus
 * duration patch").
 *
 * <p>SDL2's rumble APIs auto-expire the effect after the caller-supplied duration_ms.
 * winebus' {@code sdl_device_haptics_start} passes a real (short) duration when it drives
 * {@code SDL_JoystickRumble} / {@code SDL_JoystickRumbleTriggers} through the SDL2 pointers
 * it {@code dlsym}'d, so a held rumble dies after ~1s. This forces the 4th integer arg
 * (duration_ms, {@code w3} on aarch64) of those two indirect calls to 0xffffffff (-1) so
 * SDL2 never auto-stops it.
 *
 * <p><b>The stable shape (all aarch64 builds).</b> At both rumble call sites the compiler
 * sets the 4th arg with {@code mov w3,wN} (ORR-shifted-reg, encoding {@code E3 03 Nx 2A}
 * where {@code Nx} carries the source register) immediately before the indirect
 * {@code blr x8} ({@code 00 01 3F D6}). We rewrite the {@code mov w3,wN} to
 * {@code mov w3,#-1} ({@code movn w3,#0} = {@code 03 00 80 12}) and leave {@code blr x8}
 * intact. {@code mov w3,wzr} ({@code Nx = 0x1f}) is the zero-duration / stop shape and is
 * NEVER matched.
 *
 * <p><b>CODE-DERIVED patterns (exact, per Proton build):</b>
 * <pre>
 *   Proton 9.0 arm64ec (bundled, 220176 B): mov w3,w20  E3 03 14 2A 00 01 3F D6  x2
 *   Proton 10.0-x arm64ec (content pack):   mov w3,w19  E3 03 13 2A 00 01 3F D6  x2
 *   Proton 11.0-x arm64ec (content pack):   mov w3,w19  E3 03 13 2A 00 01 3F D6  x2
 *   patched (all):                          mov w3,#-1  03 00 80 12 00 01 3F D6
 * </pre>
 * P10 and P11 share identical rumble-site bytes; the two exact patterns above cover all
 * three shipped/downloadable arm64ec Protons. Each matches EXACTLY its 2 sites in its own
 * build and 0 in the others (cross-checked).
 *
 * <p><b>Build-agnostic structural fallback.</b> To avoid hand-deriving a pattern for every
 * future Proton / GE variant / imported wrapper, if none of the exact patterns matched
 * exactly 2 sites we run a masked structural scan for {@code mov w3,w<0..30> ; blr x8}
 * (source register wildcarded, {@code wzr} excluded, {@code blr x8} required). It is applied
 * ONLY if it matches EXACTLY 2 sites; 0 or &gt;2 = ambiguous -&gt; SKIP. This same shape
 * yields exactly 2 sites on P9/P10/P11 (the 3 {@code mov w3,wzr ; blr x8} stop-shaped
 * sites are excluded), so it would cover them even without the exact patterns.
 *
 * <p><b>Residual risk (accepted).</b> A build containing exactly two UNRELATED
 * {@code mov w3,w<reg> ; blr x8} sequences would be mis-patched by the fallback. The
 * exact patterns are tried first (and short-circuit) to avoid this on known builds, and the
 * {@code == 2} guard is the mitigation on unknown ones; this is a deliberate tradeoff versus
 * enumerating every compat layer. Everything is idempotent and non-destructive: once patched
 * the sites read {@code mov w3,#-1 ; blr x8} and re-running is a no-op; any ambiguous count
 * logs and changes nothing (never a partial patch).
 */
public final class WinebusRumblePatcher {
    private static final String TAG = "Evshim";

    private static final int EXPECTED = 2;

    // mov w3,#-1 (movn w3,#0) ; blr x8  -- the patched form of any duration-load site.
    private static final byte[] BLR_X8 = bytes(new int[]{0x00, 0x01, 0x3f, 0xd6});
    private static final byte[] PATCHED_MOV = bytes(new int[]{0x03, 0x00, 0x80, 0x12});
    private static final byte[] PATCHED_SITE = concat(PATCHED_MOV, BLR_X8);

    /** An exact, build-specific fingerprint: "set duration_ms ; blr x8". */
    private static final class Sig {
        final String name;
        final byte[] original;
        Sig(String name, int[] movBytes) {
            this.name = name;
            this.original = concat(bytes(movBytes), BLR_X8);
        }
    }

    /**
     * Exact fingerprints per wine arch dir ("aarch64-unix" / "x86_64-unix"), newest builds
     * first. Only patterns derived from a real winebus.so are registered.
     */
    private static List<Sig> exactSignaturesFor(String archDir) {
        List<Sig> sigs = new ArrayList<>();
        if ("aarch64-unix".equals(archDir)) {
            // Proton 10 / 11 arm64ec (content packs): mov w3,w19
            sigs.add(new Sig("Proton 10/11 (mov w3,w19)", new int[]{0xe3, 0x03, 0x13, 0x2a}));
            // Proton 9.0 arm64ec (bundled): mov w3,w20
            sigs.add(new Sig("Proton 9.0 (mov w3,w20)", new int[]{0xe3, 0x03, 0x14, 0x2a}));
        }
        // No x86_64-unix winebus.so ships in our assets, so no verified pattern exists there.
        return sigs;
    }

    private WinebusRumblePatcher() {}

    /**
     * Force the SDL rumble duration to never-expire in {@code winebus}. Tries the exact
     * per-build patterns first, then a build-agnostic structural fallback. Idempotent and
     * non-destructive; only ever patches when EXACTLY 2 sites are identified.
     */
    public static void patchDuration(File winebus, String archDir) {
        List<Sig> sigs = exactSignaturesFor(archDir);
        if (sigs.isEmpty()) {
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

        // Idempotency: our patch leaves exactly EXPECTED "mov w3,#-1 ; blr x8" sites.
        int patchedCount = count(data, PATCHED_SITE);
        if (patchedCount == EXPECTED) {
            Log.i(TAG, "rumble: already patched (" + patchedCount + " sites), no-op");
            return;
        }

        // 1) Exact per-build patterns. Apply the FIRST that matches exactly EXPECTED sites.
        for (Sig sig : sigs) {
            int oc = count(data, sig.original);
            if (oc == EXPECTED) {
                replaceAll(data, sig.original, PATCHED_SITE);
                if (writeBack(winebus, data)) {
                    Log.i(TAG, "rumble: forced never-expire duration on " + EXPECTED
                            + " site(s) via exact pattern [" + sig.name + "] in " + archDir);
                }
                return;
            }
        }

        // 2) Build-agnostic structural fallback (only reached when no exact pattern hit
        //    exactly EXPECTED). Masked "mov w3,w<0..30> ; blr x8".
        List<Integer> sites = findStructuralSites(data);
        if (sites.size() != EXPECTED) {
            Log.w(TAG, "rumble: structural fallback found " + sites.size() + " site(s) (patched="
                    + patchedCount + ") in " + archDir + " winebus - ambiguous/unknown, SKIP");
            return;
        }
        for (int off : sites) System.arraycopy(PATCHED_SITE, 0, data, off, PATCHED_SITE.length);
        if (writeBack(winebus, data)) {
            Log.i(TAG, "rumble: forced never-expire duration on " + EXPECTED
                    + " site(s) via structural fallback in " + archDir + " winebus");
        }
    }

    /**
     * Positions of every "mov w3,w&lt;N&gt; ; blr x8" where the mov is ORR-shifted-reg into w3
     * (Rd=3, Rn=wzr, LSL#0) with a REAL source register (N != 31/wzr, so the zero-duration
     * stop shape is excluded).
     */
    private static List<Integer> findStructuralSites(byte[] d) {
        List<Integer> out = new ArrayList<>();
        final int last = d.length - 8;
        for (int i = 0; i <= last; i++) {
            // mov w3,wN  ==  E3 03 Nx 2A, with Nx = Rm in bits[20:16]; top 3 bits (23:21) = 0.
            if (d[i] != (byte) 0xe3 || d[i + 1] != 0x03 || d[i + 3] != 0x2a) continue;
            int rm = d[i + 2] & 0xff;
            if ((rm & 0xe0) != 0) continue;      // bits 23:21 must be 0 (plain LSL#0 ORR)
            if ((rm & 0x1f) == 0x1f) continue;   // exclude wzr (zero duration)
            // ...immediately followed by blr x8
            if (d[i + 4] == BLR_X8[0] && d[i + 5] == BLR_X8[1]
                    && d[i + 6] == BLR_X8[2] && d[i + 7] == BLR_X8[3]) {
                out.add(i);
            }
        }
        return out;
    }

    private static boolean writeBack(File f, byte[] data) {
        try {
            writeAll(f, data);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "rumble: write failed, winebus left unchanged: " + e);
            return false;
        }
    }

    private static byte[] bytes(int[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int count(byte[] hay, byte[] needle) {
        int n = 0;
        for (int i = indexOf(hay, needle, 0); i >= 0; i = indexOf(hay, needle, i + 1)) n++;
        return n;
    }

    private static void replaceAll(byte[] hay, byte[] needle, byte[] repl) {
        for (int i = indexOf(hay, needle, 0); i >= 0; i = indexOf(hay, needle, i + needle.length)) {
            System.arraycopy(repl, 0, hay, i, repl.length);
        }
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
