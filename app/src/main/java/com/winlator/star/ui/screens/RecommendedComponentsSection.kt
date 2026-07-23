@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.components.Component
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.components.ComponentInstaller
import com.winlator.star.components.DependencyDetector
import com.winlator.star.components.DependencyDetector.Recommendation
import com.winlator.star.components.PrefixInstalledDetector
import com.winlator.star.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Recommended components" chip row — Pillar 2 / Phase 2.2. Detects the redistributables a game
 * bundles ([DependencyDetector]) and offers each as a one-tap install into the shortcut's container
 * prefix, reusing the EXACT install routing + installed-state tracking of [ComponentsSheet]
 * (SharedPreferences `"component_installs"`, keyed `"c<id>"`).
 *
 * Surfaced in two places: the "Confirm game" import dialog (pass [exeFile], the game's .exe) and the
 * per-game shortcut settings' Win Components tab (pass [gameDir]). Detection runs off the main thread;
 * the whole section renders nothing until at least one catalog-backed recommendation is ready, and
 * hides itself on any detection/catalog failure (best-effort — never crashes the host dialog).
 *
 * Install gating: [ComponentInstaller]/[ComponentExecInstaller] both require the container's `.wine`
 * prefix to already exist. At IMPORT time it usually doesn't, so a tap first checks for the prefix and,
 * if missing, shows "launch the game once" instead of failing. Installs are prefix-level (shared across
 * every shortcut on that container), not per-shortcut — the copy says so.
 */
@Composable
fun RecommendedComponentsSection(
    container: Container,
    exeFile: File? = null,
    gameDir: File? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    // Detection + catalog results (both off-main-thread); recs is filtered to components the catalog
    // actually carries, so every chip is installable in principle.
    var recs by remember { mutableStateOf<List<Recommendation>>(emptyList()) }
    var catalog by remember { mutableStateOf<Map<String, Component>>(emptyMap()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmExec by remember { mutableStateOf<Component?>(null) }

    // Installed-state persistence — identical scheme to ComponentsSheet so a component installed there
    // (or on a prior visit) shows as checked here, and vice-versa.
    val installsPrefs = remember { context.getSharedPreferences("component_installs", Context.MODE_PRIVATE) }
    val installKey = "c${container.id}"
    fun markInstalled(name: String) {
        installed = installed + name
        installsPrefs.edit().putStringSet(installKey, installed).apply()
    }

    // Detect (filesystem I/O) + load the catalog off the main thread when the dialog opens / target
    // changes. Keyed on the exe/gameDir path so re-opening on a different game re-detects.
    LaunchedEffect(container.id, exeFile?.path, gameDir?.path) {
        val found = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    exeFile != null -> DependencyDetector.detectForExe(exeFile)
                    gameDir != null -> DependencyDetector.detect(gameDir)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }
        if (found.isEmpty()) { recs = emptyList(); return@LaunchedEffect }
        val cat = withContext(Dispatchers.IO) {
            runCatching { ComponentCatalog.load() }.getOrDefault(emptyList())
        }.associateBy { it.name }
        catalog = cat
        val recorded = installsPrefs.getStringSet(installKey, emptySet())?.toSet() ?: emptySet()
        val detected = withContext(Dispatchers.IO) { PrefixInstalledDetector.detect(container) }
        installed = recorded + detected
        // Keep only recommendations whose component exists in the catalog (else we can't install it).
        recs = found.filter { cat.containsKey(it.componentName) }
    }

    // Run an installer-based component (vcredist/.NET): opens a container session; the app restarts
    // when it ends. Mirrors ComponentsSheet.runExecInstall.
    fun runExecInstall(c: Component) {
        installing = c.name
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                ComponentExecInstaller.startInstall(context, container, c) { /* progress not surfaced on chips */ }
            }
            installing = null
            when (res) {
                is ComponentExecInstaller.Result.Launched -> { /* session launched; app continues there */ }
                is ComponentExecInstaller.Result.Done -> markInstalled(c.name)
                is ComponentExecInstaller.Result.Error -> message = "Couldn't install ${c.name}: ${res.message}"
            }
        }
    }

    // A chip tap: gate on the prefix, then route to the same installer ComponentsSheet would.
    fun onChipTap(c: Component) {
        if (installing != null) return
        // The prefix must exist first — at import time it usually doesn't. Gate here rather than let
        // the installer return a failure, so the message is actionable.
        if (!File(container.rootDir, ".wine").isDirectory) {
            message = "Launch the game once first, then install its components from the game's settings."
            return
        }
        // Same reason logic ComponentsSheet's row uses (exec-driver vs file-drop installer).
        val reason = if (ComponentExecInstaller.handlesComponent(c)) ComponentExecInstaller.execBlockedReason(c)
                     else ComponentInstaller.blockedReason(c)
        if (reason != null) { message = reason; return }
        when {
            // Has an installer step → confirm (the container will open), then run a session.
            ComponentExecInstaller.isExecComponent(c) -> confirmExec = c
            // Local-only but not pure file-drop (set_windows/uninstall) → run inline; no session.
            ComponentExecInstaller.handlesComponent(c) -> runExecInstall(c)
            else -> {
                installing = c.name
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        ComponentInstaller.install(context, container, c) { /* progress not surfaced on chips */ }
                    }
                    installing = null
                    if (err == null) markInstalled(c.name)
                    else message = "Couldn't install ${c.name}: $err"
                }
            }
        }
    }

    message?.let { m ->
        OutlinedAlertDialog(
            onDismissRequest = { message = null },
            containerColor = cs.surfaceContainerHigh,
            text = { Text(m, color = cs.onSurface) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    confirmExec?.let { c ->
        OutlinedAlertDialog(
            onDismissRequest = { confirmExec = null },
            containerColor = cs.surfaceContainerHigh,
            title = { Text("Install ${c.name}", color = cs.onSurface) },
            text = {
                Text(
                    "This installs ${c.name} into this game's container by running its installer inside " +
                        "the container (shared by every shortcut on it). The container will open and run the " +
                        "installer — when it finishes, close it to complete the install.",
                    color = cs.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = { val comp = c; confirmExec = null; runExecInstall(comp) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmExec = null }) { Text("Cancel") } },
        )
    }

    // Nothing detected (or catalog unavailable) → render nothing at all.
    if (recs.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Recommended components",
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
        )
        Text(
            "Redistributables this game bundles — tap to install into its container.",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recs.forEach { rec ->
                val c = catalog[rec.componentName] ?: return@forEach
                val isInstalled = c.name in installed
                val isInstalling = installing == c.name
                FilterChip(
                    selected = isInstalled,
                    enabled = installing == null || isInstalling,
                    onClick = { onChipTap(c) },
                    label = { Text(rec.label) },
                    leadingIcon = {
                        when {
                            isInstalling -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            )
                            isInstalled -> Icon(
                                Icons.Filled.CheckCircle, contentDescription = "Installed",
                                modifier = Modifier.size(18.dp),
                            )
                            else -> Icon(
                                Icons.Filled.Download, contentDescription = "Install",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}
