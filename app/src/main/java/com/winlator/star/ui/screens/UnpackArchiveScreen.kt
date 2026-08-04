package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.winlator.star.core.StringUtils
import com.winlator.star.core.unpack.PowerMode
import com.winlator.star.core.unpack.ReadBuffer
import com.winlator.star.core.unpack.SevenZip
import com.winlator.star.core.unpack.UnpackManager
import com.winlator.star.core.unpack.UnpackPhase
import com.winlator.star.core.unpack.UnpackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "Unpack Archive" screen: point the bundled 7-Zip engine at a disc image / archive and extract
 * it to a chosen folder, with a foreground service doing the work so it survives backgrounding.
 *
 * Reached from the File Manager's ⋮ menu (hosted by [com.winlator.star.UnpackArchiveActivity]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnpackArchiveScreen(
    archivePath: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val selected = remember(archivePath) { File(archivePath) }
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    val state by UnpackManager.state.collectAsState()

    // InnoSetup repack? Then 7-Zip must be pointed at the installer .exe (never a lone Setup-*.bin),
    // and the whole flow is "unpack game payload" rather than "extract archive".
    val innoTarget = remember(archivePath) { SevenZip.resolveInnoTarget(selected) }
    val isInno = innoTarget != null
    // What 7-Zip is actually run against: the installer .exe for InnoSetup, else the file itself.
    val archive = remember(archivePath) { innoTarget ?: selected }

    // A friendly default extract-folder name: the repack folder name for InnoSetup (so "Setup" never
    // becomes the folder), else the archive's base name.
    val defaultName = remember(archivePath) {
        if (isInno) archive.parentFile?.name?.takeIf { it.isNotBlank() } ?: "game"
        else SevenZip.suggestedTargetName(selected)
    }

    // Detected type comes from a quick `7zz l` (metadata only). Skipped for InnoSetup — we label it
    // ourselves. Keyed on the archive so it reruns if the screen is reused for a different one.
    var detectedType by remember(archivePath) { mutableStateOf<String?>(null) }
    var typeLoading by remember(archivePath) { mutableStateOf(!isInno) }
    LaunchedEffect(archivePath) {
        if (isInno) return@LaunchedEffect
        typeLoading = true
        val info = withContext(Dispatchers.IO) { SevenZip.list(context, archive) }
        detectedType = info?.type
        typeLoading = false
    }

    // Destination defaults to a sibling folder (of the repack folder, for InnoSetup) named for the game.
    var destPath by remember(archivePath) {
        val base = if (isInno) archive.parentFile?.parentFile ?: archive.parentFile else selected.parentFile
        mutableStateOf(File(base, defaultName).absolutePath)
    }
    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            com.winlator.star.util.InAppFilePicker.pickedPath(result.data)?.let {
                // Land inside the chosen folder, in a subfolder named for the game, so the extract
                // never carpets someone's Games root with loose files.
                destPath = File(it, defaultName).absolutePath
            }
        }
    }

    var powerMode by remember { mutableStateOf(PowerMode.MAX) }
    var manualCores by remember { mutableStateOf(cores) }
    var buffer by remember { mutableStateOf(ReadBuffer.MB1) }
    var bufferMenu by remember { mutableStateOf(false) }

    // Direct java.io.File writes need All Files Access; a native process can't write through SAF, so
    // when the destination is on shared storage and access isn't granted we gate extraction and send
    // the user to grant it rather than ship a half-working SAF-for-native path.
    val hasAllFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    val destOnSharedStorage = destPath.startsWith("/storage/") && !destPath.startsWith(context.filesDir.absolutePath)
    val gatedByPermission = destOnSharedStorage && !hasAllFiles

    val running = state.isRunning && state.archivePath == archive.absolutePath
    val engineMissing = !SevenZip.isAvailable(context)

    // For display + honest speed/ETA: the data 7-Zip reads. For InnoSetup that's the Setup-*.bin
    // payload total, not the tiny Setup.exe we point it at.
    val sourceSize = remember(archivePath) {
        if (isInno) {
            archive.parentFile?.listFiles()
                ?.filter { it.isFile && (it.extension.equals("bin", true) || it == archive) }
                ?.sumOf { it.length() } ?: archive.length()
        } else selected.length()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Unpack Archive",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Source ──
            SectionCard {
                Text("Source", style = sectionTitle())
                Spacer(Modifier.height(6.dp))
                Text(selected.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                val typeText = when {
                    isInno -> "InnoSetup installer"
                    typeLoading -> "reading…"
                    detectedType != null -> detectedType
                    else -> "unknown type"
                }
                Text(
                    "${StringUtils.formatBytes(sourceSize)}  •  $typeText",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (isInno) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "7-Zip will unpack the game payload from ${archive.name} and its Setup-*.bin volumes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Destination ──
            SectionCard {
                Text("Extract to", style = sectionTitle())
                Spacer(Modifier.height(6.dp))
                Text(
                    destPath,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !running,
                    onClick = {
                        destPicker.launch(
                            com.winlator.star.util.InAppFilePicker.buildDirIntent(
                                context,
                                title = "Choose where to extract",
                                initialDir = archive.parent,
                            )
                        )
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Change folder", color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Power ──
            SectionCard {
                Text("Power", style = sectionTitle())
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(PowerMode.AUTO to "Auto", PowerMode.MAX to "Max", PowerMode.MANUAL to "Manual")
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = powerMode == mode,
                            onClick = { if (!running) powerMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }
                if (powerMode == PowerMode.MANUAL) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$manualCores of $cores cores",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = manualCores.toFloat(),
                        onValueChange = { if (!running) manualCores = it.toInt().coerceIn(1, cores) },
                        valueRange = 1f..cores.toFloat(),
                        steps = (cores - 2).coerceAtLeast(0),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Extraction is limited by storage speed; extra cores only help archives with " +
                        "many files (7z/solid). A single huge file won't parallelize.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )

                Spacer(Modifier.height(12.dp))

                // Read-buffer knob.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read buffer", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(
                            enabled = !running,
                            onClick = { bufferMenu = true },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) { Text(buffer.label, color = MaterialTheme.colorScheme.onBackground) }
                        DropdownMenu(expanded = bufferMenu, onDismissRequest = { bufferMenu = false }) {
                            ReadBuffer.entries.forEach { b ->
                                DropdownMenuItem(text = { Text(b.label) }, onClick = { buffer = b; bufferMenu = false })
                            }
                        }
                    }
                }
                Text(
                    "Larger buffers can improve throughput reading from FUSE-backed storage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Permission gate ──
            if (gatedByPermission) {
                WarnCard {
                    Text(
                        "All Files Access is off. Extraction writes directly to storage and can't use the " +
                            "slow SAF fallback for an 80 GB image, so grant access to continue.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }) { Text("Grant access") }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (engineMissing) {
                WarnCard {
                    Text(
                        "The 7-Zip engine (lib7zz.so) isn't executable on this build. Extraction is unavailable.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Extract button ──
            if (!running) {
                Button(
                    onClick = {
                        UnpackManager.clearIfTerminal()
                        val mmt = UnpackManager.mmtFor(powerMode, manualCores)
                        UnpackService.start(context, archive.absolutePath, destPath, mmt, buffer.bytes, isInno, sourceSize)
                    },
                    enabled = !gatedByPermission && !engineMissing && archive.isFile,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isInno) "Unpack game payload" else "Extract", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Progress ──
            if (running) {
                Spacer(Modifier.height(4.dp))
                SectionCard {
                    val listing = state.phase == UnpackPhase.LISTING
                    Text(
                        if (listing) "Reading archive…" else "Extracting — ${state.percent}%",
                        color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (listing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                    } else {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            if (state.speedBps > 0) append("${StringUtils.formatBytes(state.speedBps)}/s")
                            if (state.etaSeconds >= 0) {
                                if (isNotEmpty()) append("  •  ")
                                append("ETA ${humanDuration(state.etaSeconds * 1000)}")
                            }
                            if (state.filesExtracted > 0) {
                                if (isNotEmpty()) append("  •  ")
                                append("${state.filesExtracted} files")
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    state.currentFile?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { UnpackService.cancel(context) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                }
            }

            // ── Terminal result ──
            if (state.archivePath == archive.absolutePath && !running) {
                when (state.phase) {
                    UnpackPhase.DONE -> {
                        Spacer(Modifier.height(4.dp))
                        SectionCard {
                            Text("Done", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${state.filesExtracted} files • ${StringUtils.formatBytes(state.archiveSize)} in ${humanDuration(state.elapsedMs)}",
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(state.destPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    UnpackPhase.ERROR -> {
                        Spacer(Modifier.height(4.dp))
                        WarnCard {
                            if (state.isInno) {
                                // Some repacks use a customised InnoSetup that 7-Zip can't parse. The
                                // honest fallback is to run the real installer inside a container.
                                Text("Couldn't unpack this InnoSetup repack", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "This InnoSetup repack must be installed by running Setup.exe inside a Winlator container.",
                                    color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                RunSetupInContainer(exe = archive)
                            } else {
                                Text("Extraction failed", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            state.errorTail?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it.takeLast(600), color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    UnpackPhase.CANCELLED -> {
                        Spacer(Modifier.height(4.dp))
                        SectionCard {
                            Text("Cancelled", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * "Run Setup.exe in a container" fallback for InnoSetup repacks 7-Zip can't unpack. Picks the sole
 * container automatically; with several it offers a menu; with none it says so.
 */
@Composable
private fun RunSetupInContainer(exe: File) {
    val context = LocalContext.current
    val containers = remember { com.winlator.star.util.ContainerExeRunner.containers(context) }
    var menu by remember { mutableStateOf(false) }

    fun launch(container: com.winlator.star.container.Container) {
        val err = com.winlator.star.util.ContainerExeRunner.run(context, container, exe)
        if (err != null) android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
    }

    when {
        containers.isEmpty() -> Text(
            "Create a container first, then run ${exe.name} from the File Manager.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        )
        else -> Box {
            Button(onClick = { if (containers.size == 1) launch(containers.first()) else menu = true }) {
                Text("Run ${exe.name} in a container")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                containers.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { menu = false; launch(c) })
                }
            }
        }
    }
}

@Composable
private fun sectionTitle() = MaterialTheme.typography.labelLarge.copy(
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), content = content)
    }
}

@Composable
private fun WarnCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}

private fun humanDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
