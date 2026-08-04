package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Thin wrapper around the bundled 7-Zip standalone console binary (`7zz`, vendored as
 * `jniLibs/arm64-v8a/lib7zz.so` — see NOTICE_7ZIP.txt for version/source/licence).
 *
 * Why a bundled native binary instead of commons-compress ([com.winlator.star.core.ArchiveExtractor]):
 * the disc images games arrive on are ISO9660/UDF hybrids, and the target device kernel has NO
 * iso9660/udf filesystem support (`/proc/filesystems` shows only fuse*), so a loop-mount fails with
 * "No such device". Extraction therefore has to happen in userspace, and it has to handle single
 * files well over 4 GB (an 80 GB+ InnoSetup repack inside an 82 GB image). 7-Zip does both, plus RAR
 * (including multi-part), 7z, split volumes (.001/.bin) and zip in one engine.
 *
 * The .so trick: Android extracts native libs to `nativeLibraryDir`, a directory that permits `exec`
 * even under scoped storage / W^X, so we resolve the binary there and exec it directly — no chmod,
 * and crucially NOT from filesDir (exec-from-filesDir is blocked on Android 10+). The vendored binary
 * is the STATICALLY linked `7zzs` build, renamed to `lib7zz.so`: a static aarch64 ELF makes raw
 * syscalls and needs neither glibc nor bionic .so files, so it runs on Android's kernel unchanged.
 */
object SevenZip {
    private const val TAG = "SevenZip"

