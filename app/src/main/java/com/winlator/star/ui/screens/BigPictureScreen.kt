@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.XrActivity
import com.winlator.star.container.ContainerManager
import com.winlator.star.communityconfigs.CommunityConfigApply
import com.winlator.star.container.Shortcut
import com.winlator.star.store.DownloadManagerActivity
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.ui.Screen
import com.winlator.star.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Big Picture — a couch/TV launcher shown at startup instead of the normal UI when the pref is on.
// Full-bleed Compose rebuild of the old XML BigPictureActivity: blurred hero background, a centred
// hero for the selected game and a scrolling cover carousel, all driven controller-first. Reached as
// the Screen.BigPicture NavHost route (see AppNavGraph / MainActivity), so every rail is a plain
// navController.navigate(...).

private enum class BpZone { RAIL, PLAY, CAROUSEL }
private enum class BpSheet { GAME_OPTIONS, TOOLS, POWER }

private val CARD_WIDTH = 84.dp
private val CARD_HEIGHT = 126.dp

@Composable
fun BigPictureScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val manager = remember { ContainerManager(context) }
    var shortcuts by remember { mutableStateOf<List<Shortcut>>(manager.loadShortcuts()) }
    var selectedIndex by remember { mutableStateOf(0) }
    // Community configs — reuses the phone UI's browser + account dialogs (now internal). Applies
    // to the currently selected game; the smart missing-component install sub-flow stays on the
    // phone UI for now, so the result dialog just surfaces the outcome message.
    val communityVm: ShortcutsViewModel = viewModel()
    var showCommunity by remember { mutableStateOf(false) }
    var showBpAccount by remember { mutableStateOf(false) }
    var communityApplyResult by remember { mutableStateOf<CommunityConfigApply.ConfigApplyResult?>(null) }
    var communityApplying by remember { mutableStateOf(false) }

    // Decoded covers keyed by shortcut name; guarded by a plain in-flight set so we never re-fetch.
    val coverCache = remember { mutableStateMapOf<String, ImageBitmap>() }
    val inFlight = remember { HashSet<String>() }

    val listState = rememberLazyListState()

    var zone by remember { mutableStateOf(BpZone.CAROUSEL) }
    var railIndex by remember { mutableStateOf(0) } // 0 = Community, 1 = App Settings, 2 = Tools, 3 = Power
    var playIndex by remember { mutableStateOf(0) } // in the PLAY zone: 0 = Play, 1 = Game options

    var activeSheet by remember { mutableStateOf<BpSheet?>(null) }
    var editShortcut by remember { mutableStateOf(false) }

    // A SINGLE focus target on the root Box. No sub-element (rail / Play / carousel) is focusable, so
    // Compose never paints its own default focus ring — every "focus" you see is our own zone/index
    // state drawn as borders. This is what kills the phantom highlight that used to appear over the
    // spec chips when pressing D-pad up.
    val rootFocus = remember { FocusRequester() }
    val grabFocus: () -> Unit = { runCatching { rootFocus.requestFocus() } }

    // Lazily resolve a cover for a shortcut: custom path first, then SteamGridDB (saved as the custom
    // cover so it persists), mirroring the Shortcuts screen scrape flow.
    val ensureCover: (Shortcut) -> Unit = { s ->
        if (!coverCache.containsKey(s.name) && inFlight.add(s.name)) {
            scope.launch(Dispatchers.IO) {
                val img = loadCover(s)
                withContext(Dispatchers.Main) {
                    if (img != null) coverCache[s.name] = img
                    inFlight.remove(s.name)
                }
            }
        }
    }

    // Immersive system bars while on Big Picture; restored on leaving.
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Refresh shortcuts on resume (new games, edited settings/covers) and re-grab focus after we
    // return from launching a game.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shortcuts = manager.loadShortcuts()
                if (selectedIndex > shortcuts.lastIndex) selectedIndex = shortcuts.lastIndex.coerceAtLeast(0)
                grabFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Seed the root focus once so key events route to us from the first frame.
    LaunchedEffect(Unit) { grabFocus() }

    // Re-grab focus after a sheet / dialog closes so D-pad keeps working.
    LaunchedEffect(activeSheet, editShortcut) { if (activeSheet == null && !editShortcut) grabFocus() }

    // Preload the selected cover so the hero + blurred background are ready.
    LaunchedEffect(selectedIndex, shortcuts) {
        shortcuts.getOrNull(selectedIndex)?.let { ensureCover(it) }
    }

    // Keep the selected carousel item roughly centred.
    LaunchedEffect(selectedIndex, shortcuts.size) {
        if (shortcuts.isNotEmpty()) {
            val viewport = listState.layoutInfo.viewportSize.width
            val itemPx = with(density) { CARD_WIDTH.roundToPx() }
            val offset = if (viewport > 0) -(viewport / 2 - itemPx / 2) else 0
            runCatching { listState.animateScrollToItem(selectedIndex.coerceIn(0, shortcuts.lastIndex), offset) }
        }
    }

    val selected = shortcuts.getOrNull(selectedIndex)
    val heroCover = selected?.let { coverCache[it.name] }

    val onLaunch: () -> Unit = { selected?.let { runShortcut(activity, it) } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Community overlays own Back first (they sit above sheets).
                if (showCommunity || showBpAccount || communityApplyResult != null) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.ButtonB, Key.Back -> {
                            when {
                                communityApplyResult != null -> communityApplyResult = null
                                showBpAccount -> showBpAccount = false
                                else -> showCommunity = false
                            }
                            true
                        }
                        else -> false
                    }
                }
                // While a sheet is up, only handle the close gesture; everything else is the sheet's.
                if (activeSheet != null || editShortcut) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.ButtonB, Key.Back -> { activeSheet = null; editShortcut = false; true }
                        else -> false
                    }
                }
                when (event.key) {
                    Key.DirectionLeft -> {
                        when (zone) {
                            BpZone.CAROUSEL -> if (selectedIndex > 0) selectedIndex--
                            BpZone.RAIL     -> if (railIndex > 0) railIndex--
                            BpZone.PLAY     -> playIndex = 0   // Play
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        when (zone) {
                            BpZone.CAROUSEL -> if (selectedIndex < shortcuts.lastIndex) selectedIndex++
                            BpZone.RAIL     -> if (railIndex < 3) railIndex++
                            BpZone.PLAY     -> playIndex = 1   // Game options
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        zone = when (zone) {
                            BpZone.CAROUSEL -> { playIndex = 0; BpZone.PLAY }
                            BpZone.PLAY     -> BpZone.RAIL
                            BpZone.RAIL     -> BpZone.RAIL
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        zone = when (zone) {
                            BpZone.RAIL     -> { playIndex = 0; BpZone.PLAY }
                            BpZone.PLAY     -> BpZone.CAROUSEL
                            BpZone.CAROUSEL -> BpZone.CAROUSEL
                        }
                        true
                    }
                    Key.ButtonA, Key.Enter, Key.DirectionCenter -> {
                        when (zone) {
                            // A on the carousel launches the highlighted game (fastest couch path).
                            BpZone.CAROUSEL -> onLaunch()
                            // In the PLAY zone A activates whichever button is highlighted.
                            BpZone.PLAY -> if (playIndex == 0) onLaunch()
                                           else if (selected != null) activeSheet = BpSheet.GAME_OPTIONS
                            BpZone.RAIL -> when (railIndex) {
                                0 -> showCommunity = true
                                1 -> navController.navigate(Screen.Settings.route)
                                2 -> activeSheet = BpSheet.TOOLS
                                3 -> activeSheet = BpSheet.POWER
                            }
                        }
                        true
                    }
                    // Y = game options for the selected game (from carousel or the hero).
                    Key.ButtonY -> {
                        if (selected != null && zone != BpZone.RAIL) activeSheet = BpSheet.GAME_OPTIONS
                        true
                    }
                    Key.ButtonR1 -> { activeSheet = BpSheet.TOOLS; true }
                    Key.ButtonB, Key.Back -> true // stay in Big Picture
                    else -> false
                }
            },
    ) {
        // 1. Blurred hero background, cross-faded on selection change.
        Crossfade(
            targetState = (selected?.name ?: "") to heroCover,
            animationSpec = tween(350),
            label = "bp-hero-bg",
        ) { (_, cover) ->
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(40.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surface,
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                )
            }
        }
        // Dark scrim for text legibility (top transparent → bottom near-black).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
        )

        // 2. Foreground: rail on top, hero fills the middle as a WEIGHTED sibling, carousel is a
        // compact bottom strip. Column stacking (not overlapping alignment) guarantees the hero's
        // Play / Game-options buttons can never be drawn under the carousel — the earlier version
        // reserved carousel space with bottom-padding, which overflowed and clipped them.
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            // Global rail (top-end).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RailButton(
                    icon = Icons.Filled.Public,
                    label = "Community Configs",
                    focused = zone == BpZone.RAIL && railIndex == 0,
                    onClick = { zone = BpZone.RAIL; railIndex = 0; showCommunity = true },
                )
                Spacer(Modifier.width(12.dp))
                RailButton(
                    icon = Icons.Filled.Settings,
                    label = "App Settings",
                    focused = zone == BpZone.RAIL && railIndex == 1,
                    onClick = { zone = BpZone.RAIL; railIndex = 1; navController.navigate(Screen.Settings.route) },
                )
                Spacer(Modifier.width(12.dp))
                RailButton(
                    icon = Icons.Filled.Apps,
                    label = "Tools",
                    focused = zone == BpZone.RAIL && railIndex == 2,
                    onClick = { zone = BpZone.RAIL; railIndex = 2; activeSheet = BpSheet.TOOLS },
                )
                Spacer(Modifier.width(12.dp))
                RailButton(
                    icon = Icons.Filled.PowerSettingsNew,
                    label = "Power",
                    focused = zone == BpZone.RAIL && railIndex == 3,
                    onClick = { zone = BpZone.RAIL; railIndex = 3; activeSheet = BpSheet.POWER },
                )
            }

            if (shortcuts.isEmpty()) {
                // Empty state.
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No games yet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { navController.navigate(Screen.Games.route) }) { Text("Add games") }
                }
            } else {
                // Hero — fills the band between the rail and the carousel (a weighted sibling, so its
                // buttons can never render under the carousel). Cover scales to the band, capped so it
                // doesn't get huge on tablets.
                // Per-game spec = the SAME resolved settings the game cards and the pre-launch screen
                // show (buildLaunchSpec: renderer / driver / DXVK / VKD3D / frame-gen / x86 backend),
                // so Big Picture matches the rest of the app instead of the old wrapper/box64 chips.
                val spec = selected?.let { buildLaunchSpec(it, context.resources) }
                val chips = spec?.let {
                    listOf(
                        "Renderer" to it.rendererLabel,
                        "Driver" to it.driverLabel,
                        "DXVK" to it.dxvkVersion,
                        "VKD3D" to it.vkd3dVersion,
                        "Frame Gen" to it.frameGenLabel,
                        "x86" to it.backendLabel,
                    ).filter { p -> p.second.isNotBlank() }
                }.orEmpty()

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val coverH = maxHeight.coerceAtMost(260.dp)
                    val coverW = coverH * 2f / 3f            // 2:3 poster ratio
                    Row(modifier = Modifier.fillMaxWidth().height(coverH), verticalAlignment = Alignment.CenterVertically) {
                        CoverCard(cover = heroCover, modifier = Modifier.width(coverW).fillMaxHeight(), selected = false)
                        Spacer(Modifier.width(24.dp))
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            // Title + spec take the space ABOVE the buttons and clip if a game has a very
                            // long title or many chips (e.g. GTA IV's 6 chips); the buttons below are
                            // pinned and therefore always visible — the previous overflow clipped them.
                            Column(modifier = Modifier.weight(1f).clipToBounds()) {
                                Text(
                                    text = selected?.name ?: "",
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!spec?.meta.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(spec!!.meta, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                val stats = playtimeStats(context, selected?.name ?: "")
                                Text(
                                    "Played ${stats.first}×  ·  ${stats.second}",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 13.sp,
                                )
                                if (chips.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        chips.forEach { (l, v) -> SpecChip(l, v) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            // Play + Game options: pinned at the bottom of the hero. Each shows our own
                            // focus border when it's the highlighted item in the PLAY zone (Left/Right
                            // toggles), and both stay tappable for touch-only users.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val playFocused = zone == BpZone.PLAY && playIndex == 0
                                val optionsFocused = zone == BpZone.PLAY && playIndex == 1
                                Button(
                                    onClick = { zone = BpZone.PLAY; playIndex = 0; onLaunch() },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .then(
                                            if (playFocused)
                                                Modifier.border(3.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(16.dp))
                                            else Modifier
                                        ),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                OutlinedButton(
                                    onClick = { zone = BpZone.PLAY; playIndex = 1; if (selected != null) activeSheet = BpSheet.GAME_OPTIONS },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .then(
                                            if (optionsFocused)
                                                Modifier.border(3.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(16.dp))
                                            else Modifier
                                        ),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Icon(Icons.Filled.Tune, contentDescription = null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Game options", color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Carousel — compact bottom strip.
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(shortcuts) { index, s ->
                        val isSel = index == selectedIndex
                        val scale by animateFloatAsState(if (isSel) 1.12f else 1f, label = "bp-card-scale")
                        LaunchedEffect(s.name) { ensureCover(s) }
                        CoverCard(
                            cover = coverCache[s.name],
                            modifier = Modifier
                                .width(CARD_WIDTH)
                                .height(CARD_HEIGHT)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .clickable { zone = BpZone.CAROUSEL; selectedIndex = index },
                            selected = isSel,
                        )
                    }
                }
            }
        }
    }

    // ── Sheets ────────────────────────────────────────────────────────────────
    when (activeSheet) {
        BpSheet.GAME_OPTIONS -> selected?.let { s ->
            GameOptionsSheet(
                shortcut = s,
                onDismiss = { activeSheet = null },
                onEditShortcut = { activeSheet = null; editShortcut = true },
                onContainerSettings = {
                    activeSheet = null
                    navController.navigate("container_detail?id=${s.container.id}")
                },
                onChangeCover = { bmp ->
                    s.saveCustomCoverArt(bmp)
                    coverCache[s.name] = bmp.asImageBitmap()
                },
                onRemoveCover = {
                    s.removeCustomCoverArt()
                    coverCache.remove(s.name)
                    inFlight.remove(s.name)
                },
                onCommunityConfigs = {
                    // Selecting the game first, then opening the browser, so a config picked there
                    // applies to this game. (Stage 2: the per-game match sheet directly.)
                    selectedIndex = shortcuts.indexOfFirst { it.name == s.name }.coerceAtLeast(0)
                    activeSheet = null
                    showCommunity = true
                },
            )
        }
        BpSheet.TOOLS -> ToolsSheet(
            onDismiss = { activeSheet = null },
            onFileManager = { activeSheet = null; navController.navigate(Screen.FileManager.route) },
            onWrappers = { activeSheet = null; navController.navigate(Screen.Wrappers.route) },
            onDownloads = {
                activeSheet = null
                context.startActivity(Intent(context, DownloadManagerActivity::class.java))
            },
        )
        BpSheet.POWER -> PowerSheet(
            onDismiss = { activeSheet = null },
            onExit = {
                activeSheet = null
                navController.navigate(Screen.Games.route) {
                    popUpTo(Screen.BigPicture.route) { inclusive = true }
                }
            },
            onTurnOff = {
                activeSheet = null
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putBoolean("enable_big_picture_mode", false).apply()
                navController.navigate(Screen.Games.route) {
                    popUpTo(Screen.BigPicture.route) { inclusive = true }
                }
            },
            onQuit = { activity.finishAffinity() },
        )
        null -> {}
    }

    if (editShortcut && selected != null) {
        ShortcutSettingsDialogScreen(
            shortcut = selected,
            onDismiss = {
                editShortcut = false
                shortcuts = manager.loadShortcuts()
            },
        )
    }

    // ── Community configs ──────────────────────────────────────────────────
    if (showCommunity) {
        CommunityCatalogBrowser(
            vm = communityVm,
            onDismiss = { showCommunity = false },
            onPick = { pick ->
                // Apply to the currently selected game. Big Picture always sits on one, so there is
                // no separate target picker — unlike the phone browser's "Apply to game…" chooser.
                val target = selected ?: return@CommunityCatalogBrowser
                communityApplying = true
                val onDone: (CommunityConfigApply.ConfigApplyResult) -> Unit = { res ->
                    communityApplying = false
                    communityApplyResult = res
                    shortcuts = manager.loadShortcuts()
                }
                when (pick) {
                    is CommunityPick.File -> communityVm.applyCommunityConfigFile(target, pick.ref, onDone)
                    is CommunityPick.Device -> communityVm.applyCommunityConfig(target, pick.game, pick.device, onDone)
                }
            },
            onMyAccount = { showBpAccount = true },
        )
    }

    if (showBpAccount) {
        MyAccountDialog(
            vm = communityVm,
            onDismiss = { showBpAccount = false },
            onOpenMyUploads = { showBpAccount = false },  // Stage 2: dedicated My-uploads manager
            onLoggedIn = {},
        )
    }

    communityApplyResult?.let { res ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { communityApplyResult = null },
            title = { Text(if (res.ok) "Config applied" else "Couldn't apply") },
            text = {
                Text(
                    res.message + (if (res.ok) "\n\nApplied to \"${selected?.name.orEmpty()}\"." else ""),
                    color = Color.White.copy(alpha = 0.85f),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { communityApplyResult = null }) { Text("OK") }
            },
        )
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
            .then(
                if (focused)
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(50))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White,
        )
    }
}

@Composable
private fun CoverCard(
    cover: ImageBitmap?,
    modifier: Modifier = Modifier,
    selected: Boolean,
) {
    Card(
        modifier = modifier.then(
            if (selected)
                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            else Modifier
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 12.dp else 4.dp),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.cover_art_placeholder),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

@Composable
private fun SpecChip(label: String, value: String) {
    // Compact so the full spec (up to 6 chips, e.g. GTA IV) packs into at most two short rows that
    // clear the pinned Play / Game-options buttons instead of getting clipped.
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.12f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) {
            Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, lineHeight = 11.sp)
            Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun GameOptionsSheet(
    shortcut: Shortcut,
    onDismiss: () -> Unit,
    onEditShortcut: () -> Unit,
    onContainerSettings: () -> Unit,
    onChangeCover: (android.graphics.Bitmap) -> Unit,
    onRemoveCover: () -> Unit,
    onCommunityConfigs: () -> Unit,
) {
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            com.winlator.star.util.InAppFilePicker.pickedUri(result.data)?.let { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)?.let(onChangeCover)
                    }
                }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle(shortcut.name)
            SheetRow(Icons.Filled.Edit, "Edit shortcut", onEditShortcut)
            SheetRow(Icons.Filled.Tune, "Container settings", onContainerSettings)
            SheetRow(Icons.Filled.Public, "Community configs", onCommunityConfigs)
            SheetRow(Icons.Filled.Image, "Change cover art") {
                coverPicker.launch(
                    com.winlator.star.util.InAppFilePicker.buildIntent(
                        context, com.winlator.star.util.InAppFilePicker.IMAGES, "Select cover art"
                    )
                )
            }
            if (!shortcut.customCoverArtPath.isNullOrEmpty()) {
                SheetRow(Icons.Filled.Delete, "Remove cover art") { onRemoveCover(); onDismiss() }
            }
        }
    }
}

