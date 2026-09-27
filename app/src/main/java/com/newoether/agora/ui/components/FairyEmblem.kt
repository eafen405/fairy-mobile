package com.newoether.agora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.LocalZzzTokens
import kotlin.math.PI
import kotlin.math.cos

private const val BREATH_PERIOD_MS = 4200f
private const val SETTLE_MS = 300

/** Breathing cycle value: 0 at 0 ms, 0.5 at 1050 ms, 1 at 2100 ms, 0 at 4200 ms. */
fun emblemBreath(elapsedMs: Long): Float {
    val phase = (elapsedMs % BREATH_PERIOD_MS.toLong()) / BREATH_PERIOD_MS
    return ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
}

/**
 * The flat Fairy emblem (see `res/drawable/fairy_emblem.xml` for the same
 * geometry): white ring, fairyBlue ring, black disc, white Q-shaped eye.
 *
 * While [animating] and the motion policy allows continuous motion, the emblem
 * breathes on a 4200 ms cycle: the blue ring lerps fairyBlue -> fairyGlow, a
 * radial glow halo behind the emblem fades to 0.55 alpha (radius 0.65 x size,
 * drawn beyond the bounds), and the eye scales 1.00 -> 1.04. Turning
 * [animating] off settles the breath back to 0 over 300 ms. Reduced Motion or
 * a non-animating state renders the emblem static.
 */
@Composable
fun FairyEmblem(
    animating: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val running = animating && motionPolicy.allowContinuousMotion

    val breath = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (running) {
            val start = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                breath.snapTo(emblemBreath(now - start))
            }
        } else {
            breath.animateTo(0f, tween(SETTLE_MS))
        }
    }

    FairyEmblemFrame(modifier = modifier, size = size, breath = breath.value)
}

/** Test seam: draws one frame of the emblem at an explicit [breath] value. */
@Composable
internal fun FairyEmblem(
    animating: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    breathOverride: Float,
) {
    FairyEmblemFrame(modifier = modifier, size = size, breath = breathOverride)
}

@Composable
private fun FairyEmblemFrame(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    breath: Float,
) {
    val tokens = LocalZzzTokens.current
    Canvas(modifier.size(size)) {
        drawFairyEmblem(
            breath = breath,
            fairyBlue = tokens.fairyBlue,
            fairyGlow = tokens.fairyGlow,
        )
    }
}

private fun DrawScope.drawFairyEmblem(
    breath: Float,
    fairyBlue: Color,
    fairyGlow: Color,
) {
    val unit = this.size.minDimension / 48f
    val center = Offset(this.size.width / 2f, this.size.height / 2f)

    if (breath > 0f) {
        val haloRadius = this.size.minDimension * 0.65f
        drawCircle(
            brush = Brush.radialGradient(
                0f to fairyGlow.copy(alpha = 0.55f * breath),
                1f to fairyGlow.copy(alpha = 0f),
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = center,
        )
    }

    // White outer ring: diameter 48, stroke 3.
    drawCircle(
        color = Color.White,
        radius = 22.5f * unit,
        center = center,
        style = Stroke(width = 3f * unit),
    )
    // fairyBlue ring breathing toward fairyGlow: stroke 5.
    drawCircle(
        color = lerp(fairyBlue, fairyGlow, breath),
        radius = 18.5f * unit,
        center = center,
        style = Stroke(width = 5f * unit),
    )
    // Black disc.
    drawCircle(color = Color.Black, radius = 16f * unit, center = center)
    // Q-shaped eye: ring diameter 20 (centerline r = 8), stroke 4, tail to
    // bottom-right, round caps; breathes 1.00 -> 1.04.
    val eyeScale = 1f + 0.04f * breath
    val eyeRadius = 8f * unit * eyeScale
    val eyeStroke = 4f * unit * eyeScale
    drawCircle(
        color = Color.White,
        radius = eyeRadius,
        center = center,
        style = Stroke(width = eyeStroke, cap = StrokeCap.Round),
    )
    val diagonal = 0.70710678f
    drawLine(
        color = Color.White,
        start = Offset(
            center.x + eyeRadius * diagonal,
            center.y + eyeRadius * diagonal,
        ),
        end = Offset(
            center.x + 13.5f * unit * eyeScale * diagonal,
            center.y + 13.5f * unit * eyeScale * diagonal,
        ),
        strokeWidth = eyeStroke,
        cap = StrokeCap.Round,
    )
}
