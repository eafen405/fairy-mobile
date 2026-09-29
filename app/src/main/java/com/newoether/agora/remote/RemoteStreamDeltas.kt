package com.newoether.agora.remote

import com.newoether.agora.model.StreamingTextDelta

/** Snapshot transport supplies real appended-text boundaries to the original glyph fade owner. */
internal class RemoteStreamDeltas {
    companion object {
        // Hydration reconnects reset this owner, while the mounted fade tracker can survive.
        private val sequence = java.util.concurrent.atomic.AtomicLong()
    }

    fun apply(previous: List<RemoteMessage>, fresh: List<RemoteMessage>,
        previousRuntime: RemoteRuntime?, runtime: RemoteRuntime?): List<RemoteMessage> {
        val old = previous.associateBy { it.id }
        return apply(fresh, previousRuntime, runtime, old::get)
    }

    /** Look up only records in the incoming page; retained history can be much larger. */
    fun apply(fresh: List<RemoteMessage>, previousRuntime: RemoteRuntime?, runtime: RemoteRuntime?,
        previousForId: (String) -> RemoteMessage?): List<RemoteMessage> {
        val liveTurns = listOfNotNull(previousRuntime?.activeTurnId, runtime?.activeTurnId).toSet()
        return fresh.map { message ->
            val before = previousForId(message.id)
            val text = message.displayText()
            val oldText = before?.displayText().orEmpty()
            val preserved = before?.streamingTextDeltas.orEmpty()
            val deltas = if (previousRuntime != null && message.role == "assistant" && message.activity == null &&
                message.turnId in liveTurns && text.startsWith(oldText) && text.length > oldText.length) {
                // A single cumulative count survives conflation. The fade tracker caps it by
                // the actually observed text growth, so older glyphs are not reborn.
                listOf(StreamingTextDelta(sequence.incrementAndGet(),
                    (preserved.singleOrNull()?.codePointCount ?: 0) +
                        text.codePointCount(oldText.length, text.length), cumulative = true,
                    sourceId = message.id))
            } else if (text == oldText || text.startsWith(oldText)) preserved else emptyList()
            message.copy(streamingTextDeltas = deltas)
        }
    }
}
