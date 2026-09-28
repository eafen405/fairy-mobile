package com.newoether.agora.ui.remote

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.components.fairyPanel
import com.newoether.agora.ui.motion.fairyPress
import com.newoether.agora.ui.theme.LocalFairyTokens

private val QuickPrompts = listOf(
    R.string.remote_quick_today,
    R.string.remote_quick_research,
    R.string.remote_quick_file,
)

/**
 * Empty-session greeting under the expanded Fairy window: one line of
 * greeting and three quick-prompt pills. A pill fills the composer and
 * focuses it; it never sends on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FairyEmptyGreeting(
    enabled: Boolean,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalFairyTokens.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.remote_greeting),
            color = tokens.textPrimary,
            fontSize = 18.sp,
            lineHeight = 26.sp,
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickPrompts.forEach { id ->
                val text = stringResource(id)
                val interaction = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = { onQuickPrompt(text) },
                    enabled = enabled,
                    shape = shape,
                    color = Color.Transparent,
                    contentColor = tokens.textPrimary,
                    interactionSource = interaction,
                    modifier = Modifier.fairyPress(interaction).fairyPanel(shape, tokens.pill),
                ) {
                    Text(
                        text = text,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
