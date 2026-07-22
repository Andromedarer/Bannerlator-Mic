package com.winlator.star.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.screens.LabeledDropdown
import com.winlator.star.ui.screens.MenuItemDivider
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.SectionBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Environment-variables editor — Compose replacement for the legacy EnvVarsView
// AndroidView. Shared verbatim by the container editor (ContainerDetailScreen)
// and the per-game shortcut editor (ShortcutsScreen), so the two can never drift.
//
// Storage format is unchanged: a single space-separated "NAME=VALUE" string, as
// parsed by core/EnvVars.putAll() (split on spaces, then on the FIRST '='). A
// value therefore can NOT contain a space — every value written here is stripped
// of spaces, exactly like the legacy view did.
// ─────────────────────────────────────────────────────────────────────────────

internal enum class EnvVarType { CHECKBOX, SELECT, SELECT_MULTIPLE, NUMBER, TEXT }

/** One catalog entry. For CHECKBOX, [options] is exactly [offValue, onValue]. */
internal data class KnownEnvVar(
    val name: String,
    val type: EnvVarType,
    val options: List<String> = emptyList(),
)

/**
 * The catalog of environment variables we know how to render with a typed control.
 * Single source of truth (ported from the old EnvVarsView.knownEnvVars Java array) —
 * both editors and the add-picker read this list and nothing else.
 */
internal object KnownEnvVars {
    val all: List<KnownEnvVar> = listOf(
        KnownEnvVar("ZINK_DESCRIPTORS", EnvVarType.SELECT, listOf("auto", "lazy", "cached", "notemplates")),
        KnownEnvVar("ZINK_DEBUG", EnvVarType.SELECT_MULTIPLE, listOf("nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder")),
        KnownEnvVar("MESA_SHADER_CACHE_DISABLE", EnvVarType.CHECKBOX, listOf("false", "true")),
        KnownEnvVar("mesa_glthread", EnvVarType.CHECKBOX, listOf("false", "true")),
        KnownEnvVar("WINEESYNC", EnvVarType.CHECKBOX, listOf("0", "1")),
        KnownEnvVar("FD_DEV_FEATURES", EnvVarType.SELECT_MULTIPLE, listOf("enable_tp_ubwc_flag_hint=1", "storage_8bit=1")),
        KnownEnvVar("TU_DEBUG", EnvVarType.SELECT_MULTIPLE, listOf(
            "forcecb", "nocb", "startup", "deck_emu", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout",
            "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw",
            "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos",
            "3d_load", "fdm", "noconform", "rd")),
        KnownEnvVar("IR3_SHADER_DEBUG", EnvVarType.SELECT_MULTIPLE, listOf("nouboopt", "nopreamble", "noearlypreamble")),
        KnownEnvVar("DXVK_HUD", EnvVarType.SELECT_MULTIPLE, listOf(
            "scale=0.5", "scale=0.7", "scale=1.0", "opacity=0.5", "opacity=0.7", "devinfo", "fps", "frametimes",
            "submissions", "drawcalls", "pipelines", "descriptors", "memory", "gpuload", "version", "api", "cs",
            "compiler", "samplers")),
        KnownEnvVar("DXVK_CONFIG_FILE", EnvVarType.SELECT, listOf(
            "/storage/emulated/0/starengine.ini",
            "/storage/emulated/0/Download/starengine.ini",
            "/storage/emulated/0/Winlator/starengine.ini")),
        KnownEnvVar("MESA_EXTENSION_MAX_YEAR", EnvVarType.NUMBER),
        KnownEnvVar("WRAPPER_MAX_IMAGE_COUNT", EnvVarType.TEXT),
        KnownEnvVar("MESA_GL_VERSION_OVERRIDE", EnvVarType.TEXT),
        KnownEnvVar("PULSE_LATENCY_MSEC", EnvVarType.NUMBER),
        KnownEnvVar("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", EnvVarType.CHECKBOX, listOf("0", "1")),
        KnownEnvVar("WINE_NEW_MEDIASOURCE", EnvVarType.CHECKBOX, listOf("0", "1")),
        KnownEnvVar("GALLIUM_HUD", EnvVarType.SELECT_MULTIPLE, listOf("simple", "fps", "frametime")),
        // DXVK tuning
        KnownEnvVar("DXVK_DISABLE_TIMELINE_SEMAPHORES", EnvVarType.CHECKBOX, listOf("0", "1")),
        KnownEnvVar("DXVK_FRAME_RATE", EnvVarType.NUMBER),
        KnownEnvVar("DXVK_STATE_CACHE", EnvVarType.CHECKBOX, listOf("0", "1")),
        KnownEnvVar("DXVK_LOG_LEVEL", EnvVarType.SELECT, listOf("none", "error", "warn", "info", "debug")),
        // VKD3D (D3D12) tuning
        KnownEnvVar("VKD3D_SHADER_MODEL", EnvVarType.SELECT, listOf("6_6", "6_5", "6_4", "6_3", "6_2", "6_1", "6_0", "5_1")),
        KnownEnvVar("VKD3D_FEATURE_LEVEL", EnvVarType.SELECT, listOf("12_2", "12_1", "12_0", "11_1", "11_0")),
        KnownEnvVar("VKD3D_CONFIG", EnvVarType.SELECT_MULTIPLE, listOf(
            "dxr", "dxr11", "force_static_cbv", "no_upload_hvv", "force_host_cached", "single_queue",
            "force_bindless_texel_buffer")),
        KnownEnvVar("VKD3D_DEBUG", EnvVarType.SELECT, listOf("none", "err", "fixme", "warn", "trace")),
        // Wine tuning
        KnownEnvVar("WINEFSYNC", EnvVarType.CHECKBOX, listOf("0", "1")),
        KnownEnvVar("WINEDEBUG", EnvVarType.SELECT, listOf("-all", "fixme-all", "+seh", "+relay", "warn+all")),
        KnownEnvVar(DllOverrides.VAR, EnvVarType.TEXT),
        KnownEnvVar("WINE_FULLSCREEN_FSR", EnvVarType.CHECKBOX, listOf("0", "1")),
        KnownEnvVar("WINE_FULLSCREEN_FSR_STRENGTH", EnvVarType.NUMBER),
        // Mesa / Turnip present + driver
        KnownEnvVar("MESA_VK_WSI_PRESENT_MODE", EnvVarType.SELECT, listOf("fifo", "mailbox", "immediate", "relaxed")),
        KnownEnvVar("MESA_NO_ERROR", EnvVarType.CHECKBOX, listOf("0", "1")),
        // GPU selection / present latency
        KnownEnvVar("DXVK_FILTER_DEVICE_NAME", EnvVarType.TEXT),
        KnownEnvVar("VKD3D_SWAPCHAIN_LATENCY_FRAMES", EnvVarType.NUMBER),
    )

