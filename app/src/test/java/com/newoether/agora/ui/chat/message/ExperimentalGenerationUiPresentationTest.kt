package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalGenerationUiPresentationTest {

    @Test
    fun `only the current card loads and stale active content never overrides its position`() {
        for (generating in listOf(false, true)) {
            // Reproduces a running tool/thought followed by a newly published answer block.
            assertFalse(compactSegmentShowsLoading(generating, isCurrentCard = false))
        }
        assertTrue(compactSegmentShowsLoading(generationActive = true, isCurrentCard = true))
        assertFalse(compactSegmentShowsLoading(generationActive = false, isCurrentCard = true))
    }

    private fun message(status: MessageStatus): ChatMessage = ChatMessage(
        text = "",
        status = status,
        participant = Participant.MODEL,
    )
    @Test
    fun `inline generation activity only fills the intentional gaps`() {
        assertEquals(
            AssistantInlineActivityMode.EMPTY,
            assistantInlineActivityMode(
                generationActive = true,
                hasAnswer = false,
                hasVisibleInfoSegment = false,
            ),
        )
        assertEquals(
            AssistantInlineActivityMode.NONE,
            assistantInlineActivityMode(
                generationActive = true,
                hasAnswer = true,
                hasVisibleInfoSegment = true,
            ),
        )
        assertEquals(
            AssistantInlineActivityMode.NONE,
            assistantInlineActivityMode(
                generationActive = true,
                hasAnswer = true,
                hasVisibleInfoSegment = false,
            ),
        )
        assertEquals(
            AssistantInlineActivityMode.NONE,
            assistantInlineActivityMode(
                generationActive = false,
                hasAnswer = false,
                hasVisibleInfoSegment = false,
            ),
        )
    }

    @Test
    fun `stopping hides the inline dot while retaining only its status slot`() {
        val active = assistantInlineActivityPresentation(
            generationActive = true,
            isStopping = false,
            hasAnswer = false,
            hasVisibleInfoSegment = false,
        )
        val stopping = assistantInlineActivityPresentation(
            generationActive = true,
            isStopping = true,
            hasAnswer = false,
            hasVisibleInfoSegment = false,
        )
        val stopped = assistantInlineActivityPresentation(
            generationActive = false,
            isStopping = false,
            hasAnswer = false,
            hasVisibleInfoSegment = false,
        )

        assertEquals(AssistantInlineActivityMode.EMPTY, active.mode)
        assertFalse(active.retainLayout)
        assertEquals(AssistantInlineActivityMode.NONE, stopping.mode)
        assertTrue(stopping.retainLayout)
        assertTrue(stopping.retainLayout && stopping.mode == AssistantInlineActivityMode.NONE)
        assertEquals(AssistantInlineActivityMode.NONE, stopped.mode)
        assertFalse(stopped.retainLayout)
    }

}
