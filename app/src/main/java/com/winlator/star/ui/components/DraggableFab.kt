package com.winlator.star.ui.components

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An "add" button the user can long-press and slide along the bottom of the screen.
 *
 * The problem it solves: the button floats over the list, so on whichever card ends up beneath it
 * the play and overflow buttons are unreachable. There is no corner that is always safe — which
 * card sits under it depends on list length, view mode and screen size — so instead of guessing,
 * let the user move it out of their own way and remember where they put it.
 *
 * Horizontal only, pinned to the bottom. Free 2D placement would let someone strand it over the
 * header or lose it behind a dialog, and the bottom row is exactly the row that gets blocked.
 *
 * Behaviour lives here; the look does not. Games draws a bordered square and Containers a Material
 * FAB, so the caller passes its own styling through [buttonModifier] and its own icon as content.
 *
 * @param prefKey distinct per screen, so Games and Containers remember separate positions.
 */
@Composable
fun BoxScope.DraggableAddButton(
    prefKey: String,
    onClick: () -> Unit,
    buttonModifier: Modifier,
    outerPadding: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("fab_positions", Context.MODE_PRIVATE) }

    // Stored as a 0..1 fraction of the free travel rather than as pixels, so the same setting
    // survives a rotation and lands somewhere sensible on another screen size. Default 1f = hard
    // right, which is where the button has always been.
    var fraction by remember { mutableFloatStateOf(prefs.getFloat(prefKey, 1f).coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }

    // Measured on the parent, not the button: the button is translated, so its own bounds cannot
    // report how much room it has to travel in.
    var trackPx by remember { mutableFloatStateOf(0f) }
    var buttonPx by remember { mutableFloatStateOf(0f) }
    val travel = (trackPx - buttonPx).coerceAtLeast(0f)

    val lift by animateFloatAsState(if (dragging) 1.12f else 1f, label = "fabLift")

    fun commit() {
        dragging = false
        prefs.edit().putFloat(prefKey, fraction).apply()
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(outerPadding)
            .onSizeChanged { trackPx = it.width.toFloat() },
    ) {
        Box(
            // Order is load-bearing. graphicsLayer must come BEFORE the caller's border/background,
            // because modifiers nest outside-in: a transform only moves what is nested inside it.
            // With it after, the border and fill drew at the layout position while the icon and the
            // touch target slid away — an empty square stuck at the left with its hit box somewhere
            // off to the right. onSizeChanged goes after buttonModifier so it measures the sized
            // button rather than the incoming constraints.
            modifier = Modifier
                .graphicsLayer { translationX = travel * fraction }
                .scale(lift)
                .then(buttonModifier)
                .onSizeChanged { buttonPx = it.width.toFloat() }
                // Drag detector FIRST so it can claim the gesture, tap detector second. The tap
                // detector declares an (empty) onLongPress on purpose: without one, a long hold
                // still counts as a tap on release, and picking the button up would also fire it.
                .pointerInput(travel) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = { commit() },
                        onDragCancel = { commit() },
                        onDrag = { change, drag ->
                            change.consume()
                            if (travel > 0f) fraction = (fraction + drag.x / travel).coerceIn(0f, 1f)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { }, onTap = { onClick() })
                },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
