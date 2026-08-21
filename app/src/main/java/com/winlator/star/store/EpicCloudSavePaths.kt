package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.SaveLocator
import java.io.File

/**
 * Epic cloud-save path resolution — the Epic peer of [SteamCloudSavePaths].
 *
 * Two jobs:
 *   1. [resolveContainer] — find the Wine container a given Epic game launches in, adapting the
 *      PATTERN of [SteamCloudSavePaths.resolveContainer]. Epic shortcuts are stamped
 *      `storeSource=epic` + `epicAppName=<appName>` by [StarLaunchBridge], so we match that extra
 *      directly (exact), falling back to install-dir path-matching for shortcuts written before the
 *      tag existed.
 *   2. [resolveSaveDirectory] — expand a game's Epic **CloudSaveFolder** token string
 *      (`customAttributes.CloudSaveFolder`, persisted per-game in `bh_epic_prefs` at
 *      `epic_save_folder_<appName>`) into a concrete directory inside the chosen container's Wine
 *      prefix. This is a clean-room reimplementation of the behaviour of Epic's launcher / Legendary's
 *      `resolve_save_path` — reimplemented from the documented token semantics, NOT copied from the
 *      GPL-3.0 GameNative/Legendary source.
 *
 * ── Token semantics (verified against Epic/Legendary, 2026-08) ────────────────────────────────
 * Epic's `{appdata}` maps to **%LOCALAPPDATA%** (AppData/Local), NOT Roaming — Legendary's
 * `resolve_save_path` expands `{appdata}` → `%LOCALAPPDATA%`. So both `{appdata}` and `{localappdata}`
 * resolve to AppData/Local here (this matches GameNative — which is correct in this instance, and
 * confirmed against Legendary rather than trusted blindly). Roaming AppData has its own explicit
 * `{roamingappdata}` token.
 *
 * SAFETY: every resolve rejects `..` escape and refuses any result that canonicalizes outside the
 * container's Wine prefix — mirrors [SteamCloudSavePaths.toContainerPath]. Returns null (⇒ caller
 * skips / reports "can't resolve") rather than guessing.
 */
object EpicCloudSavePaths {

    private const val TAG = "BH_EPIC_CLOUD"
    private const val PREFS_NAME = "bh_epic_prefs"

    /** The persisted CloudSaveFolder token string for a game (written from the catalog parse). */
    fun cloudSaveFolder(ctx: Context, appName: String): String? =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("epic_save_folder_$appName", null)
            ?.takeIf { it.isNotBlank() }

    /** Absolute on-device install dir for a game (`imagefs/epic_games/<sanitized>`), or null. */
    fun installDir(ctx: Context, appName: String): File? =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("epic_dir_$appName", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }

    // ── Container resolution ──────────────────────────────────────────────────────

