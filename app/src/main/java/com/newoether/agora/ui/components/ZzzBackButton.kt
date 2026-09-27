package com.newoether.agora.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.LocalZzzTokens
import com.newoether.agora.ui.motion.zzzPress
import kotlin.math.tan

/**
 * The red parallelogram back button: #E53A1E fill skewed ~12 deg (leaning
 * right), 10 dp corners, 2 dp black outline, black arrow.
 */
@Composable
fun ZzzBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalZzzTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val corner = 6.dp
    val outlineWidth = 2.dp
    // Skew matrix applied to a plain rounded rect produces a rounded
    // parallelogram; the content width is reduced by the skew span so the
    // skewed shape still fits inside the bounds.
    val skewTan = tan(Math.toRadians(12.0)).toFloat()
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 40.dp)
            .zzzPress(interactionSource)
            .drawBehind {
                val skewSpan = skewTan * size.height
                val bodyWidth = size.width - skewSpan
                val skew = Matrix().apply { values[1] = skewTan }
                val outline = outlineWidth.toPx()
                val radius = CornerRadius(corner.toPx())
                withTransform({
                    translate(left = skewSpan / 2f)
                    transform(skew)
                }) {
                    drawRoundRect(
                        color = tokens.danger,
                        topLeft = Offset.Zero,
                        size = Size(bodyWidth, size.height),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset.Zero,
                        size = Size(bodyWidth, size.height),
                        cornerRadius = radius,
                        style = Stroke(width = outline),
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.size(20.dp),
            tint = Color.Black,
        )
    }
}
