package com.winlator.star.perf

import android.util.Log

/**
 * Applies (and reverts) the ROOT-tier performance toggles by writing real sysfs nodes through the
 * safety pipeline: every write is snapshot-first via [PerfRevertRegistry.applyWrite], so exit /
 * background / crash restores the captured original verbatim. Turning a single toggle OFF reverts
 * ONLY that toggle's nodes ([PerfRevertRegistry.revertNodes]) — other still-on toggles are untouched.
 *
 * Nothing here writes unless [RootManager] is GRANTED. The two dangerous toggles (thermal disable,
 * fan max) additionally no-op unless [PerfRevertRegistry.harnessProven] — belt-and-braces with the
 * disabled UI, so a broken revert can never cook a device before the on-device test signs off.
 *
 * Node paths come from [PerfNodeResolver] (SoC-aware), never hard-coded per vendor.
 */
object PerfRootApplier {

    private const val TAG = "PerfRootApplier"

    // Canonical keys — used as BOTH the shortcut-extra key (per-game override), the
    // PerformanceSettings global-default key, and the in-game drawer map key.
    const val KEY_CPU_GOVERNOR = "rootCpuGovernorPerf"
    const val KEY_CPU_FREQ_LOCK = "rootCpuFreqLockMax"
    const val KEY_CORES_ONLINE = "rootAllCoresOnline"
    const val KEY_GPU_CLOCK_LOCK = "rootGpuMaxClockLock"
    const val KEY_THERMAL_DISABLE = "rootThermalDisable"
    const val KEY_FAN_MAX = "rootFanMax"

    /** All root toggle keys, in display order. */
    val ROOT_KEYS = listOf(
        KEY_CPU_GOVERNOR, KEY_CPU_FREQ_LOCK, KEY_CORES_ONLINE,
        KEY_GPU_CLOCK_LOCK, KEY_THERMAL_DISABLE, KEY_FAN_MAX,
    )

    /** Keys gated behind the safety harness (dangerous — can overheat if revert is broken). */
    val HARNESS_GATED = setOf(KEY_THERMAL_DISABLE, KEY_FAN_MAX)

    fun isHarnessGated(key: String): Boolean = key in HARNESS_GATED

    // ── generic dispatch ─────────────────────────────────────────────────────────────────────────

    /** Apply or revert one toggle by key. No-ops when root isn't granted / harness-gated-and-unproven. */
    fun apply(key: String, on: Boolean) {
        when (key) {
            KEY_CPU_GOVERNOR -> applyCpuGovernorPerformance(on)
            KEY_CPU_FREQ_LOCK -> applyCpuFreqLockMax(on)
            KEY_CORES_ONLINE -> applyAllCoresOnline(on)
            KEY_GPU_CLOCK_LOCK -> applyGpuMaxClockLock(on)
            KEY_THERMAL_DISABLE -> applyThermalDisable(on)
            KEY_FAN_MAX -> applyFanMax(on)
            else -> Log.w(TAG, "apply: unknown key $key")
        }
    }

    private inline fun guarded(key: String, body: () -> Unit) {
        if (!RootManager.isGranted) { Log.d(TAG, "skip $key: root not granted"); return }
        if (isHarnessGated(key) && !PerfRevertRegistry.harnessProven.value) {
            Log.w(TAG, "skip $key: harness not proven"); return
        }
        try { body() } catch (t: Throwable) { Log.w(TAG, "apply $key failed", t) }
    }

    // ── CPU governor ─────────────────────────────────────────────────────────────────────────────
    fun applyCpuGovernorPerformance(on: Boolean) = guarded(KEY_CPU_GOVERNOR) {
        val nodes = PerfNodeResolver.cpuCores().mapNotNull { it.governor }
        if (on) nodes.forEach { PerfRevertRegistry.applyWrite(it, "performance") }
        else PerfRevertRegistry.revertNodes(nodes)
    }

