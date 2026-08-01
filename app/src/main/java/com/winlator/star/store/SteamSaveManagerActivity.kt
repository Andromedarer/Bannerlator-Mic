package com.winlator.star.store

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central Steam Save Manager — one screen listing every game that has cloud saves or a local
 * Library folder, with an instant per-game sync status (rendered from the persisted sidecar,
 * NO network on open) and per-row quick Download/Upload. Pull-to-refresh does the live cloud
 * diff ([SaveSyncStore.refreshFromCloud]) per visible game off the main thread so "cloud ahead"
 * can surface.
 *
 * Two entry points reach here: the Steam store-home toolbar (no focus) and the Games-tab per-item
 * ⋮ menu on Steam-origin shortcuts (passes [EXTRA_FOCUS_APP_ID] so the list opens scrolled to and
 * highlighting that game). Tapping a row opens that game's detail Cloud Saves section.
 */
class SteamSaveManagerActivity : ComponentActivity() {

    companion object {
        /** appId to scroll to / highlight when opened from a per-game menu (0 = no focus). */
        const val EXTRA_FOCUS_APP_ID = "focus_app_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val focusAppId = intent.getIntExtra(EXTRA_FOCUS_APP_ID, 0)
        setContent {
            WinlatorTheme {
                SaveManagerScreen(
                    focusAppId = focusAppId,
                    onBack = { finish() },
                    onOpenGame = ::openGameDetail,
                )
            }
        }
    }

