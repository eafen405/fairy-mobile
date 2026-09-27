package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.LocalZzzTokens
import com.newoether.agora.ui.theme.fairyBubbleScheme

/**
 * Fairy's white chat bubble: #F4F4F4 fill, 20 dp corners with a 6 dp corner on
 * the tail-side top, and an 8 dp triangular tail drawn past the edge toward the
 * avatar. Content runs under a local [fairyBubbleScheme] so Markdown, code
 * blocks, quotes, links and file cards pick up dark-on-light colors without
 * touching individual renderers; body text is Bold.
 */
@Composable
internal fun FairyBubble(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalZzzTokens.current
    val fill = tokens.fairyBubble
    val shape = RoundedCornerShape(
        topStart = tokens.bubbleTailCorner,
        topEnd = 20.dp,
        bottomEnd = 20.dp,
        bottomStart = 20.dp,
    )
    val baseTypography = MaterialTheme.typography
    val bubbleTypography = remember(baseTypography) {
        fun TextStyle.bold() = copy(fontWeight = FontWeight.Bold)
        baseTypography.copy(
            bodyLarge = baseTypography.bodyLarge.bold(),
            bodyMedium = baseTypography.bodyMedium.bold(),
            bodySmall = baseTypography.bodySmall.bold(),
        )
    }
    Box(
        modifier = modifier
            .drawBubbleTail(fill, tailAtTopEnd = false)
            .background(fill, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        MaterialTheme(
            colorScheme = fairyBubbleScheme(),
            typography = bubbleTypography,
        ) {
            content()
        }
    }
}

/** Blue user bubble mirror: same corners with the tail on the top end. */
fun Modifier.drawBubbleTail(color: Color, tailAtTopEnd: Boolean): Modifier =
    drawBehind {
        val tail = 8.dp.toPx()
        val top = 10.dp.toPx()
        val path = Path()
        if (tailAtTopEnd) {
            path.moveTo(size.width, top)
            path.lineTo(size.width + tail, top + tail / 2f)
            path.lineTo(size.width, top + tail)
        } else {
            path.moveTo(0f, top)
            path.lineTo(-tail, top + tail / 2f)
            path.lineTo(0f, top + tail)
        }
        path.close()
        drawPath(path, color)
    }

/**
 * Small fairyBlue pill above a bubble marking a relayed message:
 * "转达自 {source}" in the title font.
 */
@Composable
internal fun FairyRelayBadge(source: String, modifier: Modifier = Modifier) {
    val tokens = LocalZzzTokens.current
    Row(
        modifier = modifier
            .background(tokens.fairyBlue, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.remote_relayed_from, source),
            color = Color.White,
            fontFamily = tokens.titleFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}
