package com.winlator.star.ui.screens

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogLocation
import com.winlator.star.core.LogcatCapture
import java.io.File

/**
 * App Settings → Log Manager. One place for everything logging: where logs go, which ones are
 * produced, how many past runs are kept, and what is on disk right now per game.
 *
 * Mirrors the Performance screen deliberately — same card layout, same always-live "?" help button
 * on every toggle. Here the help copy leads with the PERFORMANCE COST, because two of these
 * genuinely slow games down and users need to know that before leaving one on.
 */
@Composable
fun LogManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var perGame by remember { mutableStateOf(prefs.getBoolean(LogLocation.PREF_PER_GAME, true)) }
    var keepLast by remember {
        mutableStateOf(prefs.getInt(LogLocation.PREF_KEEP_LAST, LogLocation.DEFAULT_KEEP_LAST))
    }
    var wineDebug by remember { mutableStateOf(prefs.getBoolean("enable_wine_debug", false)) }
    var box64Logs by remember { mutableStateOf(prefs.getBoolean("enable_box64_logs", false)) }
    var dxvkLogs by remember { mutableStateOf(prefs.getBoolean("enable_dxvk_logs", true)) }
    var logcat by remember { mutableStateOf(prefs.getBoolean("enable_logcat", true)) }
    var crashReports by remember { mutableStateOf(prefs.getBoolean("enable_crash_reports", true)) }

    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showExplainAll by remember { mutableStateOf(false) }
    // Bumped after any destructive/refreshing action to force a re-scan of the filesystem.
    var refreshTick by remember { mutableStateOf(0) }
    // Folder the embedded File Manager is showing, or null when it is closed.
    var browseDir by remember { mutableStateOf<File?>(null) }
    val entries = remember(refreshTick, perGame) { LogInventory.scan(context) }

    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

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
                    "Log Manager",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            Text(
                "Logs help diagnose a problem. Two of these slow games down while they're on — " +
                    "tap ? on any option to see which.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
            )

            // ── Where logs go ──
            LogCard(title = "Where logs go") {
                Text(
                    LogLocation.resolveLogDir(context)?.absolutePath ?: "—",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                )
                Text(
                    "Change the location in Settings › Logs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                LogToggle("Folder for each game", perGame,
                    onInfo = { info = "Folder for each game" to LogCopy.PER_GAME }) {
                    perGame = it; putBool(LogLocation.PREF_PER_GAME, it); refreshTick++
                }
            }

            // ── What to record ──
            LogCard(title = "What to record") {
                LogToggle("Wine debug", wineDebug,
                    onInfo = { info = "Wine debug" to LogCopy.WINE }) {
                    wineDebug = it; putBool("enable_wine_debug", it)
                }
                if (wineDebug) {
                    Text(
                        "Channels: " + prefs.getString("wine_debug_channels", "seh,err,warn,fixme"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
                LogToggle("Box64 / FEXCore", box64Logs,
                    onInfo = { info = "Box64 / FEXCore" to LogCopy.BOX64 }) {
                    box64Logs = it; putBool("enable_box64_logs", it)
                }
                LogToggle("DXVK & VKD3D", dxvkLogs,
                    onInfo = { info = "DXVK & VKD3D" to LogCopy.DXVK }) {
                    dxvkLogs = it; putBool("enable_dxvk_logs", it)
                }
                LogToggle("Android logcat", logcat,
                    onInfo = { info = "Android logcat" to LogCopy.LOGCAT }) {
                    logcat = it; putBool("enable_logcat", it)
                }
                LogToggle("Crash reports", crashReports,
                    onInfo = { info = "Crash reports" to LogCopy.CRASH }) {
                    crashReports = it; putBool("enable_crash_reports", it)
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            LogcatCapture.captureToFile(context, LogcatCapture.DEFAULT_LINES)
                            refreshTick++
                        },
                        // Disabled rather than hidden when logcat is off, so the switch above visibly
                        // controls something instead of being a preference that does nothing.
                        enabled = logcat,
                        modifier = Modifier.weight(1f)
                    ) { Text("Capture logcat now", color = MaterialTheme.colorScheme.onPrimary) }
                    InfoDot { info = "Capture logcat now" to LogCopy.CAPTURE_NOW }
                }
            }

            // ── History ──
            LogCard(title = "History") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep last $keepLast runs per game",
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("Older runs are moved to a \"previous\" folder, then pruned.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    InfoDot { info = "Keep last runs" to LogCopy.KEEP_LAST }
                    Spacer(Modifier.width(4.dp))
                    Text("−", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp,
                        modifier = Modifier
                            .clickable {
                                if (keepLast > 0) {
                                    keepLast--; prefs.edit().putInt(LogLocation.PREF_KEEP_LAST, keepLast).apply()
                                }
                            }
                            .padding(horizontal = 10.dp))
                    Text("+", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp,
                        modifier = Modifier
                            .clickable {
                                if (keepLast < 50) {
                                    keepLast++; prefs.edit().putInt(LogLocation.PREF_KEEP_LAST, keepLast).apply()
                                }
                            }
                            .padding(horizontal = 10.dp))
                }
            }

            // ── Logs on disk ──
            LogCard(title = "Logs on disk — " + LogInventory.humanBytes(LogInventory.totalBytes(entries))) {
                if (entries.isEmpty()) {
                    Text("No logs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    entries.forEach { e ->
                        LogGroupRow(e, onOpen = { browseDir = e.dir })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { browseDir = LogLocation.resolveLogDir(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Browse all logs", color = MaterialTheme.colorScheme.onPrimary) }
            }

            Button(onClick = { showExplainAll = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Explain the log types", color = MaterialTheme.colorScheme.onPrimary)
            }

            Text(
                "Logs are scrubbed of usernames, e-mail addresses and tokens before they are written, " +
                    "so they are safe to share. Logcat only ever contains Bannerlator's own output.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // The File Manager can delete or move files, so re-scan when it closes rather than
    // showing counts that no longer match what is on disk.
    browseDir?.let { dir ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { browseDir = null; refreshTick++ },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            FileManagerScreen(initialDir = dir)
        }
    }

    info?.let { (title, body) -> PerfInfoDialog(title = title, body = body, onDismiss = { info = null }) }
    if (showExplainAll) {
        PerfInfoDialog(title = "What each log is for", body = LogCopy.explainAll(),
            onDismiss = { showExplainAll = false })
    }
}

@Composable
private fun LogGroupRow(entry: LogInventory.Entry, onOpen: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(if (entry.isAppBucket) "App & crash logs" else entry.name,
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                val bits = buildString {
                    append("${entry.fileCount} file${if (entry.fileCount == 1) "" else "s"}")
                    append(" · ").append(LogInventory.humanBytes(entry.totalBytes))
                    if (entry.archivedRuns > 0) append(" · ${entry.archivedRuns} kept")
                }
                Text(bits, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            // View / share / delete all live in the File Manager — it already does multi-select,
            // bulk delete, share and a file viewer. Opening it here rooted at this game's folder
            // reuses all of that instead of reimplementing it badly in three places.
            Text("Open", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                modifier = Modifier.clickable { onOpen() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            LogInventory.filesIn(entry.dir).forEach { f ->
                Text("• ${f.name}  (${LogInventory.humanBytes(f.length())})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp))
            }
        }
    }
}

@Composable
private fun LogCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun LogToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onInfo: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier.alpha(0.4f))
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
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
