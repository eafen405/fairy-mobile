package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Immutable
data class StreamingTextDelta(
    val sequence: Long,
    val codePointCount: Int,
    /** A cumulative count allows remote snapshots to retain one delta after conflation. */
    val cumulative: Boolean = false,
    /** Each native text record has its own cumulative count. */
    val sourceId: String? = null,
)

/**
 * One projected Remote message record. Server activities resolve to "answer",
 * "thought", "tool", or a terminal "error" segment.
 */
@Serializable
data class MessageSegment(
    val type: String,
    val content: String = "",
    val toolCallId: String? = null,
    val durationMs: Long? = null,
    /** Durable UI lifecycle for tool segments. */
    val toolState: String? = null,
    /** Stable server-curated title for a bounded remote activity. */
    val toolDisplayName: String? = null,
    /**
     * Short server-curated outcome note for a bounded remote activity, shown only on
     * failure or stop. Never a raw error, tool output, or internal detail.
     */
    val toolNote: String? = null,
    /** In-memory provider delta boundaries for the active answer; never persisted. */
    @Transient
    val streamingTextDeltas: List<StreamingTextDelta> = emptyList(),
)

object ToolExecutionStates {
    const val CALLING = "calling"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val EMPTY = "empty"
    const val FAILED = "failed"
    const val STOPPED = "stopped"
    const val BACKGROUND_RUNNING = "background_running"

    val TERMINAL = setOf(SUCCEEDED, EMPTY, FAILED, STOPPED, BACKGROUND_RUNNING)
}

object ThinkingSegmentDisplayModes {
    const val CARD = "card"
    const val BOTTOM_SHEET = "bottom_sheet"
    const val DEFAULT = CARD

    fun normalize(value: String?): String = when (value) {
        BOTTOM_SHEET -> BOTTOM_SHEET
        else -> CARD
    }

    fun isAvailableFor(toolCallDisplayMode: String?): Boolean =
        ToolCallDisplayModes.normalize(toolCallDisplayMode) != ToolCallDisplayModes.TIMELINE

    fun effectiveMode(
        thinkingSegmentDisplayMode: String?,
        toolCallDisplayMode: String?,
    ): String = if (isAvailableFor(toolCallDisplayMode)) {
        normalize(thinkingSegmentDisplayMode)
    } else {
        CARD
    }

    fun allowsAutoExpand(
        thinkingSegmentDisplayMode: String?,
        toolCallDisplayMode: String?,
    ): Boolean =
        ToolCallDisplayModes.normalize(toolCallDisplayMode) ==
            ToolCallDisplayModes.GROUPED_TIMELINE &&
            normalize(thinkingSegmentDisplayMode) == CARD
}

object ToolCallDisplayModes {
    const val TIMELINE = "timeline"
    const val GROUPED_TIMELINE = "grouped_timeline"
    const val COMPACT = "compact"
    const val DEFAULT = GROUPED_TIMELINE

    fun normalize(value: String?): String = when (value) {
        COMPACT -> COMPACT
        GROUPED_TIMELINE -> GROUPED_TIMELINE
        TIMELINE -> TIMELINE
        else -> DEFAULT
    }
}

enum class Participant {
    USER, MODEL, ERROR
}

/**
 * A file published to this device by the Remote service. Only safe display
 * metadata lives here: [fileId] identifies the authenticated download and is
 * never a URL, [source] is the relay origin label, and bytes stay server-side
 * until the user explicitly saves.
 */
@Immutable
data class RemoteFile(
    val fileId: String,
    val deliveryId: String? = null,
    val name: String = "",
    val bytes: Long = 0,
    val mime: String? = null,
    val source: String? = null,
)

enum class MessageStatus {
    SENDING, THINKING, TOOL_CALLING, SUCCESS, STOPPED, ERROR
}

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val status: MessageStatus = MessageStatus.SUCCESS,
    val participant: Participant,
    val timestamp: Long = System.currentTimeMillis(),
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val segments: List<MessageSegment>? = null,
    val attachmentMeta: AttachmentMeta? = null,
    /** Remote message topology: the previous group's tail owns the next group's parent edge. */
    val parentId: String? = null,
    val runId: String? = null,
    /** Optional in-memory page boundary; older pages cannot reparent a rendered list item. */
    val displayPageId: String? = null,
    /** Files published by the Remote service on this message; display metadata only. */
    val remoteFiles: List<RemoteFile> = emptyList(),
    /** Disposable off-main Markdown preparation; never persisted or sent to the service. */
    val preparedMarkdown: Map<String, com.mikepenz.markdown.model.State.Success> = emptyMap(),
    val preparedMarkdownBytes: Long = 0,
)

@Immutable
data class StableMessageList(val list: List<ChatMessage> = emptyList())
