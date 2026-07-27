package com.winlator.star.perf

import android.os.Process
import android.util.Log
import java.io.File

/**
 * Non-root thread-priority boost for the "Thread Priority Boost" toggle. Raises the app's OWN
 * render/present/emulation threads to a display-urgent scheduling priority via
 * [android.os.Process.setThreadPriority] — no root, no CAP_SYS_NICE (an app may renice its own
 * threads within its rlimit).
 *
 * Targeting is best-effort by thread name: the guest itself runs as separate PRoot child processes
 * we can't (and shouldn't) renice, so the win here is the host-side render/present threads. The exact
 * present-thread identity is renderer-internal and vendor-dependent; this scans /proc/self/task and
 * matches known name tokens, which is why it's deliberately conservative and failure-isolated.
 */
object PerfPriority {

    private const val TAG = "PerfPriority"

    // Thread comm tokens for our host render / present / emulation-pump threads across the GL,
    // ASurface and Vulkan paths. Lower-cased substring match.
    private val RENDER_THREAD_TOKENS = arrayOf(
        "glthread", "render", "present", "vulkan", "vk", "compositor", "surface", "winhandler",
    )

    /** Boost matching threads. Returns how many were successfully renified. Safe to call repeatedly. */
    fun boostRenderThreads(): Int = applyToRenderThreads(boost = true)

    /** Restore matching threads to the default priority (used when the toggle is turned OFF in-game). */
    fun restoreRenderThreads(): Int = applyToRenderThreads(boost = false)

    private fun applyToRenderThreads(boost: Boolean): Int {
        val tasks = File("/proc/self/task").listFiles() ?: return 0
        var count = 0
        for (task in tasks) {
            val tid = task.name.toIntOrNull() ?: continue
            val comm = try { File(task, "comm").readText().trim().lowercase() } catch (_: Exception) { continue }
            if (RENDER_THREAD_TOKENS.none { comm.contains(it) }) continue
            if (setPriority(tid, boost)) count++
        }
        Log.d(TAG, "${if (boost) "boosted" else "restored"} $count render thread(s)")
        return count
    }

    private fun setPriority(tid: Int, boost: Boolean): Boolean {
        if (!boost) {
            return try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT); true }
            catch (_: Throwable) { false }
        }
        // Prefer URGENT_DISPLAY (-8); fall back to DISPLAY (-4) if the rlimit rejects it.
        return try {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY)
            true
        } catch (_: Throwable) {
            try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DISPLAY); true }
            catch (_: Throwable) { false }
        }
    }
}
