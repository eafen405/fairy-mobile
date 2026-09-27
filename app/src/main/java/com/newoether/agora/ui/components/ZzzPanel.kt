package com.newoether.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.LocalZzzTokens

/**
 * ZZZ panel surface: [color] fill inside [shape], a 1 dp dot matrix on a 5 dp
 * grid clipped inside the shape, then the double outline (2 dp black outer,
 * 1 dp inner highlight). Children are not clipped by this modifier; callers
 * pad content so it stays clear of the outline.
 *
 * The dot grid is painted with a tiled [ImageShader] — one drawPath instead of
 * thousands of per-dot drawCircle calls on every redraw.
 */
fun Modifier.zzzPanel(
    shape: Shape,
    color: Color? = null,
): Modifier = composed {
    val tokens = LocalZzzTokens.current
    val fill = color ?: tokens.panel
    val density = LocalDensity.current
    // ImageBitmap construction relies on Bitmap.createBitmap, which returns
    // null on software-only test runtimes — fall back to per-dot drawing.
    val dotTile = remember(density.density, tokens.dotMatrix) {
        runCatching { dotTileBitmap(density.density, tokens.dotMatrix) }.getOrNull()
    }
    this
        .background(fill, shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = when (outline) {
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Generic -> outline.path
            }
            val outerColor = tokens.outlineOuterColor
            val outerWidth = tokens.outlineOuterWidth.toPx()
            val innerColor = tokens.outlineInnerColor
            val innerWidth = tokens.outlineInnerWidth.toPx()
            val dotRadius = 0.5.dp.toPx()
            val dotStep = 5.dp.toPx()
            val dotColor = tokens.dotMatrix
            onDrawWithContent {
                if (dotTile != null) {
                    // Shader fill: one drawPath, dots bounded by the shape.
                    drawPath(clip, brush = dotTile)
                } else {
                    clipPath(clip) {
                        var y = dotStep / 2f
                        while (y < size.height) {
                            var x = dotStep / 2f
                            while (x < size.width) {
                                drawCircle(dotColor, radius = dotRadius, center = Offset(x, y))
                                x += dotStep
                            }
                            y += dotStep
                        }
                    }
                }
                drawContent()
                drawPath(clip, color = outerColor, style = Stroke(width = outerWidth))
                drawPath(clip, color = innerColor, style = Stroke(width = innerWidth))
            }
        }
}

private fun dotTileBitmap(densityScale: Float, dotColor: Color): ShaderBrush {
    val tilePx = (5 * densityScale).toInt().coerceAtLeast(4)
    val bitmap = ImageBitmap(tilePx, tilePx)
    CanvasDrawScope().draw(
        density = Density(densityScale),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(tilePx.toFloat(), tilePx.toFloat()),
    ) {
        drawCircle(
            dotColor,
            radius = densityScale / 2f,
            center = Offset(tilePx / 2f, tilePx / 2f),
        )
    }
    return object : ShaderBrush() {
        override fun createShader(size: Size) =
            ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
    }
}
