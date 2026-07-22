package com.winlator.star.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * A storage volume offered in the file manager's drive menu.
 *
 * [dir] is the deepest path we can actually read on that volume, which is not always the volume
 * root: when shared-storage access is unavailable the app-specific directory is still reachable,
 * and browsing that beats hiding the card entirely.
 */
data class StorageRoot(
    val label: String,
    val dir: File,
    val removable: Boolean,
    /** False when nothing on the volume could be listed; the entry is still shown. */
    val readable: Boolean,
)

/**
 * Enumerates mounted storage volumes.
 *
 * Listing `/storage` is not enough on its own: a volume can be absent from this process's mount
 * view (notably after a container exits and the app process is restarted onto a stale storage
 * sandbox) even though it is mounted and healthy. The framework knows about it regardless, so the
 * volume set is built from several independent sources and merged:
 *
 *  1. [StorageManager.getStorageVolumes] — the authoritative list, read over Binder.
 *  2. [Context.getExternalFilesDirs] — per-app directories, granted separately from shared storage.
 *  3. `/storage` and `/mnt/media_rw` directory listings — the filesystem view, as a backstop.
 *
 * A volume reported by any source is always emitted, so the SD card cannot silently disappear.
 */
object StorageRoots {

    private const val INTERNAL_PATH = "/storage/emulated/0"

    fun list(context: Context): List<StorageRoot> {
        val roots = LinkedHashMap<String, StorageRoot>()

        fun put(root: StorageRoot) {
            val key = canonicalPath(root.dir)
            // First writer wins, except that a readable path always beats an unreadable one.
            val existing = roots[key]
            if (existing == null || (!existing.readable && root.readable)) roots[key] = root
        }

        val appDirs = appSpecificDirs(context)
        val storageManager = context.getSystemService(StorageManager::class.java)

        // Internal storage is never removable and never absent; seed it first so it heads the menu.
        val internal = File(INTERNAL_PATH)
        put(StorageRoot("Internal", internal, removable = false, readable = canBrowse(internal)))

        // 1 + 2 — framework-reported volumes, resolved to the best path we can browse.
        storageManager?.storageVolumes.orEmpty()
            .filter { isMounted(it.state) }
            .forEach { volume ->
                val seeds = appDirs.filter { belongsTo(storageManager!!, it, volume) }
                val dir = resolveVolumeDir(volume, seeds)
                put(
                    StorageRoot(
                        label = labelFor(context, volume),
                        dir = dir,
                        removable = volume.isRemovable,
                        readable = canBrowse(dir),
                    )
                )
            }

        // 3 — filesystem backstop, for anything the framework did not report.
        listOf(File("/storage"), File("/mnt/media_rw")).forEach { parent ->
            parent.listFiles().orEmpty().forEach { child ->
                if (!child.isDirectory) return@forEach
                if (child.name == "self" || child.name == "emulated") return@forEach
                put(StorageRoot(child.name, child, removable = true, readable = canBrowse(child)))
            }
        }

        // Any app-specific directory whose volume none of the above surfaced.
        appDirs.forEach { dir ->
            val root = volumeRootOf(dir) ?: return@forEach
            val best = if (canBrowse(root)) root else deepestReadable(root, dir) ?: return@forEach
            put(StorageRoot(root.name, best, removable = true, readable = canBrowse(best)))
        }

        // Internal first, then removable volumes, then anything else — stable within each group.
        return roots.values.sortedBy { if (canonicalPath(it.dir) == canonicalPath(internal)) 0 else 1 }
    }

    /**
     * Picks the path to browse for [volume]: its root if we can read it, otherwise the deepest
     * readable directory on the way down to one of the app-specific [seeds].
     */
    private fun resolveVolumeDir(volume: StorageVolume, seeds: List<File>): File {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory?.let(::add)
            volume.uuid?.takeIf { it.isNotBlank() }?.let { uuid ->
                add(File("/storage/$uuid"))
                add(File("/mnt/media_rw/$uuid"))
            }
            seeds.mapNotNull(::volumeRootOf).forEach(::add)
            if (volume.isPrimary) add(File(INTERNAL_PATH))
        }

        candidates.firstOrNull(::canBrowse)?.let { return it }

        // Nothing at the root is readable — fall back into the volume via an app-specific dir.
        candidates.forEach { root ->
            seeds.forEach { seed -> deepestReadable(root, seed)?.let { return it } }
        }
        seeds.firstOrNull(::canBrowse)?.let { return it }

        // Report the volume anyway; an entry that lists empty is better than one that vanished.
        return candidates.firstOrNull() ?: File("/storage/${volume.uuid.orEmpty()}")
    }

    /** Walks up from [seed] towards [root] and returns the highest directory we can list. */
    private fun deepestReadable(root: File, seed: File): File? {
        var current: File? = seed.absoluteFile
        var best: File? = null
        while (current != null && isWithin(current, root)) {
            if (canBrowse(current)) best = current
            current = current.parentFile
        }
        return best
    }

    /** `/storage/ABCD-1234/Android/data/<pkg>/files` -> `/storage/ABCD-1234`. */
    private fun volumeRootOf(appDir: File): File? =
        generateSequence(appDir.absoluteFile) { it.parentFile }
            .firstOrNull { it.name.equals("Android", ignoreCase = true) }
            ?.parentFile

    private fun appSpecificDirs(context: Context): List<File> =
        context.getExternalFilesDirs(null)
            .orEmpty()
            .filterNotNull()
            .filter { isMounted(Environment.getExternalStorageState(it)) }

    private fun belongsTo(storageManager: StorageManager, dir: File, volume: StorageVolume): Boolean {
        val owner = storageManager.getStorageVolume(dir) ?: return false
        if (owner.isPrimary != volume.isPrimary) return false
        val ownerUuid = owner.uuid
        val volumeUuid = volume.uuid
        return if (!ownerUuid.isNullOrBlank() || !volumeUuid.isNullOrBlank()) {
            ownerUuid == volumeUuid
        } else {
            true
        }
    }

    private fun labelFor(context: Context, volume: StorageVolume): String = when {
        volume.isPrimary -> "Internal"
        else -> volume.getDescription(context)?.takeIf { it.isNotBlank() }
            ?: volume.uuid?.takeIf { it.isNotBlank() }
            ?: "SD card"
    }

    private fun isMounted(state: String?): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun canBrowse(dir: File?): Boolean =
        dir != null && dir.isDirectory && dir.canRead() && dir.listFiles() != null

    private fun isWithin(child: File, ancestor: File): Boolean {
        val c = canonicalPath(child)
        val a = canonicalPath(ancestor)
        return c == a || c.startsWith("$a/")
    }

    private fun canonicalPath(file: File): String =
        try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }.trimEnd('/')
}
