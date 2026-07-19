package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.R
import com.winlator.star.contents.WrapperManager
import com.winlator.star.core.StringUtils
import com.winlator.star.util.InAppFilePicker

/**
 * Wrapper Version Manager (issue #132). Step 1: a fixed-slot updater — each bundled graphics-wrapper
 * archive gets Update (swap in a newer .tzst of the same name) + Reset (revert to the built-in).
 * Step 2: free-form IMPORTED wrappers — bring your own wrapper, name it, delete it; imports appear in
 * the Graphics Driver dropdown. Overrides + imports live at filesDir/graphics_driver/<name> and win
 * at game launch.
 */
/** Full-screen entry point (reached from the app drawer via Screen.Wrappers). Chrome + scroll only;
 *  the actual slot list / actions live in [WrapperManagerBody] so the inline dialog can reuse them. */
@Composable
fun WrapperManagerScreen() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .verticalScroll(rememberScrollState())
    ) {
        WrapperManagerBody()
    }
}

/**
 * Inline dialog entry point: the same wrapper manager surfaced from the Graphics Driver row's cloud
 * button in the Container editor and the Game/Shortcut editor. Reuses [WrapperManagerBody] verbatim
 * (including its file-picker launcher and toasts) so there is a single install implementation.
 */
@Composable
fun WrapperManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        // Use (almost) the full screen width — the default AlertDialog width squeezes the wrapper
        // cards so their labels truncate. usePlatformDefaultWidth=false lets the modifier size it.
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(context.getString(R.string.wrapper_manager_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                WrapperManagerBody()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.wrapper_manager_close))
            }
        },
    )
}

/**
 * The wrapper-manager content, minus any Scaffold/top-bar/scroll chrome: header + Reset-all, the slot
 * cards (Update/Reset), the imported-wrapper cards (Delete), the Import affordance, plus the
 * file-picker launcher and confirm/name dialogs. Callers wrap it in their own scroll container.
 */
