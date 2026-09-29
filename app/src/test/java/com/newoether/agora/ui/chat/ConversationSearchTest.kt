package com.newoether.agora.ui.chat

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {
    @Test
    fun matchingIsCaseInsensitiveAndCountsEveryOccurrence() {
        val messages = listOf(
            ChatMessage(id = "a", text = "One one", participant = Participant.USER),
            ChatMessage(id = "b", text = "none", participant = Participant.MODEL),
        )

        val matches = findConversationSearchMatches(messages, "ONE")

        assertEquals(listOf("a", "a", "b"), matches.map { it.messageId })
        assertEquals(listOf(0, 4, 1), matches.map { it.start })
    }

    @Test
    fun canonicalMatchOrderIsSelectedPathThenSourceRange() {
        val messages = listOf(
            ChatMessage(
                id = "root-user",
                text = "needle then needle",
                participant = Participant.USER,
            ),
            ChatMessage(
                id = "leaf-assistant",
                text = "prefix needle suffix",
                participant = Participant.MODEL,
            ),
        )

        val matches = findConversationSearchMatches(messages, "needle")

        assertEquals(
            listOf(
                "root-user:0:6",
                "root-user:12:18",
                "leaf-assistant:7:13",
            ),
            matches.map(ConversationSearchMatch::key),
        )
        assertEquals(listOf(0, 1, 0), matches.map(ConversationSearchMatch::occurrenceInMessage))
    }

    @Test
    fun nearestMatchUsesTheCurrentViewportTurn() {
        val matches = listOf(
            ConversationSearchMatch("a", 0, 1, 0),
            ConversationSearchMatch("b", 0, 1, 0),
            ConversationSearchMatch("c", 0, 1, 0),
        )

        val index = nearestConversationSearchMatchIndex(
            matches,
            mapOf("a" to 1, "b" to 8, "c" to 12),
            anchorTurnIndex = 10,
        )

        assertEquals(1, index)
    }

    @Test
    fun nearestVisibleMatchUsesExactRenderedDistanceWithinTheSameTurn() {
        val matches = listOf(
            ConversationSearchMatch("long-answer", 0, 3, 0),
            ConversationSearchMatch("long-answer", 400, 403, 1),
            ConversationSearchMatch("long-answer", 900, 903, 2),
        )

        val index = nearestVisibleConversationSearchMatchIndex(
            matches,
            mapOf(
                matches[0].key to 520f,
                matches[1].key to 18f,
                matches[2].key to 310f,
            ),
        )

        assertEquals(1, index)
    }

    @Test
    fun markdownSearchExcludesHiddenLinkTargetsFromCount() {
        val message = ChatMessage(
            id = "assistant",
            text = "[Shown](https://hidden.example) then hidden",
            participant = Participant.MODEL,
        )

        val matches = findConversationSearchMatches(listOf(message), "hidden")

        assertEquals(1, matches.size)
        assertEquals(message.text.lastIndexOf("hidden"), matches.single().start)
    }

    @Test
    fun markdownSearchExcludesFenceLanguageButIncludesCodeContent() {
        val message = ChatMessage(
            id = "assistant",
            text = "```kotlin\nval kotlin = true\n```",
            participant = Participant.MODEL,
        )

        val matches = findConversationSearchMatches(listOf(message), "kotlin")

        assertEquals(1, matches.size)
        assertEquals(message.text.indexOf("kotlin", startIndex = 4), matches.single().start)
    }

    @Test
    fun pagedSearchLoadsStubPayloadsAndRestoresSelectedPathOrder() = runTest {
        val stubs = List(66) { index ->
            ChatMessage(
                id = "message-$index",
                text = "",
                participant = if (index % 2 == 0) Participant.USER else Participant.MODEL,
            )
        }
        val matchingIds = setOf("message-0", "message-63", "message-64", "message-65")
        val payloadsById = stubs.associate { stub ->
            stub.id to stub.copy(text = if (stub.id in matchingIds) "needle" else "none")
        }
        val loadedPages = mutableListOf<List<String>>()

        val matches = scanConversationSearchMatches(
            selectedPathMessageIds = conversationSearchMessageIds(stubs),
            query = "needle",
        ) { pageIds ->
            loadedPages += pageIds
            pageIds.reversed().mapNotNull(payloadsById::get)
        }

        assertEquals(listOf(64, 2), loadedPages.map(List<String>::size))
        assertEquals(matchingIds.toList(), matches.map(ConversationSearchMatch::messageId))
    }

}
