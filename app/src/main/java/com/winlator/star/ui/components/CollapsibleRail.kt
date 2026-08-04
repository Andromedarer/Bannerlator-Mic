package com.winlator.star.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tappable entry in a [CollapsibleRail] (a container tab, a file-manager location, …). */
data class RailItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** A group of [RailItem]s under an optional small section header (STORAGE / QUICK / FAVORITES …). */
data class RailSection(val header: String?, val items: List<RailItem>)

/** A secondary link shown under the header (e.g. "What is all this?", "Reset to app defaults"). */
data class RailLink(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * State for a [CollapsibleRail], with the app-wide unified rule:
 *  - PORTRAIT → always collapsed (icon-only), the toggle is HIDDEN and it can never expand.
 *  - LANDSCAPE → expanded by default; the user's collapse choice is remembered PER-SCREEN
 *    ([screenKey]) and is NOT overridden by rotation. portrait→landscape restores that choice.
 *
 * Backed by rememberSaveable (survives process death) and by the fact the app UI activity keeps
 * orientation in configChanges (no recreate) so the selected item / fields / scroll are preserved.
 */
class RailState internal constructor(
    val collapsed: Boolean,
    /** True in portrait: the rail is locked collapsed and must not show an expand toggle. */
    val portraitLocked: Boolean,
    val onToggle: () -> Unit,
)

@Composable
fun rememberRailState(screenKey: String): RailState {
    val context = LocalContext.current
    val prefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val choseKey = "rail_${screenKey}_userChose"
    val collapsedKey = "rail_${screenKey}_collapsed"

    // Keep the landscape choice in saveable state regardless of orientation so portrait→landscape
    // restores it. In portrait we simply report collapsed+locked and ignore it.
    var userChose by rememberSaveable(screenKey) { mutableStateOf(prefs.getBoolean(choseKey, false)) }
    var landscapeCollapsed by rememberSaveable(screenKey) {
        mutableStateOf(if (prefs.getBoolean(choseKey, false)) prefs.getBoolean(collapsedKey, false) else false)
    }

    if (!isLandscape) {
        return RailState(collapsed = true, portraitLocked = true, onToggle = {})
    }
    return RailState(
        collapsed = landscapeCollapsed,
        portraitLocked = false,
        onToggle = {
            landscapeCollapsed = !landscapeCollapsed
            userChose = true
            prefs.edit()
                .putBoolean(choseKey, true)
                .putBoolean(collapsedKey, landscapeCollapsed)
                .apply()
        },
    )
}

/**
 * The shared collapsible left rail (mockup "Option 3"): app glyph + title, optional links, and the
 * navigation items, replacing a top tab bar so content runs full height beside it. Width animates
 * 190dp ↔ 58dp. When collapsed it is icon-only (the caller surfaces the active item's name over the
 * content). Used by the container editors, File Manager and Save Manager.
 *
 * [footer] is an optional slot pinned to the bottom of the rail (a weighted spacer pushes it down),
 * for a persistent per-screen summary (e.g. the Save Manager's "N games need syncing" line). The
 * caller renders whatever it wants there and can key it off [RailState.collapsed] to go icon-only.
 */
@Composable
fun CollapsibleRail(
    state: RailState,
    title: String,
    sections: List<RailSection>,
    modifier: Modifier = Modifier,
    links: List<RailLink> = emptyList(),
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val collapsed = state.collapsed
    val width by animateDpAsState(if (collapsed) 58.dp else 190.dp, label = "railWidth")

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        // ── Header: app glyph + title + collapse handle (handle hidden when portrait-locked) ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (collapsed) 0.dp else 10.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            if (!collapsed) {
                Spacer(Modifier.width(9.dp))
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!state.portraitLocked) {
                    IconButton(onClick = state.onToggle, modifier = Modifier.size(28.dp)) {
                        Text("‹‹", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                }
            }
        }
        // Collapsed (landscape only) → an expand handle; portrait is locked with no handle.
        if (collapsed && !state.portraitLocked) {
            IconButton(onClick = state.onToggle, modifier = Modifier.fillMaxWidth().height(24.dp)) {
                Text("››", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
        }

        // ── Links ──
        if (links.isNotEmpty()) {
            if (collapsed) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    links.forEach { link ->
                        IconButton(onClick = link.onClick, modifier = Modifier.size(32.dp)) {
                            Icon(link.icon, link.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                links.forEach { link ->
                    TextButton(onClick = link.onClick, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(link.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(link.label, fontSize = 11.sp)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        // ── Sections + items ──
        sections.forEach { section ->
            if (!collapsed && section.header != null) {
                Text(
                    section.header,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                )
            } else if (collapsed && section.header != null && section != sections.first()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            section.items.forEach { item ->
                val active = item.selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = item.onClick)
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = if (collapsed) 0.dp else 13.dp, vertical = 11.dp)
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    if (!collapsed) {
                        Spacer(Modifier.width(11.dp))
                        Text(
                            item.label,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ── Optional pinned footer (e.g. the sync summary) ──
        if (footer != null) {
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            footer()
        }
    }
}
