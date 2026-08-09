package com.winlator.star.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.XServerDialogState

@Composable
fun CastDialog(state: XServerDialogState) {
    val devices by state.castDevices.collectAsState()
    val scanning by state.castScanning.collectAsState()
    val status by state.castStatus.collectAsState()
    val targetName by state.castTargetName.collectAsState()
    val detail by state.castStatusDetail.collectAsState()

    Dialog(
        onDismissRequest = { state.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f).padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Cast to a TV (wireless)", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { state.onCastRefresh?.run() }) { Text("Refresh") }
                }
                Text(
                    text = "Google TV / Chromecast devices on your Wi-Fi. Nothing to install on the TV.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                if (devices.isEmpty()) {
                    Text(
                        text = if (scanning) "Searching…" else "No devices found. Make sure your TV is on the " +
                            "same Wi-Fi, then tap Refresh.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        devices.forEach { d ->
                            val isTarget = d.name == targetName
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { state.onCastConnect?.accept(d) },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isTarget) MaterialTheme.colorScheme.primaryContainer
                                                     else MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(d.name, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                                    Text(d.type, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    // Live status appears under the device you tapped.
                                    if (isTarget && status != XServerDialogState.CastStatus.IDLE) {
                                        Spacer(Modifier.height(6.dp))
                                        val label = when (status) {
                                            XServerDialogState.CastStatus.CONNECTING -> "Connecting…"
                                            XServerDialogState.CastStatus.CONNECTED  -> "Connected"
                                            XServerDialogState.CastStatus.FAILED     -> "Failed to connect"
                                            else -> ""
                                        }
                                        val color = when (status) {
                                            XServerDialogState.CastStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                                            XServerDialogState.CastStatus.FAILED    -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (status == XServerDialogState.CastStatus.CONNECTING)
                                                CircularProgressIndicator(Modifier.size(14.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                                            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        }
                                        if (detail.isNotBlank())
                                            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                        if (status == XServerDialogState.CastStatus.CONNECTED ||
                                            status == XServerDialogState.CastStatus.CONNECTING) {
                                            TextButton(onClick = { state.onCastDisconnect?.run() },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                                Text("Disconnect")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { state.dismiss() }) { Text("Close") }
                }
            }
        }
    }
}
