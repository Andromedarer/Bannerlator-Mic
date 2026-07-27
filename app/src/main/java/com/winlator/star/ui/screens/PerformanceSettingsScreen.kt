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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager

/**
 * App Settings → Performance menu. Edits the GLOBAL DEFAULTS of the non-root toggles, bound to the
 * same [PerformanceSettings] flows the in-game drawer reads — so a change here is reflected live in
 * the other surface (two-way sync via one store). The root + watchdog section is scaffolded greyed
 * ("Unlocks with root — coming soon") to mirror the structure that lands next phase; no live root UI,
 * grant-gate, or watchdog disclaimer is wired in this cut.
 */
@Composable
fun PerformanceSettingsScreen(onClose: () -> Unit) {
    val sustained by PerformanceSettings.sustainedPerfMode.collectAsState()
    val priority by PerformanceSettings.perfPriorityBoost.collectAsState()
    val bigCores by PerformanceSettings.preferBigCores.collectAsState()
    val rootState by PerformanceSettings.rootState.collectAsState()
    val watchdogOn by PerformanceSettings.watchdogEnabled.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title bar
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

            // ── Global defaults (non-root, live-editable) ──
            PerfCard(title = "Global defaults") {
                PerfToggle("Sustained Performance Mode", sustained) { PerformanceSettings.setSustainedPerfMode(it) }
                PerfToggle("Thread Priority Boost", priority) { PerformanceSettings.setPerfPriorityBoost(it) }
                PerfToggle("Prefer Big Cores", bigCores) { PerformanceSettings.setPreferBigCores(it) }
            }

            // ── Root tier (scaffolded, disabled) ──
            PerfCard(title = "Root performance controls") {
                Text(
                    "Unlocks with root — coming soon.",
                    color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    "Root status: " + rootStateLabel(rootState),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                )
                Spacer(Modifier.height(2.dp))
                // Disabled previews of what the root tier will expose.
                PerfToggle("CPU governor / frequency lock", false, enabled = false) {}
                PerfToggle("GPU clock floor", false, enabled = false) {}
                PerfToggle("Keep cores online", false, enabled = false) {}
            }

            PerfCard(title = "Temperature watchdog") {
                Text(
                    "Unlocks with root — coming soon.",
                    color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
                // Read-only reflection of the (already-persisted) watchdog state; the arming control +
                // its safety disclaimer are wired next phase.
                PerfToggle("Thermal auto-revert (85°C)", watchdogOn, enabled = false) {}
            }

            Spacer(Modifier.height(24.dp))
        }
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