    /** The vendored 7zz, resolved from the native-lib dir where it is unpacked and exec-able. */
    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "lib7zz.so")

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    // Extensions the Unpack action is offered for. A superset of the plain-Extract set:
    // disc images + RAR + split volumes that commons-compress cannot read.
    private val SUPPORTED = listOf(
        ".iso", ".udf", ".img", ".7z", ".zip", ".rar", ".r00", ".001", ".bin",
        ".cab", ".wim", ".vhd", ".vhdx", ".dmg", ".cso", ".cue", ".gz", ".xz", ".bz2", ".tar",
    )

    /** True when [file] is something the 7-Zip engine can be pointed at. */
    fun isSupported(file: File): Boolean {
        if (file.isDirectory) return false
        val name = file.name.lowercase()
        return SUPPORTED.any { name.endsWith(it) }
    }

    // A Setup-1.bin / game-2.bin style InnoSetup data volume.
    private val INNO_BIN = Regex("""(?i)-\d+\.bin$""")

    /**
     * If [file] is part of an InnoSetup repack (the layout games arrive in — a `Setup.exe` next to
     * `Setup-1.bin`, `Setup-2.bin`, …), returns the installer `.exe` that 7-Zip must be pointed at to
     * unpack the game payload; otherwise null. 7-Zip opens the payload behind the `.bin` volumes only
     * when given the `.exe`, never a lone `.bin`.
     *
     * Handles both entry points: the user selecting `Setup.exe`, or selecting a `Setup-N.bin` volume.
     */
    fun resolveInnoTarget(file: File): File? {
        if (file.isDirectory) return null
        val parent = file.parentFile ?: return null
        val siblings = parent.listFiles() ?: return null
        val ext = file.extension.lowercase()
        val hasInnoBin = siblings.any { it.isFile && INNO_BIN.containsMatchIn(it.name) }
        return when {
            // An .exe sitting next to Setup-*.bin data (or a plainly-named setup.exe) is the installer.
            ext == "exe" && (hasInnoBin || file.name.equals("setup.exe", ignoreCase = true)) -> file
            // A Setup-N.bin was picked directly — point 7-Zip at the sibling installer .exe instead.
            ext == "bin" && INNO_BIN.containsMatchIn(file.name) ->
                siblings.firstOrNull { it.isFile && it.extension.equals("exe", true) && it.name.contains("setup", true) }
                    ?: siblings.firstOrNull { it.isFile && it.extension.equals("exe", true) }
            else -> null
        }
    }

    /** True when [file] is (part of) an InnoSetup repack — see [resolveInnoTarget]. */
    fun isInnoSetup(file: File): Boolean = resolveInnoTarget(file) != null

    /** Which path an InnoSetup target can actually be unpacked by. */
    enum class InnoRoute {
        /** 7-Zip can open the installer — offer in-app payload extraction. */
        SEVENZIP,
        /** 7-Zip cannot read it (FreeArc etc.) — must be installed by running Setup.exe in a container. */
        CONTAINER_ONLY,
    }

    data class InnoClassification(
        val installer: File,
        val route: InnoRoute,
        /** Human compression name from Records.ini, e.g. "FreeArc", when known. */
        val compression: String?,
        /** Declared payload size from Records.ini (already human-formatted), when known. */
        val declaredSize: String?,
    )

    /**
     * Decides how an InnoSetup [installer] must be unpacked, BEFORE offering the user a doomed 7-Zip
     * action. Most modern game repacks (FitGirl/DODI/…) are FreeArc-compressed, which 7-Zip has no
     * support for, so pointing 7-Zip at their Setup.exe fails with "Cannot open the file as archive".
     *
     * Signals, in order:
     *  1. A sibling `Records.ini` whose `Type=` starts with "Freearc" → CONTAINER_ONLY (authoritative;
     *     the payload codec the repack's own installer uses). Its `Type` + `Size` are surfaced for UI.
     *  2. Otherwise a fast pre-flight `7zz l <installer>` — non-zero / "Cannot open" → CONTAINER_ONLY.
     *
     * Runs a native exec, so call it off the main thread.
     */
    fun classifyInno(context: Context, installer: File): InnoClassification {
        val records = readRecordsIni(installer.parentFile)
        val rawType = records?.first
        val freeArc = rawType?.trim()?.startsWith("freearc", ignoreCase = true) == true
        val compression = rawType?.let { prettyCompression(it) }
        val declaredSize = records?.second?.let { prettySize(it) }

        // FreeArc is a certain no for 7-Zip — skip the (doomed) pre-flight. Otherwise let 7-Zip itself
        // be the authority on whether it can open the installer.
        val openable = if (freeArc) false else sevenZipCanOpen(context, installer)
        return InnoClassification(
            installer = installer,
            route = if (openable) InnoRoute.SEVENZIP else InnoRoute.CONTAINER_ONLY,
            compression = compression,
            declaredSize = declaredSize,
        )
    }

    /** Fast "can 7-Zip even open this?" pre-flight via `7zz l`. */
    fun sevenZipCanOpen(context: Context, file: File): Boolean {
        val bin = binary(context)
        if (!bin.canExecute()) return false
        return try {
            val proc = ProcessBuilder(bin.absolutePath, "l", file.absolutePath)
                .redirectErrorStream(true)
                .apply { environment()["TMPDIR"] = context.cacheDir.absolutePath }
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            code == 0 && !out.contains("Cannot open the file as archive", ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "canOpen pre-flight failed for ${file.name}", e)
            false
        }
    }

    /** Reads the first `Type=` and `Size=` from a repack's `Records.ini`, or null if absent. */
    private fun readRecordsIni(dir: File?): Pair<String?, String?>? {
        val ini = dir?.listFiles()?.firstOrNull { it.isFile && it.name.equals("Records.ini", ignoreCase = true) }
            ?: return null
        var type: String? = null
        var size: String? = null
        runCatching {
            ini.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.trim()
                    if (type == null && t.startsWith("Type=", ignoreCase = true)) type = t.substringAfter('=').trim()
                    if (size == null && t.startsWith("Size=", ignoreCase = true)) size = t.substringAfter('=').trim()
                    if (type != null && size != null) break
                }
            }
        }
        return type to size
    }

    private fun prettyCompression(rawType: String): String = when {
        rawType.startsWith("freearc", ignoreCase = true) -> "FreeArc"
        else -> rawType.substringBefore('_')
    }

    /** Records.ini Size is sometimes a raw byte count, sometimes already "81GB" — normalise for display. */
    private fun prettySize(raw: String): String =
        raw.toLongOrNull()?.let { StringUtils.formatBytes(it) } ?: raw

    /** A friendly default folder name to extract [archive] into — its name minus one extension. */
    fun suggestedTargetName(archive: File): String {
        val name = archive.name
        val lower = name.lowercase()
        // Handle multi-part markers so "Game.part1.rar" -> "Game", "disc.001" -> "disc".
        for (suffix in listOf(".part1.rar", ".part01.rar", ".tar.gz", ".tar.xz", ".tar.bz2")) {
            if (lower.endsWith(suffix)) return name.dropLast(suffix.length).ifBlank { name }
        }
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    data class ArchiveInfo(val type: String?, val entryCount: Int)

    /**
     * Lists [archive] with `7zz l -slt` and reports its declared Type plus a rough entry count.
     * Metadata-only, so it is cheap even for an 80 GB image. Returns null if 7zz can't open it.
     */
    fun list(context: Context, archive: File): ArchiveInfo? {
        val bin = binary(context)
        if (!bin.canExecute()) return null
        return try {
            val proc = ProcessBuilder(bin.absolutePath, "l", "-slt", archive.absolutePath)
                .redirectErrorStream(true)
                .apply { environment()["TMPDIR"] = context.cacheDir.absolutePath }
                .start()
            var type: String? = null
            var pathCount = 0
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        type == null && line.startsWith("Type = ") -> type = line.substring(7).trim()
                        line.startsWith("Path = ") -> pathCount++
                    }
                }
            }
            proc.waitFor()
            // The first "Path =" block is the archive itself; the rest are entries.
            ArchiveInfo(type, (pathCount - 1).coerceAtLeast(0))
        } catch (e: Exception) {
            Log.e(TAG, "list failed for ${archive.name}", e)
            null
        }
    }

    /** Progress + logging surface for [extract]. Every callback fires off the extraction thread. */
    interface Listener {
        /** [percent] 0..100; [currentFile] is the entry 7zz is on, when it names one. */
        fun onProgress(percent: Int, currentFile: String?)
        /** A processed-file line (from `-bb1`); used to count extracted files. */
        fun onFile(name: String)
    }

    /** Outcome of an extraction. [exitCode] is 7-Zip's own code (0 ok, 1 warnings, ≥2 error). */
    data class Result(val exitCode: Int, val stderrTail: String)

    private val PERCENT = Regex("""(\d{1,3})%""")

    /**
     * Extracts [archive] into [destDir] via `7zz x`, streaming progress to [listener].
     *
     * @param mmt        thread count for `-mmt=` (see [UnpackManager.mmtFor]).
     * @param bufferBytes size of the pipe read buffer — the Read-buffer knob. It only affects how we
     *                    drain 7zz's stdout pipe (the sole stream we own here); 7-Zip does its own
     *                    file IO. Honest naming: it is a FUSE/pipe throughput knob, not a speed dial.
     * @param onProcess  handed the live [Process] so the caller (service) can destroy it to cancel.
     */
    fun extract(
        context: Context,
        archive: File,
        destDir: File,
        mmt: Int,
        bufferBytes: Int,
        listener: Listener,
        onProcess: (Process) -> Unit,
    ): Result {
        val bin = binary(context)
        destDir.mkdirs()
        val proc = ProcessBuilder(
            bin.absolutePath, "x", archive.absolutePath,
            "-o${destDir.absolutePath}",
            "-y",        // assume Yes (overwrite) — the screen owns the destination folder
            "-bsp1",     // progress -> stdout so we can parse it
            "-bb1",      // log each processed file
            "-mmt=$mmt",
        ).apply { environment()["TMPDIR"] = context.cacheDir.absolutePath }
            .start()
        onProcess(proc)

        // stderr collected on its own thread for the failure tail.
        val stderr = StringBuilder()
        val errThread = Thread {
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) {
                        stderr.append(line).append('\n')
                        // Keep only a tail so a pathological archive can't balloon memory.
                        if (stderr.length > 8192) stderr.delete(0, stderr.length - 8192)
                    }
                }
            }
        }.also { it.start() }

        // 7-Zip redraws its progress line with carriage returns and backspaces rather than newlines,
        // so we can't use readLine(). Accumulate raw bytes into segments split on \r / \n, dropping
        // backspaces, then decode each segment as UTF-8 (safe: \r \n \b are single bytes in UTF-8).
        var lastPercent = 0
        val seg = ByteArrayOutputStream(256)
        val buf = ByteArray(bufferBytes.coerceAtLeast(64 * 1024))
        val input = proc.inputStream
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                when (val b = buf[i].toInt()) {
                    '\r'.code, '\n'.code -> { flushSegment(seg, listener) { lastPercent = it } }
                    '\b'.code -> { val a = seg.toByteArray(); seg.reset(); if (a.isNotEmpty()) seg.write(a, 0, a.size - 1) }
                    else -> seg.write(b)
                }
            }
        }
        flushSegment(seg, listener) { lastPercent = it }

        val exit = proc.waitFor()
        runCatching { errThread.join(500) }
        // Push a final 100% so a clean finish never leaves the bar short of the end.
        if (exit <= 1) listener.onProgress(100, null)
        return Result(exit, synchronized(stderr) { stderr.toString().trim() })
    }

    private inline fun flushSegment(seg: ByteArrayOutputStream, listener: Listener, onPercent: (Int) -> Unit) {
        if (seg.size() == 0) return
        val text = seg.toByteArray().toString(Charsets.UTF_8)
        seg.reset()
        if (text.isBlank()) return

        // Filename: 7z progress lines look like " 34% 12 - path\to\file"; -bb1 lines like "- path".
        val fileName = when {
            text.contains(" - ") -> text.substringAfterLast(" - ").trim().ifBlank { null }
            text.startsWith("- ") -> text.substring(2).trim().ifBlank { null }
            else -> null
        }
        if (fileName != null && !text.contains('%')) listener.onFile(fileName)

        val pct = PERCENT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (pct != null) {
            val clamped = pct.coerceIn(0, 100)
            onPercent(clamped)
            listener.onProgress(clamped, fileName)
        }
    }
}
