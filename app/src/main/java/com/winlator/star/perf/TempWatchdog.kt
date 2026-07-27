package com.winlator.star.perf

import android.content.Context
import android.util.Log
import com.winlator.star.widget.HudMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thermal failsafe for the root perf tier. A background coroutine polls SoC temperature (via the
 * same [HudMetrics] the HUD reads) and, above a conservative ceiling, force-restores thermal +
 * governor state through [PerfRevertRegistry.revertAll] REGARDLESS of any toggle — an overheating
 * device wins over a user override.
 *
 * The watchdog itself is a user toggle (default ON). If the user turns it OFF it stays OFF across
 * restarts until manually re-armed — it is never silently re-enabled on launch. The disclaimer
 * dialog that guards turning it off is a later phase; this class only owns the persisted toggle and
 * the watchdog behavior.
 */
object TempWatchdog {

    private const val TAG = "TempWatchdog"
    private const val PREFS = "perf_prefs"
    private const val KEY_ENABLED = "temp_watchdog_enabled"

    /** Conservative SoC ceiling in °C. Above this the watchdog reverts everything. */
    const val CEILING_C = 85

    private const val POLL_MS = 3_000L
    // Require two consecutive over-ceiling reads before acting, to shrug off a single spurious sensor spike.
    private const val TRIP_COUNT = 2

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Live SoC temperature the watchdog last observed (°C); 0 when unknown / not polling. */
    private val _lastTempC = MutableStateFlow(0)
    val lastTempC: StateFlow<Int> = _lastTempC.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var appContext: Context? = null

    /** Load the persisted toggle. Call once from the Application before start(). */
    fun init(context: Context) {
        appContext = context.applicationContext
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true) // DEFAULT ON; a persisted false stays false across restarts
    }

    /**
     * Persist and apply the user's choice. Turning it OFF survives restarts (no auto-re-arm); turning
     * it ON re-arms immediately if a session is active.
     */
    fun setWatchdogEnabled(enabled: Boolean) {
        _enabled.value = enabled
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        Log.d(TAG, "watchdog ${if (enabled) "armed" else "disarmed"}")
    }

    /** Start polling for a game session. Idempotent. Ticks are cheap no-ops while disabled. */
    fun start(context: Context) {
        if (appContext == null) init(context)
        if (job?.isActive == true) return
        val metrics = HudMetrics(context.applicationContext)
        var over = 0
        job = scope.launch {
            Log.d(TAG, "started (ceiling ${CEILING_C}°C)")
            while (isActive) {
                if (_enabled.value) {
                    val soc = readSocTempC(metrics)
                    if (soc != null) {
                        _lastTempC.value = soc
                        if (soc >= CEILING_C) {
                            over++
                            if (over >= TRIP_COUNT) {
                                Log.w(TAG, "SoC ${soc}°C >= ${CEILING_C}°C — force-reverting perf state")
                                try { PerfRevertRegistry.revertAll() } catch (t: Throwable) {
                                    Log.w(TAG, "watchdog revert failed", t)
                                }
                                over = 0
                            }
                        } else {
                            over = 0
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _lastTempC.value = 0
    }

    /** SoC temperature proxy = hottest of the CPU / GPU zones. Null when neither is readable. */
    private fun readSocTempC(metrics: HudMetrics): Int? {
        val cpu = metrics.cpuTempC
        val gpu = metrics.gpuTempC
        return when {
            cpu != null && gpu != null -> maxOf(cpu, gpu)
            cpu != null -> cpu
            gpu != null -> gpu
            else -> null
        }
    }
}
