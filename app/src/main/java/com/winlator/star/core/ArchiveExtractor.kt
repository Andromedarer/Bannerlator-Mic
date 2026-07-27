package com.winlator.star.core

import android.util.Log
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import com.github.luben.zstd.ZstdInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Extracts the archive formats games actually arrive in, on top of the compression libraries the
 * app already ships (commons-compress, xz, zstd-jni).
 *
 * [TarCompressorUtils] covers only the two formats our own content pipeline uses (tar.xz and
 * tar.zst) and is built around asset/Uri sources, so it doesn't fit a user browsing files. This
 * handles the general case and reports progress so a multi-gigabyte extract isn't a frozen screen.
 *
 * **RAR is deliberately absent** — commons-compress cannot read it and pulling in a RAR library is
 * a separate decision, so `.rar` is reported as unsupported rather than silently failing.
 */
object ArchiveExtractor {
    private const val TAG = "ArchiveExtractor"

    /** Reports bytes written so far and, when the archive declares it, the expected total. */
    fun interface ProgressCallback {
        fun onProgress(written: Long, total: Long)
    }

    /** Extensions offered an Extract action. Kept in one place so the UI and the extractor agree. */
    private val ARCHIVE_SUFFIXES = listOf(
        ".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tgz", ".txz", ".tbz2",
        ".zip", ".7z", ".tar", ".gz", ".xz", ".bz2", ".zst",
    )

    /** True when [file] looks like something we can extract. */
    fun isArchive(file: File): Boolean {
        if (file.isDirectory) return false
        val name = file.name.lowercase()
        return ARCHIVE_SUFFIXES.any { name.endsWith(it) }
    }

