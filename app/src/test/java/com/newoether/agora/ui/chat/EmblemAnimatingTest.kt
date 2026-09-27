package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmblemAnimatingTest {

    private fun modelMessage(status: MessageStatus) = ChatMessage(
        text = "", participant = Participant.MODEL, status = status,
    )

    @Test
    fun `tail model message animates while generating`() {
        listOf(
            MessageStatus.SENDING,
            MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING,
        ).forEach { status ->
            assertTrue(
                "$status tail should animate",
                isEmblemAnimating(isLoading = true, message = modelMessage(status), isTail = true),
            )
        }
    }

    @Test
    fun `no animation for terminal, non-tail, user, or idle states`() {
        listOf(
            MessageStatus.SUCCESS,
            MessageStatus.STOPPED,
            MessageStatus.ERROR,
            MessageStatus.TRANSCRIBING,
        ).forEach { status ->
            assertFalse(
                "$status tail should not animate",
                isEmblemAnimating(true, modelMessage(status), isTail = true),
            )
        }
        assertFalse(isEmblemAnimating(true, modelMessage(MessageStatus.SENDING), isTail = false))
        assertFalse(
            isEmblemAnimating(
                true,
                ChatMessage(text = "hi", participant = Participant.USER, status = MessageStatus.SENDING),
                isTail = true,
            ),
        )
        assertFalse(isEmblemAnimating(false, modelMessage(MessageStatus.THINKING), isTail = true))
    }
}