    // ── CPU frequency lock (pin min to the node's max => runs at top clock) ────────────────────────
    fun applyCpuFreqLockMax(on: Boolean) = guarded(KEY_CPU_FREQ_LOCK) {
        val cores = PerfNodeResolver.cpuCores()
        if (on) {
            for (c in cores) {
                val minNode = c.minFreq ?: continue
                val maxNode = c.maxFreq ?: continue
                val maxVal = RootManager.readNode(maxNode) ?: continue
                PerfRevertRegistry.applyWrite(minNode, maxVal)
            }
        } else {
            PerfRevertRegistry.revertNodes(cores.mapNotNull { it.minFreq })
        }
    }

    // ── Keep all cores online ──────────────────────────────────────────────────────────────────────
    fun applyAllCoresOnline(on: Boolean) = guarded(KEY_CORES_ONLINE) {
        val nodes = PerfNodeResolver.cpuCores().mapNotNull { it.online }
        if (on) nodes.forEach { PerfRevertRegistry.applyWrite(it, "1") }
        else PerfRevertRegistry.revertNodes(nodes)
    }

    // ── GPU max-clock lock + force clocks on ───────────────────────────────────────────────────────
    fun applyGpuMaxClockLock(on: Boolean) = guarded(KEY_GPU_CLOCK_LOCK) {
        val g = PerfNodeResolver.gpu()
        val touched = listOfNotNull(g.minClock, g.forceClkOn)
        if (on) {
            g.maxClock?.let { maxNode ->
                val maxVal = RootManager.readNode(maxNode)
                if (maxVal != null && g.minClock != null) PerfRevertRegistry.applyWrite(g.minClock, maxVal)
            }
            g.forceClkOn?.let { PerfRevertRegistry.applyWrite(it, "1") }
        } else {
            PerfRevertRegistry.revertNodes(touched)
        }
    }

    // ── Thermal disable (DANGEROUS, harness-gated) ─────────────────────────────────────────────────
    fun applyThermalDisable(on: Boolean) = guarded(KEY_THERMAL_DISABLE) {
        val nodes = PerfNodeResolver.thermalZoneModes()
        if (on) nodes.forEach { PerfRevertRegistry.applyWrite(it, "disabled") }
        else PerfRevertRegistry.revertNodes(nodes)
    }

    // ── Fan max (DANGEROUS, harness-gated) ─────────────────────────────────────────────────────────
    // Generic path: pwm* -> 255, *_enable -> 1 (manual), cur_state -> its max_state sibling. Some
    // handhelds (AYANEO EC, etc.) expose bespoke EC nodes instead — TODO: per-device fan profiles.
    fun applyFanMax(on: Boolean) = guarded(KEY_FAN_MAX) {
        val nodes = PerfNodeResolver.fanNodes()
        if (on) {
            for (node in nodes) PerfRevertRegistry.applyWrite(node, fanMaxValueFor(node))
        } else {
            PerfRevertRegistry.revertNodes(nodes)
        }
    }

    private fun fanMaxValueFor(node: String): String = when {
        node.endsWith("_enable") -> "1"                       // pwmN_enable: manual control
        node.contains("/pwm") -> "255"                        // pwmN: full duty
        node.endsWith("cur_state") -> readMaxState(node) ?: "1" // cooling device: its max_state
        node.endsWith("_target") -> "255"                     // fanN_target: best-effort
        else -> "1"
    }

    private fun readMaxState(curStatePath: String): String? {
        val maxStatePath = curStatePath.removeSuffix("cur_state") + "max_state"
        return RootManager.readNode(maxStatePath)
    }

    // ── Free memory now (one-shot; no revert needed) ───────────────────────────────────────────────
    fun freeMemoryNow(): Boolean {
        if (!RootManager.isGranted) return false
        return RootManager.writeNode("/proc/sys/vm/drop_caches", "3")
    }

    // TODO(next pass): background-app freeze — needs `am`/package enumeration + a safelist; bigger and
    // riskier, deliberately deferred.

    /** Apply the resolved effective state of every root toggle at game launch. */
    fun applyEffective(effective: Map<String, Boolean>) {
        if (!RootManager.isGranted) return
        for (key in ROOT_KEYS) apply(key, effective[key] ?: false)
    }
}
