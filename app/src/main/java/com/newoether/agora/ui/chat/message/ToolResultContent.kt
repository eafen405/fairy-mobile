package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.theme.ChatType

/**
 * Segment-detail body for one bounded Remote activity. The wire supplies a
 * server-curated label, a lifecycle state, and an optional outcome note, so
 * the detail surface renders that same state — never arguments, results, or
 * host internals, which do not cross the client boundary.
 */
@Composable
internal fun ToolDetailContent(segment: MessageSegment) {
    val presentation = ToolPresentationResolver.resolve(segment)
    Column(modifier = Modifier.fillMaxWidth()) {
        ToolSectionLabel(stringResource(R.string.result_label))
        Spacer(Modifier.height(6.dp))
        when (presentation.state) {
            ToolPresentationState.CALLING,
            ToolPresentationState.RUNNING,
            ToolPresentationState.BACKGROUND_RUNNING -> ToolActiveContent(
                text = toolSummary(presentation),
            )
            ToolPresentationState.FAILED -> ToolErrorContent(
                presentation.errorMessage ?: stringResource(R.string.tool_call_failed),
            )
            ToolPresentationState.STOPPED -> GenerationTerminalText(
                text = presentation.errorMessage?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.tool_execution_stopped),
                fillWidth = true,
            )
            ToolPresentationState.EMPTY,
            ToolPresentationState.COMPLETED -> ToolMutedContent(
                stringResource(R.string.tool_done),
            )
        }
    }
}

internal fun toolDetailHorizontalPadding(segment: MessageSegment): Dp = 24.dp

@Composable
private fun ToolSectionLabel(text: String) {
    Text(
        text = text,
        style = ChatType.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ToolActiveContent(text: String) {
    Text(
        text = text,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToolErrorContent(message: String) {
    GenerationTerminalText(
        text = message,
        selectable = true,
        fillWidth = true,
    )
}

@Composable
private fun ToolMutedContent(message: String) {
    Text(
        text = message,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
