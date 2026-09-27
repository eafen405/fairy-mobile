package com.newoether.agora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlin.math.sin

private const val BREATH_PERIOD_MS = 4200f
private const val SETTLE_MS = 300

private val EmblemRingOuter = Color(0xFF3A6AF0)
private val EmblemRingInner = Color(0xFF1F55E0)
private val EmblemRingOuterBreath = Color(0xFF7FB0FF)

/** Breathing cycle value: 0 at 0 ms, 0.5 at 1050 ms, 1 at 2100 ms, 0 at 4200 ms. */
fun emblemBreath(elapsedMs: Long): Float {
    val phase = (elapsedMs % BREATH_PERIOD_MS.toLong()) / BREATH_PERIOD_MS
    return ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
}

/**
 * The flat Fairy emblem (see `res/drawable/fairy_emblem.xml` for the same
 * geometry): white ring, gradient blue ring, black disc with four compass
 * bumps, white eye ring, black pupil, white tail-dot.
 *
 * While [animating] and the motion policy allows continuous motion, the emblem
 * breathes on a 4200 ms cycle: the blue ring gradient lerps toward fairyGlow,
 * a radial glow halo behind the emblem fades to 0.55 alpha (radius 0.65 x
 * size, drawn beyond the bounds), and the eye scales 1.00 -> 1.04. Turning
 * [animating] off settles the breath back to 0 over 300 ms. Reduced Motion or
 * a non-animating state renders the emblem static — with no running
 * coroutines and no per-draw path/brush allocation.
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

    FairyEmblemFrame(modifier = modifier, size = size, breath = { breath.value })
}

/** Test seam: draws one frame of the emblem at an explicit [breath] value. */
@Composable
internal fun FairyEmblem(
    animating: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    breathOverride: Float,
) {
    FairyEmblemFrame(modifier = modifier, size = size, breath = { breathOverride })
}

@Composable
private fun FairyEmblemFrame(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    breath: () -> Float,
) {
    val tokens = LocalZzzTokens.current
    val fairyGlow = tokens.fairyGlow
    Spacer(
        modifier.size(size).drawWithCache {
            val unit = this.size.minDimension / 48f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            // Disc+bumps path built once per size, not per frame.
            val disc = discPath(unit, center)
            val haloRadius = this.size.minDimension * 0.65f
            onDrawBehind {
                drawFairyEmblem(breath(), fairyGlow, unit, center, haloRadius, disc)
            }
        }
    )
}

private fun DrawScope.drawFairyEmblem(
    breath: Float,
    fairyGlow: Color,
    unit: Float,
    center: Offset,
    haloRadius: Float,
    disc: Path,
) {
    if (breath > 0f) {
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

    // White outer ring: r 24 -> 22 (centerline 23, width 2).
    drawCircle(
        color = Color.White,
        radius = 23f * unit,
        center = center,
        style = Stroke(width = 2f * unit),
    )
    // Blue ring r 22 -> 16 (centerline 19, width 6): radial gradient, lighter
    // outside; breathing lerps both stops toward fairyGlow tones.
    drawCircle(
        brush = Brush.radialGradient(
            0f to lerp(EmblemRingInner, fairyGlow, breath),
            1f to lerp(EmblemRingOuter, EmblemRingOuterBreath, breath),
            center = center,
            radius = 19f * unit,
        ),
        radius = 19f * unit,
        center = center,
        style = Stroke(width = 6f * unit),
    )
    // Black disc with four pointed compass bumps.
    drawPath(disc, Color.Black)
    // Eye: white ring outer r 9.6 (centerline 8.0, width 3.2), black pupil
    // r 6.4, white tail-dot r 2.5 centered at r 6.6, 45deg lower-right.
    val eyeScale = 1f + 0.04f * breath
    drawCircle(
        color = Color.White,
        radius = 8f * unit * eyeScale,
        center = center,
        style = Stroke(width = 3.2f * unit * eyeScale, cap = StrokeCap.Round),
    )
    drawCircle(color = Color.Black, radius = 6.4f * unit * eyeScale, center = center)
    val diagonal = 0.70710678f
    drawCircle(
        color = Color.White,
        radius = 2.5f * unit * eyeScale,
        center = Offset(
            center.x + 6.6f * unit * eyeScale * diagonal,
            center.y + 6.6f * unit * eyeScale * diagonal,
        ),
    )
}

/** Disc-with-compass-bumps path matching `fairy_emblem.xml`'s `disc` path. */
private fun discPath(unit: Float, center: Offset): Path {
    val r = 15f * unit
    val tipR = 19.5f * unit
    val betaDeg = Math.toDegrees(kotlin.math.atan2(4f * unit, r).toDouble()).toFloat()
    fun pt(radius: Float, angleDeg: Float) = Offset(
        center.x + radius * cos(Math.toRadians(angleDeg.toDouble())).toFloat(),
        center.y + radius * sin(Math.toRadians(angleDeg.toDouble())).toFloat(),
    )
    val path = Path()
    val dirs = floatArrayOf(-90f, 0f, 90f, 180f)
    dirs.forEachIndexed { i, deg ->
        val b = pt(r, deg - betaDeg)
        val e = pt(r, deg + betaDeg)
        val t = pt(tipR, deg)
        val c1 = Offset(
            (b.x + t.x) / 2f + (center.x - (b.x + t.x) / 2f) * 0.25f,
            (b.y + t.y) / 2f + (center.y - (b.y + t.y) / 2f) * 0.25f,
        )
        val c2 = Offset(
            (t.x + e.x) / 2f + (center.x - (t.x + e.x) / 2f) * 0.25f,
            (t.y + e.y) / 2f + (center.y - (t.y + e.y) / 2f) * 0.25f,
        )
        if (i == 0) {
            path.moveTo(b.x, b.y)
        } else {
            path.arcTo(
                rect = Rect(center, r),
                startAngleDegrees = dirs[i - 1] + betaDeg,
                sweepAngleDegrees = deg - dirs[i - 1] - 2f * betaDeg,
                forceMoveTo = false,
            )
        }
        path.quadraticTo(c1.x, c1.y, t.x, t.y)
        path.quadraticTo(c2.x, c2.y, e.x, e.y)
    }
    path.arcTo(
        rect = Rect(center, r),
        startAngleDegrees = 180f + betaDeg,
        sweepAngleDegrees = 270f - 2f * betaDeg,
        forceMoveTo = false,
    )
    path.close()
    return path
}
