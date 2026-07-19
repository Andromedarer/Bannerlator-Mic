package com.winlator.star.contents;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;

import com.winlator.star.core.StreamUtils;
import com.winlator.star.core.TarCompressorUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 1 of the Wrapper Version Manager (issue #132) — a fixed-slot updater for our bundled
 * graphics wrappers, mirroring WinlatorMali's ManageGraphicsDriversFragment.
 *
 * There are 6 updatable slots (see {@link #SLOTS}). For each, the user may install an override
 * (a newer .tzst of the SAME name) or reset back to the bundled asset. An override for slot
 * {@code X.tzst} is stored at {@code filesDir/graphics_driver/X.tzst} and, at game launch,
 * {@code XServerDisplayActivity.extractGraphicsDriverFiles()} prefers that file over the bundled
 * asset (per extracted asset file).
 *
 * This class is deliberately additive: it does NOT touch the graphics-driver dropdown,
 * parseIdentifier, Container/Shortcut persistence, or any cascade. Free-form import / rename /
 * delete-cascade and manifest-driven settings are Steps 2/3.
 */
public class WrapperManager {

    private static final String TAG = "WrapperManager";
    /** Subdir (under filesDir) that holds user override .tzst files. Mirrors the launcher path. */
    public static final String OVERRIDE_DIR_NAME = "graphics_driver";
    /** Bundled asset directory prefix. */
    private static final String ASSET_DIR = "graphics_driver/";
    /** Wrapper archives must ship this entry; validated before an override is accepted. */
    private static final String WRAPPER_MARKER_ENTRY = "usr/lib/libvulkan_wrapper.so";

    /** The 6 updatable slots, in display order. extra_libs is a shared-libs payload, not a wrapper. */
    public static final Slot[] SLOTS = new Slot[] {
        new Slot("wrapper.tzst",            "Wrapper (default)",   true),
        new Slot("wrapper-original.tzst",   "Wrapper (original)",  true),
        new Slot("wrapper-legacy.tzst",     "Wrapper (legacy)",    true),
        new Slot("wrapper-leegao.tzst",     "Wrapper (leegao)",    true),
        new Slot("wrapper-gamenative.tzst", "Wrapper (GameNative)", true),
        new Slot("extra_libs.tzst",         "Extra libraries",     false),
    };

    /** Static definition of a slot (file name + friendly label + whether it's a wrapper archive). */
    public static final class Slot {
        public final String fileName;
        public final String label;
        /** True for the wrapper-* archives (validated for the vulkan wrapper marker), false for extra_libs. */
        public final boolean isWrapper;

        Slot(String fileName, String label, boolean isWrapper) {
            this.fileName = fileName;
            this.label = label;
            this.isWrapper = isWrapper;
        }
    }

    /** Runtime view of a slot: static definition + current state (override present, version/notes). */
    public static final class WrapperSlot {
        public final String fileName;
        public final String label;
        public final boolean isWrapper;
        public final boolean isOverridden;
        public final String version;
        public final String notes;

        WrapperSlot(Slot slot, boolean isOverridden, String version, String notes) {
            this.fileName = slot.fileName;
            this.label = slot.label;
            this.isWrapper = slot.isWrapper;
            this.isOverridden = isOverridden;
            this.version = version;
            this.notes = notes;
        }
    }

    private final Context mContext;
    private final File overrideDir;

    public WrapperManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.overrideDir = new File(mContext.getFilesDir(), OVERRIDE_DIR_NAME);
        if (!overrideDir.exists()) overrideDir.mkdirs();
    }

    /** The on-device override file for a slot (may or may not exist). */
    public File overrideFileFor(String fileName) {
        return new File(overrideDir, fileName);
    }

    /** True when the user has installed an override for the given slot. */
    public boolean hasOverride(String fileName) {
        return overrideFileFor(fileName).isFile();
    }

    /**
     * Build the list of slots with current state. Version info is read from the user's OVERRIDE
     * file when present (a small, user-supplied archive). Bundled assets are intentionally NOT
     * scanned here: each is a multi-MB zstd tar and this runs synchronously on screen open, so
     * decompressing all 6 would jank the UI — and our bundled wrappers ship no version.txt today
     * (they'd all read "Unknown" anyway). When we start shipping version.txt in bundled wrappers,
     * move that read off the main thread. See {@link #readVersionInfoFromAsset}.
     */
    public List<WrapperSlot> listSlots() {
        ArrayList<WrapperSlot> out = new ArrayList<>();
        for (Slot slot : SLOTS) {
            boolean overridden = hasOverride(slot.fileName);
            String[] info = overridden
                ? readVersionInfo(overrideFileFor(slot.fileName))
                : new String[] {"Unknown", ""};
            out.add(new WrapperSlot(slot, overridden, info[0], info[1]));
        }
        return out;
    }

    /**
     * Copy the picked file's bytes verbatim to {@code filesDir/graphics_driver/<fileName>}.
     * Validates first that it's a readable zstd tar; for wrapper-* slots additionally that it
     * contains the vulkan wrapper marker. Writes to a temp file then renames, so prior state is
     * left intact on any failure. Returns true on success.
     */
    public boolean installOverride(String fileName, Uri src) {
        Slot slot = slotFor(fileName);
        if (slot == null || src == null) return false;

        if (!overrideDir.exists()) overrideDir.mkdirs();
        File tmp = new File(overrideDir, fileName + ".tmp");
        if (tmp.exists()) tmp.delete();

        // 1. Copy bytes to a temp file.
        try (InputStream in = mContext.getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                Log.d(TAG, "installOverride: could not open source " + src);
                tmp.delete();
                return false;
            }
            if (!StreamUtils.copy(in, out)) {
                Log.d(TAG, "installOverride: copy failed for " + fileName);
                tmp.delete();
                return false;
            }
        }
        catch (IOException | SecurityException e) {
            Log.d(TAG, "installOverride: copy error for " + fileName + " — " + e.getMessage());
            tmp.delete();
            return false;
        }

        // 2. Validate it's a readable zstd tar.
        if (!TarCompressorUtils.isValidArchive(TarCompressorUtils.Type.ZSTD, tmp)) {
            Log.d(TAG, "installOverride: not a readable zstd tar — " + fileName);
            tmp.delete();
            return false;
        }

        // 3. Wrapper slots must contain the vulkan wrapper marker.
        if (slot.isWrapper
                && !TarCompressorUtils.containsEntry(TarCompressorUtils.Type.ZSTD, tmp, WRAPPER_MARKER_ENTRY)) {
            Log.d(TAG, "installOverride: missing " + WRAPPER_MARKER_ENTRY + " — " + fileName);
            tmp.delete();
            return false;
        }

        // 4. Atomically replace.
        File dst = overrideFileFor(fileName);
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            Log.d(TAG, "installOverride: rename failed for " + fileName);
            tmp.delete();
            return false;
        }
        Log.d(TAG, "installOverride: installed override for " + fileName);
        return true;
    }

    /** Delete a single slot's override (revert to bundled). No-op if absent. */
    public void removeOverride(String fileName) {
        File f = overrideFileFor(fileName);
        if (f.exists()) {
            f.delete();
            Log.d(TAG, "removeOverride: reverted " + fileName + " to bundled");
        }
    }

    /** Delete every override among the 6 known slots (revert all to bundled). */
    public void resetAll() {
        for (Slot slot : SLOTS) removeOverride(slot.fileName);
    }

    /**
     * Read an optional {@code version.txt} from inside a .tzst, parsing {@code version:} and
     * {@code notes:} lines. Returns {"Unknown", ""} when the file/entry is missing. Never throws.
     * Index 0 = version, index 1 = notes.
     */
    public String[] readVersionInfo(File tzst) {
        String content = TarCompressorUtils.readTextFile(TarCompressorUtils.Type.ZSTD, tzst, "version.txt");
        return parseVersionInfo(content);
    }

    private String[] readVersionInfoFromAsset(String assetPath) {
        // Stream version.txt straight out of the bundled asset (no extraction / temp copy).
        // Bundled wrappers may not ship version.txt yet -> "Unknown" (no crash).
        String content = TarCompressorUtils.readTextFile(TarCompressorUtils.Type.ZSTD, mContext, assetPath, "version.txt");
        return parseVersionInfo(content);
    }

    private static String[] parseVersionInfo(String content) {
        String version = "Unknown";
        String notes = "";
        if (content != null) {
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.regionMatches(true, 0, "version:", 0, 8)) {
                    version = trimmed.substring(8).trim();
                } else if (trimmed.regionMatches(true, 0, "notes:", 0, 6)) {
                    notes = trimmed.substring(6).trim();
                }
            }
            if (version.isEmpty()) version = "Unknown";
        }
        return new String[] {version, notes};
    }

    private static Slot slotFor(String fileName) {
        for (Slot slot : SLOTS) if (slot.fileName.equals(fileName)) return slot;
        return null;
    }

    /** Whether a bundled asset exists for the slot (defensive; the 6 are always shipped). */
    public boolean hasBundledAsset(String fileName) {
        AssetManager am = mContext.getAssets();
        try (InputStream is = am.open(ASSET_DIR + fileName)) {
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }
}
