package com.newoether.agora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
 */
@Composable
fun ZzzDotProgressBar(modifier: Modifier = Modifier) {
    val tokens = LocalZzzTokens.current
    val allowContinuous = LocalAgoraMotionPolicy.current.allowContinuousMotion
    // Sweep span includes the band width so the band fully exits on the right
    // before wrapping to the left.
    val sweep = (DOT_COUNT + BAND_WIDTH).toFloat()
    val bandStart = if (allowContinuous) {
        val transition = rememberInfiniteTransition(label = "zzzDotProgress")
        val position by transition.animateFloat(
            initialValue = 0f,
            targetValue = sweep,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PERIOD_MS, easing = LinearEasing),
            ),
            label = "zzzDotProgressPosition",
        )
        position
    } else {
        0f
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(DOT_COUNT) { index ->
            val lit = index.toFloat() >= bandStart && index < bandStart + BAND_WIDTH
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(6.dp)
                    .background(
                        color = if (lit) tokens.fairyGlow else tokens.pill,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}
