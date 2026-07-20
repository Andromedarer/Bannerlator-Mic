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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.R
import com.winlator.star.contents.WrapperManager
import com.winlator.star.contents.WrapperSettingsDictionary
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.StringUtils
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // Resolve the real GPU once (native probe): vendor 0x5143 = Qualcomm/Adreno, plus a friendly model
    // name for the applicability lines. Wrapped defensively so a probe hiccup never crashes the screen.
    val gpu = remember {
        val vendor = runCatching { GPUInformation.getVendorID(null, null) }.getOrDefault(0)
        val model = runCatching { GPUInformation.extractModelName(GPUInformation.getRenderer(null, null)) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "this GPU"
        GpuInfo(isQualcomm = vendor == 0x5143, model = model)
    }

    var slots by remember { mutableStateOf(manager.listSlots().toList()) }
    var imported by remember { mutableStateOf(manager.enumerateImported().toList()) }
    // The slot awaiting a picked file (set before the picker launches so the result knows its target).
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    // True when the pending file-pick is for a free-form import (vs a slot override update).
    var importMode by remember { mutableStateOf(false) }
    var confirmInstallPrompt by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    // Import naming: set after an import file is picked AND inspected; drives the inspection+name dialog.
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingInspection by remember { mutableStateOf<WrapperManager.Inspection?>(null) }
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
                    // Pre-import INSPECT: validate + scan the picked file WITHOUT installing. Invalid
                    // archives abort here with the usual toast; valid ones open the inspection+name
                    // dialog so the user decides with full capability info before Add.
                    val inspection = manager.inspectUri(uri)
                    if (!inspection.valid) {
                        Toast.makeText(context, R.string.wrapper_invalid_toast, Toast.LENGTH_LONG).show()
                    } else {
                        // Default the display name to the file's base name.
                        val base = (path?.substringAfterLast('/') ?: uri.lastPathSegment?.substringAfterLast('/'))
                            ?.substringBeforeLast('.')
                            ?.takeIf { it.isNotBlank() }
                            ?: "Imported wrapper"
                        importNameDraft = base
                        pendingInspection = inspection
                        pendingImportUri = uri
                    }
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
                caps = manager.capsFor(slot.fileName.removeSuffix(".tzst")),
                gpu = gpu,
                manager = manager,
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
                manager = manager,
                gpu = gpu,
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

    // Pre-import INSPECTION + name (shown after a valid file is picked+inspected). Shows what the
    // wrapper is, its detected capabilities, what it will add / needs to work, then the Name field.
    val importUri = pendingImportUri
    val inspection = pendingInspection
    if (importUri != null && inspection != null) {
        fun closeInspect() { pendingImportUri = null; pendingInspection = null }
        OutlinedAlertDialog(
            onDismissRequest = { closeInspect() },
            title = { Text(context.getString(R.string.wrapper_import_title).removeSuffix("…")) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // What it is.
                    DetailRow("What it is", importDescription(inspection.caps))
                    DetailRow(
                        "Version",
                        if (inspection.notes.isNotBlank()) "${inspection.version} · ${inspection.notes}"
                        else inspection.version,
                    )
                    val labels = capLabels(inspection.caps)
                    if (labels.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Detected capabilities",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(4.dp))
                        CapabilityChips(labels)
                    }
                    // What will be added / needs to work.
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "What will be added",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(2.dp))
                    importNeeds(inspection.caps, gpu, inspection.version).forEach { line ->
                        Text(
                            "• $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    // #132 Layer 1: env-var names auto-detected from the binaries (advanced preview).
                    if (inspection.envKeys.isNotEmpty()) {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Detected settings (${inspection.envKeys.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            inspection.envKeys.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    // Name field.
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = context.getString(R.string.wrapper_import_name_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(6.dp))
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
                            closeInspect()
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
                TextButton(onClick = { closeInspect() }) {
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
    // Expandable detail (Part 1): when expandable, tapping the card toggles [expanded] and a chevron
    // is shown; [details] renders inside the card below a divider while expanded.
    expandable: Boolean = false,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    details: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val base = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
    // A card is either a plain click affordance (import card) or an expandable detail card; both map
    // to a single clickable action here.
    val clickAction: (() -> Unit)? = onClick ?: (if (expandable) onToggleExpand else null)
    Card(
        modifier = if (clickAction != null) base.clickable(onClick = clickAction) else base,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        border = BorderStroke(1.dp, cs.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                if (expandable) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            if (expandable && expanded) {
                Divider(color = cs.outline.copy(alpha = 0.4f))
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    details()
                }
            }
        }
    }
}

/** GPU probe result used by the applicability lines. */
private data class GpuInfo(val isQualcomm: Boolean, val model: String)

/** Chip labels for the true capabilities of a wrapper (in a stable order). */
private fun capLabels(caps: WrapperManager.WrapperCaps): List<String> {
    val out = ArrayList<String>()
    if (caps.hasIcd) out.add("Vulkan ICD")
    if (caps.hasBcnLayer) out.add("BCn layer")
    if (caps.hasCompatLayer) out.add("DX12/compat layer")
    return out
}

/** One-line "what is this" summary for the inspection page (a valid import always carries an ICD). */
private fun importDescription(caps: WrapperManager.WrapperCaps): String = when {
    caps.hasIcd && (caps.hasBcnLayer || caps.hasCompatLayer) -> "Vulkan ICD wrapper with extra layers"
    caps.hasIcd -> "Vulkan ICD wrapper"
    caps.hasBcnLayer -> "BCn layer"
    caps.hasCompatLayer -> "DX12 compat layer"
    else -> "Wrapper"
}

/** GPU-applicability one-liner for the detail view; null when there are no GPU-gated layers. */
private fun gpuApplicability(caps: WrapperManager.WrapperCaps, gpu: GpuInfo): String? {
    val layerish = caps.hasBcnLayer || caps.hasCompatLayer
    return when {
        layerish && gpu.isQualcomm ->
            "BCn/compat layers apply to Mali/non-Qualcomm GPUs — inert on this Adreno (${gpu.model})"
        layerish -> "Applies to this GPU (${gpu.model})"
        else -> null
    }
}

/** Plain "what will be added / needs to work" lines for the inspection page. */
private fun importNeeds(caps: WrapperManager.WrapperCaps, gpu: GpuInfo, version: String): List<String> {
    val out = ArrayList<String>()
    if (caps.hasBcnLayer) {
        out.add(
            "Contains a BCn layer → the BCn Layer Settings become available; requires a Mali/non-Qualcomm GPU to take effect" +
                if (gpu.isQualcomm) " (this device is Adreno — inert)." else "."
        )
    }
    if (caps.hasCompatLayer) {
        out.add("Contains a DX12/compat layer → compat options (Mali-branch feature).")
    }
    if (!caps.hasBcnLayer && !caps.hasCompatLayer) {
        out.add("Plain Vulkan ICD wrapper — no extra layer settings.")
    }
    if (version == "Unknown") {
        out.add("No version.txt — version will show Unknown.")
    }
    return out
}

/** A label · value line in the container-card idiom (dimmed label, on-surface value). */
@Composable
private fun DetailRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Small rounded chips for detected capabilities. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityChips(labels: List<String>) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * The shared per-wrapper detail box (Part 1) rendered inside an expanded card. Below the capability
 * chips it carries a NESTED, independently-expandable "Environment variables" section that lists the
 * full set of env-var names the wrapper references (issue #132) — read-only, informational.
 *
 * [manager] + [isImported] + [envSourceId] drive that env-var list: for an imported wrapper the keys
 * come from its cached .meta ([WrapperManager.detectedEnvKeys], [envSourceId] = identifier); for a
 * bundled slot they are scanned ON DEMAND from the asset/override ([WrapperManager.scanBundledEnvKeys],
 * [envSourceId] = slot file name).
 */
@Composable
private fun WrapperDetailBox(
    fileName: String,
    stateLabel: String,
    version: String,
    notes: String,
    caps: WrapperManager.WrapperCaps,
    gpu: GpuInfo,
    manager: WrapperManager,
    isImported: Boolean,
    envSourceId: String,
) {
    val cs = MaterialTheme.colorScheme
    DetailRow("File", fileName)
    DetailRow("State", stateLabel)
    DetailRow("Version", version)
    if (notes.isNotBlank()) DetailRow("Notes", notes)
    val labels = capLabels(caps)
    if (labels.isNotEmpty()) {
        Spacer(Modifier.size(8.dp))
        Text(
            "Detected capabilities",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        CapabilityChips(labels)
    }
    gpuApplicability(caps, gpu)?.let {
        Spacer(Modifier.size(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    EnvVarSection(manager = manager, isImported = isImported, envSourceId = envSourceId)
}

/**
 * A nested, independently-expandable "Environment variables" row inside [WrapperDetailBox]. Default
 * collapsed; opening it triggers the (bounded, off-main) scan ONLY when the user asks. Each entry
 * shows the friendly dictionary label ([WrapperSettingsDictionary.defFor]) plus the raw env key in a
 * dim monospace face. Read-only.
 */
@Composable
private fun EnvVarSection(
    manager: WrapperManager,
    isImported: Boolean,
    envSourceId: String,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(envSourceId) { mutableStateOf(false) }
    // null = not yet scanned (show a spinner); non-null = resolved (possibly empty) list.
    var keys by remember(envSourceId) { mutableStateOf<List<String>?>(null) }

    // Scan ON DEMAND: bundled slots are multi-MB zstd tars we skip on screen-open, so we only pay the
    // decompress when the user opens THIS section. Off the main thread; WrapperManager caches bundled
    // scans so a re-expand is instant. Imported wrappers read from their .meta (near-instant) but we
    // still hop off-main for uniformity + the older-import backfill path. Never crashes (empty list).
    LaunchedEffect(expanded, envSourceId) {
        if (expanded && keys == null) {
            keys = withContext(Dispatchers.IO) {
                runCatching {
                    if (isImported) manager.detectedEnvKeys(envSourceId)
                    else manager.scanBundledEnvKeys(envSourceId)
                }.getOrDefault(emptyList())
            }
        }
    }

    Spacer(Modifier.size(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = keys?.let { "Environment variables (${it.size})" } ?: "Environment variables",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    if (expanded) {
        val resolved = keys
        when {
            resolved == null ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Scanning…",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            resolved.isEmpty() ->
                Text(
                    "No environment variables detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            else ->
                // Height-bounded + scrollable so a long list can't blow out the width-constrained dialog.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    resolved.forEach { key ->
                        EnvVarRow(label = WrapperSettingsDictionary.defFor(key).label, rawKey = key)
                    }
                }
        }
    }
}

/** One read-only env-var entry: friendly label (when the dictionary has one) over the raw key in a
 *  dim monospace face. A generic key (no dictionary hit) shows just the mono key — defFor returns the
 *  key as its own label, so we skip the duplicate line. */
@Composable
private fun EnvVarRow(label: String, rawKey: String) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        if (label != rawKey) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
        }
        Text(
            rawKey,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun WrapperSlotCard(
    slot: WrapperManager.WrapperSlot,
    caps: WrapperManager.WrapperCaps,
    gpu: GpuInfo,
    manager: WrapperManager,
    onUpdate: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
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
        expandable = true,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        details = {
            WrapperDetailBox(
                fileName = slot.fileName,
                stateLabel = stateLabel,
                version = slot.version,
                notes = slot.notes,
                caps = caps,
                gpu = gpu,
                manager = manager,
                isImported = false,
                envSourceId = slot.fileName,
            )
        },
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
    manager: WrapperManager,
    gpu: GpuInfo,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val subtitle = "${imported.identifier}.tzst · " + context.getString(R.string.wrapper_imported_label)
    // Detected caps + version.txt for this import — read once per card (off the launch path).
    val caps = remember(imported.identifier) { manager.capsFor(imported.identifier) }
    val versionInfo = remember(imported.identifier) {
        manager.readVersionInfo(manager.importedFileFor(imported.identifier))
    }
    WrapperCardFrame(
        icon = Icons.Filled.Layers,
        title = imported.label,
        subtitle = subtitle,
        highlightSubtitle = true,
        expandable = true,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        details = {
            WrapperDetailBox(
                fileName = "${imported.identifier}.tzst",
                stateLabel = context.getString(R.string.wrapper_imported_label),
                version = versionInfo[0],
                notes = versionInfo[1],
                caps = caps,
                gpu = gpu,
                manager = manager,
                isImported = true,
                envSourceId = imported.identifier,
            )
        },
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
