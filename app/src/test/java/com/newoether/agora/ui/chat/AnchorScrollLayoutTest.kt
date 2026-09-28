package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorScrollLayoutTest {
    @Test
    fun reserveFillsTheViewportRoomBelowShortContent() {
        assertEquals(448, anchorTailReserve(1_000, 140, 100, 12, 300))
    }

    @Test
    fun reserveIsZeroWhenContentExactlyFillsTheViewport() {
        assertEquals(0, anchorTailReserve(1_000, 140, 100, 12, 748))
    }

    @Test
    fun reserveIsZeroOnceContentOverflowsTheViewport() {
        assertEquals(0, anchorTailReserve(1_000, 140, 100, 12, 1_500))
    }

    @Test
    fun reserveNeverGoesNegativeOnDegenerateInput() {
        assertEquals(0, anchorTailReserve(0, 140, 100, 12, 300))
        assertEquals(0, anchorTailReserve(-500, 0, 0, 0, 0))
        assertEquals(0, anchorTailReserve(1_000, 140, 100, 12, Int.MAX_VALUE))
    }

    @Test
    fun reserveShrinksOneForOneAsAnchoredContentGrows() {
        val base = anchorTailReserve(1_000, 140, 100, 12, 300)
        val grown = anchorTailReserve(1_000, 140, 100, 12, 380)
        assertEquals(80, base - grown)
    }

    @Test
    fun anchoredContentCountsTheRenderedRegionBelowTheAnchorTop() {
        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
            ),
        )
        // The tail holder renders at max(tailMin, measured); the gap pad sits outside that
        // minimum, so the region below the anchored top excludes only what sits above it.
        assertEquals(
            600,
            anchoredTailContentHeightPx(
                anchorMessageId = "user",
                turns = turns,
                messageHeights = mapOf("user" to 100, "assistant" to 50),
                tailHolderKey = turns.last().key,
                tailMinHeightPx = 600,
            ),
        )
        // Anchoring the assistant mid-turn excludes the user message above it too.
        assertEquals(
            600 - 100,
            anchoredTailContentHeightPx(
                anchorMessageId = "assistant",
                turns = turns,
                messageHeights = mapOf("user" to 100, "assistant" to 50),
                tailHolderKey = turns.last().key,
                tailMinHeightPx = 600,
            ),
        )
    }

    @Test
    fun anchoredContentCountsMeasuredHeightsOnceContentPassesTheTailMinimum() {
        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
            ),
        )
        assertEquals(
            1_200,
            anchoredTailContentHeightPx(
                anchorMessageId = "user",
                turns = turns,
                messageHeights = mapOf("user" to 100, "assistant" to 1_100),
                tailHolderKey = turns.last().key,
                tailMinHeightPx = 600,
            ),
        )
    }

    @Test
    fun anchoredContentIncludesLaterTurnsInFull() {
        val turns = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("user-2", Participant.USER),
            ),
        )
        // Anchoring mid-turn counts the rest of that turn plus the whole following turn.
        assertEquals(
            400 + 700,
            anchoredTailContentHeightPx(
                anchorMessageId = "assistant-1",
                turns = turns,
                messageHeights = mapOf("user-1" to 100, "assistant-1" to 400, "user-2" to 700),
                tailHolderKey = turns.last().key,
                tailMinHeightPx = 0,
            ),
        )
    }

    @Test
    fun anchoredContentIsZeroWithoutAnAnchor() {
        val turns = buildMessageListTurns(listOf(message("user", Participant.USER)))
        assertEquals(
            0,
            anchoredTailContentHeightPx(null, turns, mapOf("user" to 100), turns.last().key, 600),
        )
        assertEquals(
            0,
            anchoredTailContentHeightPx(
                "missing", turns, mapOf("user" to 100), turns.last().key, 600,
            ),
        )
    }

    @Test
    fun newModelTailAnchorsWhenAtBottomWithoutAPendingAttempt() {
        val previous = message("user", Participant.USER).copy(runId = "turn-1")
        val reply = message("assistant", Participant.MODEL).copy(runId = "turn-2")
        assertTrue(
            shouldAnchorIncomingTurn(
                previousTail = previous,
                previousTailObserved = true,
                newTail = reply,
                userRunIds = setOf("turn-1"),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
    }

    @Test
    fun incomingTurnAnchorDeclinesWhenReaderIsAwayFromBottom() {
        val previous = message("user", Participant.USER).copy(runId = "turn-1")
        val reply = message("assistant", Participant.MODEL).copy(runId = "turn-2")
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = previous,
                previousTailObserved = true,
                newTail = reply,
                userRunIds = setOf("turn-1"),
                hasPendingAttempt = false,
                withinAttachThreshold = false,
            ),
        )
    }

    @Test
    fun incomingTurnAnchorDeclinesWhileASendAttemptIsPending() {
        val previous = message("user", Participant.USER).copy(runId = "turn-1")
        val reply = message("assistant", Participant.MODEL).copy(runId = "turn-2")
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = previous,
                previousTailObserved = true,
                newTail = reply,
                userRunIds = setOf("turn-1"),
                hasPendingAttempt = true,
                withinAttachThreshold = true,
            ),
        )
    }

    @Test
    fun incomingTurnAnchorDeclinesWhenTheTailDidNotChange() {
        val tail = message("assistant", Participant.MODEL).copy(runId = "turn-1")
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = tail,
                previousTailObserved = true,
                newTail = tail,
                userRunIds = emptySet(),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
        val userTail = message("user", Participant.USER).copy(runId = "turn-1")
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = tail,
                previousTailObserved = true,
                newTail = userTail,
                userRunIds = emptySet(),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
    }

    @Test
    fun incomingTurnAnchorDeclinesForAReplyInsideTheSameTurn() {
        val user = message("user", Participant.USER).copy(runId = "turn-1")
        val reply = message("assistant", Participant.MODEL).copy(runId = "turn-1")
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = user,
                previousTailObserved = true,
                newTail = reply,
                userRunIds = setOf("turn-1"),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
    }

    @Test
    fun incomingTurnAnchorDeclinesForABatchedLocalReplyEvenAfterDeliveryConfirmed() {
        // One refresh carries the confirmed USER message and its MODEL reply together. The
        // send-confirmation anchor targets the user message; the watcher must not re-anchor to
        // the reply once the pending flag has already cleared.
        val previous = message("old-model", Participant.MODEL).copy(runId = "turn-0")
        val reply = message("reply", Participant.MODEL).copy(runId = "turn-1")
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = previous,
                previousTailObserved = true,
                newTail = reply,
                userRunIds = setOf("turn-1"),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
    }

    @Test
    fun firstProactiveMessageAnchorsAnAlreadyLoadedEmptyConversation() {
        val proactive = message("assistant", Participant.MODEL).copy(runId = "turn-1")
        assertTrue(
            shouldAnchorIncomingTurn(
                previousTail = null,
                previousTailObserved = true,
                newTail = proactive,
                userRunIds = emptySet(),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
    }

    @Test
    fun theInitialTailCaptureNeverAnchors() {
        val proactive = message("assistant", Participant.MODEL).copy(runId = "turn-1")
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = null,
                previousTailObserved = false,
                newTail = proactive,
                userRunIds = emptySet(),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = message("old", Participant.MODEL).copy(runId = "turn-0"),
                previousTailObserved = false,
                newTail = proactive,
                userRunIds = emptySet(),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
    }

    @Test
    fun incomingTurnAnchorDeclinesWhenThereIsNoTail() {
        assertFalse(
            shouldAnchorIncomingTurn(
                previousTail = message("user", Participant.USER),
                previousTailObserved = true,
                newTail = null,
                userRunIds = emptySet(),
                hasPendingAttempt = false,
                withinAttachThreshold = true,
            ),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
