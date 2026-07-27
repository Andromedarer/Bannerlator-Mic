package com.winlator.star

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.winlator.star.perf.PerfRevertRegistry
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.perf.TempWatchdog

/**
 * The app's Application. Its sole current job is standing up the power-user performance safety core
 * as early as possible: probe root, repair any sysfs snapshot a hard kill left dirty last session,
 * and revert privileged writes when the WHOLE app (not just one activity) goes to background.
 *
 * All of this is wrapped so a failure here can never take down app startup — the perf tier is
 * strictly additive.
 */
class BannerlatorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // Restore-if-dirty + root probe + crash/shutdown safety nets, BEFORE anything touches a node.
            RootManager.onAppStartup(this)
            TempWatchdog.init(this)
            PerformanceSettings.init(this) // global defaults both perf surfaces bind to

            // App-level background => revert privileged writes (a single game Activity stopping is
            // handled in XServerDisplayActivity; this catches process-wide backgrounding).
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    try { PerfRevertRegistry.revertAll() } catch (t: Throwable) {
                        Log.w("BannerlatorApp", "background revert failed", t)
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w("BannerlatorApp", "perf safety-core init failed", t)
        }
    }
}
