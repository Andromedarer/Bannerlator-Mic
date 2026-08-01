package com.winlator.star.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import java.io.File

/**
 * A persistent, local-only save vault for CUSTOM (non-Steam) games — the counterpart of the Steam
 * auto-Collect-on-exit. Each game keeps ONE current snapshot keyed by its stable identity, living on
 * external storage independent of the container/install, so it survives the shortcut (and even the
 * game) being removed. No cloud is ever involved.
 *
 * Layout: every custom game's backups — manual AND this auto-on-exit vault — share ONE per-game
 * folder, `Downloads/Bannerlator/game saves/<GameName>/` (see [perGameDir]). The auto snapshot is a
 * single `auto-latest.zip` overwritten each exit; the manual flow drops timestamped
 * `<GameName>_<epoch>.zip` siblings (history). The zip is a [GameSaveBackup.BackupLayout.WINLATOR]
 * archive, so it restores through the same [GameSaveBackup.restore] path used elsewhere.
 *
 * Everything here is best-effort and never throws to the caller; the blocking work ([snapshot]) is
 * synchronous so callers can run it on their own worker thread and bound-wait on it.
 */
object CustomSaveVault {

    private const val TAG = "BH_SAVE_SYNC"

    /** Filename of the single overwrite-on-exit auto snapshot inside a game's [perGameDir]. */
    private const val AUTO_LATEST = "auto-latest.zip"

    /**
     * The one per-game backup folder shared by the manual "Back up saves" flow and the auto vault:
     * `Downloads/Bannerlator/game saves/<sanitized game name>/`. Public so the manual flow writes its
     * timestamped zips into the same place as the auto snapshot.
     */
    fun perGameDir(gameName: String): File =
        File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Bannerlator/game saves",
            ),
            sanitize(gameName),
        )

    /** The timestamped manual-backup file for [gameName]: `<perGameDir>/<GameName>_<epoch>.zip`. */
    fun perGameBackupFile(gameName: String, epochMillis: Long): File =
        File(perGameDir(gameName), "${sanitize(gameName)}_$epochMillis.zip")

    /** Filesystem-safe per-game identity key (wmClass when present, else the shortcut filename). */
    fun key(shortcut: Shortcut): String =
        sanitize(SaveLocator.gameKey(shortcut.wmClass, shortcut.file.name))

    /** The auto-on-exit snapshot file for this game (one current backup, overwritten each exit). */
    private fun vaultFile(shortcut: Shortcut): File = File(perGameDir(shortcut.name), AUTO_LATEST)

    /** Whether this game already has a vault snapshot. */
    fun hasBackup(shortcut: Shortcut): Boolean = vaultFile(shortcut).isFile

    /** Last snapshot time (epoch millis), or 0 if none. */
    fun backupTimeMillis(shortcut: Shortcut): Long {
        val f = vaultFile(shortcut)
        return if (f.isFile) f.lastModified() else 0L
    }

    data class VaultResult(val ok: Boolean, val fileCount: Int, val path: String?, val error: String?)

    /**
     * Snapshot this game's saves into `<vault>/<key>.zip`, overwriting any previous snapshot.
     * Discovers the game's save roots via [SaveLocator] (persisted sidecar first, else a fresh
     * discover); if none are found this is a no-op ("no saves"). SYNCHRONOUS — run off the main
     * thread. Never throws.
     *
     * Writes to a temp sibling then atomically renames over the current zip, so a killed process (the
     * emulator restarts on exit) can never leave a half-written snapshot in place of the last good one.
     */
    fun snapshot(context: Context, container: Container, shortcut: Shortcut): VaultResult {
        return try {
            val gameKey = SaveLocator.gameKey(shortcut.wmClass, shortcut.file.name)
            val roots: List<String> = run {
                val saved = SaveLocator.loadMap(container, gameKey)
                if (saved != null && saved.roots.isNotEmpty()) saved.roots
                else SaveLocator.discover(container, shortcut.name, shortcut.path ?: "", shortcut.wmClass ?: "")
                    .map { it.relPath }
            }
            if (roots.isEmpty()) return VaultResult(false, 0, null, "no saves")

            val out = vaultFile(shortcut)
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".tmp")

            val res = GameSaveBackup.backupToFile(container, roots, GameSaveBackup.BackupLayout.WINLATOR, tmp)
            if (!res.ok) {
                tmp.delete()
                return VaultResult(false, res.fileCount, null, res.error)
            }

            // Overwrite the current snapshot atomically (rename; copy-fallback if rename is refused).
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "vault snapshot \"${shortcut.name}\" → ${res.fileCount} files (${out.name})")
            VaultResult(true, res.fileCount, out.absolutePath, null)
        } catch (t: Throwable) {
            Log.w(TAG, "vault snapshot failed for \"${shortcut.name}\"", t)
            VaultResult(false, 0, null, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Restore this game's latest vault snapshot into [targetContainer], reusing [GameSaveBackup.restore]
     * (off the UI thread; posts [onResult] on the main thread). If there is no snapshot, reports a
     * failure result rather than doing nothing silently.
     */
    fun restoreLatest(
        context: Context,
        shortcut: Shortcut,
        targetContainer: Container,
        onResult: (GameSaveBackup.RestoreResult) -> Unit,
    ) {
        val f = vaultFile(shortcut)
        if (!f.isFile) {
            Handler(Looper.getMainLooper()).post {
                onResult(GameSaveBackup.RestoreResult(false, 0, "No vault backup for this game"))
            }
            return
        }
        GameSaveBackup.restore(context, Uri.fromFile(f), targetContainer, onResult)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "game" }
}
