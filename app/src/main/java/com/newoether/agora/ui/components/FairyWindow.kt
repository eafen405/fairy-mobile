package com.newoether.agora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.LocalFairyTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan
import kotlinx.coroutines.delay

private const val IDLE_BREATH_MS = 4200f
private const val WORKING_BREATH_MS = 1600f
private const val IDLE_SCAN_PERIOD_MS = 2400f
private const val SPEECH_PULSE_MS = 180
private const val SPEECH_PULSE_SCALE = 1.03f
private const val TEXT_GROWTH_WINDOW_MS = 400L

/**
 * Test seam: when non-null, every Fairy breathing surface (window glow, accent
 * bar) draws this fixed breath value instead of running its frame loop.
 */
internal val LocalFairyBreathOverride = staticCompositionLocalOf<Float?> { null }

/** Breath value (0..1) for a phase in cycles; 0 at phase 0, 1 at half a cycle. */
internal fun breathAt(phase: Float): Float = ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()

/** Glow alpha range (min to peak) per presence; null means no glow. */
internal fun fairyGlowRange(presence: FairyPresence): ClosedFloatingPointRange<Float>? =
    when (presence) {
        FairyPresence.IDLE -> 0.30f..0.55f
        FairyPresence.CONNECTING -> 0.15f..0.275f
        FairyPresence.THINKING, FairyPresence.SPEAKING -> 0.30f..0.85f
        FairyPresence.OFFLINE -> null
    }

/**
 * Tracks growth of the tail message's visible text. [growing] is true while
 * the text grew within the last 400 ms; [pulse] increments on every growth
 * step (the SPEAKING eye pulse listens to it).
 */
@Stable
class FairyTextGrowth internal constructor() {
    var growing by mutableStateOf(false)
        internal set
    var pulse by mutableIntStateOf(0)
        internal set
}

@Composable
fun rememberFairyTextGrowth(key: Any?, visibleLength: Int): FairyTextGrowth {
    val growth = remember { FairyTextGrowth() }
    val previous = remember(key) { intArrayOf(visibleLength) }
    LaunchedEffect(key, visibleLength) {
        val grew = visibleLength > previous[0]
        previous[0] = visibleLength
        if (!grew) {
            growth.growing = false
            return@LaunchedEffect
        }
        growth.pulse++
        growth.growing = true
        // A newer growth step restarts this effect, extending the window.
        delay(TEXT_GROWTH_WINDOW_MS)
        growth.growing = false
    }
    return growth
}

/**
 * Inputs for the Fairy window bar in [com.newoether.agora.ui.chat.ChatTopBar]:
 * the presence, whether the screen is expanded (empty session), and the
 * SPEAKING eye-pulse counter from [FairyTextGrowth.pulse].
 */
@androidx.compose.runtime.Immutable
data class FairyWindowState(
    val presence: FairyPresence,
    val expanded: Boolean = false,
    val speechPulse: Int = 0,
)

/** Shared per-screen animation clock; values are read only while drawing. */
@Stable
private class FairyScreenClock {
    var breathPhase by mutableFloatStateOf(0f)
    var scanPhase by mutableFloatStateOf(0f)
}

/**
 * Fairy's CRT screen: screenCenter -> screenEdge radial base, fairyGlow
 * radial bloom behind the eye (radius 0.8 x eye diameter), the eye itself,
 * drifting 1 px scanlines (2 dp period, alpha 0.06) and a 1 dp fairyBlue
 * inner outline, clipped to the 14 dp screen shape.
 *
 * The breath and scan phases advance in one frame loop that only writes
 * draw-phase state, so frames redraw without recomposing. The loop runs only
 * while continuous motion is allowed, the presence animates, and the host
 * lifecycle is at least STARTED; Reduced Motion draws the still frame
 * (breath 0.5, scanlines parked).
 */
