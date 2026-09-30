package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageGenerationBoundaryResolverTest {
    @Test
    fun usersAndDurableRunsSeparateGenerations() {
        val messages = listOf(
            message("u0", Participant.USER, runId = "run-a"),
            message("m0", Participant.MODEL, "u0", runId = "run-a"),
            message("m1", Participant.MODEL, "m0", runId = "run-a"),
            message("m2", Participant.MODEL, "m1", runId = "run-b"),
            message("u1", Participant.USER, "m2", runId = "run-c"),
            message("m3", Participant.MODEL, "u1", runId = "run-c"),
        )

        val boundaries = MessageGenerationBoundaryResolver.resolve(messages)

        assertEquals(3, boundaries.size)
        assertEquals("u0", boundaries[0].input?.id)
        assertEquals("m0", boundaries[0].firstAssistant?.id)
        assertEquals("m1", boundaries[0].lastAssistant?.id)
        assertNull(boundaries[1].input)
        assertEquals("m2", boundaries[1].firstAssistant?.id)
        assertEquals("m2", boundaries[1].lastAssistant?.id)
        assertEquals("u1", boundaries[2].input?.id)
        assertEquals("m3", boundaries[2].lastAssistant?.id)
    }


    private fun message(
        id: String,
        participant: Participant,
        parentId: String? = null,
        runId: String = "",
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = id,
        participant = participant,
        runId = runId,
    )
}