    /**
     * The Wine container this Epic game launches in. Primary match: a shortcut tagged
     * `storeSource=epic` whose `epicAppName` extra equals [appName] (exact — the shortcut
     * [StarLaunchBridge] wrote at launch). Fallback: a shortcut whose exec target points into the
     * game's install dir (the [SteamCloudSavePaths.resolveContainer] pattern), for shortcuts written
     * before the epicAppName tag. Null ⇒ the game isn't set up in a container yet.
     */
    fun resolveContainer(ctx: Context, appName: String, installDir: String?): Container? {
        val shortcuts = try { ContainerManager(ctx).loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "loadShortcuts failed", e); return null
        }
        return matchContainer(ctx, shortcuts, appName, installDir)
    }

    /** [resolveContainer] over a pre-loaded shortcut list — so a batch ([listStatuses]) loads once. */
    private fun matchContainer(
        ctx: Context,
        shortcuts: List<Shortcut>,
        appName: String,
        installDir: String?,
    ): Container? {
        // Primary: exact epicAppName tag.
        for (sc in shortcuts) {
            if (sc.getExtra("storeSource") == "epic" && sc.getExtra("epicAppName") == appName) {
                return sc.container
            }
        }

        // Fallback: install-dir path match (mirrors SteamCloudSavePaths.resolveContainer).
        if (installDir.isNullOrBlank()) return null
        val imageFsRoot = File(ctx.filesDir, "imagefs").absolutePath.replace('\\', '/').trimEnd('/')
        val instAbs = installDir.replace('\\', '/').trimEnd('/')
        val instRel = if (instAbs.lowercase().startsWith(imageFsRoot.lowercase()))
            instAbs.substring(imageFsRoot.length).trimStart('/') else instAbs.trimStart('/')
        val keys = listOf("/${instRel.lowercase()}/", "/${instAbs.trimStart('/').lowercase()}/")

        for (sc in shortcuts) {
            val raw = sc.path ?: continue
            var exec = raw.replace('\\', '/').lowercase().trim()
            exec = exec.replaceFirst(Regex("^[a-z]:"), "")
            if (!exec.startsWith("/")) exec = "/$exec"
            if (keys.any { it.length > 2 && exec.contains(it) }) return sc.container
        }
        return null
    }

    /** Human label for dialogs/rows, e.g. "Container 2 — Default". Mirrors the Steam helper. */
    fun containerLabel(container: Container): String {
        val name = container.name
        return if (!name.isNullOrBlank()) "Container ${container.id} — $name"
        else "Container ${container.id}"
    }

    // ── Save Manager Epic-tab listing (models CustomSaveVault.CustomGameStatus / .listStatuses) ──

    /** Per-installed-Epic-game row for the Save Manager's Epic tab. */
    data class EpicSaveStatus(
        val appName: String,
        val name: String,
        /** The launch container's label, or null when the game isn't set up in a container yet. */
        val containerLabel: String?,
        /** True when the catalog reported a CloudSaveFolder for this game (sync is meaningful). */
        val cloudSaveEnabled: Boolean,
        /** Last successful sync (epoch millis) from `epic_sync_timestamp_`, or 0 if never. */
        val lastSyncMillis: Long,
    )

    /**
     * Enumerate every installed Epic game (`epic_exe_<appName>` keys in `bh_epic_prefs`) and build
     * its Epic-tab status: display name (from the `epic_cache` metadata), launch container, whether
     * it's cloud-save enabled, and its last-sync time. BLOCKING (loads shortcuts once) — call off the
     * main thread. Sorted cloud-enabled-first, then by name.
     */
    fun listStatuses(ctx: Context): List<EpicSaveStatus> {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appNames = prefs.all.keys
            .filter { it.startsWith("epic_exe_") }
            .map { it.removePrefix("epic_exe_") }
            .filter { it.isNotEmpty() }
            .distinct()
        if (appNames.isEmpty()) return emptyList()

        val shortcuts: List<Shortcut> = try { ContainerManager(ctx).loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "listStatuses: loadShortcuts failed", e); emptyList()
        }

        return appNames.map { an ->
            val detail = EpicLibrarySync.cachedDetail(ctx, an)
            val title = detail?.title?.takeIf { it.isNotBlank() } ?: an
            val installDir = prefs.getString("epic_dir_$an", null)
            val container = matchContainer(ctx, shortcuts, an, installDir)
            EpicSaveStatus(
                appName = an,
                name = title,
                containerLabel = container?.let {
                    it.name?.takeIf { n -> n.isNotBlank() } ?: "Container ${it.id}"
                },
                cloudSaveEnabled = cloudSaveFolder(ctx, an) != null,
                lastSyncMillis = parseIso8601Ms(prefs.getString("epic_sync_timestamp_$an", null)),
            )
        }.sortedWith(compareBy({ !it.cloudSaveEnabled }, { it.name.lowercase() }))
    }

    /** Best-effort parse of an ISO-8601 UTC "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" marker → epoch millis (0 if none). */
    private fun parseIso8601Ms(s: String?): Long {
        if (s.isNullOrBlank() || s.length < 19) return 0L
        return try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(
                s.substring(0, 4).toInt(), s.substring(5, 7).toInt() - 1, s.substring(8, 10).toInt(),
                s.substring(11, 13).toInt(), s.substring(14, 16).toInt(), s.substring(17, 19).toInt(),
            )
            cal.timeInMillis
        } catch (e: Exception) { 0L }
    }

    // ── Save-directory resolution (the prize) ─────────────────────────────────────

    /**
     * Resolve a game's CloudSaveFolder token string into the concrete directory to sync, inside
     * [container]'s Wine prefix. Requires the persisted `epic_save_folder_<appName>` string. Null if
     * that string is missing, the leading token is unknown/unsupported, the token needs a container
     * we don't have, the remainder is unsafe (`..`), or the result would escape the prefix.
     *
     * After resolving, descends into the first non-empty per-user subdirectory when the resolved
     * folder itself holds no files but wraps one or more populated sub-folders (some Epic titles nest
     * saves under an unnamed per-user/account id folder the token string doesn't spell out).
     */
    fun resolveSaveDirectory(ctx: Context, appName: String, container: Container?): File? {
        val raw = cloudSaveFolder(ctx, appName) ?: run {
            Log.w(TAG, "resolveSaveDirectory: no CloudSaveFolder for $appName"); return null
        }
        // '\'→'/', drop a leading slash; split leading {token} from the literal remainder.
        val norm = raw.replace('\\', '/').trim().trimStart('/')
        val tokenMatch = Regex("^\\{([^}]+)\\}").find(norm)
        if (tokenMatch == null) {
            Log.w(TAG, "resolveSaveDirectory: no leading token in CloudSaveFolder '$raw'"); return null
        }
        val token = tokenMatch.groupValues[1].lowercase()

        val accountId = EpicCredentialStore.load(ctx)?.accountId ?: ""

        // Leading token → base directory. Profile-relative tokens need a container.
        val base: File = when (token) {
            "installdir" -> installDir(ctx, appName) ?: run {
                Log.w(TAG, "resolveSaveDirectory: {installdir} but no install dir for $appName"); return null
            }
            "userprofile" -> profileOrNull(container) ?: return null
            "userdir" -> profileOrNull(container)?.let { File(it, "Documents") } ?: return null
            "usersavedgames" -> profileOrNull(container)?.let { File(it, "Saved Games") } ?: return null
            // Epic's {appdata} == %LOCALAPPDATA% (verified against Legendary) → AppData/Local.
            "appdata", "localappdata" -> profileOrNull(container)?.let { File(it, "AppData/Local") } ?: return null
            "roamingappdata" -> profileOrNull(container)?.let { File(it, "AppData/Roaming") } ?: return null
            else -> {
                Log.w(TAG, "resolveSaveDirectory: unknown token '{$token}' in '$raw'"); return null
            }
        }

        // Remainder after the leading token; expand inline {epicid}/{appname}, guard '..'.
        var remainder = norm.substring(tokenMatch.value.length).trimStart('/')
        remainder = remainder
            .replace("{epicid}", accountId, ignoreCase = true)
            .replace("{appname}", appName, ignoreCase = true)
        val segments = remainder.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            Log.w(TAG, "resolveSaveDirectory: rejecting '..' in '$raw'"); return null
        }

        // Walk each segment case-insensitively against what's actually on disk (Windows apps write
        // AppData/appdata/"Saved Games" with varying case onto our case-sensitive ext4 prefix), while
        // still allowing a not-yet-created path to resolve to its literal name (download creates it).
        var dir = base
        for (seg in segments) {
            val existing = dir.listFiles()?.firstOrNull { it.name.equals(seg, ignoreCase = true) }
            dir = existing ?: File(dir, seg)
        }

        // Escape guard: canonical dest must be the base or strictly under it.
        val baseCanon = try { base.canonicalPath } catch (e: Exception) { return null }
        val destCanon = try { dir.canonicalPath } catch (e: Exception) { return null }
        if (destCanon != baseCanon && !destCanon.startsWith(baseCanon + File.separator)) {
            Log.w(TAG, "resolveSaveDirectory: path escapes base for '$raw'"); return null
        }

        return descendToUserSubdir(dir)
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun profileOrNull(container: Container?): File? =
        container?.let { SaveLocator.profileDir(it) }

    /**
     * Some Epic titles nest their saves one level deeper, under an unnamed per-user / account-id
     * folder the CloudSaveFolder string doesn't spell out. If [dir] exists and holds no regular files
     * but does contain a populated sub-directory, descend into the first such sub-directory; otherwise
     * return [dir] unchanged. Best-effort — never throws.
     */
    private fun descendToUserSubdir(dir: File): File {
        return try {
            val children = dir.listFiles() ?: return dir
            val hasFiles = children.any { it.isFile }
            if (hasFiles) return dir
            val firstNonEmptyDir = children
                .filter { it.isDirectory }
                .firstOrNull { (it.listFiles()?.isNotEmpty() == true) }
            firstNonEmptyDir ?: dir
        } catch (t: Throwable) {
            dir
        }
    }
}