@Composable
fun FairyScreen(
    presence: FairyPresence,
    eyeSize: Dp,
    modifier: Modifier = Modifier,
    speechPulse: Int = 0,
) {
    val tokens = LocalFairyTokens.current
    val allowContinuous = LocalAgoraMotionPolicy.current.allowContinuousMotion
    val breathOverride = LocalFairyBreathOverride.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val clock = remember { FairyScreenClock() }
    val running = allowContinuous && breathOverride == null &&
        presence != FairyPresence.OFFLINE && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val currentPresence by rememberUpdatedState(presence)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val dt = (now - last).coerceIn(0L, 100L).toFloat()
            last = now
            val working = currentPresence.isWorking
            val breathPeriod = if (working) WORKING_BREATH_MS else IDLE_BREATH_MS
            val scanPeriod = if (working) IDLE_SCAN_PERIOD_MS / 2f else IDLE_SCAN_PERIOD_MS
            clock.breathPhase = (clock.breathPhase + dt / breathPeriod) % 1f
            clock.scanPhase = (clock.scanPhase + dt / scanPeriod) % 1f
        }
    }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(speechPulse) {
        if (speechPulse == 0 || presence != FairyPresence.SPEAKING || !allowContinuous) return@LaunchedEffect
        pulse.snapTo(SPEECH_PULSE_SCALE)
        pulse.animateTo(1f, tween(SPEECH_PULSE_MS, easing = FastOutSlowInEasing))
    }
    val glowRange = fairyGlowRange(presence)
    val eyeAlpha = if (presence == FairyPresence.OFFLINE) 0.4f else 1f
    val scanlineColor = Color.White.copy(alpha = 0.06f)
    val scanlinesMove = running
    Spacer(
        modifier.drawWithCache {
            val outline = tokens.screenShape.createOutline(size, layoutDirection, this)
            val clip = Path().apply {
                when (outline) {
                    is Outline.Rectangle -> addRect(outline.rect)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Generic -> addPath(outline.path)
                }
            }
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = Brush.radialGradient(
                0f to tokens.screenCenter,
                1f to tokens.screenEdge,
                center = center,
                radius = maxOf(size.width, size.height) * 0.75f,
            )
            val eyePx = eyeSize.toPx()
            val unit = eyePx / 48f
            val disc = eyeDiscPath(unit, Offset.Zero)
            val glowRadius = eyePx * 0.8f
            val scanStep = 2.dp.toPx()
            val border = 1.dp.toPx()
            onDrawBehind {
                clipPath(clip) {
                    drawRect(base)
                    val breath = breathOverride ?: if (running) breathAt(clock.breathPhase) else 0.5f
                    if (glowRange != null) {
                        val a = glowRange.start + (glowRange.endInclusive - glowRange.start) * breath
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to tokens.fairyGlow.copy(alpha = a),
                                0.55f to tokens.fairyGlow.copy(alpha = a * 0.35f),
                                1f to tokens.fairyGlow.copy(alpha = 0f),
                                center = center,
                                radius = glowRadius,
                            ),
                            radius = glowRadius,
                            center = center,
                        )
                    }
                    val scale = pulse.value
                    withTransform({
                        translate(center.x, center.y)
                        scale(scale, scale, Offset.Zero)
                    }) {
                        drawFairyEye(Offset.Zero, unit, disc, eyeAlpha)
                    }
                    val phasePx = if (scanlinesMove) clock.scanPhase * scanStep else 0f
                    var y = phasePx - scanStep
                    while (y < size.height) {
                        if (y >= 0f) drawRect(scanlineColor, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 1f))
                        y += scanStep
                    }
                    // Clipped double-width stroke: only the inner 1 dp shows.
                    drawPath(
                        clip,
                        color = tokens.fairyBlue.copy(alpha = 0.4f),
                        style = Stroke(width = border * 2f),
                    )
                }
            }
        },
    )
}

/**
 * Slanted small-label look (~ -8 deg): a horizontal shear that leans the
 * content to the right, pivoting on the vertical center.
 */
fun Modifier.fairySlant(degrees: Float = 8f): Modifier = drawWithContent {
    val k = tan(Math.toRadians(degrees.toDouble())).toFloat()
    val matrix = Matrix().apply {
        values[Matrix.SkewX] = -k
        values[Matrix.TranslateX] = k * size.height / 2f
    }
    withTransform({ transform(matrix) }) {
        this@drawWithContent.drawContent()
    }
}
