package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.R
import com.winlator.star.contents.WrapperManager
import com.winlator.star.util.InAppFilePicker

/**
 * Step 1 of the Wrapper Version Manager (issue #132): a fixed-slot updater. Each of the 6 bundled
 * graphics-wrapper archives gets an Update button (swap in a newer .tzst of the same name) and a
 * Reset button (revert to the built-in). Overrides live at filesDir/graphics_driver/<name> and win
 * at game launch. No dynamic dropdown / free-form import — that's Step 2.
 */
@Composable
fun WrapperManagerScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val manager = remember { WrapperManager(activity) }
    val cs = MaterialTheme.colorScheme

    var slots by remember { mutableStateOf(manager.listSlots().toList()) }
    // The slot awaiting a picked file (set before the picker launches so the result knows its target).
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var confirmInstallPrompt by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }

    fun refresh() { slots = manager.listSlots().toList() }

    // File picker for a wrapper .tzst override.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingFileName
        pendingFileName = null
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            // In-app picker returns a path (wrapped as file:// Uri); SAF returns result.data.data.
            val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data)
            if (uri != null && manager.installOverride(target, uri)) {
                Toast.makeText(context, R.string.wrapper_updated_toast, Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(context, R.string.wrapper_invalid_toast, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
    ) {
        // Header + Reset all
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = context.getString(R.string.wrapper_manager_header),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            OutlinedButton(onClick = { confirmResetAll = true }) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(context.getString(R.string.wrapper_reset_all))
            }
        }

        Divider(color = cs.outline.copy(alpha = 0.4f))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(slots) { _, slot ->
                WrapperItem(
                    slot = slot,
                    onUpdate = {
                        pendingFileName = slot.fileName
                        confirmInstallPrompt = true
                    },
                    onReset = {
                        manager.removeOverride(slot.fileName)
                        Toast.makeText(context, R.string.wrapper_reset_toast, Toast.LENGTH_SHORT).show()
                        refresh()
                    },
                )
                Divider(color = cs.outline.copy(alpha = 0.25f))
            }
        }
    }

    // Confirm: install (offers in-app picker or system SAF, like AdrenoToolsScreen)
    if (confirmInstallPrompt) {
        OutlinedAlertDialog(
            onDismissRequest = { confirmInstallPrompt = false; pendingFileName = null },
            title = { Text(context.getString(R.string.wrapper_update)) },
            text = { Text(context.getString(R.string.wrapper_manager_header)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmInstallPrompt = false
                    filePicker.launch(
                        InAppFilePicker.buildIntent(
                            context,
                            InAppFilePicker.WRAPPER,
                            context.getString(R.string.wrapper_select_title),
                        )
                    )
                }) { Text("Browse files") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmInstallPrompt = false
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        filePicker.launch(intent)
                    }) { Text("Pick via system…") }
                    TextButton(onClick = { confirmInstallPrompt = false; pendingFileName = null }) {
                        Text(context.getString(android.R.string.cancel))
                    }
                }
            },
        )
    }

    // Confirm: reset all
    if (confirmResetAll) {
        OutlinedAlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text(context.getString(R.string.wrapper_reset_all_confirm_title)) },
            text = { Text(context.getString(R.string.wrapper_reset_all_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    manager.resetAll()
                    confirmResetAll = false
                    Toast.makeText(context, R.string.wrapper_reset_toast, Toast.LENGTH_SHORT).show()
                    refresh()
                }) { Text(context.getString(R.string.wrapper_reset_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) {
                    Text(context.getString(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun WrapperItem(
    slot: WrapperManager.WrapperSlot,
    onUpdate: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Layers,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = slot.label, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(text = slot.fileName, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            val stateLabel = context.getString(
                if (slot.isOverridden) R.string.wrapper_updated_label else R.string.wrapper_bundled
            )
            Text(
                text = "Version: ${slot.version} ($stateLabel)",
                style = MaterialTheme.typography.bodySmall,
                color = if (slot.isOverridden) cs.primary else cs.onSurfaceVariant,
                fontWeight = if (slot.isOverridden) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (slot.notes.isNotEmpty()) {
                Text(text = slot.notes, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(context.getString(R.string.wrapper_update))
            }
            if (slot.isOverridden) {
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = onReset) {
                    Text(context.getString(R.string.wrapper_reset))
                }
            }
        }
    }
}
