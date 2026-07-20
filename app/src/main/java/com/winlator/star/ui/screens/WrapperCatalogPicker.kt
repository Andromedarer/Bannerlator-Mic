package com.winlator.star.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.R
import com.winlator.star.contents.WrapperCatalog
import com.winlator.star.contents.WrapperCatalogDownloader
import com.winlator.star.contents.WrapperCatalogEntry
import com.winlator.star.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 5 (issue #132) — the curated wrapper-catalog browser, mirroring the ReShade catalog picker.
 * Layered as a dialog OVER the wrapper manager (which itself may be a dialog): lists every entry from
 * wrappers.json as a card (name / author / license / size / gpuTargets chips) with a Download button +
 * progress. A download routes through [WrapperCatalogDownloader.install] -> WrapperManager.importWrapper,
 * so it lands as a normal import; [onInstalled] lets the manager refresh so the new wrapper appears.
 *
 * GPU applicability: entries whose gpuTargets don't include the current GPU family (and aren't "all")
 * are greyed with a note ("Mali-only — inert on this Adreno") — never hard-blocked; the user may still
 * download. The current family is derived from the manager's already-probed GPU (0x5143 = Adreno).
 *
 * Degrades gracefully: an absent/404 catalog, offline device, or bad JSON -> "No wrappers available
 * yet." and never crashes.
 */
@Composable
fun WrapperCatalogPicker(
    isQualcomm: Boolean,
    gpuModel: String,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var catalog by remember { mutableStateOf<List<WrapperCatalogEntry>>(emptyList()) }
    var source by remember { mutableStateOf(WrapperCatalog.Source.NONE) }
    var loading by remember { mutableStateOf(true) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // Catalog ids installed this session — the row then shows a "Downloaded" state (the imported
    // identifier is derived from the name, so tracking by catalog id is the simplest reliable signal).
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { WrapperCatalog.loadCached(context) }
        catalog = result.entries
        source = result.source
        loading = false
    }

    // Only a live NETWORK load lets us download (asset URLs may have moved since a cache was written).
    val offline = source != WrapperCatalog.Source.NETWORK

    fun startDownload(entry: WrapperCatalogEntry) {
        downloadingId = entry.id
        progress = 0
        errorMsg = null
        scope.launch {
            val id = WrapperCatalogDownloader.install(context, entry) { pct ->
                activity?.runOnUiThread { progress = pct }
            }
            downloadingId = null
            if (id != null) {
                installedIds = installedIds + entry.id
                Toast.makeText(
                    context,
                    context.getString(R.string.wrapper_catalog_download_ok, entry.name),
                    Toast.LENGTH_SHORT,
                ).show()
                onInstalled()   // parent rescans imported list -> new wrapper appears
            } else {
                errorMsg = context.getString(R.string.wrapper_catalog_download_failed, entry.name)
            }
        }
    }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.wrapper_catalog_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (offline && !loading) {
                    Text(
                        context.getString(
                            if (source == WrapperCatalog.Source.CACHE) R.string.wrapper_catalog_offline_cache
                            else R.string.wrapper_catalog_offline
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                errorMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = cs.primary)
                    }
                    catalog.isEmpty() -> Text(
                        context.getString(R.string.wrapper_catalog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        catalog.forEach { entry ->
                            WrapperCatalogRow(
                                entry = entry,
                                applicable = isApplicable(entry.gpuTargets, isQualcomm),
                                applicabilityNote = applicabilityNote(entry.gpuTargets, isQualcomm, gpuModel),
                                installed = entry.id in installedIds,
                                busy = downloadingId == entry.id,
                                anyBusy = downloadingId != null,
                                offline = offline,
                                progress = progress,
                                onDownload = { startDownload(entry) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.wrapper_catalog_close))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapperCatalogRow(
    entry: WrapperCatalogEntry,
    applicable: Boolean,
    applicabilityNote: String?,
    installed: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    offline: Boolean,
    progress: Int,
    onDownload: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Inapplicable-on-this-GPU rows are dimmed (but still downloadable); a busy/installed row stays full.
    val alpha = if (applicable || busy || installed) 1f else 0.55f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface.copy(alpha = alpha))
                val sub = buildString {
                    if (entry.author.isNotBlank()) append(entry.author)
                    if (entry.license.isNotBlank()) { if (isNotEmpty()) append(" · "); append(entry.license) }
                    val sz = formatSize(entry.fileSize)
                    if (sz != null) { if (isNotEmpty()) append(" · "); append(sz) }
                }
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = alpha))
                }
                if (entry.description.isNotBlank()) {
                    Text(entry.description, style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = alpha))
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                installed -> Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded",
                    tint = cs.primary, modifier = Modifier.size(22.dp))
                busy -> {}
                else -> OutlinedButton(enabled = !anyBusy && !offline, onClick = onDownload) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(LocalContextString(R.string.wrapper_catalog_download))
                }
            }
        }

        // gpuTargets chips.
        if (entry.gpuTargets.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entry.gpuTargets.forEach { t ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(cs.secondaryContainer.copy(alpha = alpha))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(t, style = MaterialTheme.typography.labelSmall,
                            color = cs.onSecondaryContainer.copy(alpha = alpha))
                    }
                }
            }
        }

        // GPU-applicability note (dim), only when the entry doesn't target this GPU.
        applicabilityNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }

        // Offline hint on rows that can't be fetched.
        if (offline && !installed && !busy) {
            Spacer(Modifier.height(4.dp))
            Text(LocalContextString(R.string.wrapper_catalog_needs_connection),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            val frac = (progress.coerceIn(0, 100)) / 100f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    LocalContextString(
                        if (progress >= 100) R.string.wrapper_catalog_installing
                        else R.string.wrapper_catalog_downloading
                    ),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Text("$progress%", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            Divider(color = cs.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = frac,
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = cs.primary,
                trackColor = cs.surfaceContainerHighest,
            )
        }
    }
}

/** Convenience for reading a string resource inside a row composable. */
@Composable
private fun LocalContextString(resId: Int): String = LocalContext.current.getString(resId)

/** An entry applies to the current GPU when it targets "all" or the current family. On a non-Adreno
 *  device (we can't tell Mali/Exynos/PowerVR apart from the vendor id alone) any non-adreno target
 *  counts as applicable; on Adreno only an explicit "adreno" target does. */
private fun isApplicable(targets: List<String>, isQualcomm: Boolean): Boolean {
    if (targets.isEmpty() || targets.contains("all")) return true
    return if (isQualcomm) targets.contains("adreno") else targets.any { it != "adreno" }
}

/** A short "inert on this GPU" note, or null when the entry applies (or targets "all"). */
private fun applicabilityNote(targets: List<String>, isQualcomm: Boolean, gpuModel: String): String? {
    if (isApplicable(targets, isQualcomm)) return null
    return if (isQualcomm)
        "${targets.joinToString("/")}-only — inert on this Adreno ($gpuModel)"
    else
        "Adreno-only — inert on this $gpuModel"
}

/** Human-readable byte size, or null when unknown (0). */
private fun formatSize(bytes: Long): String? {
    if (bytes <= 0) return null
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format((bytes / 1024).coerceAtLeast(1L))
}
