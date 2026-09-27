package com.newoether.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.theme.LocalZzzTokens
import kotlin.math.sqrt

/**
 * Static ZZZ page background: theme background color, two soft deep-blue
 * corner glows, a pair of -20 deg diagonal bands in the top-right, a 45 deg
 * hatch (1 dp lines, 6 dp spacing), and one large centered "HDD" watermark
 * in the title font.
 *
 * Everything is recorded once inside [drawWithCache] on a [graphicsLayer]:
 * the hatch is a tiled [ImageShader] (one drawRect instead of ~200 lines) and
 * the watermark is a single cached [TextMeasurer] layout, so the layer
 * re-rasterizes only when the size changes.
 */
@Composable
fun ZzzBackground(modifier: Modifier = Modifier) {
    val tokens = LocalZzzTokens.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // ImageBitmap relies on Bitmap.createBitmap, null on software-only test
    // runtimes — fall back to the line loop there.
    val hatchBrush = remember(density.density, tokens.watermark) {
        runCatching { hatchBrush(density.density, 6f * density.density, tokens.watermark) }
            .getOrNull()
    }
    val textStyle = TextStyle(
        color = tokens.watermarkText,
        fontFamily = tokens.titleFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 150.sp,
    )
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Isolate the fully static background in its own layer so parent
            // invalidations re-composite a cached raster instead of re-running
            // the whole draw block.
            .graphicsLayer()
            .drawWithCache {
                val layout = textMeasurer.measure("HDD", textStyle, softWrap = false)
                val glowColor = tokens.deepDecor
                val bandColor = tokens.deepDecorBand.copy(alpha = 0.35f)
                val bandWidth = 3.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                onDrawBehind {
                    // Deep-blue radial glows: top-right corner (alpha 0.45,
                    // center just outside the corner) and bottom-start corner
                    // (alpha 0.35).
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to glowColor.copy(alpha = 0.45f),
                            1f to glowColor.copy(alpha = 0f),
                            center = Offset(size.width * 1.02f, -size.height * 0.02f),
                            radius = size.width * 0.7f,
                        ),
                        radius = size.width * 0.7f,
                        center = Offset(size.width * 1.02f, -size.height * 0.02f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to glowColor.copy(alpha = 0.35f),
                            1f to glowColor.copy(alpha = 0f),
                            center = Offset(-size.width * 0.02f, size.height * 1.02f),
                            radius = size.width * 0.6f,
                        ),
                        radius = size.width * 0.6f,
                        center = Offset(-size.width * 0.02f, size.height * 1.02f),
                    )
                    // One thin -20 deg accent band through the lower third,
                    // running off both screen edges.
                    val angle = Math.toRadians(-20.0).toFloat()
                    val dir = Offset(kotlin.math.cos(angle), kotlin.math.sin(angle))
                    val nrm = Offset(-dir.y, dir.x)
                    val run = (size.width + size.height) * 1.5f
                    val anchor = Offset(size.width * 0.5f, size.height * 0.67f)
                    run {
                        val u = Offset(dir.x * run / 2f, dir.y * run / 2f)
                        val v = Offset(nrm.x * bandWidth / 2f, nrm.y * bandWidth / 2f)
                        val p = Path()
                        p.moveTo(anchor.x - u.x - v.x, anchor.y - u.y - v.y)
                        p.lineTo(anchor.x + u.x - v.x, anchor.y + u.y - v.y)
                        p.lineTo(anchor.x + u.x + v.x, anchor.y + u.y + v.y)
                        p.lineTo(anchor.x - u.x + v.x, anchor.y - u.y + v.y)
                        p.close()
                        drawPath(p, bandColor)
                    }
                    // 45 deg hatch: one tiled shader rect (or the line loop
                    // where bitmaps are unavailable).
                    if (hatchBrush != null) {
                        drawRect(brush = hatchBrush)
                    } else {
                        val stroke = 1.dp.toPx()
                        val spacing = 6.dp.toPx()
                        var x = -size.height
                        while (x < size.width) {
                            drawLine(
                                color = tokens.watermark,
                                start = Offset(x, 0f),
                                end = Offset(x + size.height, size.height),
                                strokeWidth = stroke,
                            )
                            x += spacing
                        }
                    }
                    // Centered -20 deg "HDD" watermark.
                    withTransform({
                        rotate(degrees = -20f, pivot = center)
                    }) {
                        drawText(
                            layout,
                            topLeft = Offset(
                                center.x - layout.size.width / 2f,
                                center.y - layout.size.height / 2f,
                            ),
                        )
                    }
                }
            }
    )
}

/**
 * A brush that tiles a single 45 deg line seamlessly. The tile is a square of
 * side `spacing * sqrt(2)` so a diagonal drawn corner-to-corner repeats at a
 * perpendicular line spacing of `spacing` px.
 */
private fun hatchBrush(
    density: Float,
    spacingPx: Float,
    color: androidx.compose.ui.graphics.Color,
): ShaderBrush {
    val tileSize = (spacingPx * sqrt(2f)).toInt().coerceAtLeast(4)
    val stroke = density.coerceAtLeast(1f) // 1dp
    val bitmap = ImageBitmap(tileSize, tileSize)
    CanvasDrawScope().draw(
        density = androidx.compose.ui.unit.Density(density),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = androidx.compose.ui.geometry.Size(tileSize.toFloat(), tileSize.toFloat()),
    ) {
        drawLine(color, Offset(0f, 0f), Offset(tileSize.toFloat(), tileSize.toFloat()), stroke)
    }
    return object : ShaderBrush() {
        override fun createShader(size: androidx.compose.ui.geometry.Size) =
            ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
    }
}
