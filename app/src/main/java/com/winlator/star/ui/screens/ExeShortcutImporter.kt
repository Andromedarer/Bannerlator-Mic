package com.winlator.star.ui.screens

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.FileUtils
import com.winlator.star.core.WinePath
import com.winlator.star.store.StarLaunchBridge
import java.io.File

/**
 * Single source of truth for turning a Windows executable on disk into a permanent
 * Games/Shortcuts entry (a `.desktop` in the container's desktop dir). Shared by the
 * Games-tab "+" importer ([ShortcutsViewModel.importExe]) and the File Manager's
 * "Add to shortcuts" action so the two never drift.
 */
internal object ExeShortcutImporter {
    private const val TAG = "ExeShortcutImporter"

    /**
     * Write a permanent shortcut for [exeFile] into [container]'s desktop dir and return the
     * `.desktop` file. Cover-art / icon resolution runs on a background thread (SGDB lookup →
     * PE-icon fallback); [onCoverArtReady] fires off the main thread once that finishes so
     * callers can refresh their list. Throws [java.io.IOException] if the shortcut can't be written.
     */
    fun addToShortcuts(
        context: Context,
        container: Container,
        exeFile: File,
        displayName: String,
        steamAppId: Int? = null,
        onCoverArtReady: () -> Unit = {},
    ): File {
        val shortcutFile = writeExeShortcut(container, exeFile, displayName)
        // Cover art on a background thread — SteamGridDB lookup involves network I/O.
        // Fallback chain: exact SGDB-by-Steam-appid (when known) → SGDB name search → PE icon.
        val safeName = shortcutFile.nameWithoutExtension
        val appCtx = context.applicationContext
        Thread({
            try {
                StarLaunchBridge.saveCoverArt(appCtx, container, shortcutFile, safeName, null, steamAppId)
                val iconFile = container.getIconsDir(64)?.let { File(it, "$safeName.png") }
                if (iconFile == null || !iconFile.exists()) {
                    // SGDB miss — try extracting an icon from the EXE itself.
                    ExeIconExtractor.extract(exeFile)?.let { bmp ->
                        container.getIconsDir(64)?.let { iconsDir ->
                            if (!iconsDir.exists()) iconsDir.mkdirs()
                            FileUtils.saveBitmapToFile(bmp, File(iconsDir, "$safeName.png"))
                        }
                        try {
                            Shortcut(container, shortcutFile).saveCustomCoverArt(bmp)
                        } catch (e: Exception) {
                            Log.w(TAG, "saveCustomCoverArt failed for $safeName", e)
                        }
                        Log.d(TAG, "PE icon extraction succeeded for $safeName")
                    }
                }
                onCoverArtReady()
            } catch (e: Exception) {
                Log.w(TAG, "Cover art lookup failed for $safeName", e)
            }
        }, "exe-import-cover-art").start()
        return shortcutFile
    }

    private fun writeExeShortcut(container: Container, exeFile: File, displayName: String): File {
        val desktopDir = container.getDesktopDir()
        if (!desktopDir.exists()) desktopDir.mkdirs()

        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "game" }
        val shortcutFile = File(desktopDir, "$safeName.desktop")

        // Resolve to a Wine drive letter against the container's mount map. Z: would
        // map to imagefs root (chroot view) and not reach external storage, so we use
        // F:/D:/etc. as defined in container.drives. If no existing drive contains the
        // EXE path we add and persist a new letter pointing at the parent directory.
        val winPath = WinePath.resolveWindowsPath(container, exeFile.absolutePath)
        // 4-backslash separators per Winlator's two-pass StringUtils.unescape().
        val escaped = WinePath.escapeForExec(winPath)
        val content = buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(displayName).append("\n")
            append("Exec=wine ").append(escaped).append("\n")
            append("Icon=").append(safeName).append("\n")
            append("Type=Application\n")
            append("StartupWMClass=explorer\n")
            append("\n")
            append("[Extra Data]\n")
        }
        shortcutFile.writeText(content)
        Log.d(TAG, "Wrote EXE shortcut: ${shortcutFile.path} -> $winPath ($exeFile)")
        return shortcutFile
    }
}
