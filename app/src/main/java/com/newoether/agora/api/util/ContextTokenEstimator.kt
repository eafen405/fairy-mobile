package com.newoether.agora.api.util

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlin.math.ceil

/**
 * Deterministic cross-provider estimate of Provider-visible conversation tokens.
 *
 * Exact tokenization is model-specific and unavailable offline for arbitrary custom providers.
 * This estimator intentionally leans conservative: ASCII word-like runs use roughly four
 * characters per token, non-ASCII code points and punctuation cost one each, and message/image/
 * tool framing has explicit overhead. Every threshold, rollout decision, and UI indicator uses
 * this same function, so the estimate cannot drift between surfaces.
 */
object ContextTokenEstimator {
    private const val MESSAGE_OVERHEAD = 8

    /**
     * Byte-proportional image cost. Providers tokenize visual tiles, not bytes, so the byte
     * divisor is a deliberately coarse proxy that keeps a thumbnail well below a full-resolution
     * photo while staying model independent. `768` bytes per token puts a 768 KiB image at the
     * historical 1024-token figure, and the bounds keep one image between a quarter and one and a
     * half of that figure.
     */
    private const val IMAGE_BYTES_PER_TOKEN = 768L
    private const val MIN_IMAGE_TOKENS = 256L
    private const val MAX_IMAGE_TOKENS = 1_536L

    /** Fallback for images whose durable metadata does not record a byte size. */
    private const val UNKNOWN_IMAGE_TOKENS = 1_024L
    private const val TOOL_CALL_OVERHEAD = 16
    private const val SAFETY_NUMERATOR = 11L
    private const val SAFETY_DENOMINATOR = 10L

    fun estimate(
        messages: List<ChatMessage>,
        includeAssistantReasoning: Boolean = false,
    ): Int {
        val raw = messages.fold(0L) { total, message ->
            (total + estimateMessageRaw(message, includeAssistantReasoning))
                .coerceAtMost(Int.MAX_VALUE.toLong())
        }
        return applySafetyMargin(raw)
    }

    /** Provider-visible cost that exists even when the conversation history is empty. */
    fun estimateFixed(
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        initialUserPrompt: String? = null,
        codeExecutionEnabled: Boolean = false,
        googleSearchEnabled: Boolean = false,
        openAiWebSearchEnabled: Boolean = false,
    ): Int {
        var raw = MESSAGE_OVERHEAD.toLong() + estimateTextRaw(systemPrompt.orEmpty())
        initialUserPrompt?.takeIf(String::isNotBlank)?.let { prompt ->
            raw += MESSAGE_OVERHEAD + estimateTextRaw(prompt)
        }
        tools.forEach { tool ->
            raw += TOOL_CALL_OVERHEAD
            raw += estimateTextRaw(tool.type)
            raw += estimateTextRaw(tool.function.name)
            raw += estimateTextRaw(tool.function.description)
            raw += estimateTextRaw(tool.function.parameters.type)
            tool.function.parameters.properties.toSortedMap().forEach { (name, property) ->
                raw += estimateTextRaw(name)
                raw += estimateToolPropertyRaw(property)
            }
            tool.function.parameters.required.sorted().forEach { required ->
                raw += estimateTextRaw(required)
            }
        }
        listOfNotNull(
            "code_execution".takeIf { codeExecutionEnabled },
            "google_search".takeIf { googleSearchEnabled },
            "web_search".takeIf { openAiWebSearchEnabled },
        ).forEach { nativeTool ->
            raw += TOOL_CALL_OVERHEAD + estimateTextRaw(nativeTool)
        }
        return applySafetyMargin(raw.coerceAtMost(Int.MAX_VALUE.toLong()))
    }

    internal fun estimateText(text: String): Int = applySafetyMargin(estimateTextRaw(text))