    private val byName = all.associateBy { it.name }

    fun find(name: String): KnownEnvVar? = byName[name]

    val names: List<String> = all.map { it.name }
}

/**
 * Helpers for the "Prefer game-folder DLLs" toggle, which is a *view* over the
 * WINEDLLOVERRIDES environment variable rather than a field of its own — there is
 * exactly one source of truth and hand-editing the variable keeps working.
 *
 * WINEDLLOVERRIDES grammar: entries separated by ';', each `dll[,dll2]=order`,
 * where order is a comma-separated list of 'n' (native) / 'b' (builtin). The
 * toggle's own signature is a single-key entry with the value "n,b".
 */
internal object DllOverrides {
    const val VAR = "WINEDLLOVERRIDES"

    /**
     * The DLLs a game may legitimately ship next to its EXE (wrappers, loaders, ASI
     * hosts, mod launchers) and that Wine is happy to load native-first.
     *
     * Graphics DLLs (d3d9/d3d10/d3d11/d3d12/dxgi) are deliberately NOT here: DXVK and
     * VKD3D own those, and forcing native would load the game's stub over the wrapper
     * and break rendering outright.
     */
    val PREFER_GAME_FOLDER: List<String> =
        listOf("version", "winmm", "dinput", "dinput8", "dsound", "xinput1_3", "msacm32", "dbghelp")

    private const val SIGNATURE = "n,b"

    /** One `dll[,dll2]=order` entry. [order] is null for a bare entry with no '='. */
    private data class Entry(val keys: List<String>, val order: String?) {
        fun render(): String = keys.joinToString(",") + if (order != null) "=$order" else ""
        fun has(dll: String): Boolean = keys.any { it.equals(dll, ignoreCase = true) }
    }

    private fun parse(raw: String): MutableList<Entry> =
        raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { part ->
                val i = part.indexOf('=')
                if (i < 0) Entry(part.split(',').map { it.trim() }.filter { it.isNotEmpty() }, null)
                else Entry(
                    part.substring(0, i).split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    part.substring(i + 1).trim()
                )
            }
            .filter { it.keys.isNotEmpty() }
            .toMutableList()

