package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.components.FairyWindowState
import com.newoether.agora.ui.components.fairyPresence
import com.newoether.agora.ui.components.rememberFairyTextGrowth

@Composable
internal fun rememberChatFairyWindowState(
    streamingMessage: ChatMessage?,
    lastMessage: ChatMessage?,
    isNewChatMode: Boolean,
    isLoading: Boolean,
    isSwitching: Boolean,
): FairyWindowState {
    val tail = streamingMessage ?: lastMessage
    val textGrowth = rememberFairyTextGrowth(
        key = tail?.id,
        visibleLength = tail?.text?.length ?: 0,
    )
    return FairyWindowState(
        presence = fairyPresence(
            connection = McpConnectionStatus.CONNECTED,
            tailParticipant = tail?.participant,
            tailStatus = tail?.status,
            tailTextGrowing = textGrowth.growing,
        ),
        expanded = !isNewChatMode && !isLoading && !isSwitching &&
            streamingMessage == null && lastMessage == null,
        speechPulse = textGrowth.pulse,
    )
}
