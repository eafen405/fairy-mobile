package com.newoether.agora.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.newoether.agora.ui.theme.LocalFairyTokens
import kotlin.math.roundToInt

/**
 * Static Fairy page background, in three layers: the deep-navy vertical
 * gradient (bgTop -> bgBottom), faint CRT scanlines (1 px on a 3 dp period),
 * and a top-centered radial ambient light (radius ~0.9 x width).
 *
 * Everything is recorded once inside [drawWithCache] on its own
 * [graphicsLayer]: scanlines are a tiled [ImageShader] (one drawRect), so the
 * layer re-rasterizes only when the size changes and scrolling content above
 * never redraws it.
 */
@Composable
fun FairyBackground(modifier: Modifier = Modifier) {
    val tokens = LocalFairyTokens.current
    val density = LocalDensity.current
    // ImageBitmap relies on Bitmap.createBitmap, null on software-only test
    // runtimes — fall back to the line loop there.
    val scanlines = remember(density.density, tokens.scanline) {
        runCatching { scanlineBrush(density.density, 3f, tokens.scanline) }.getOrNull()
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawWithCache {
                val gradient = Brush.verticalGradient(
                    0f to tokens.bgTop,
                    1f to tokens.bgBottom,
                )
                val glowCenter = Offset(size.width / 2f, 0f)
                val glowRadius = size.width * 0.9f
                val glow = Brush.radialGradient(
                    0f to tokens.ambientGlow,
                    1f to tokens.ambientGlow.copy(alpha = 0f),
                    center = glowCenter,
                    radius = glowRadius,
                )
                onDrawBehind {
                    drawRect(gradient)
                    drawScanlines(scanlines, tokens.scanline, 3f)
                    drawCircle(glow, radius = glowRadius, center = glowCenter)
                }
            },
    )
}

/** Draws 1 px scanlines every [periodDp], via [brush] when available. */
internal fun DrawScope.drawScanlines(
    brush: ShaderBrush?,
    color: Color,
    periodDp: Float,
    phasePx: Float = 0f,
) {
    if (brush != null && phasePx == 0f) {
        drawRect(brush = brush)
        return
    }
    val step = periodDp * density
    var y = (phasePx % step + step) % step
    while (y < size.height) {
        drawRect(color, topLeft = Offset(0f, y), size = Size(size.width, 1f))
        y += step
    }
}

/** A tile of one 1 px horizontal line per [periodDp]; repeats seamlessly. */
internal fun scanlineBrush(densityScale: Float, periodDp: Float, color: Color): ShaderBrush {
    val tile = (periodDp * densityScale).roundToInt().coerceAtLeast(2)
    val bitmap = ImageBitmap(1, tile)
    CanvasDrawScope().draw(
        density = Density(densityScale),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(1f, tile.toFloat()),
    ) {
        drawRect(color, topLeft = Offset.Zero, size = Size(1f, 1f))
    }
    return object : ShaderBrush() {
        override fun createShader(size: Size) =
            ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
    }
}
