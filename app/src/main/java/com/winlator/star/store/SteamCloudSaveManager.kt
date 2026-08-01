package com.winlator.star.store

import android.content.Context
import android.os.Build
import android.util.Log
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Steam Cloud (UFS) per-game save up/download manager.
 *
 * Mirrors the shape of [GogCloudSaveManager]: a stateless helper with two directional static-style
 * entry points ([downloadSaves] cloud -> local, [uploadSaves] local -> cloud) plus a [Callback].
 * Each op runs on its own background thread and reports progress via the callback.
 *
 * Uses JavaSteam's [SteamCloud] handler (obtained from [SteamRepository.getSteamCloud]) which speaks
 * the real Steam UFS protocol:
 *   - [SteamCloud.getAppFileListChange] -> the remote file manifest for an app
 *   - [SteamCloud.clientFileDownload]   -> a signed CDN URL to GET one cloud file
 *   - [SteamCloud.beginAppUploadBatch] / [SteamCloud.beginFileUpload] /
 *     [SteamCloud.commitFileUpload] / [SteamCloud.completeAppUploadBatch] -> the upload handshake
 *
 * SAFETY — cloud saves can never be deleted by this class. See [uploadSaves]: `filesToDelete` is
 * hard-wired to an empty list on every code path, and an empty local folder is refused before any
 * batch is opened. There is no code path that computes or sends a deletion to the cloud.
 */
object SteamCloudSaveManager {

    private const val TAG = "BH_STEAM_CLOUD"

    /** Blocking timeout for each JavaSteam CM future (list / begin / commit / complete). */
    private const val FUTURE_TIMEOUT_SEC = 60L

    /** HTTP connect/read timeout for CDN block GET/PUT. */
    private const val HTTP_TIMEOUT_MS = 60_000

    interface Callback {
        fun onStatus(message: String)
        fun onDone(summary: String)
        fun onError(message: String)
    }

    // ── Cloud -> local ────────────────────────────────────────────────────────
    // Only ever writes to the local filesystem. It reads the remote manifest and GETs files.
    // It is STRUCTURALLY incapable of modifying the cloud: no upload/commit/delete calls are made.

