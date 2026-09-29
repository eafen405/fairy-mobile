package com.newoether.agora.remote

import com.newoether.agora.ui.chat.message.StreamingTailFadeTracker
import org.junit.Assert.*
import org.junit.Test

class RemoteStreamDeltasTest {
    private val active = RemoteRuntime("active", "turn")
    private val message = RemoteMessage("answer", "turn", null, "assistant", "Hello", 1)
    private fun deltas(messages: List<RemoteMessage>) =
        projectRemoteMessages(messages, active).first().segments!!.first().streamingTextDeltas

    @Test fun initialHistoryIsOpaqueButAppendedSnapshotUsesOriginalGlyphFade() {
        val source = RemoteStreamDeltas()
        val first = source.apply(emptyList(), listOf(message), null, active)
        assertTrue(deltas(first).isEmpty())
        val fade = StreamingTailFadeTracker()
        assertTrue(fade.update("Hello", 10, deltas(first)).birthTimesMs.isEmpty())
        val updated = source.apply(first, listOf(message.copy(text = "Hello 🌍!\n")), active, active)
        val projected = projectRemoteMessages(updated, active).first().segments!!.first()
        assertEquals("Hello 🌍!", projected.content)
        assertEquals(3, projected.streamingTextDeltas.single().codePointCount)
        assertArrayEquals(longArrayOf(20, 20, 20),
            fade.update(projected.content, 20, projected.streamingTextDeltas).birthTimesMs)
        val repeated = source.apply(updated, listOf(message.copy(text = "Hello 🌍!\n")), active, active)
        assertEquals(deltas(updated), deltas(repeated))
        assertArrayEquals(longArrayOf(20, 20, 20),
            fade.update(projected.content, 30, deltas(repeated)).birthTimesMs)
    }

    @Test fun finalChunkRetainsDeltaIdentityAndRewritesDoNotInventText() {
        val source = RemoteStreamDeltas()
        val first = source.apply(listOf(message), listOf(message.copy(text = "Hello world")), active, active)
        val terminal = source.apply(first, listOf(message.copy(text = "Hello world!")), active, RemoteRuntime("idle"))
        assertEquals(listOf(7), deltas(terminal).map { it.codePointCount })
        assertTrue(deltas(terminal).single().cumulative)
        val rewritten = source.apply(terminal, listOf(message.copy(text = "Edited history")), RemoteRuntime("idle"), RemoteRuntime("idle"))
        assertTrue(deltas(rewritten).isEmpty())
    }

    @Test fun conflatedSnapshotsKeepAllNewPublishedBoundariesWithoutReplayingHistory() {
        val source = RemoteStreamDeltas()
        val first = source.apply(listOf(message), listOf(message.copy(text = "Hello one")), active, active)
        val next = source.apply(first, listOf(message.copy(text = "Hello one two")), active, active)
        val projected = projectRemoteMessages(next, active).first().segments!!.first()
        val fade = StreamingTailFadeTracker()
        fade.update("Hello", 0, emptyList())
        assertEquals(8, fade.update(projected.content, 100, projected.streamingTextDeltas).birthTimesMs.size)
        val history = source.apply(emptyList(), listOf(message.copy(turnId = "old")), active, active)
        assertTrue(history.single().streamingTextDeltas.isEmpty())
    }

    @Test fun longStreamRetainsOneDeltaAndConflatedFadeCountsOnlyUnseenText() {
        val source = RemoteStreamDeltas()
        val fade = StreamingTailFadeTracker()
        var previous = message
        fade.update(previous.text, 0, emptyList())
        repeat(1_000) { step ->
            val fresh = message.copy(text = "Hello" + "x".repeat(step + 1))
            val updated = source.apply(listOf(previous), listOf(fresh), active, active).single()
            assertEquals(1, updated.streamingTextDeltas.size)
            if (step % 10 == 9) {
                val sample = fade.update(updated.text, 100L + step,
                    updated.streamingTextDeltas)
                assertArrayEquals(LongArray(10) { 100L + step }, sample.birthTimesMs.takeLast(10).toLongArray())
            }
            previous = updated
        }
    }

    @Test fun lookupPathTouchesOnlyIncomingIds() {
        val source = RemoteStreamDeltas()
        val requested = mutableListOf<String>()
        val fresh = message.copy(text = "Hello again")
        val result = source.apply(listOf(fresh), active, active) { id ->
            requested += id
            message
        }
        assertEquals(listOf("answer"), requested)
        assertEquals(6, result.single().streamingTextDeltas.single().codePointCount)
    }
}