    private fun estimateMessageRaw(
        message: ChatMessage,
        includeAssistantReasoning: Boolean,
    ): Long {
        val isToolProtocol = message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(Constants.RESULT_MSG_PREFIX)
        // Provider adapters serialize tool protocol payload from segments/toolCall and ignore the
        // mirrored Room text field. Counting both made result-heavy contexts look up to 2x larger.
        var total = MESSAGE_OVERHEAD.toLong() +
            if (isToolProtocol) 0L else estimateTextRaw(message.text)
        // Only user-role images reach a provider: every adapter (OpenAI-compatible, Anthropic,
        // Gemini, Ollama, local) serializes images for user rows and drops them elsewhere, so
        // tool-result images stay display-only and are deliberately not counted here.
        if (!isToolProtocol && message.participant == Participant.USER) {
            total += imageTokensTotal(message)
        }
        if (!isToolProtocol && includeAssistantReasoning && message.participant != Participant.USER) {
            message.segments.orEmpty()
                .asSequence()
                .filter { it.type == "thought" }
                .forEach { segment -> total += estimateTextRaw(segment.content) }
        }
        if (isToolProtocol) {
            message.segments.orEmpty()
                .asSequence()
                .filter { it.type == "thought" }
                .forEach { segment ->
                    total += estimateTextRaw(segment.content)
                    total += estimateTextRaw(segment.signature.orEmpty())
                }
            val segments = message.segments.orEmpty().filter { it.type == "tool" }
            if (segments.isNotEmpty()) {
                segments.forEach { segment ->
                    total += TOOL_CALL_OVERHEAD
                    total += estimateTextRaw(segment.toolName.orEmpty())
                    total += estimateTextRaw(segment.toolArgs.orEmpty())
                    total += estimateTextRaw(segment.toolResult.orEmpty())
                    total += estimateTextRaw(segment.signature.orEmpty())
                }
                segments.firstOrNull { it.responseOutputItems.isNotEmpty() }
                    ?.responseOutputItems
                    .orEmpty()
                    .forEach { item ->
                        total += estimateTextRaw(item.toString())
                    }
            } else {
                message.toolCall?.let { call ->
                    total += TOOL_CALL_OVERHEAD
                    total += estimateTextRaw(call.toolName)
                    total += estimateTextRaw(call.arguments)
                    total += estimateTextRaw(call.result)
                    total += estimateTextRaw(call.signature.orEmpty())
                    call.responseOutputItems.forEach { item ->
                        total += estimateTextRaw(item.toString())
                    }
                }
            }
        }
        return total
    }

    /** Provider-visible image cost of one message, proportional to each stored image's bytes. */
    private fun imageTokensTotal(message: ChatMessage): Long {
        val sizes = imageByteSizes(message)
        var total = 0L
        repeat(message.images.size) { index ->
            total += estimateImageTokens(sizes[index])
        }
        return total
    }

    /**
     * Byte size per [ChatMessage.images] entry, read from the durable attachment metadata.
     * One item covers a contiguous image range (`imageIndex` plus `pageCount`), so its bytes are
     * split evenly across the images it renders. Entries without a recorded size stay unmapped and
     * fall back to [UNKNOWN_IMAGE_TOKENS].
     */
    private fun imageByteSizes(message: ChatMessage): Map<Int, Long> {
        val items = message.attachmentMeta?.items ?: return emptyMap()
        return buildMap {
            items.forEach { item ->
                if (item.type != "image" && item.type != "pdf" && item.type != "video") return@forEach
                val start = item.imageIndex ?: return@forEach
                if (start !in message.images.indices) return@forEach
                val bytes = item.fileSize?.takeIf { it > 0L } ?: return@forEach
                val pages = (item.pageCount ?: 1).coerceAtLeast(1)
                val bytesPerPage = bytes / pages
                repeat(minOf(pages, message.images.size - start)) { offset ->
                    put(start + offset, bytesPerPage)
                }
            }
        }
    }

    internal fun estimateImageTokens(imageBytes: Long?): Long = imageBytes
        ?.takeIf { it > 0L }
        ?.let { bytes -> (bytes / IMAGE_BYTES_PER_TOKEN).coerceIn(MIN_IMAGE_TOKENS, MAX_IMAGE_TOKENS) }
        ?: UNKNOWN_IMAGE_TOKENS

    private fun estimateTextRaw(text: String): Long {
        if (text.isEmpty()) return 0L
        var tokens = 0L
        var asciiRun = 0

        fun flushAsciiRun() {
            if (asciiRun > 0) {
                tokens += ceil(asciiRun / 4.0).toLong()
                asciiRun = 0
            }
        }

        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when {
                codePoint <= 0x7f && Character.isLetterOrDigit(codePoint) -> asciiRun++
                Character.isWhitespace(codePoint) -> flushAsciiRun()
                else -> {
                    flushAsciiRun()
                    tokens++
                }
            }
            index += Character.charCount(codePoint)
        }
        flushAsciiRun()
        return tokens
    }

    private fun estimateToolPropertyRaw(property: com.newoether.agora.api.ToolProperty): Long =
        estimateTextRaw(property.type) +
            estimateTextRaw(property.description) +
            (property.items?.let(::estimateToolPropertyRaw) ?: 0L)

    private fun applySafetyMargin(raw: Long): Int =
        ((raw * SAFETY_NUMERATOR + SAFETY_DENOMINATOR - 1) / SAFETY_DENOMINATOR)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
}
