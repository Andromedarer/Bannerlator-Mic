package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.star.core.FileUtils
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogLocation
import com.winlator.star.core.LogcatCapture
import kotlinx.coroutines.launch
import java.io.File

/**
 * App Settings → Log Manager. One place for everything logging: where logs go, which ones are
 * produced, how many past runs are kept, and what is on disk right now per game.
 *
 * Mirrors the Performance screen deliberately — same card layout, same always-live "?" help button
 * on every toggle. Here the help copy leads with the PERFORMANCE COST, because two of these
 * genuinely slow games down and users need to know that before leaving one on.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LogManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var perGame by remember { mutableStateOf(prefs.getBoolean(LogLocation.PREF_PER_GAME, true)) }
    var keepLast by remember {
        mutableStateOf(prefs.getInt(LogLocation.PREF_KEEP_LAST, LogLocation.DEFAULT_KEEP_LAST))
    }
    var wineDebug by remember { mutableStateOf(prefs.getBoolean("enable_wine_debug", false)) }
    var box64Logs by remember { mutableStateOf(prefs.getBoolean("enable_box64_logs", false)) }
    var dxvkLogs by remember { mutableStateOf(prefs.getBoolean("enable_dxvk_logs", true)) }
    var logcat by remember { mutableStateOf(prefs.getBoolean("enable_logcat", true)) }
    var crashReports by remember { mutableStateOf(prefs.getBoolean("enable_crash_reports", true)) }

    // Location + channels moved here from the old Settings › Logs section, which this screen
    // replaces. They used to be saved by the Settings "Save" FAB; here every change is written
    // immediately, matching the Performance screen and removing the risk of two screens holding
    // the same preference and one overwriting the other on save.
    var locationMode by remember {
        mutableStateOf(prefs.getString(LogLocation.PREF_MODE, LogLocation.MODE_APP_DATA) ?: LogLocation.MODE_APP_DATA)
    }
    var customPath by remember {
        mutableStateOf(prefs.getString(LogLocation.PREF_CUSTOM_PATH, "") ?: "")
    }
    var showLocationMenu by remember { mutableStateOf(false) }
    var channels by remember {
        mutableStateOf(
            (prefs.getString("wine_debug_channels", com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS)
                ?: com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS)
                .split(",").filter { it.isNotBlank() }
        )
    }
    var showChannelPicker by remember { mutableStateOf(false) }

    // Bumped after any destructive/refreshing action to force a re-scan of the filesystem.
    // Declared before saveMode() below, which increments it — a Kotlin local function can only
    // capture locals declared above it.
    var refreshTick by remember { mutableStateOf(0) }

    fun saveMode(mode: String) {
        locationMode = mode
        prefs.edit().putString(LogLocation.PREF_MODE, mode).apply()
        refreshTick++
    }
    fun saveChannels(list: List<String>) {
        channels = list
        prefs.edit().putString("wine_debug_channels", list.joinToString(",")).apply()
    }

    val dirLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            InAppFilePicker.pickedPath(result.data)?.let { path ->
                customPath = path
                prefs.edit().putString(LogLocation.PREF_CUSTOM_PATH, path).apply()
                saveMode(LogLocation.MODE_CUSTOM)
            }
        }
    }

    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showExplainAll by remember { mutableStateOf(false) }
    // Folder the embedded File Manager is showing, or null when it is closed.
    var browseDir by remember { mutableStateOf<File?>(null) }
    val entries = remember(refreshTick, perGame) { LogInventory.scan(context) }

    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Log Manager",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            // ── Where logs go ────────────────────────────────────────────
            SectionLabel("Where logs go")
            LogCard {
                PickRow(
                    label = when (locationMode) {
                        LogLocation.MODE_DOWNLOAD -> "Download"
                        LogLocation.MODE_DOCUMENTS -> "Documents"
                        LogLocation.MODE_CUSTOM -> if (customPath.isNotEmpty()) "Custom folder" else "Choose folder…"
                        else -> "App data (default)"
                    },
                    sub = LogLocation.resolveLogDir(context)?.absolutePath ?: "—",
                    action = "Change",
                    onClick = { showLocationMenu = true }
                )
                Box {
                    DropdownMenu(expanded = showLocationMenu, onDismissRequest = { showLocationMenu = false }) {
                        DropdownMenuItem(text = { Text("App data (default)") },
                            onClick = { saveMode(LogLocation.MODE_APP_DATA); showLocationMenu = false })
                        DropdownMenuItem(text = { Text("Download") },
                            onClick = { saveMode(LogLocation.MODE_DOWNLOAD); showLocationMenu = false })
                        DropdownMenuItem(text = { Text("Documents") },
                            onClick = { saveMode(LogLocation.MODE_DOCUMENTS); showLocationMenu = false })
                        DropdownMenuItem(text = { Text("Choose folder…") },
                            onClick = {
                                showLocationMenu = false
                                dirLauncher.launch(InAppFilePicker.buildDirIntent(context, "Select log folder"))
                            })
                    }
                }
                LogToggle("Folder for each game", perGame,
                    hint = "Each game keeps its own folder",
                    onInfo = { info = "Folder for each game" to LogCopy.PER_GAME }) {
                    perGame = it; putBool(LogLocation.PREF_PER_GAME, it); refreshTick++
                }
            }

            // ── What to record ───────────────────────────────────────────
            SectionLabel("What to record")
            LogCard {
                LogToggle("Wine debug", wineDebug,
                    hint = "Slows games down — for diagnosing a problem",
                    onInfo = { info = "Wine debug" to LogCopy.WINE }) {
                    wineDebug = it; putBool("enable_wine_debug", it)
                }
                if (wineDebug) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp)
                    ) {
                        channels.forEachIndexed { i, ch ->
                            Chip(ch) { saveChannels(channels.toMutableList().also { it.removeAt(i) }) }
                        }
                        ChipButton("+ add") { showChannelPicker = true }
                        ChipButton("reset") {
                            saveChannels(com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS
                                .split(",").filter { it.isNotBlank() })
                        }
                    }
                }
                LogToggle("Box64 / FEXCore", box64Logs,
                    hint = "Slows games down at higher verbosity",
                    onInfo = { info = "Box64 / FEXCore" to LogCopy.BOX64 }) {
                    box64Logs = it; putBool("enable_box64_logs", it)
                }
                LogToggle("DXVK & VKD3D", dxvkLogs,
                    hint = "Safe to leave on — mostly written at startup",
                    onInfo = { info = "DXVK & VKD3D" to LogCopy.DXVK }) {
                    dxvkLogs = it; putBool("enable_dxvk_logs", it)
                }
                LogToggle("Android logcat", logcat,
                    hint = "Bannerlator's own output only",
                    onInfo = { info = "Android logcat" to LogCopy.LOGCAT }) {
                    logcat = it; putBool("enable_logcat", it)
                }
                LogToggle("Crash reports", crashReports,
                    hint = "Nothing runs until something crashes",
                    onInfo = { info = "Crash reports" to LogCopy.CRASH }) {
                    crashReports = it; putBool("enable_crash_reports", it)
                }

                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            // Runtime.exec + 1000 lines + a redaction pass + a file write: far too
                            // much for the UI thread (its own docs say so). Off to IO, refresh after.
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    LogcatCapture.captureToFile(context, LogcatCapture.DEFAULT_LINES)
                                }
                                refreshTick++
                            }
                        },
                        enabled = logcat,
                        modifier = Modifier.weight(1f)
                    ) { Text("Capture logcat now", color = MaterialTheme.colorScheme.onPrimary) }
                    InfoDot { info = "Capture logcat now" to LogCopy.CAPTURE_NOW }
                }
            }

            // ── Housekeeping ─────────────────────────────────────────────
            SectionLabel("Housekeeping")
            LogCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep last", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        Text(
                            if (keepLast == 0) "No history — each run replaces the last"
                            else "$keepLast run${if (keepLast == 1) "" else "s"} per game",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                        )
                    }
                    InfoDot { info = "Keep last runs" to LogCopy.KEEP_LAST }
                    Stepper(
                        onMinus = {
                            if (keepLast > 0) { keepLast--; prefs.edit().putInt(LogLocation.PREF_KEEP_LAST, keepLast).apply() }
                        },
                        onPlus = {
                            if (keepLast < 50) { keepLast++; prefs.edit().putInt(LogLocation.PREF_KEEP_LAST, keepLast).apply() }
                        }
                    )
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Total size", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        Text(
                            LogInventory.humanBytes(LogInventory.totalBytes(entries)) +
                                " across ${entries.size} " + if (entries.size == 1) "folder" else "folders",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                        )
                    }
                    // Deleting goes through the File Manager rather than a button here — see the
                    // note in LogInventory about why this screen owns no delete of its own.
                    Text("Manage", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { browseDir = LogLocation.resolveLogDir(context) }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }

            // ── Logs by game ─────────────────────────────────────────────
            SectionLabel("Logs by game")
            if (entries.isEmpty()) {
                LogCard {
                    Text("No logs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            } else {
                entries.forEach { e -> GameLogCard(e) { browseDir = e.dir } }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { showExplainAll = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Explain the log types", color = MaterialTheme.colorScheme.onPrimary)
            }

            Text(
                "Logs are scrubbed of usernames, e-mail addresses and tokens before they are written, " +
                    "so they are safe to share. Logcat only ever contains Bannerlator's own output.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showChannelPicker) {
        val allChannels = remember {
            try {
                val arr = org.json.JSONArray(FileUtils.readString(context, "wine_debug_channels.json"))
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) { emptyList() }
        }
        val selected = remember { mutableStateOf(channels.toSet()) }
        OutlinedAlertDialog(
            onDismissRequest = { showChannelPicker = false },
            title = { Text("Wine debug channels") },
            text = {
                // Hundreds of channels — bound the height and scroll, same as the old dialog.
                Column(modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())) {
                    allChannels.forEach { ch ->
                        val checked = ch in selected.value
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                selected.value = if (checked) selected.value - ch else selected.value + ch
                            }) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selected.value = if (it) selected.value + ch else selected.value - ch
                            })
                            Text(ch)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveChannels(selected.value.toList()); showChannelPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showChannelPicker = false }) { Text("Cancel") }
            }
        )
    }

    info?.let { (title, body) -> PerfInfoDialog(title = title, body = body, onDismiss = { info = null }) }
    if (showExplainAll) {
        PerfInfoDialog(title = "What each log is for", body = LogCopy.explainAll(),
            onDismiss = { showExplainAll = false })
    }
}

/** Small uppercase section heading that sits ABOVE its card, as in the design. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        letterSpacing = 0.08.em,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
    )
}

/** Untitled card — the heading lives above it now. */
@Composable
private fun LogCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) { content() }
}