@Composable
fun WrapperManagerBody(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // WrapperManager only needs a Context (it calls getApplicationContext). Do NOT cast to Activity:
    // when this body is hosted inside a dialog, LocalContext is a ContextThemeWrapper, not an
    // Activity, and the cast crashes (device-verified). Pass the context straight through.
    val manager = remember { WrapperManager(context) }
    val cs = MaterialTheme.colorScheme

    var slots by remember { mutableStateOf(manager.listSlots().toList()) }
    var imported by remember { mutableStateOf(manager.enumerateImported().toList()) }
    // The slot awaiting a picked file (set before the picker launches so the result knows its target).
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    // True when the pending file-pick is for a free-form import (vs a slot override update).
    var importMode by remember { mutableStateOf(false) }
    var confirmInstallPrompt by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    // Import naming: set after an import file is picked; drives the name dialog.
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importNameDraft by remember { mutableStateOf("") }
    // Imported wrapper queued for deletion (confirm dialog).
    var deleteTarget by remember { mutableStateOf<WrapperManager.Imported?>(null) }

    fun refresh() {
        slots = manager.listSlots().toList()
        imported = manager.enumerateImported().toList()
    }

    // Single file picker, shared by slot-Update and free-form Import (importMode disambiguates).
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingFileName
        val wasImport = importMode
        pendingFileName = null
        importMode = false
        if (result.resultCode == Activity.RESULT_OK) {
            // In-app picker returns a path; SAF returns result.data.data.
            val path = InAppFilePicker.pickedPath(result.data)
            val uri = result.data?.data ?: path?.let { InAppFilePicker.asUri(it) }
            if (uri != null) {
                if (wasImport) {
                    // Default the display name to the file's base name.
                    val base = (path?.substringAfterLast('/') ?: uri.lastPathSegment?.substringAfterLast('/'))
                        ?.substringBeforeLast('.')
                        ?.takeIf { it.isNotBlank() }
                        ?: "Imported wrapper"
                    importNameDraft = base
                    pendingImportUri = uri
                } else if (target != null) {
                    if (manager.installOverride(target, uri)) {
                        Toast.makeText(context, R.string.wrapper_updated_toast, Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        Toast.makeText(context, R.string.wrapper_invalid_toast, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun launchImportPicker() {
        importMode = true
        confirmInstallPrompt = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
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
        Spacer(Modifier.size(4.dp))

        // Fixed bundled slots.
        slots.forEach { slot ->
            WrapperSlotCard(
                slot = slot,
                onUpdate = {
                    pendingFileName = slot.fileName
                    importMode = false
                    confirmInstallPrompt = true
                },
                onReset = {
                    manager.removeOverride(slot.fileName)
                    Toast.makeText(context, R.string.wrapper_reset_toast, Toast.LENGTH_SHORT).show()
                    refresh()
                },
            )
        }

        // Imported (free-form) wrappers.
        imported.forEach { imp ->
            ImportedWrapperCard(
                imported = imp,
                onDelete = { deleteTarget = imp },
            )
        }

        // Import affordance.
        ImportWrapperCard(onClick = { launchImportPicker() })
        Spacer(Modifier.size(8.dp))
    }

    // Confirm: pick a file (offers in-app picker or system SAF, like AdrenoToolsScreen). Shared by
    // slot-Update and Import; the pending flags decide what happens with the picked file.
    if (confirmInstallPrompt) {
        OutlinedAlertDialog(
            onDismissRequest = { confirmInstallPrompt = false; pendingFileName = null; importMode = false },
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
                    TextButton(onClick = {
                        confirmInstallPrompt = false; pendingFileName = null; importMode = false
                    }) {
                        Text(context.getString(android.R.string.cancel))
                    }
                }
            },
        )
    }

    // Name an imported wrapper (shown after its file is picked).
    val importUri = pendingImportUri
    if (importUri != null) {
        OutlinedAlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(context.getString(R.string.wrapper_import_name_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = context.getString(R.string.wrapper_import_name_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(10.dp))
                    OutlinedTextField(
                        value = importNameDraft,
                        onValueChange = { importNameDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = importNameDraft.trim()
                    val id = StringUtils.parseIdentifier(name)
                    when {
                        name.isEmpty() ->
                            Toast.makeText(context, R.string.wrapper_import_name_empty_toast, Toast.LENGTH_SHORT).show()
                        manager.isReservedIdentifier(id) ->
                            Toast.makeText(context, R.string.wrapper_import_reserved_toast, Toast.LENGTH_LONG).show()
                        else -> {
                            pendingImportUri = null
                            if (manager.importWrapper(importUri, name) != null) {
                                Toast.makeText(context, R.string.wrapper_imported_toast, Toast.LENGTH_SHORT).show()
                                refresh()
                            } else {
                                Toast.makeText(context, R.string.wrapper_invalid_toast, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text(context.getString(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text(context.getString(android.R.string.cancel))
                }
            },
        )
    }

    // Confirm: delete an imported wrapper (runs the reference cascade).
    val toDelete = deleteTarget
    if (toDelete != null) {
        OutlinedAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(context.getString(R.string.wrapper_delete_confirm_title)) },
            text = { Text(context.getString(R.string.wrapper_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    manager.deleteImported(toDelete.identifier)
                    deleteTarget = null
                    Toast.makeText(context, R.string.wrapper_reset_toast, Toast.LENGTH_SHORT).show()
                    refresh()
                }) { Text(context.getString(R.string.wrapper_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(context.getString(android.R.string.cancel))
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

/**
 * A compact card in the container-card idiom (RoundedCornerShape(12), surfaceVariant, 1dp outline
 * border) but tighter: a ~40dp icon tile, a weight(1f) info column (title + dimmed subtitle), and a
 * trailing actions slot. Shared frame for slot cards, imported cards, and the import affordance.
 */
@Composable
private fun WrapperCardFrame(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    highlightSubtitle: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val base = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
    Card(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        border = BorderStroke(1.dp, cs.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlightSubtitle) cs.primary else cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun WrapperSlotCard(
    slot: WrapperManager.WrapperSlot,
    onUpdate: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val stateLabel = context.getString(
        if (slot.isOverridden) R.string.wrapper_updated_label else R.string.wrapper_bundled
    )
    val subtitle = buildString {
        append(slot.fileName)
        append(" · Version: ").append(slot.version).append(" (").append(stateLabel).append(")")
        if (slot.notes.isNotEmpty()) append(" · ").append(slot.notes)
    }
    WrapperCardFrame(
        icon = Icons.Filled.Layers,
        title = slot.label,
        subtitle = subtitle,
        highlightSubtitle = slot.isOverridden,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onUpdate) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.wrapper_update))
                }
                if (slot.isOverridden) {
                    TextButton(onClick = onReset) {
                        Text(context.getString(R.string.wrapper_reset))
                    }
                }
            }
        },
    )
}

@Composable
private fun ImportedWrapperCard(
    imported: WrapperManager.Imported,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val subtitle = "${imported.identifier}.tzst · " + context.getString(R.string.wrapper_imported_label)
    WrapperCardFrame(
        icon = Icons.Filled.Layers,
        title = imported.label,
        subtitle = subtitle,
        highlightSubtitle = true,
        trailing = {
            TextButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = cs.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(context.getString(R.string.wrapper_delete), color = cs.error)
            }
        },
    )
}

@Composable
private fun ImportWrapperCard(onClick: () -> Unit) {
    val context = LocalContext.current
    WrapperCardFrame(
        icon = Icons.Filled.Add,
        title = context.getString(R.string.wrapper_import_title),
        subtitle = context.getString(R.string.wrapper_import_subtitle),
        onClick = onClick,
    )
}
