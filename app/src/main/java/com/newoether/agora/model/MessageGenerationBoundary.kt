package com.newoether.agora.model

/**
 * One visible generation group.
 *
 * Every row participates in Run grouping. A real USER is always a hard boundary, while rows with
 * one nonblank durable Run id remain indivisible. Remote conversations are linear, so this only
 * locates which assistant row is a generation's terminal output.
 */
internal data class MessageGenerationBoundary(
    val messages: List<ChatMessage>,
) {
    val input: ChatMessage? =
        messages.firstOrNull(MessageGenerationBoundaryResolver::isRealUser)
    val firstAssistant: ChatMessage? =
        messages.firstOrNull(MessageGenerationBoundaryResolver::isOrdinaryAssistant)
    val lastAssistant: ChatMessage? =
        messages.lastOrNull(MessageGenerationBoundaryResolver::isOrdinaryAssistant)
}

internal object MessageGenerationBoundaryResolver {
    fun resolve(visibleMessages: List<ChatMessage>): List<MessageGenerationBoundary> {
        val boundaries = mutableListOf<MessageGenerationBoundary>()
        val current = mutableListOf<ChatMessage>()
        var currentRunId: String? = null

        fun finishBoundary() {
            if (current.isNotEmpty()) {
                boundaries += MessageGenerationBoundary(current.toList())
                current.clear()
                currentRunId = null
            }
        }

        visibleMessages.distinctBy(ChatMessage::id).forEach { message ->
            val messageRunId = message.runId.orEmpty().takeIf(String::isNotBlank)
            if (isRealUser(message)) {
                finishBoundary()
            } else if (
                current.isNotEmpty() &&
                currentRunId != null &&
                messageRunId != null &&
                currentRunId != messageRunId
            ) {
                finishBoundary()
            }
            current += message
            if (currentRunId == null) currentRunId = messageRunId
        }
        finishBoundary()
        return boundaries
    }

    fun containing(
        visibleMessages: List<ChatMessage>,
        messageId: String,
    ): MessageGenerationBoundary? = resolve(visibleMessages).firstOrNull { boundary ->
        boundary.messages.any { it.id == messageId }
    }

    fun isRealUser(message: ChatMessage): Boolean =
        message.participant == Participant.USER

    fun isOrdinaryAssistant(message: ChatMessage): Boolean =
        message.participant == Participant.MODEL
}
