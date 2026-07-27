package com.winlator.star.perf

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for the GLOBAL DEFAULTS of the non-root performance toggles. Both perf
 * surfaces bind to these flows:
 *  - App Settings → Performance menu reads AND writes the global default here.
 *  - The in-game drawer reads the EFFECTIVE value (per-game override ?? this global default) and, for
 *    a game with no per-game store, writes the global default here too — so the two surfaces stay in
 *    two-way live sync via one store instead of independent copies.
 *
 * Resolution model is exactly two levels: **per-game shortcut override → this global default**. There
 * is no per-container level.
 *
 * SharedPreferences-backed (shared "perf_prefs" file with [TempWatchdog]); each toggle is a
 * StateFlow so Compose can observe it live. Device-wide items that already have their own singletons
 * ([TempWatchdog] enabled, [RootManager] grant state) are re-exposed here read-only so a settings
 * screen can bind everything from one place.
 */
object PerformanceSettings {

    private const val PREFS = "perf_prefs"
    private const val KEY_SUSTAINED = "global_sustainedPerfMode"
    private const val KEY_PRIORITY = "global_perfPriorityBoost"
    private const val KEY_BIG_CORES = "global_preferBigCores"

    private var appContext: Context? = null

    private val _sustainedPerfMode = MutableStateFlow(false)
    val sustainedPerfMode: StateFlow<Boolean> = _sustainedPerfMode.asStateFlow()

    private val _perfPriorityBoost = MutableStateFlow(false)
    val perfPriorityBoost: StateFlow<Boolean> = _perfPriorityBoost.asStateFlow()

    private val _preferBigCores = MutableStateFlow(false)
    val preferBigCores: StateFlow<Boolean> = _preferBigCores.asStateFlow()

    // Device-wide state re-exposed read-only so one screen can bind the whole picture.
    val watchdogEnabled: StateFlow<Boolean> get() = TempWatchdog.enabled
    val rootState: StateFlow<RootManager.RootState> get() = RootManager.state

    /** Load persisted global defaults. Call once from the Application, before either surface binds. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _sustainedPerfMode.value = prefs.getBoolean(KEY_SUSTAINED, false)
        _perfPriorityBoost.value = prefs.getBoolean(KEY_PRIORITY, false)
        _preferBigCores.value = prefs.getBoolean(KEY_BIG_CORES, false)
    }

    fun setSustainedPerfMode(v: Boolean) = put(KEY_SUSTAINED, v, _sustainedPerfMode)
    fun setPerfPriorityBoost(v: Boolean) = put(KEY_PRIORITY, v, _perfPriorityBoost)
    fun setPreferBigCores(v: Boolean) = put(KEY_BIG_CORES, v, _preferBigCores)

    private fun put(key: String, v: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = v
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(key, v)?.apply()
    }
}
