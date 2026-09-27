package com.newoether.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.theme.LocalZzzTokens
import androidx.compose.material3.Text

/**
 * Static ZZZ page background: theme background color, a 45° hatch (1 dp lines,
 * 6 dp spacing, watermark color) drawn once via [drawWithCache], and huge
 * rotated `FAIRY` watermark text in the title font, tiled across three rows.
 */
@Composable
fun ZzzBackground(modifier: Modifier = Modifier) {
    val tokens = LocalZzzTokens.current
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawWithCache {
                val stroke = 1.dp.toPx()
                val spacing = 6.dp.toPx()
                val color = tokens.watermark
                onDrawBehind {
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(
                            color = color,
                            start = Offset(x, 0f),
                            end = Offset(x + size.height, size.height),
                            strokeWidth = stroke,
                        )
                        x += spacing
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(3) {
                Text(
                    text = "FAIRY  FAIRY  FAIRY",
                    color = tokens.watermark,
                    fontFamily = tokens.titleFontFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 120.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .rotate(-20f),
                )
            }
        }
    }
}
