package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.MessageStatus

internal fun RemoteSession.displayTitle(untitled: String): String = title.takeUnless { it.isBlank() || it == id } ?: untitled

/** Windows extended paths are retained for native operations, but not shown as device syntax. */
internal fun RemoteSession.displayDirectory(): String = when {
    cwd.startsWith("\\\\?\\UNC\\", ignoreCase = true) -> "\\\\" + cwd.substring(8)
    cwd.startsWith("\\\\?\\") && cwd.length >= 7 && cwd[4].isLetter() && cwd[5] == ':' && cwd[6] == '\\' -> cwd.substring(4)
    else -> cwd
}

/** Native records stay in the Remote cache; only presentation groups adjacent assistant records. */
internal fun projectRemoteMessages(messages: List<RemoteMessage>, runtime: RemoteRuntime? = null): List<ChatMessage> = buildList {
    var index = 0
    while (index < messages.size) {
        val first = messages[index++]
        require(first.role == "user" || first.role == "assistant")
        val answerText = StringBuilder()
        val inlineImages = mutableMapOf<String, com.newoether.agora.model.MarkdownImage>()
        var previousAnswerId: String? = null
        var userText = first.displayText()
        if (first.role == "user") {
            val nativeId = first.nativeId ?: first.id
            while (messages.getOrNull(index)?.let {
                it.role == "user" && (it.nativeId ?: it.id) == nativeId
            } == true) userText += messages[index++].displayText()
        }
        val segments = if (first.role == "assistant") buildList<MessageSegment> {
            var current = first
            var previousNativeId: String? = null
            while (true) {
                current.imageLinks.forEach { link ->
                    inlineImages[link] = current.inlineImages[link] ?: com.newoether.agora.model.MarkdownImage()
                }
                val activity = current.activity
                val segment = if (current.error) MessageSegment(type = "error", content = current.displayText()) else when (activity?.type) {
                    null -> MessageSegment(type = "answer", content = current.displayText(),
                        streamingTextDeltas = current.streamingTextDeltas)
                    // Reasoning text never crosses the client boundary; a thought record
                    // stays invisible and the "思考中" indicator is driven by runtime state.
                    "thought" -> MessageSegment(type = "thought", durationMs = activity.durationMs)
                    // Bounded activity: the wire supplies only a user-facing label, a
                    // lifecycle state, and an optional outcome note. Internal tool names,
                    // arguments, results, and host paths are never projected into the model.
                    "tool" -> MessageSegment(
                        type = "tool", toolCallId = current.id,
                        toolDisplayName = activity.label.boundedActivityText(),
                        toolNote = activity.note.boundedActivityText(),
                        toolState = activity.state,
                        durationMs = activity.durationMs,
                        toolImages = activity.images,
                    )
                    else -> error("Unsupported Remote activity")
                }
                val nativeId = current.nativeId ?: current.id
                if (segment.type == "answer" && (segment.content.isNotBlank() || current.textContinues)) {
                    if (answerText.isNotEmpty() && previousAnswerId != nativeId) answerText.append("\n\n")
                    answerText.append(segment.content)
                    previousAnswerId = nativeId
                }
                if (segment.type == "tool" || segment.content.isNotBlank() || current.textContinues) {
                    val previous = lastOrNull()
                    if (segment.type != "tool" && previous?.type == segment.type && previousNativeId == nativeId) {
                        set(lastIndex, previous.copy(content = previous.content + segment.content,
                            streamingTextDeltas = previous.streamingTextDeltas + segment.streamingTextDeltas))
                    } else {
                        // Different native text records remain separate paragraphs.
                        add(if (segment.type != "tool" && previous?.type == segment.type) {
                            segment.copy(content = "\n\n" + segment.content)
                        } else segment)
                    }
                    previousNativeId = nativeId
                }
                val next = messages.getOrNull(index)
                if (next?.role != "assistant" || next.turnId != first.turnId) break
                current = next
                index++
            }
        } else null
        if (segments != null && segments.isEmpty()) continue
        add(ChatMessage(
            id = first.groupId ?: first.nativeId ?: first.id, parentId = lastOrNull()?.id,
            text = if (segments == null) userText else answerText.toString(),
            participant = if (first.role == "user") Participant.USER else Participant.MODEL,
            timestamp = first.timestamp, modelName = "Fairy", runId = first.turnId,
            segments = segments, markdownImages = inlineImages,
            status = if (segments?.any { it.type == "error" } == true) MessageStatus.ERROR else MessageStatus.SUCCESS,
        ))
    }
    val turn = runtime?.activeTurnId?.takeIf { active ->
        runtime.hasVisibleGeneration(messages)
    }
    if (turn != null) {
        val tail = lastOrNull()
        // A persisted terminal failure must not become a generating card from stale runtime.
        if (tail?.runId == turn && tail.status == MessageStatus.ERROR) return@buildList
        if (tail?.participant == Participant.MODEL && tail.runId == turn) {
            // Thought segments carry no content, so only a trailing tool chip narrows
            // the run-state indicator; everything else keeps the generic "思考中" card.
            val status = when (tail.segments?.lastOrNull()?.type) {
                "tool" -> MessageStatus.TOOL_CALLING
                else -> MessageStatus.SENDING
            }
            set(lastIndex, tail.copy(status = status, modelName = runtime.model ?: "Fairy"))
        } else {
            // Display-only empty assistant uses the existing initial-generation indicator.
            // Its authority is the real native active turn, not an inferred local request.
            add(ChatMessage(id = "remote-active-$turn", parentId = tail?.id, text = "",
                participant = Participant.MODEL, timestamp = tail?.timestamp ?: 0,
                modelName = runtime.model ?: "Fairy", runId = turn, status = MessageStatus.SENDING))
        }
    }
}

internal fun RemoteMessage.displayText(): String = if (textContinues) text else text.trimEnd('\r', '\n')

/**
 * Activity label/note are server-curated display strings, but they remain untrusted
 * input: strip control characters, bound the length, and drop blanks entirely.
 */
private fun String?.boundedActivityText(): String? =
    this?.filter { !it.isISOControl() || it == '\n' }?.trim()?.take(512)?.takeIf { it.isNotBlank() }

internal fun RemoteRuntime?.hasVisibleGeneration(messages: List<RemoteMessage>): Boolean =
    this?.isRunning == true && activeTurnId != null && (activeTurnHasUserMessage ||
        messages.any { it.role == "user" && it.turnId == activeTurnId })
