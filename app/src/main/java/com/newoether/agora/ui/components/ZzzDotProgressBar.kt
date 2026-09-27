package com.newoether.agora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.LocalZzzTokens

private const val DOT_COUNT = 12
private const val BAND_WIDTH = 4
private const val PERIOD_MS = 1200

/**
 * The connecting-page progress bar: [DOT_COUNT] 6dp dots, a 4-dot-wide
 * fairyGlow band sweeping left to right every 1200ms. Under Reduced Motion the
 * band stays parked at the leftmost position instead of animating.
 *
 * The animated position is read inside [drawBehind] so the sweep redraws
 * without recomposing.
 */
@Composable
fun ZzzDotProgressBar(modifier: Modifier = Modifier) {
    val tokens = LocalZzzTokens.current
    val glow = tokens.fairyGlow
    val unlit = tokens.pill
    val allowContinuous = LocalAgoraMotionPolicy.current.allowContinuousMotion
    // Sweep span includes the band width so the band fully exits on the right
    // before wrapping to the left.
    val sweep = (DOT_COUNT + BAND_WIDTH).toFloat()
    val bandStart: State<Float> = if (allowContinuous) {
        rememberInfiniteTransition(label = "zzzDotProgress").animateFloat(
            initialValue = 0f,
            targetValue = sweep,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PERIOD_MS, easing = LinearEasing),
            ),
            label = "zzzDotProgressPosition",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val dotStep = 10.dp // 6dp dot + 4dp gap
    Spacer(
        modifier
            .width(dotStep * DOT_COUNT - 4.dp)
            .height(6.dp)
            .drawBehind {
                val start = bandStart.value
                val dotW = 6.dp.toPx()
                val step = dotStep.toPx()
                repeat(DOT_COUNT) { index ->
                    val lit = index.toFloat() >= start && index < start + BAND_WIDTH
                    drawRoundRect(
                        color = if (lit) glow else unlit,
                        topLeft = Offset(index * step, 0f),
                        size = Size(dotW, size.height),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            }
    )
}
