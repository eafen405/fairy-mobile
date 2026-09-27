package com.newoether.agora.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.LocalZzzTokens
import kotlin.math.tan

/**
 * Right-leaning parallelogram: horizontal shear only — the top edge shifts
 * right by height * tan(skewDegrees), the bottom edge stays anchored at the
 * left bound. Corners are rounded to [cornerRadius].
 */
internal class ZzzParallelogramShape(
    private val skewDegrees: Float = 12f,
    private val cornerRadius: Dp = 6.dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val k = tan(Math.toRadians(skewDegrees.toDouble())).toFloat()
        val skewSpan = k * size.height
        val bodyWidth = (size.width - skewSpan).coerceAtLeast(0f)
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, bodyWidth, size.height),
                    cornerRadius = CornerRadius(with(density) { cornerRadius.toPx() }),
                ),
            )
            transform(
                Matrix().apply {
                    // x' = x - k*y shifts the top edge right relative to the
                    // bottom; the translate re-anchors the result at x=0..w.
                    values[Matrix.SkewX] = -k
                    values[Matrix.TranslateX] = skewSpan
                },
            )
        }
        return Outline.Generic(path)
    }
}

/**
 * The red parallelogram back button: #E53A1E fill, ~12 deg right-leaning skew,
 * 6 dp corners, 2 dp black outline, upright black arrow.
 */
@Composable
fun ZzzBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalZzzTokens.current
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 44.dp, height = 40.dp),
        shape = ZzzParallelogramShape(),
        color = tokens.danger,
        contentColor = Color.Black,
        border = BorderStroke(2.dp, Color.Black),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(20.dp),
                tint = Color.Black,
            )
        }
    }
}