    /** Download every cloud save file for [appId] into [localFolder], preserving the cloud path
     *  layout. Overwrites local copies; never touches the cloud. */
    fun downloadSaves(ctx: Context, appId: Int, localFolder: File, cb: Callback) {
        Thread({
            try {
                val steamCloud = requireCloud() ?: run { cb.onError("Not signed in to Steam"); return@Thread }

                cb.onStatus("Fetching cloud file list…")
                val fileList: AppFileChangeList =
                    steamCloud.getAppFileListChange(appId).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

                val files = fileList.files
                if (files.isEmpty()) { cb.onDone("No cloud saves found for this game"); return@Thread }

                if (!localFolder.exists()) localFolder.mkdirs()

                var downloaded = 0
                var skipped = 0
                for (f in files) {
                    val remotePath = remotePathOf(f, fileList)
                    val relLocal = sanitizeRelative(remotePath)
                    if (relLocal == null) {
                        Log.w(TAG, "Skipping unsafe cloud path: $remotePath")
                        skipped++
                        continue
                    }
                    cb.onStatus("Downloading: ${f.filename}")
                    val ok = downloadOne(steamCloud, appId, remotePath, File(localFolder, relLocal))
                    if (!ok) { cb.onError("Download failed for: ${f.filename}"); return@Thread }
                    downloaded++
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Downloaded $downloaded file${plural(downloaded)}$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "downloadSaves failed", e)
                cb.onError("Download error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-download-$appId").start()
    }

    // ── Local -> cloud ────────────────────────────────────────────────────────
    // STRICTLY ADDITIVE. Enumerates local files and uploads/overwrites them. `filesToDelete` is
    // ALWAYS an empty list, so the cloud can only gain/refresh files — never lose them. An empty
    // local folder is refused up-front, so we never even open a batch for a wipe.

    /** Upload every file under [localFolder] to [appId]'s Steam Cloud, additively. Never deletes
     *  anything from the cloud. Refuses (no-op) if the local folder has no files. */
    fun uploadSaves(ctx: Context, appId: Int, localFolder: File, cb: Callback) {
        Thread({
            try {
                val steamCloud = requireCloud() ?: run { cb.onError("Not signed in to Steam"); return@Thread }

                cb.onStatus("Scanning local saves…")
                val localFiles = enumerateLocal(localFolder)   // List<Pair<File, cloudRelPath>>

                // ── BELT-AND-SUSPENDERS GUARD ──────────────────────────────────────────
                // If there is nothing local to upload we return immediately and NEVER call
                // beginAppUploadBatch. This makes an empty/absent save folder a pure no-op and
                // removes any chance of an "upload nothing" turning into a cloud wipe.
                if (localFiles.isEmpty()) {
                    cb.onDone("No local save files found — nothing was sent to the cloud")
                    return@Thread
                }

                val filesToUpload: List<String> = localFiles.map { it.second }

                // filesToDelete is ALWAYS empty. This is the single source of truth for the
                // "never delete from cloud" guarantee — it is a literal emptyList() and is never
                // populated from local/remote diffs anywhere in this class.
                val filesToDelete: List<String> = emptyList()

                cb.onStatus("Opening cloud upload batch…")
                // Signature (JavaSteam 1.8.0):
                //   beginAppUploadBatch(appId, machineName, filesToUpload, filesToDelete, clientId, appBuildId)
                // clientId/appBuildId are best-effort 0L (classic token logon exposes no auth-session
                // clientID, and we don't parse the installed build id). See report notes.
                val batch = steamCloud.beginAppUploadBatch(
                    appId,
                    machineName(),
                    filesToUpload,
                    filesToDelete,   // ← always empty
                    0L,              // clientId (unknown under classic logon)
                    0L,              // appBuildId (not tracked)
                ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

                var uploaded = 0
                var allOk = true
                for ((file, cloudPath) in localFiles) {
                    cb.onStatus("Uploading: ${file.name}")
                    val ok = uploadOne(steamCloud, appId, file, cloudPath, batch.batchID)
                    if (ok) uploaded++ else allOk = false
                }

                // Close the batch with the aggregate result (OK only if every file committed).
                steamCloud.completeAppUploadBatch(
                    appId,
                    batch.batchID,
                    if (allOk) EResult.OK else EResult.Fail,
                ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

                if (allOk) {
                    cb.onDone("Uploaded $uploaded file${plural(uploaded)} to the cloud")
                } else {
                    cb.onError("Uploaded $uploaded of ${localFiles.size}; some files failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadSaves failed", e)
                cb.onError("Upload error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-upload-$appId").start()
    }

    // ── Three-tier moves (Cloud ⇄ Library ⇄ Container) ───────────────────────────
    // Library = the managed local folder [SteamCloudSavePaths.libraryDir]; canonical local copy.
    // These wrap the primitives above / add container-side copies. SAFETY is unchanged: Download and
    // Upload delegate to [downloadSaves]/[uploadSaves] (Upload stays additive, filesToDelete empty,
    // empty-Library refused). Apply/Collect are pure copies — they may overwrite the DESTINATION
    // side only and NEVER delete the source and NEVER touch the cloud.

    /** Cloud → Library. Thin wrapper over [downloadSaves] with the Library dir resolved internally. */
    fun downloadToLibrary(ctx: Context, appId: Int, cb: Callback) {
        downloadSaves(ctx, appId, SteamCloudSavePaths.libraryDir(ctx, appId), cb)
    }

    /** Library → Cloud, strictly additive. Thin wrapper over [uploadSaves] (filesToDelete stays
     *  emptyList(); an empty Library is refused before any batch is opened). */
    fun uploadFromLibrary(ctx: Context, appId: Int, cb: Callback) {
        uploadSaves(ctx, appId, SteamCloudSavePaths.libraryDir(ctx, appId), cb)
    }

    /** Library → Container. For every file in the Library, translate its `%Root%/rest` layout to an
     *  absolute container path via [SteamCloudSavePaths.toContainerPath] and copy it in (mkdirs,
     *  mtime preserved). Overwrites the container's copies; never deletes anything, never touches the
     *  cloud. Files whose leading root token is unknown/unsafe are skipped (logged), never guessed. */
    fun applyToContainer(ctx: Context, appId: Int, installDir: String, cb: Callback) {
        Thread({
            try {
                val library = SteamCloudSavePaths.libraryDir(ctx, appId)
                val container = SteamCloudSavePaths.resolveContainer(ctx, appId, installDir)
                    ?: run { cb.onError("This game isn't set up in a container yet"); return@Thread }

                cb.onStatus("Scanning Library…")
                val files = enumerateLocal(library)
                if (files.isEmpty()) {
                    cb.onDone("Library is empty — download from the cloud first")
                    return@Thread
                }

                var applied = 0
                var skipped = 0
                for ((file, rel) in files) {
                    val dest = SteamCloudSavePaths.toContainerPath(rel, container, installDir)
                    if (dest == null) {
                        Log.w(TAG, "Apply: skipping unmapped/unsafe path: $rel")
                        skipped++
                        continue
                    }
                    cb.onStatus("Applying: ${file.name}")
                    copyPreserving(file, dest)
                    applied++
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Applied $applied file${plural(applied)} to " +
                    "${SteamCloudSavePaths.containerLabel(container)}$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "applyToContainer failed", e)
                cb.onError("Apply error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-apply-$appId").start()
    }

    /** Container → Library. Walks the container's save scopes (the directories the Library already
     *  established for this game — see [enumerateContainerSaves]), maps each file back to its
     *  `%Root%/rest` layout via [SteamCloudSavePaths.toLibraryRel], and copies it into the Library
     *  (mkdirs, mtime preserved). Overwrites the Library's copies; never deletes anything from the
     *  container, never touches the cloud. */
    fun collectFromContainer(ctx: Context, appId: Int, installDir: String, cb: Callback) {
        Thread({
            try {
                val library = SteamCloudSavePaths.libraryDir(ctx, appId)
                val container = SteamCloudSavePaths.resolveContainer(ctx, appId, installDir)
                    ?: run { cb.onError("This game isn't set up in a container yet"); return@Thread }

                cb.onStatus("Scanning container saves…")
                val saves = enumerateContainerSaves(ctx, appId, container, installDir)
                if (saves.isEmpty()) {
                    cb.onDone("No saves found in " +
                        "${SteamCloudSavePaths.containerLabel(container)} to collect")
                    return@Thread
                }

                var collected = 0
                var skipped = 0
                for ((file, libraryRel) in saves) {
                    val relLocal = sanitizeRelative(libraryRel)
                    if (relLocal == null) {
                        Log.w(TAG, "Collect: skipping unsafe library path: $libraryRel")
                        skipped++
                        continue
                    }
                    cb.onStatus("Collecting: ${file.name}")
                    copyPreserving(file, File(library, relLocal))
                    collected++
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Collected $collected file${plural(collected)} into your Library$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "collectFromContainer failed", e)
                cb.onError("Collect error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-collect-$appId").start()
    }

    /** Freshness snapshot for the staleness guard the UI shows before Apply/Upload. Synchronous
     *  filesystem scan — the caller runs it off the main thread. Container side is scoped to this
     *  game's save directories (the same set [collectFromContainer] would collect), so it never
     *  reflects other games' data. */
    data class Staleness(
        val libraryNewestMtime: Long,   // 0 if Library empty/absent
        val containerNewestMtime: Long, // 0 if container absent or no save files
        val libraryFileCount: Int,
        val containerFileCount: Int,
    )

    fun staleness(ctx: Context, appId: Int, installDir: String): Staleness {
        val library = SteamCloudSavePaths.libraryDir(ctx, appId)
        val libraryFiles = enumerateLocal(library)
        val libraryNewest = libraryFiles.maxOfOrNull { it.first.lastModified() } ?: 0L

        val container = SteamCloudSavePaths.resolveContainer(ctx, appId, installDir)
        val containerSaves = if (container == null) emptyList() else try {
            enumerateContainerSaves(ctx, appId, container, installDir)
        } catch (e: Exception) {
            Log.w(TAG, "staleness: container scan failed", e); emptyList()
        }
        val containerNewest = containerSaves.maxOfOrNull { it.first.lastModified() } ?: 0L

        return Staleness(libraryNewest, containerNewest, libraryFiles.size, containerSaves.size)
    }

    /**
     * The container-side save files for this game, paired with their `%Root%/rest` library-relative
     * paths. Scope = the container directories the Library already established as this game's save
     * locations (each tracked Library file's container-parent), then walked for their current
     * contents. This discovers NEW sibling files in the real save folder while staying scoped to
     * this game — it never sweeps a whole shared root (Documents/AppData/…). Empty if the Library
     * has never been populated (⇒ nothing tracked yet).
     */
    private fun enumerateContainerSaves(
        ctx: Context, appId: Int, container: com.winlator.star.container.Container, installDir: String,
    ): List<Pair<File, String>> {
        val library = SteamCloudSavePaths.libraryDir(ctx, appId)
        val tracked = enumerateLocal(library)
        if (tracked.isEmpty()) return emptyList()

        val scopeDirs = LinkedHashSet<File>()
        for ((_, rel) in tracked) {
            val dest = SteamCloudSavePaths.toContainerPath(rel, container, installDir) ?: continue
            val parent = dest.parentFile ?: continue
            if (parent.isDirectory) {
                try { scopeDirs.add(parent.canonicalFile) } catch (_: Exception) {}
            }
        }

        val out = ArrayList<Pair<File, String>>()
        val seen = HashSet<String>()
        for (dir in scopeDirs) {
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val libraryRel = SteamCloudSavePaths.toLibraryRel(f, container, installDir)
                    ?: return@forEach
                val canon = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
                if (seen.add(canon)) out.add(f to libraryRel)
            }
        }
        return out
    }

    /** Copy [src] onto [dst] (creating parents), preserving the modified time. Overwrites [dst]. */
    private fun copyPreserving(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
        try { dst.setLastModified(src.lastModified()) } catch (_: Exception) {}
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun requireCloud(): SteamCloud? = SteamRepository.getInstance().steamCloud

    /** The remote (Steam-side) path for a file = its path prefix + filename, as Steam stores it.
     *  This exact string is what [SteamCloud.clientFileDownload] and the upload calls key on. */
    private fun remotePathOf(f: AppFileInfo, list: AppFileChangeList): String {
        val prefixes = list.pathPrefixes
        val idx = f.pathPrefixIndex
        val prefix = if (idx in prefixes.indices) prefixes[idx] else ""
        return when {
            prefix.isEmpty() -> f.filename
            f.filename.isEmpty() -> prefix
            // Join with a single '/', matching GameNative's Paths.get(prefix, filename) on unix.
            else -> prefix.trimEnd('/', '\\') + "/" + f.filename.trimStart('/', '\\')
        }
    }

    /** Convert a Steam cloud path into a SAFE relative filesystem path under the local folder.
     *  Normalizes '\' -> '/', strips leading slashes, and REJECTS any '..' traversal (returns null).
     *  Placeholder folders like %WinMyDocuments% are kept verbatim as literal directory names, so a
     *  later upload reconstructs the identical cloud path from the local layout. */
    private fun sanitizeRelative(path: String): String? {
        val norm = path.replace('\\', '/').trimStart('/')
        if (norm.isEmpty()) return null
        val parts = norm.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** GET one cloud file to [dest]. Returns true on success. Only writes locally. */
    private fun downloadOne(sc: SteamCloud, appId: Int, remotePath: String, dest: File): Boolean {
        val info = sc.clientFileDownload(appId, remotePath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (info.urlHost.isEmpty()) {
            Log.w(TAG, "Empty CDN host for $remotePath")
            return false
        }
        val url = (if (info.useHttps) "https://" else "http://") + info.urlHost + info.urlPath
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        for (h in info.requestHeaders) conn.setRequestProperty(h.name, h.value)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code downloading $remotePath")
                return false
            }
            dest.parentFile?.mkdirs()
            // Steam serves the file zip-compressed when fileSize != rawFileSize (single entry).
            val compressed = info.fileSize != info.rawFileSize
            conn.inputStream.use { raw ->
                if (compressed) {
                    ZipInputStream(raw).use { zip ->
                        if (zip.nextEntry == null) {
                            Log.w(TAG, "Compressed cloud file $remotePath had no zip entry")
                            return false
                        }
                        dest.outputStream().use { out -> zip.copyTo(out) }
                    }
                } else {
                    dest.outputStream().use { out -> raw.copyTo(out) }
                }
            }
            try { dest.setLastModified(info.timestamp.time) } catch (_: Exception) {}
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** Upload a single local file to the cloud under [cloudPath]. Additive — no deletion involved. */
    private fun uploadOne(sc: SteamCloud, appId: Int, file: File, cloudPath: String, batchId: Long): Boolean {
        val sha = sha1(file)
        val fileSize = file.length().toInt()

        // beginFileUpload (JavaSteam 1.8.0): the remaining params (platformsToSync, cellId,
        // canEncrypt, isSharedFile, deprecatedRealm, parentScope) carry Kotlin defaults.
        val info = sc.beginFileUpload(
            appId = appId,
            fileSize = fileSize,
            rawFileSize = fileSize,
            fileSha = sha,
            timestamp = Date(file.lastModified()),
            filename = cloudPath,
            uploadBatchId = batchId,
        ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

        var ok = true
        RandomAccessFile(file, "r").use { raf ->
            for (block in info.blockRequests) {
                val len = block.blockLength
                val buf = ByteArray(len)
                raf.seek(block.blockOffset)
                var read = 0
                while (read < len) {
                    val n = raf.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val url = (if (block.useHttps) "https://" else "http://") + block.urlHost + block.urlPath
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.doOutput = true
                conn.requestMethod = "PUT"
                for (h in block.requestHeaders) conn.setRequestProperty(h.name, h.value)
                try {
                    conn.setFixedLengthStreamingMode(read)
                    conn.outputStream.use { out -> out.write(buf, 0, read) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        Log.w(TAG, "HTTP $code uploading block of $cloudPath")
                        ok = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Block upload failed for $cloudPath: ${e.javaClass.simpleName}")
                    ok = false
                } finally {
                    conn.disconnect()
                }
            }
        }

        // Commit tells the CM whether the transfer for this file succeeded. This does not delete
        // anything; on failure the CM simply drops this file's pending upload.
        sc.commitFileUpload(ok, appId, sha, cloudPath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        return ok
    }

    /** Recursively list files under [root], each paired with its cloud path (relative to root,
     *  '/'-separated). Empty list if the folder is absent/empty (upload then refuses). */
    private fun enumerateLocal(root: File): List<Pair<File, String>> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val base = root.absolutePath.trimEnd('/')
        val out = ArrayList<Pair<File, String>>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isNotEmpty()) out.add(f to rel)
        }
        return out
    }

    private fun sha1(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n = input.read(buf)
            while (n >= 0) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest()
    }

    private fun machineName(): String = "Bannerlator (${Build.MODEL})"

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