    /** Reuse the existing detail nav; its Cloud Saves section is the per-game control surface. */
    private fun openGameDetail(appId: Int) {
        startActivity(
            Intent(this, SteamGameDetailActivity::class.java)
                .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, appId),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveManagerScreen(
    focusAppId: Int,
    onBack: () -> Unit,
    onOpenGame: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    var statuses by remember { mutableStateOf<List<SaveStatus>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // appIds with an in-flight quick action (Download/Upload) — disables that row's buttons.
    var busyAppIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Live per-row progress line for a running (or just-finished) quick action, keyed by appId.
    // While busy it shows what the move is doing (from Callback.onStatus); on done/error it briefly
    // holds the summary/error, then the entry is removed and the row reverts to its last-synced line.
    val rowProgress = remember { mutableStateMapOf<Int, RowProgress>() }

    // Instant load (sidecar + on-disk scan, no network) — off the main thread all the same.
    suspend fun reload() {
        val fresh = withContext(Dispatchers.IO) { SaveSyncStore.listStatuses(context) }
        statuses = fresh
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    // Pull-to-refresh: live cloud diff per visible game (blocking network → IO), then re-list so
    // the needs-attention-first sort re-settles with any freshly-surfaced "cloud ahead".
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            withContext(Dispatchers.IO) {
                for (s in statuses) {
                    try {
                        SaveSyncStore.refreshFromCloud(context, s.appId)
                    } catch (_: Throwable) { /* keep going; one bad game shouldn't stall the sweep */ }
                }
            }
            reload()
            pullState.endRefresh()
        }
    }

    // Focus: once the list is populated, scroll the requested game into view (it also renders a
    // highlighted border via row-level appId match below).
    LaunchedEffect(loading, focusAppId) {
        if (!loading && focusAppId != 0) {
            val idx = statuses.indexOfFirst { it.appId == focusAppId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }

    // One end-to-end combo per button: syncFrom = Download+Apply (cloud → into game), else = Collect+
    // Upload (game → to cloud). onStatus now spans both phases; the manager guards not-set-up itself
    // (returns onError with the "add to a container first" message), and we also disable the buttons
    // for NOT_SET_UP rows up front.
    fun runQuickMove(appId: Int, syncFrom: Boolean) {
        if (appId in busyAppIds) return
        busyAppIds = busyAppIds + appId
        rowProgress[appId] = RowProgress(if (syncFrom) "Preparing sync from Cloud…" else "Preparing sync to Cloud…")
        // The manager may call back on a worker thread, so marshal every UI write onto the
        // composition scope (main) before touching state.
        val cb = object : SteamCloudSaveManager.Callback {
            override fun onStatus(message: String) {
                scope.launch { rowProgress[appId] = RowProgress(message) }
            }
            override fun onDone(summary: String) {
                scope.launch {
                    busyAppIds = busyAppIds - appId
                    rowProgress[appId] = RowProgress(summary)
                    // Refresh just this row's status (records + local staleness, no network) so the
                    // pill + last-synced line are current when the progress line clears.
                    val updated = withContext(Dispatchers.IO) { SaveSyncStore.statusOf(context, appId) }
                    statuses = statuses.map { if (it.appId == appId) updated else it }
                    kotlinx.coroutines.delay(PROGRESS_LINGER_MS)
                    rowProgress.remove(appId)
                }
            }
            override fun onError(message: String) {
                scope.launch {
                    busyAppIds = busyAppIds - appId
                    rowProgress[appId] = RowProgress("Error: $message", isError = true)
                    kotlinx.coroutines.delay(PROGRESS_LINGER_MS)
                    rowProgress.remove(appId)
                }
            }
        }
        // The combos need the game's install dir (to resolve its container); look it up by appId off
        // the main thread, then dispatch. An empty dir → the manager's not-set-up guard fires.
        scope.launch {
            // Use the same installDir source as SaveSyncStore + the detail page (getGame), so the row
            // combo resolves the identical container. An empty dir → the not-set-up guard fires.
            val installDir = withContext(Dispatchers.IO) {
                SteamRepository.getInstance().database.getGame(appId)?.installDir ?: ""
            }
            if (syncFrom) SteamCloudSaveManager.syncFromCloud(context, appId, installDir, cb)
            else SteamCloudSaveManager.syncToCloud(context, appId, installDir, cb)
        }
    }

    val needSync = statuses.count { it.state.needsAttention() }
    val summary = when {
        loading -> "Loading…"
        statuses.isEmpty() -> "No Steam cloud saves or local Library folders yet."
        needSync == 0 -> "All ${statuses.size} game${plural(statuses.size)} in sync."
        else -> "$needSync game${plural(needSync)} need syncing."
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header bar — mirrors the Steam Library header idiom (back + title).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "Save Manager",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        // Summary line.
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                statuses.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Nothing to sync yet.\nDownload a game's cloud save from its detail page to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(statuses, key = { it.appId }) { s ->
                            SaveStatusRow(
                                status = s,
                                highlighted = s.appId == focusAppId,
                                busy = s.appId in busyAppIds,
                                progress = rowProgress[s.appId],
                                onOpen = { onOpenGame(s.appId) },
                                onSyncFrom = { runQuickMove(s.appId, syncFrom = true) },
                                onSyncTo = { runQuickMove(s.appId, syncFrom = false) },
                            )
                        }
                    }
                }
            }

            // material3 1.2.0's PullToRefreshContainer draws its indicator even at rest; only show
            // it while actively pulling or refreshing (matches FileManagerScreen).
            if (pullState.verticalOffset > 0.5f || pullState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun SaveStatusRow(
    status: SaveStatus,
    highlighted: Boolean,
    busy: Boolean,
    progress: RowProgress?,
    onOpen: () -> Unit,
    onSyncFrom: () -> Unit,
    onSyncTo: () -> Unit,
) {
    // Both combos require a container; NOT_SET_UP rows can't sync — disable the buttons and hint.
    val notSetUp = status.state == SaveState.NOT_SET_UP
    val actionsEnabled = !busy && !notSetUp
    // Sync-from-Cloud is pointless with nothing in the cloud (e.g. a Not-backed-up game) — gate it so
    // the meaningful action (Sync to Cloud = back it up) stands out.
    val syncFromEnabled = actionsEnabled && status.cloudFileCount > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (highlighted) 2.dp else 1.dp,
                color = if (highlighted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        val (pillColor, pillLabel) = pillFor(status.state)

        // Real Steam poster (library_600x900 → header.jpg), cached per appId; shows its own
        // spinner while loading and "×" if the art is missing.
        GameCoverArt(
            appId = status.appId,
            modifier = Modifier
                .size(width = 44.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp)),
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.gameName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status.containerLabel?.let { "Container: $it" } ?: "Not set up in a container",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // While a move runs (or just finished) the progress line takes the open space where the
            // last-synced line normally sits; a small spinner shows only while it's still running.
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = progress.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (notSetUp) {
                // Can't sync without a container — tell the user how to enable it (tap-through opens
                // the detail page where they can set it up).
                Text(
                    text = "Add this game to a container first to sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val times = syncedTimesLine(status)
                if (times.isNotEmpty()) {
                    Text(
                        text = times,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Status pill.
            Spacer(Modifier.height(6.dp))
            StatusPill(color = pillColor, label = pillLabel)
        }

        // Per-row quick actions: the two end-to-end combos — Sync from Cloud (⬇) / Sync to Cloud (⬆).
        // Disabled while a move runs (live progress is in the row body) and for NOT_SET_UP rows.
        val disabledTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onSyncFrom, enabled = syncFromEnabled) {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = "Sync from Cloud",
                    tint = if (syncFromEnabled) MaterialTheme.colorScheme.primary else disabledTint,
                )
            }
            IconButton(onClick = onSyncTo, enabled = actionsEnabled) {
                Icon(
                    imageVector = Icons.Filled.CloudUpload,
                    contentDescription = "Sync to Cloud",
                    tint = if (actionsEnabled) MaterialTheme.colorScheme.primary else disabledTint,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(color: Color, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** A row's live quick-action progress line: what the move is doing, and whether it's an error. */
private data class RowProgress(val message: String, val isError: Boolean = false)

/** How long a finished move's summary / error lingers in the row before it reverts to normal. */
private const val PROGRESS_LINGER_MS = 2500L

// Green = settled, amber/orange = local drift, blue = cloud drift, grey = nothing to do / unset.
private fun pillFor(state: SaveState): Pair<Color, String> = when (state) {
    SaveState.IN_SYNC        -> Color(0xFF3BA55D) to "In sync"
    SaveState.LOCAL_ONLY     -> Color(0xFFE0662E) to "Not backed up"
    SaveState.LOCAL_AHEAD    -> Color(0xFFE0A82E) to "Local ahead"
    SaveState.CLOUD_AHEAD    -> Color(0xFF4B9CE0) to "Cloud ahead"
    SaveState.NEVER_SYNCED   -> Color(0xFFE07B2E) to "Never synced"
    SaveState.NO_CLOUD_SAVES -> Color(0xFF9AA0A6) to "No cloud saves"
    SaveState.NOT_SET_UP     -> Color(0xFF9AA0A6) to "Not set up"
    SaveState.UNKNOWN        -> Color(0xFF9AA0A6) to "Unknown"
}

private fun SaveState.needsAttention(): Boolean = when (this) {
    SaveState.NOT_SET_UP, SaveState.CLOUD_AHEAD, SaveState.LOCAL_ONLY, SaveState.LOCAL_AHEAD, SaveState.NEVER_SYNCED -> true
    else -> false
}

private fun syncedTimesLine(status: SaveStatus): String {
    val parts = ArrayList<String>(2)
    if (status.lastDownloadAt > 0L) parts.add("Downloaded ${relTime(status.lastDownloadAt)}")
    if (status.lastUploadAt > 0L) parts.add("Uploaded ${relTime(status.lastUploadAt)}")
    return parts.joinToString(" · ")
}

private fun relTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()

private fun plural(n: Int) = if (n == 1) "" else "s"
