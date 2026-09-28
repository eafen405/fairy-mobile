package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.components.LocalFairyBreathOverride
import com.newoether.agora.ui.components.breathAt
import com.newoether.agora.ui.components.fairySlant
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.LocalFairyTokens

/** Accent bar width and its gap to the text column. */
internal val FairyAccentBarWidth = 2.dp
internal val FairyAccentBarGlow = 4.dp
internal val FairyAccentTextGap = 12.dp

/**
 * Extra insets inside the 8 dp list padding: the bar sits 16 dp from the
 * screen edge; with the 8 dp content inset the text ends 20 dp from the edge.
 */
internal val FairySpeechStartInset = 8.dp
internal val FairySpeechEndInset = 4.dp
private const val ACCENT_BREATH_MS = 1600

/**
 * Fairy's speech column: a 2 dp vertical bar spanning the whole assistant
 * message on its start edge. At rest the bar is fairyBlue at 50 %; while this
 * message is the working tail ([active]) it turns fairyGlow, its alpha
 * breathes 0.6 <-> 1.0 and a 4 dp soft glow surrounds it. The breath is read
 * only while drawing, so it redraws without recomposing; Reduced Motion
 * holds the mid value.
 */
@Composable
internal fun Modifier.fairyAccentBar(active: Boolean): Modifier {
    val tokens = LocalFairyTokens.current
    val override = LocalFairyBreathOverride.current
    val animate = active && override == null && LocalAgoraMotionPolicy.current.allowContinuousMotion
    val breath: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "fairyAccentBreath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ACCENT_BREATH_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "fairyAccentBreathPhase",
        )
    } else {
        remember { mutableFloatStateOf(0.25f) }
    }
    val resting = tokens.fairyBlue.copy(alpha = 0.5f)
    val glow = tokens.fairyGlow
    return drawBehind {
        val width = FairyAccentBarWidth.toPx()
        if (!active) {
            drawRect(resting, Offset.Zero, Size(width, size.height))
            return@drawBehind
        }
        val b = override ?: breathAt(breath.value)
        val alpha = 0.6f + 0.4f * b
        val halo = FairyAccentBarGlow.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to glow.copy(alpha = 0f),
                0.5f to glow.copy(alpha = 0.35f * alpha),
                1f to glow.copy(alpha = 0f),
                startX = -halo,
                endX = width + halo,
            ),
            topLeft = Offset(-halo, 0f),
            size = Size(width + halo * 2f, size.height),
        )
        drawRect(glow.copy(alpha = alpha), Offset.Zero, Size(width, size.height))
    }
}

/**
 * Small fairyBlue badge above relayed content: slanted
 * "转达自 {source}" in the title font.
 */
@Composable
internal fun FairyRelayBadge(source: String, modifier: Modifier = Modifier) {
    val tokens = LocalFairyTokens.current
    Box(
        modifier = modifier
            .fairySlant()
            .background(tokens.fairyBlue, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_relayed_from, source),
            color = tokens.textPrimary,
            fontFamily = tokens.titleFontFamily,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

private const val ACTIVITY_SWEEP_MS = 1400

/**
 * In-progress activity hint: a fairyGlow highlight sweeps left to right
 * across the content every 1400 ms (SrcAtop on an offscreen layer, so only
 * the glyphs light up). Off while [active] is false or under Reduced Motion.
 */
@Composable
internal fun Modifier.fairyActivitySweep(active: Boolean): Modifier {
    if (!active || !LocalAgoraMotionPolicy.current.allowContinuousMotion) return this
    val glow = LocalFairyTokens.current.fairyGlow
    val phase = rememberInfiniteTransition(label = "fairyActivitySweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ACTIVITY_SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "fairyActivitySweepPhase",
    )
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = size.width * 0.35f
            val x = -band + (size.width + band * 2f) * phase.value
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to glow.copy(alpha = 0f),
                    0.5f to glow,
                    1f to glow.copy(alpha = 0f),
                    startX = x - band,
                    endX = x,
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
}
