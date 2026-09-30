package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment

/**
 * The only localization layer for bounded Remote activity cards. Compact,
 * timeline and detail surfaces all call these functions; the wire carries a
 * server-curated label plus lifecycle state and an optional outcome note.
 */
@Composable
internal fun toolDisplayName(segment: MessageSegment): String {
    // Bounded remote activities carry only the server's display title; a missing
    // one resolves to a generic label, never to a protocol or host detail.
    return segment.toolDisplayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.remote_activity)
}

@Composable
internal fun toolSummary(segment: MessageSegment): String {
    return toolSummary(ToolPresentationResolver.resolve(segment))
}

@Composable
internal fun toolSummary(presentation: ToolPresentation): String = when (presentation.state) {
    ToolPresentationState.FAILED -> presentation.errorMessage?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.tool_call_failed)
    ToolPresentationState.STOPPED -> presentation.errorMessage?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.tool_execution_stopped)
    ToolPresentationState.CALLING,
    ToolPresentationState.RUNNING,
    ToolPresentationState.BACKGROUND_RUNNING -> stringResource(R.string.tool_calling_ellipsis)
    ToolPresentationState.EMPTY,
    ToolPresentationState.COMPLETED -> stringResource(R.string.tool_done)
}
