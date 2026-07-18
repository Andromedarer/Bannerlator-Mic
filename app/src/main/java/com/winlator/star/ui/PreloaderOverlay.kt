package com.winlator.star.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.core.Failure
import com.winlator.star.core.Phase
import com.winlator.star.core.PreloaderState

// The hero surface is always laid over a dark scrim, so text/accents use fixed light-on-dark
// values that read over any cover art rather than the ambient theme's surface colours.
private val HeroText = Color(0xFFF3F5F8)
private val HeroTextDim = Color(0xFFB6BCC6)
private val HeroAccent = Color(0xFF4C8DFF)

/**
 * Full-bleed "game hero" launch overlay. The shortcut's cover art fills the screen behind a dark
 * scrim gradient; the game name sits above a stepped progress readout (determinate bar for the
 * measurable app-side setup, an indeterminate spinner for the guest-boot tail) and a failure card.
 * Shows/hides based on PreloaderState.ui. Place at the top of the host Compose hierarchy.
 */
@Composable
fun PreloaderOverlay() {
    val state by PreloaderState.ui.collectAsState()
    val ui = state ?: return

    // Centered status/shutdown screen — calm logo + message + slim indeterminate bar.
    if (ui.centered) {
        CenteredStatus(ui.tailLabel.ifEmpty { ui.title })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // --- Background: cover art, or a branded dark fallback with the logo centered. ---
        val cover = ui.coverArt ?: ui.icon
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0B0D)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.splash_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
        }

        // --- Scrim: darken the top a touch and heavily at the bottom for legible text. ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.55f),
                        0.35f to Color.Black.copy(alpha = 0.35f),
                        0.72f to Color.Black.copy(alpha = 0.78f),
                        1.0f to Color.Black.copy(alpha = 0.94f),
                    )
                ),
        )

        val insets = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

        // --- Small Bannerlator logo, top-start corner. ---
        // NOTE: must be a raster/vector drawable — Compose painterResource cannot load the
        // adaptive-icon mipmaps (ic_launcher*), which throws IllegalArgumentException. Use the
        // Bannerlator splash logo (JPG) like the centered fallback above.
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = null,
            modifier = insets
                .padding(start = 20.dp, top = 16.dp)
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .align(Alignment.TopStart),
        )

        // --- Hero content: game name + stepped progress, anchored low. ---
        Column(
            modifier = insets
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 32.dp),
        ) {
            if (ui.title.isNotEmpty()) {
                Text(
                    text = ui.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = HeroText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!ui.subtitle.isNullOrEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = ui.subtitle.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.6.sp,
                        color = HeroTextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            when (ui.phase) {
                Phase.SETUP -> SetupProgress(ui.stepIndex, ui.stepTotal, ui.stepLabel)
                Phase.GUEST -> GuestProgress(ui.tailLabel)
                Phase.FAILED -> FailureCard(ui.failure)
            }

            // Not-frozen reassurance line (SETUP/GUEST only; the failure card is self-contained).
            if (ui.phase != Phase.FAILED) {
                AnimatedVisibility(visible = ui.hint != null) {
                    Column {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = ui.hint ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = HeroAccent,
                        )
                    }
                }
                // Cancel launch — aborts the launch and tears down the game/container so the user is
                // never stuck on the launch screen. Only during a real launch (not the shutdown message).
                if (ui.cancellable) {
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = { PreloaderState.onCancel?.run() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HeroText),
                    border = BorderStroke(1.dp, HeroText.copy(alpha = 0.45f)),
                ) {
                    Text("Cancel launch")
                }
                }
            }
        }
    }
}

@Composable
private fun SetupProgress(stepIndex: Int, stepTotal: Int, stepLabel: String) {
    // Stage row: uppercase mono label on the left, "N / M" counter on the right.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stepLabel.isNotEmpty()) {
            Text(
                text = stepLabel.trimEnd('…', '.', ' ').uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
                color = HeroText.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (stepTotal > 0 && stepIndex > 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$stepIndex / $stepTotal",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = HeroAccent,
            )
        }
    }
    Spacer(Modifier.height(11.dp))
    StepPips(stepIndex, stepTotal)
}

/** Discrete step segments: done = solid accent, current = pulsing accent, pending = faint. */
@Composable
private fun StepPips(stepIndex: Int, stepTotal: Int) {
    val transition = rememberInfiniteTransition(label = "pips")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pipPulse",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 1..stepTotal) {
            val color = when {
                i < stepIndex -> HeroAccent
                i == stepIndex -> HeroAccent.copy(alpha = pulse)
                else -> Color.White.copy(alpha = 0.14f)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Centered "working…" screen for shutdown and other indeterminate operations (backup, restore,
 * install, create-container). Deliberately quiet and distinct from the cover-art launch hero.
 */
@Composable
private fun CenteredStatus(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0D)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = HeroText,
                )
            }
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                color = HeroAccent,
                trackColor = Color.White.copy(alpha = 0.14f),
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .width(150.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun GuestProgress(tailLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            color = HeroAccent,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(20.dp),
        )
        if (tailLabel.isNotEmpty()) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = tailLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = HeroText,
            )
        }
    }
}

@Composable
private fun FailureCard(failure: Failure?) {
    failure ?: return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = "Failed · ${failure.stage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = failure.what,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!failure.detail.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = failure.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            if (failure.loggingEnabled && !failure.logDir.isNullOrEmpty()) {
                Text(
                    text = "Log saved to ${failure.logDir}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { PreloaderState.onOpenLog?.run() }) {
                        Text("Open log folder")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { PreloaderState.onClose?.run() }) {
                        Text("Close")
                    }
                }
            } else {
                Text(
                    text = "Enable logging in Settings → Logs and relaunch to capture the cause.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { PreloaderState.onClose?.run() }) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