/** A row that reads as a value with its detail underneath and an action on the right. */
@Composable
private fun PickRow(label: String, sub: String, action: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2)
        }
        Text(action, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
    }
}

@Composable
private fun Chip(text: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
        IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.Close, "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun ChipButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        modifier = Modifier
            .clickable { onClick() }
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("−", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp,
            modifier = Modifier.clickable { onMinus() }.padding(horizontal = 12.dp, vertical = 4.dp))
        Text("+", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp,
            modifier = Modifier.clickable { onPlus() }.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/**
 * One game's logs. Expanding lists the individual files; the action opens the File Manager at this
 * folder, which is where viewing, sharing and deleting live.
 */
@Composable
private fun GameLogCard(entry: LogInventory.Entry, onOpen: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (entry.isAppBucket) Icons.Outlined.HelpOutline else Icons.Default.Folder,
                    null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(if (entry.isAppBucket) "App & crash logs" else entry.name,
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Text(
                    buildString {
                        append("${entry.fileCount} file${if (entry.fileCount == 1) "" else "s"}")
                        append(" · ").append(LogInventory.humanBytes(entry.totalBytes))
                        if (entry.archivedRuns > 0) append(" · ${entry.archivedRuns} kept")
                        append(" · ").append(relativeTime(entry.lastModified))
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            LogInventory.filesIn(entry.dir).forEach { f ->
                Text("• ${f.name}  (${LogInventory.humanBytes(f.length())})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Open in File Manager", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
    }
}

/** "12 min ago" / "yesterday" — a timestamp is not what anyone is looking for in this list. */
private fun relativeTime(millis: Long): String {
    if (millis <= 0) return "—"
    val mins = (System.currentTimeMillis() - millis) / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} hr ago"
        mins < 60 * 48 -> "yesterday"
        else -> "${mins / (60 * 24)} days ago"
    }
}

@Composable
private fun LogToggle(
    label: String,
    checked: Boolean,
    hint: String? = null,
    enabled: Boolean = true,
    onInfo: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier.alpha(0.4f))
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            if (hint != null) {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        if (onInfo != null) InfoDot(onInfo)
        Spacer(Modifier.width(4.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Always-live "?" — a locked or off toggle must still be explainable. */
@Composable
private fun InfoDot(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(Icons.Outlined.HelpOutline, "What's this?",
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/**
 * Help copy. Every entry leads with the performance cost, because that is the thing a user needs to
 * decide with — two of these are genuinely expensive to leave on.
 */
private object LogCopy {
    const val PER_GAME =
        "No performance cost.\n\n" +
        "Gives each game its own folder inside your log location, named after the game, so one " +
        "game's logs don't get mixed up with another's. Turn it off to keep everything in a single " +
        "folder like older versions did.\n\n" +
        "Logs written before you turned this on are left exactly where they are."

    const val WINE =
        "⚠️ SLOWS GAMES DOWN. Only turn this on to diagnose a problem, then turn it back off.\n\n" +
        "Records what Wine itself is doing. Every message is written to disk as the game runs, and " +
        "Wine produces a lot of them — the \"seh\" channel alone logs an entry every time the game " +
        "raises an exception, which normal Windows programs do constantly. Adding heavier channels " +
        "such as \"relay\" can make a game many times slower.\n\n" +
        "This is the log to enable when a game won't start, crashes, or dies at launch."

    const val BOX64 =
        "⚠️ SLOWS GAMES DOWN at higher verbosity.\n\n" +
        "Records what the x86 translator (Box64 or FEXCore) is doing while it converts the game's " +
        "PC instructions to run on your phone. Useful for a game that crashes in a way Wine's own " +
        "log doesn't explain, or one that misbehaves only under one emulator.\n\n" +
        "Leave off for normal play."

    const val DXVK =
        "Little to no performance cost.\n\n" +
        "Records what the graphics translation layers report — DXVK for DirectX 9/10/11, VKD3D for " +
        "DirectX 12. Almost everything they write happens while the game is starting: which GPU and " +
        "driver were picked, which features are missing, why a shader failed.\n\n" +
        "This is the log to check for black screens, missing textures or a game that refuses a " +
        "graphics feature. Safe to leave on."

    const val LOGCAT =
        "No performance cost.\n\n" +
        "Android's own system log, as it relates to Bannerlator. It is captured on demand — when you " +
        "tap \"Capture logcat now\", or when the app crashes — not continuously, so switching it on " +
        "does not cost you any frames.\n\n" +
        "It contains BANNERLATOR'S OUTPUT ONLY. Android does not let an app read other apps' logs " +
        "without root, and we deliberately don't offer a system-wide capture even with root: it " +
        "would sweep up other apps' private data into a file you're likely to share. If you have " +
        "root and need the full system log, use a dedicated logcat reader."

    const val CRASH =
        "No performance cost — nothing runs until something actually crashes.\n\n" +
        "If Bannerlator itself crashes, a report is saved with your device model, Android version, " +
        "app version, what went wrong, and the last few hundred lines of the app's log. That is " +
        "exactly what's needed to work out a crash after the fact.\n\n" +
        "Reports are kept with your other logs under \"App & crash logs\"."

    const val CAPTURE_NOW =
        "Takes a snapshot of Bannerlator's recent Android log right now and saves it with your other " +
        "logs.\n\n" +
        "Useful when something went wrong but the app didn't crash — capture it while the problem is " +
        "fresh, then share it."

    const val KEEP_LAST =
        "How many past runs to keep for each game.\n\n" +
        "When a game launches, the previous run's logs are moved into a \"previous\" folder rather " +
        "than being overwritten, and anything older than this many runs is deleted. The current " +
        "run's files keep their normal names, so the newest log is always the obvious one.\n\n" +
        "Set it to 0 to keep no history at all."

    fun explainAll(): String = buildString {
        append("Costs performance while on\n\n")
        append("• Wine debug\n$WINE\n\n")
        append("• Box64 / FEXCore\n$BOX64\n\n")
        append("\nSafe to leave on\n\n")
        append("• DXVK & VKD3D\n$DXVK\n\n")
        append("• Android logcat\n$LOGCAT\n\n")
        append("• Crash reports\n$CRASH\n\n")
        append("\nOrganisation\n\n")
        append("• Folder for each game\n$PER_GAME\n\n")
        append("• Keep last runs\n$KEEP_LAST\n")
    }
}
