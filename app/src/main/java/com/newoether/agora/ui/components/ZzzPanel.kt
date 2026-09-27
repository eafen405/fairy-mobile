package com.newoether.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.LocalZzzTokens

/**
 * ZZZ panel surface: [color] fill inside [shape], a 1 dp dot matrix on a 5 dp
 * grid clipped inside the shape, then the double outline (2 dp black outer,
 * 1 dp inner highlight). Children are not clipped by this modifier; callers
 * pad content so it stays clear of the outline.
 */
fun Modifier.zzzPanel(
    shape: Shape,
    color: Color? = null,
): Modifier = composed {
    val tokens = LocalZzzTokens.current
    val fill = color ?: tokens.panel
    this
        .background(fill, shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = when (outline) {
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Generic -> outline.path
            }
            val dotRadius = 0.5.dp.toPx()
            val dotStep = 5.dp.toPx()
            val dotColor = tokens.dotMatrix
            val outerColor = tokens.outlineOuterColor
            val outerWidth = tokens.outlineOuterWidth.toPx()
            val innerColor = tokens.outlineInnerColor
            val innerWidth = tokens.outlineInnerWidth.toPx()
            onDrawWithContent {
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
                drawContent()
                drawPath(clip, color = outerColor, style = Stroke(width = outerWidth))
                drawPath(clip, color = innerColor, style = Stroke(width = innerWidth))
            }
        }
}
