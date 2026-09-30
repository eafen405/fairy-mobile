package com.newoether.agora.model

internal data class RunMessagePresentation(
    val showActions: Boolean = false,
    val copyText: String? = null,
)

/**
 * Derives the read-only affordances of a Remote conversation: each real USER
 * message and each generation's terminal assistant row expose copy affordances.
 * Remote conversations are linear — edit, regenerate and branch selectors do
 * not exist, so there is no sibling/branch resolution here.
 */
internal object RunUiProjection {
    fun project(visibleMessages: List<ChatMessage>): Map<String, RunMessagePresentation> {
        if (visibleMessages.isEmpty()) return emptyMap()

        // ID is the structural identity. A transient optimistic-commit race must never be
        // interpreted as a second generation even if a caller accidentally supplies duplicates.
        val uniqueVisibleMessages = visibleMessages.distinctBy { it.id }
        val result = uniqueVisibleMessages
            .associate { it.id to RunMessagePresentation() }
            .toMutableMap()
        uniqueVisibleMessages
            .filter(MessageGenerationBoundaryResolver::isRealUser)
            .forEach { userBoundary ->
                result[userBoundary.id] = RunMessagePresentation(
                    showActions = true,
                    copyText = userBoundary.text.takeIf { it.isNotBlank() },
                )
            }

        MessageGenerationBoundaryResolver.resolve(uniqueVisibleMessages).forEach { boundary ->
            val outputBoundary = boundary.lastAssistant ?: return@forEach
            result[outputBoundary.id] = RunMessagePresentation(
                showActions = true,
                copyText = outputBoundary.text.takeIf { it.isNotBlank() },
            )
        }
        return result
    }
}
