package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.perf.TempWatchdog
import kotlinx.coroutines.launch

/**
 * App Settings → Performance menu. Binds the GLOBAL DEFAULTS (non-root three + root six) to the same
 * [PerformanceSettings] flows the in-game drawer reads, so a change here is reflected live in the
 * other surface (two-way sync via one store).
 *
 * Root tier: a real grant gate (scroll-to-bottom + accept disclaimer -> [RootManager.requestGrant]),
 * then the root toggles applied live through [PerfRootApplier] (snapshot-before-write; reverted on
 * exit/background/crash). The two dangerous toggles (thermal disable, fan max) stay disabled until the
 * safety harness is proven. The temperature watchdog is device-wide; turning it OFF requires its own
 * hard disclaimer.
 */
@Composable
fun PerformanceSettingsScreen(onClose: () -> Unit) {
    val sustained by PerformanceSettings.sustainedPerfMode.collectAsState()
    val priority by PerformanceSettings.perfPriorityBoost.collectAsState()
    val bigCores by PerformanceSettings.preferBigCores.collectAsState()
    val rootState by PerformanceSettings.rootState.collectAsState()
    val watchdogOn by PerformanceSettings.watchdogEnabled.collectAsState()
    val harnessProven by PerformanceSettings.harnessProven.collectAsState()

    val scope = rememberCoroutineScope()
    var showRootDisclaimer by remember { mutableStateOf(false) }
    var showWatchdogDisclaimer by remember { mutableStateOf(false) }

    val granted = rootState == RootManager.RootState.GRANTED

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Performance",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            Text(
                "Global defaults apply to every game unless a game sets its own override " +
                    "(in the game's settings or from the in-game menu).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
            )

            // ── Non-root global defaults (always editable) ──
            PerfCard(title = "Global defaults") {
                PerfToggle("Sustained Performance Mode", sustained) { PerformanceSettings.setSustainedPerfMode(it) }
                PerfToggle("Thread Priority Boost", priority) { PerformanceSettings.setPerfPriorityBoost(it) }
                PerfToggle("Prefer Big Cores", bigCores) { PerformanceSettings.setPreferBigCores(it) }
            }

            // ── Root tier ──
            PerfCard(title = "Root performance controls") {
                Text("Root status: " + rootStateLabel(rootState),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

                when (rootState) {
                    RootManager.RootState.UNAVAILABLE -> {
                        Text("No root manager detected (Magisk / KernelSU / APatch). Root controls stay disabled.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    RootManager.RootState.GRANTED -> { /* toggles below are enabled */ }
                    else -> {
                        // AVAILABLE_NOT_GRANTED or DENIED -> offer (or re-offer) the grant.
                        val label = if (rootState == RootManager.RootState.DENIED) "Grant Root (retry)" else "Grant Root"
                        Button(onClick = { showRootDisclaimer = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                RootToggle(PerfRootApplier.KEY_CPU_GOVERNOR, "CPU governor → performance", granted, harnessProven)
                RootToggle(PerfRootApplier.KEY_CPU_FREQ_LOCK, "Lock CPU frequency to max", granted, harnessProven)
                RootToggle(PerfRootApplier.KEY_CORES_ONLINE, "Keep all cores online", granted, harnessProven)
                RootToggle(PerfRootApplier.KEY_GPU_CLOCK_LOCK, "Lock GPU to max clock", granted, harnessProven)
                RootToggle(PerfRootApplier.KEY_THERMAL_DISABLE, "Disable thermal throttling", granted, harnessProven)
                RootToggle(PerfRootApplier.KEY_FAN_MAX, "Fan to maximum", granted, harnessProven)

                if (granted && !harnessProven) {
                    Text("Thermal / fan controls are locked until safety-revert is verified on this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { PerfRootApplier.freeMemoryNow() },
                    enabled = granted,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Free memory now", color = MaterialTheme.colorScheme.onPrimary) }
                Text("Background-app freeze — coming in a later update.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }

            // ── Temperature watchdog (device-wide; not root-gated) ──
            PerfCard(title = "Temperature watchdog") {
                Text(
                    "Auto-reverts all performance settings if the SoC reaches ${TempWatchdog.CEILING_C}°C. " +
                        "Keep this on unless you know what you're doing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                )
                PerfToggle("Thermal auto-revert (${TempWatchdog.CEILING_C}°C)", watchdogOn) { on ->
                    if (on) TempWatchdog.setWatchdogEnabled(true)  // arming needs no disclaimer
                    else showWatchdogDisclaimer = true             // disarming does
                }
            }

            Text(
                "Auto-revert on game exit, app background and crash is always on and cannot be disabled.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRootDisclaimer) {
        PerfDisclaimerDialog(
            title = "Power-user performance — read first",
            body = PerfDisclaimerCopy.ROOT_RISK,
            confirmLabel = "Grant Root",
            onDismiss = { showRootDisclaimer = false },
            onConfirm = {
                showRootDisclaimer = false
                scope.launch { RootManager.requestGrant() } // fires the su prompt; state updates live
            }
        )
    }

    if (showWatchdogDisclaimer) {
        PerfDisclaimerDialog(
            title = "Disable thermal safety?",
            body = PerfDisclaimerCopy.WATCHDOG_OFF,
            confirmLabel = "Turn watchdog OFF",
            onDismiss = { showWatchdogDisclaimer = false },
            onConfirm = {
                showWatchdogDisclaimer = false
                TempWatchdog.setWatchdogEnabled(false)
            }
        )
    }
}

/** A root toggle bound to its global default; applies live via PerfRootApplier. */
@Composable
private fun RootToggle(key: String, label: String, granted: Boolean, harnessProven: Boolean) {
    val checked by PerformanceSettings.rootDefaultFlow(key).collectAsState()
    val gated = PerfRootApplier.isHarnessGated(key) && !harnessProven
    val enabled = granted && !gated
    PerfToggle(label, checked, enabled = enabled) { on ->
        PerformanceSettings.setRootDefault(key, on)
        PerfRootApplier.apply(key, on)
    }
}

private fun rootStateLabel(state: RootManager.RootState): String = when (state) {
    RootManager.RootState.UNKNOWN -> "checking…"
    RootManager.RootState.UNAVAILABLE -> "not available on this device"
    RootManager.RootState.AVAILABLE_NOT_GRANTED -> "available (not granted)"
    RootManager.RootState.GRANTED -> "granted"
    RootManager.RootState.DENIED -> "denied"
}

@Composable
private fun PerfCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun PerfToggle(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier.alpha(0.4f))
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