    /** True for archives we can recognise but not read, so the UI can say why. */
    fun isUnsupportedArchive(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".rar") || name.endsWith(".r00")
    }

    /**
     * A sensible folder name for extracting [archive] into — the archive name with its extension
     * (including the double `.tar.gz` kind) stripped.
     */
    fun suggestedTargetName(archive: File): String {
        val name = archive.name
        val lower = name.lowercase()
        val hit = ARCHIVE_SUFFIXES.firstOrNull { lower.endsWith(it) } ?: return name
        return name.dropLast(hit.length).ifBlank { name }
    }

    /**
     * Extracts [archive] into [destDir], which is created if needed.
     *
     * Returns true only when every entry was written. Cancellation is cooperative: pass a
     * [shouldContinue] that flips to false and the extract stops between entries, leaving what it
     * already wrote (callers that want atomicity should extract to a temp dir and move on success).
     */
    fun extract(
        archive: File,
        destDir: File,
        progress: ProgressCallback? = null,
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        if (!archive.isFile) return false
        if (!destDir.exists() && !destDir.mkdirs()) return false
        val name = archive.name.lowercase()
        return try {
            when {
                name.endsWith(".zip") -> extractStream(ZipArchiveInputStream(buffered(archive)), destDir, archive.length(), progress, shouldContinue)
                name.endsWith(".7z") -> extractSevenZ(archive, destDir, progress, shouldContinue)
                isTarball(name) -> extractStream(TarArchiveInputStream(decompressor(archive, name)), destDir, archive.length(), progress, shouldContinue)
                // A bare compressor with no tar inside: one file, named after the archive.
                else -> extractSingle(archive, destDir, name, progress, shouldContinue)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed for ${archive.name}", e)
            false
        }
    }

    private fun isTarball(lower: String) = lower.endsWith(".tar") ||
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
        lower.endsWith(".tar.xz") || lower.endsWith(".txz") ||
        lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ||
        lower.endsWith(".tar.zst")

    private fun buffered(f: File) = BufferedInputStream(FileInputStream(f))

    /** Wraps [archive] in whatever compressor its name implies; plain `.tar` passes through. */
    private fun decompressor(archive: File, lower: String): InputStream {
        val raw = buffered(archive)
        return when {
            lower.endsWith(".gz") || lower.endsWith(".tgz") -> GzipCompressorInputStream(raw)
            lower.endsWith(".xz") || lower.endsWith(".txz") -> XZCompressorInputStream(raw)
            lower.endsWith(".bz2") || lower.endsWith(".tbz2") -> BZip2CompressorInputStream(raw)
            lower.endsWith(".zst") -> ZstdInputStream(raw)
            else -> raw
        }
    }

    /**
     * Shared entry loop for the streaming formats (zip and tar).
     *
     * [total] is the COMPRESSED size — the only figure available up front for a stream — so the
     * progress bar tracks bytes read from disk rather than bytes written. It moves smoothly and
     * ends at 100%, which is what a progress bar is for.
     */
    private fun extractStream(
        input: org.apache.commons.compress.archivers.ArchiveInputStream,
        destDir: File,
        total: Long,
        progress: ProgressCallback?,
        shouldContinue: () -> Boolean,
    ): Boolean {
        input.use { stream ->
            val buffer = ByteArray(256 * 1024)
            var written = 0L
            while (true) {
                if (!shouldContinue()) return false
                val entry = stream.nextEntry ?: break
                val out = safeChild(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    while (true) {
                        if (!shouldContinue()) return false
                        val n = stream.read(buffer)
                        if (n <= 0) break
                        fos.write(buffer, 0, n)
                        written += n
                    }
                }
                progress?.onProgress(minOf(written, total), total)
            }
        }
        progress?.onProgress(total, total)
        return true
    }

    /** 7z needs random access, so it can't share the streaming path. */
    private fun extractSevenZ(
        archive: File,
        destDir: File,
        progress: ProgressCallback?,
        shouldContinue: () -> Boolean,
    ): Boolean {
        SevenZFile(archive).use { sevenZ ->
            val buffer = ByteArray(256 * 1024)
            val total = archive.length()
            var written = 0L
            while (true) {
                if (!shouldContinue()) return false
                val entry = sevenZ.nextEntry ?: break
                val out = safeChild(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    while (true) {
                        if (!shouldContinue()) return false
                        val n = sevenZ.read(buffer)
                        if (n <= 0) break
                        fos.write(buffer, 0, n)
                        written += n
                    }
                }
                progress?.onProgress(minOf(written, total), total)
            }
        }
        progress?.onProgress(archive.length(), archive.length())
        return true
    }

    /** A lone `.gz`/`.xz`/`.bz2`/`.zst` with no archive inside — decompress to a single file. */
    private fun extractSingle(
        archive: File,
        destDir: File,
        lower: String,
        progress: ProgressCallback?,
        shouldContinue: () -> Boolean,
    ): Boolean {
        val out = File(destDir, suggestedTargetName(archive))
        val total = archive.length()
        var written = 0L
        decompressor(archive, lower).use { input ->
            FileOutputStream(out).use { fos ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (!shouldContinue()) return false
                    val n = input.read(buffer)
                    if (n <= 0) break
                    fos.write(buffer, 0, n)
                    written += n
                    progress?.onProgress(minOf(written, total), total)
                }
            }
        }
        progress?.onProgress(total, total)
        return true
    }

    /**
     * Resolves an entry name under [destDir], refusing anything that escapes it.
     *
     * This is the Zip Slip guard: an archive can carry entries like `../../../etc/passwd`, and a
     * naive `File(destDir, entry.name)` will happily write outside the target. Returns null for
     * such entries so they're skipped rather than honoured.
     */
    private fun safeChild(destDir: File, entryName: String): File? {
        val child = File(destDir, entryName)
        val root = runCatching { destDir.canonicalPath }.getOrDefault(destDir.absolutePath)
        val path = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
        if (path != root && !path.startsWith(root + File.separator)) {
            Log.w(TAG, "Skipping entry escaping destination: $entryName")
            return null
        }
        return child
    }
}