    private fun render(entries: List<Entry>): String = entries.joinToString(";") { it.render() }

    private fun isNativeFirst(order: String?): Boolean {
        val first = order?.split(',')?.firstOrNull()?.trim()?.lowercase() ?: return false
        return first == "n" || first == "native"
    }

    private fun isSignature(e: Entry): Boolean =
        e.keys.size == 1 &&
            PREFER_GAME_FOLDER.any { it.equals(e.keys[0], ignoreCase = true) } &&
            e.order?.replace(" ", "")?.lowercase() == SIGNATURE

    /** True when every DLL in the safe list already resolves native-first. */
    fun isEnabled(overrides: String): Boolean {
        if (overrides.isBlank()) return false
        val entries = parse(overrides)
        return PREFER_GAME_FOLDER.all { dll ->
            entries.any { it.has(dll) && isNativeFirst(it.order) }
        }
    }

    /**
     * Turn the option on, merging into whatever the user already wrote. Entries that are
     * already native-first are left completely alone; an entry that names the DLL with a
     * different order is rewritten (and, if it grouped several DLLs, only this DLL is
     * split out so the rest of the group keeps the user's order).
     */
    fun enable(overrides: String): String {
        val entries = parse(overrides)
        for (dll in PREFER_GAME_FOLDER) {
            val idx = entries.indexOfFirst { it.has(dll) }
            if (idx < 0) {
                entries += Entry(listOf(dll), SIGNATURE)
                continue
            }
            val existing = entries[idx]
            if (isNativeFirst(existing.order)) continue
            if (existing.keys.size == 1) {
                entries[idx] = Entry(listOf(dll), SIGNATURE)
            } else {
                entries[idx] = existing.copy(keys = existing.keys.filterNot { it.equals(dll, ignoreCase = true) })
                entries += Entry(listOf(dll), SIGNATURE)
            }
        }
        return render(entries)
    }

    /**
     * Turn the option off: drop only the entries that carry the toggle's own signature,
     * then put back anything [baseline] (the value as it stood when the editor opened)
     * had for those DLLs. A hand-written `version=b,n`, a grouped `version,winmm=n` or an
     * entry for a DLL outside the safe list is never touched.
     */
    fun disable(overrides: String, baseline: String): String {
        val entries = parse(overrides).filterNot { isSignature(it) }.toMutableList()
        val baselineEntries = parse(baseline)
        for (dll in PREFER_GAME_FOLDER) {
            if (entries.any { it.has(dll) }) continue
            baselineEntries.firstOrNull { it.keys.size == 1 && it.has(dll) }?.let { entries += it }
        }
        return render(entries)
    }

    /**
     * The value to restore to when the toggle is switched off later in this session.
     * If the option is already on when the editor opens, a previous session wrote those
     * signature entries, so they are stripped from the baseline — otherwise the toggle
     * could never be switched back off. (A hand-written override that happens to be
     * byte-identical to our signature is indistinguishable from ours and shares that fate.)
     */
    fun baselineOf(overrides: String): String =
        if (isEnabled(overrides)) render(parse(overrides).filterNot { isSignature(it) }) else overrides
}

// ─────────────────────────────────────────────────────────────────────────────

/** One editable row. [id] keeps Compose identity stable while the name is edited. */
private data class EnvRow(val id: Int, val name: String, val value: String)

private fun parseRows(raw: String, nextId: () -> Int): List<EnvRow> =
    raw.split(" ")
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else EnvRow(nextId(), part.substring(0, i), part.substring(i + 1))
        }

private fun renderRows(rows: List<EnvRow>): String =
    rows.filter { it.name.isNotBlank() && it.value.isNotBlank() }
        .joinToString(" ") { "${it.name}=${it.value}" }

/**
 * The shared environment-variables editor.
 *
 * @param value  the current space-separated "NAME=VALUE" string.
 * @param onValueChange called with the new string on every edit — the caller owns the state,
 *        so nothing has to be flushed back when this composable leaves composition.
 * @param gameDir  when non-null (shortcut editor), the game's folder is scanned off the main
 *        thread for shipped Windows DLLs and an actionable hint is offered. The container
 *        editor has no single game folder and passes null.
 */
