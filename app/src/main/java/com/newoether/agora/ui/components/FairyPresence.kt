package com.newoether.agora.ui.components

import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant

/** What Fairy is doing right now, as shown by her window. */
enum class FairyPresence {
    IDLE,
    THINKING,
    SPEAKING,
    OFFLINE,
    CONNECTING;

    /** Thinking or speaking: the working glow, fast breath and active accent bar. */
    val isWorking: Boolean get() = this == THINKING || this == SPEAKING
}

/** Same in-flight set as RemoteConversation's `generationVisible`. */
internal val FairyGeneratingStatuses = setOf(
    MessageStatus.SENDING,
    MessageStatus.THINKING,
    MessageStatus.TOOL_CALLING,
)

/**
 * Pure presence decision. A connection other than CONNECTED maps to
 * CONNECTING/OFFLINE. An assistant tail in a generating status is SPEAKING
 * when its visible text grew within the last 400 ms ([tailTextGrowing]) and
 * THINKING otherwise. A user tail (sent, reply not yet present) is THINKING.
 * Everything else is IDLE.
 */
fun fairyPresence(
    connection: McpConnectionStatus,
    tailParticipant: Participant?,
    tailStatus: MessageStatus?,
    tailTextGrowing: Boolean,
): FairyPresence = when {
    connection == McpConnectionStatus.CONNECTING -> FairyPresence.CONNECTING
    connection != McpConnectionStatus.CONNECTED -> FairyPresence.OFFLINE
    tailParticipant == Participant.MODEL && tailStatus in FairyGeneratingStatuses ->
        if (tailTextGrowing) FairyPresence.SPEAKING else FairyPresence.THINKING
    tailParticipant == Participant.USER && tailStatus != MessageStatus.ERROR &&
        tailStatus != MessageStatus.STOPPED -> FairyPresence.THINKING
    else -> FairyPresence.IDLE
}
