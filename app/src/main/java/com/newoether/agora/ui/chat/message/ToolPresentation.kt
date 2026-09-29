package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates

internal enum class ToolPresentationState {
    CALLING,
    RUNNING,
    COMPLETED,
    EMPTY,
    FAILED,
    STOPPED,
    BACKGROUND_RUNNING,
}

/**
 * Presentation of one bounded Remote activity. The wire supplies only a
 * server-curated display label, a lifecycle state, and an optional outcome
 * note — no tool names, arguments, results, or host details ever cross the
 * client boundary, so the card resolves to lifecycle + note only.
 */
internal data class ToolPresentation(
    val state: ToolPresentationState,
    val errorMessage: String?,
) {
    /**
     * Drives the group loading indicator. BACKGROUND_RUNNING is deliberately excluded: a
     * detached activity must not occupy the loading bar once its round ends.
     */
    val isActive: Boolean
        get() = state == ToolPresentationState.CALLING ||
            state == ToolPresentationState.RUNNING
}

internal object ToolPresentationResolver {
    fun resolve(segment: MessageSegment): ToolPresentation {
        val state = stateFromWire(segment.toolState) ?: ToolPresentationState.CALLING
        // A bounded remote activity carries no result payload; the server's short note
        // is the only safe failure/stop detail it can ever surface.
        val note = segment.toolNote?.takeIf { it.isNotBlank() }
        return ToolPresentation(
            state = state,
            errorMessage = when (state) {
                ToolPresentationState.FAILED,
                ToolPresentationState.STOPPED -> note
                else -> null
            },
        )
    }

    fun stateFromWire(value: String?): ToolPresentationState? = when (value) {
        ToolExecutionStates.CALLING -> ToolPresentationState.CALLING
        ToolExecutionStates.RUNNING -> ToolPresentationState.RUNNING
        ToolExecutionStates.SUCCEEDED -> ToolPresentationState.COMPLETED
        ToolExecutionStates.EMPTY -> ToolPresentationState.EMPTY
        ToolExecutionStates.FAILED -> ToolPresentationState.FAILED
        ToolExecutionStates.STOPPED -> ToolPresentationState.STOPPED
        ToolExecutionStates.BACKGROUND_RUNNING -> ToolPresentationState.BACKGROUND_RUNNING
        else -> null
    }
}