@Composable
internal fun EnvVarsEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    gameDir: File? = null,
) {
    val idSource = remember { intArrayOf(0) }
    val nextId = { idSource[0]++ }
    val rows: SnapshotStateList<EnvRow> = remember { mutableStateListOf<EnvRow>().apply { addAll(parseRows(value, nextId)) } }
    // What we last pushed out, so an external change to `value` (a different container being
    // loaded into the same ViewModel) re-seeds the rows while our own edits do not.
    var lastEmitted by remember { mutableStateOf(value) }
    // The WINEDLLOVERRIDES value as it stood when the editor opened — see DllOverrides.disable().
    var dllBaseline by remember {
        mutableStateOf(DllOverrides.baselineOf(rows.firstOrNull { it.name == DllOverrides.VAR }?.value ?: ""))
    }

    var showAddPicker by remember { mutableStateOf(false) }
    var rawMode by remember { mutableStateOf(false) }
    var foundDlls by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(value) {
        if (value != lastEmitted) {
            rows.clear()
            rows.addAll(parseRows(value, nextId))
            lastEmitted = value
            dllBaseline = DllOverrides.baselineOf(rows.firstOrNull { it.name == DllOverrides.VAR }?.value ?: "")
        }
    }

    // Detection hint (shortcut editor only): does the game ship any of the safe-list DLLs?
    // Directory listing is I/O, so it runs off the main thread; an unreadable folder simply
    // yields no hint. We never apply anything on our own — some games ship a dsound.dll or
    // dbghelp.dll that actually works better on builtin, so flipping this without the user
    // asking could regress a game that runs fine today.
    LaunchedEffect(gameDir) {
        val dir = gameDir ?: return@LaunchedEffect
        foundDlls = withContext(Dispatchers.IO) {
            runCatching {
                val present = dir.list()?.map { it.lowercase() }?.toSet() ?: emptySet()
                DllOverrides.PREFER_GAME_FOLDER.filter { "$it.dll" in present }
            }.getOrDefault(emptyList())
        }
    }

    fun emit() {
        val rendered = renderRows(rows)
        lastEmitted = rendered
        onValueChange(rendered)
    }

    fun setRow(index: Int, newValue: String) {
        rows[index] = rows[index].copy(value = newValue.trim().replace(" ", ""))
        emit()
    }

    fun putVar(name: String, newValue: String) {
        val index = rows.indexOfFirst { it.name == name }
        if (newValue.isEmpty()) {
            if (index >= 0) rows.removeAt(index)
        } else if (index >= 0) {
            rows[index] = rows[index].copy(value = newValue)
        } else {
            rows.add(EnvRow(nextId(), name, newValue))
        }
        emit()
    }

    val overrides = rows.firstOrNull { it.name == DllOverrides.VAR }?.value ?: ""
    val preferGameFolder = DllOverrides.isEnabled(overrides)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── Compatibility (pinned to the top) ────────────────────────────────
        // Hidden while the raw text editor is open: there the user is authoring the
        // whole string by hand, and a helper that rewrites it under them would fight
        // the cursor.
        if (!rawMode) SectionBox(title = "Compatibility") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = preferGameFolder,
                    onCheckedChange = { on ->
                        putVar(
                            DllOverrides.VAR,
                            if (on) DllOverrides.enable(overrides) else DllOverrides.disable(overrides, dllBaseline)
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Prefer game-folder DLLs")
                    Text(
                        "Loads the game's own copies of " + DllOverrides.PREFER_GAME_FOLDER.joinToString(", ") +
                            " instead of Wine's. Needed by many wrappers and mod loaders. Graphics DLLs are " +
                            "never included — DXVK/VKD3D own those.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (foundDlls.isNotEmpty() && !preferGameFolder) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        foundDlls.joinToString(", ") { "$it.dll" } +
                            " found in the game folder. This game may need the option above.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { putVar(DllOverrides.VAR, DllOverrides.enable(overrides)) }) {
                        Text("Enable")
                    }
                }
            }
        }

        // ── Variables ────────────────────────────────────────────────────────
        SectionBox(title = "Environment Variables") {
            if (rawMode) {
                // Escape hatch: edit the whole string by hand. Same format that gets stored,
                // so anything the typed controls can't express can still be entered here.
                var rawText by remember { mutableStateOf(renderRows(rows)) }
                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        // Keep the row model in step so the typed controls (and the
                        // compatibility toggle) are already correct on the way back out.
                        rows.clear()
                        rows.addAll(parseRows(it, nextId))
                        lastEmitted = it
                        onValueChange(it)
                    },
                    label = { Text("NAME=VALUE, separated by spaces") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                )
            } else if (rows.isEmpty()) {
                Text(
                    "No environment variables set",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                rows.forEachIndexed { index, row ->
                    if (index > 0) MenuItemDivider()
                    key(row.id) {
                        EnvVarRow(
                            row = row,
                            onValueChange = { setRow(index, it) },
                            onRemove = { rows.removeAt(index); emit() }
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showAddPicker = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add Variable")
            }
            TextButton(onClick = { rawMode = !rawMode }) {
                Text(if (rawMode) "Done" else "Edit as text")
            }
        }
    }

    if (showAddPicker) {
        AddEnvVarPicker(
            existingNames = rows.map { it.name }.toSet(),
            onAdd = { name ->
                val clean = name.trim().replace(" ", "")
                if (clean.isNotEmpty() && rows.none { it.name == clean }) {
                    rows.add(EnvRow(nextId(), clean, defaultValueFor(clean)))
                    emit()
                }
                showAddPicker = false
            },
            onDismiss = { showAddPicker = false }
        )
    }
}

/** Seed value for a freshly added variable so the row renders with a sane control state. */
private fun defaultValueFor(name: String): String {
    val known = KnownEnvVars.find(name) ?: return ""
    return when (known.type) {
        EnvVarType.CHECKBOX -> known.options.getOrElse(0) { "0" }
        EnvVarType.SELECT -> known.options.firstOrNull() ?: ""
        else -> ""
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnvVarRow(
    row: EnvRow,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val known = KnownEnvVars.find(row.name)
    val type = known?.type ?: EnvVarType.TEXT
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            when (type) {
                EnvVarType.CHECKBOX -> {
                    val offValue = known?.options?.getOrNull(0) ?: "0"
                    val onValue = known?.options?.getOrNull(1) ?: "1"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = row.value == onValue || row.value == "1" || row.value == "true",
                            onCheckedChange = { onValueChange(if (it) onValue else offValue) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(row.name, modifier = Modifier.weight(1f))
                    }
                }
                EnvVarType.SELECT -> {
                    val options = known?.options.orEmpty()
                    LabeledDropdown(
                        label = row.name,
                        options = options,
                        selectedOption = row.value.ifEmpty { options.firstOrNull() ?: "" },
                        onSelect = onValueChange
                    )
                }
                EnvVarType.SELECT_MULTIPLE -> {
                    val selected = row.value.split(",").filter { it.isNotBlank() }
                    Column {
                        Text(
                            row.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            known?.options.orEmpty().forEach { option ->
                                val isOn = option in selected
                                FilterChip(
                                    selected = isOn,
                                    onClick = {
                                        val next = if (isOn) selected - option else selected + option
                                        onValueChange(next.joinToString(","))
                                    },
                                    label = { Text(option, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
                EnvVarType.NUMBER, EnvVarType.TEXT -> {
                    OutlinedTextField(
                        value = row.value,
                        onValueChange = onValueChange,
                        label = { Text(row.name) },
                        singleLine = true,
                        keyboardOptions = if (type == EnvVarType.NUMBER)
                            KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove ${row.name}")
        }
    }
}

/**
 * Searchable add-picker. The catalog is well past 30 entries, so a flat list is unusable —
 * the query filters the catalog and doubles as the name field for a variable we don't know
 * about, which is the free-form escape hatch for anything outside the catalog.
 */
@Composable
private fun AddEnvVarPicker(
    existingNames: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val candidates = remember(query, existingNames) {
        KnownEnvVars.names
            .filter { it !in existingNames }
            .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    }
    val typed = query.trim().replace(" ", "")
    val isNewName = typed.isNotEmpty() && typed !in KnownEnvVars.names && typed !in existingNames

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add environment variable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search or type a name") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (isNewName) {
                        TextButton(onClick = { onAdd(typed) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Add custom variable \"$typed\"", modifier = Modifier.weight(1f))
                        }
                        if (candidates.isNotEmpty()) MenuItemDivider()
                    }
                    candidates.forEachIndexed { index, name ->
                        if (index > 0) MenuItemDivider()
                        TextButton(onClick = { onAdd(name) }, modifier = Modifier.fillMaxWidth()) {
                            Text(name, modifier = Modifier.weight(1f))
                        }
                    }
                    if (candidates.isEmpty() && !isNewName) {
                        Text(
                            "No matches",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