@Composable
private fun ToolsSheet(
    onDismiss: () -> Unit,
    onFileManager: () -> Unit,
    onWrappers: () -> Unit,
    onDownloads: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle("Tools")
            SheetRow(Icons.Filled.FolderOpen, "File Manager", onFileManager)
            SheetRow(Icons.Filled.Layers, "Manage Wrappers", onWrappers)
            SheetRow(Icons.Filled.Download, "Downloads", onDownloads)
        }
    }
}

@Composable
private fun PowerSheet(
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onTurnOff: () -> Unit,
    onQuit: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle("Power")
            SheetRow(Icons.Filled.ExitToApp, "Exit Big Picture", onExit)
            SheetRow(Icons.Filled.PowerSettingsNew, "Turn off Big Picture mode", onTurnOff)
            SheetRow(Icons.Filled.Close, "Quit", onQuit)
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────

// (play count, formatted playtime) from the same prefs XServerDisplayActivity writes.
private fun playtimeStats(context: Context, name: String): Pair<Int, String> {
    val prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
    val totalMs = prefs.getLong("${name}_playtime", 0L)
    val playCount = prefs.getInt("${name}_play_count", 0)
    val seconds = (totalMs / 1000) % 60
    val minutes = (totalMs / (1000 * 60)) % 60
    val hours   = (totalMs / (1000 * 60 * 60)) % 24
    val days    = (totalMs / (1000 * 60 * 60 * 24))
    return playCount to String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
}

// Resolve a cover: existing custom art first, then a SteamGridDB grid (persisted as the custom cover).
private fun loadCover(s: Shortcut): ImageBitmap? {
    val custom = s.customCoverArtPath
    if (!custom.isNullOrEmpty()) {
        val f = File(custom)
        if (f.exists()) BitmapFactory.decodeFile(custom)?.let { return it.asImageBitmap() }
    }
    return try {
        val arr = JSONArray(StarLaunchBridge.sgdbFetchGridsJson(s.name))
        if (arr.length() == 0) return null
        val url = arr.getJSONObject(0).optString("url", "")
        if (url.isEmpty()) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val bmp = BitmapFactory.decodeStream(conn.inputStream)
        conn.disconnect()
        if (bmp != null) {
            s.saveCustomCoverArt(bmp)
            bmp.asImageBitmap()
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun runShortcut(activity: Activity, shortcut: Shortcut) {
    if (!XrActivity.isEnabled(activity)) {
        val intent = Intent(activity, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", shortcut.container.id)
            putExtra("shortcut_path", shortcut.file.path)
            putExtra("shortcut_name", shortcut.name)
            putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"))
        }
        activity.startActivity(intent)
    } else {
        XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.path)
    }
}
