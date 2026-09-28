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
import com.newoether.agora.ui.theme.LocalFairyTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val BREATH_PERIOD_MS = 4200f
private const val SETTLE_MS = 300

private val EmblemRingTop = Color(0xFF3D8AE6)
private val EmblemRingBottom = Color(0xFF1A35D6)
private val EmblemRingTopBreath = Color(0xFF7FB8FF)
private val EmblemPupilTop = Color(0xFF0A1830)

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
    val tokens = LocalFairyTokens.current
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

    // White outer ring: r 24 -> 20.6 (centerline 22.3, width 3.4).
    drawCircle(
        color = Color.White,
        radius = 22.3f * unit,
        center = center,
        style = Stroke(width = 3.4f * unit),
    )
    // Blue ring r 20.6 -> 15.6 (centerline 18.1, width 5): vertical linear
    // gradient #3D8AE6 top -> #1A35D6 bottom; breathing brightens both stops
    // (#7FB8FF / fairyGlow).
    val ringR = 18.1f * unit
    drawCircle(
        brush = Brush.linearGradient(
            0f to lerp(EmblemRingTop, EmblemRingTopBreath, breath),
            1f to lerp(EmblemRingBottom, fairyGlow, breath),
            start = Offset(center.x, center.y - ringR),
            end = Offset(center.x, center.y + ringR),
        ),
        radius = ringR,
        center = center,
        style = Stroke(width = 5f * unit),
    )
    // Black disc r 15.6 with four right-angled triangular compass tips
    // (straight 45deg sides, tip r 19.0, base ~6.4 wide, ~0.4 rounded apex).
    drawPath(disc, Color.Black)
    // Eye: white ring outer r 12.5 (centerline 10.3, width 4.4), pupil r 8.1
    // (vertical gradient #0A1830 -> #000), tail-dot r 3.4 at r 6.1, 45deg
    // lower-right; the group breathes 1.00 -> 1.04.
    val eyeScale = 1f + 0.04f * breath
    drawCircle(
        color = Color.White,
        radius = 10.3f * unit * eyeScale,
        center = center,
        style = Stroke(width = 4.4f * unit * eyeScale, cap = StrokeCap.Round),
    )
    val pupilR = 8.1f * unit * eyeScale
    drawCircle(
        brush = Brush.verticalGradient(
            0f to EmblemPupilTop,
            1f to Color.Black,
            startY = center.y - pupilR,
            endY = center.y + pupilR,
        ),
        radius = pupilR,
        center = center,
    )
    val diagonal = 0.70710678f
    drawCircle(
        color = Color.White,
        radius = 3.4f * unit * eyeScale,
        center = Offset(
            center.x + 6.1f * unit * eyeScale * diagonal,
            center.y + 6.1f * unit * eyeScale * diagonal,
        ),
    )
}

/** Disc-with-compass-bumps path matching `fairy_emblem.xml`'s `disc` path. */
internal fun discPath(
    unit: Float,
    center: Offset,
    discRadius: Float = 15.6f,
    tipRadius: Float = 19f,
    halfBase: Float = 3.2f,
): Path {
    val r = discRadius * unit
    val tipR = tipRadius * unit
    val betaDeg = Math.toDegrees(kotlin.math.atan2(halfBase * unit, r).toDouble()).toFloat()
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
        // Straight 45deg sides to the tip, with a ~0.4-unit rounded apex.
        val apex = 0.4f * unit
        val d1x = (t.x - b.x); val d1y = (t.y - b.y)
        val d1l = kotlin.math.hypot(d1x, d1y)
        val d2x = (e.x - t.x); val d2y = (e.y - t.y)
        val d2l = kotlin.math.hypot(d2x, d2y)
        val p1x = t.x - d1x / d1l * apex; val p1y = t.y - d1y / d1l * apex
        val p2x = t.x + d2x / d2l * apex; val p2y = t.y + d2y / d2l * apex
        path.lineTo(p1x, p1y)
        path.quadraticTo(t.x, t.y, p2x, p2y)
        path.lineTo(e.x, e.y)
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

private val EyeRingTop = Color(0xFF3D7BE8)
private val EyeRingBottom = Color(0xFF1A45C8)
private val EyeNotchDisc = Color(0xFF0B2A78)
private val EyeQRing = Color(0xFFEEF3FF)
private val EyeInnerRing = Color(0xFF8FB2F2)
private val EyePupilTop = Color(0xFF0D3490)
private val EyePupilBottom = Color(0xFF07205F)

/**
 * The CRT-screen eye: the emblem's concentric geometry in screen colors —
 * outer blue ring, dark disc with four notches, white Q ring, pale inner
 * ring, deep-blue pupil and the lower-right white dot. [disc] comes from
 * [discPath] with the eye radii (see [eyeDiscPath]) so frames allocate no
 * paths. Units are 1/48 of the eye diameter.
 */
internal fun DrawScope.drawFairyEye(
    center: Offset,
    unit: Float,
    disc: Path,
    alpha: Float = 1f,
) {
    val ringR = 21.75f * unit
    drawCircle(
        brush = Brush.verticalGradient(
            0f to EyeRingTop,
            1f to EyeRingBottom,
            startY = center.y - 24f * unit,
            endY = center.y + 24f * unit,
        ),
        radius = ringR,
        center = center,
        style = Stroke(width = 4.5f * unit),
        alpha = alpha,
    )
    drawPath(disc, EyeNotchDisc, alpha = alpha)
    drawCircle(
        color = EyeQRing,
        radius = 12.6f * unit,
        center = center,
        style = Stroke(width = 5.6f * unit),
        alpha = alpha,
    )
    drawCircle(
        color = EyeInnerRing,
        radius = 8.9f * unit,
        center = center,
        style = Stroke(width = 1.8f * unit),
        alpha = alpha,
    )
    drawCircle(
        brush = Brush.verticalGradient(
            0f to EyePupilTop,
            1f to EyePupilBottom,
            startY = center.y - 8f * unit,
            endY = center.y + 8f * unit,
        ),
        radius = 8f * unit,
        center = center,
        alpha = alpha,
    )
    val angle = Math.toRadians(50.0)
    drawCircle(
        color = EyeQRing,
        radius = 3.2f * unit,
        center = Offset(
            center.x + 6.6f * unit * cos(angle).toFloat(),
            center.y + 6.6f * unit * sin(angle).toFloat(),
        ),
        alpha = alpha,
    )
}

/** The notched dark disc behind the eye's Q ring. */
internal fun eyeDiscPath(unit: Float, center: Offset): Path =
    discPath(unit, center, discRadius = 19.5f, tipRadius = 22.5f, halfBase = 3.6f)
