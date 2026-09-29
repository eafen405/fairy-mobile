package com.newoether.agora.ui.chat

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RemoteFile

internal const val HYDRATED_MESSAGE_CACHE_MAX_ENTRIES = 16
internal const val HYDRATED_MESSAGE_CACHE_MAX_BYTES = 8L * 1024L * 1024L

internal class HydratedMessagePayloadLru(
    private val maxEntries: Int = HYDRATED_MESSAGE_CACHE_MAX_ENTRIES,
    private val maxWeightBytes: Long = HYDRATED_MESSAGE_CACHE_MAX_BYTES,
    private val weightOf: (ChatMessage) -> Long = ChatMessage::estimatedHydratedPayloadBytes,
) {
    private data class Entry(
        val message: ChatMessage,
        val weightBytes: Long,
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    var totalWeightBytes: Long = 0L
        private set

    val size: Int
        get() = entries.size

    init {
        require(maxEntries >= 0)
        require(maxWeightBytes >= 0L)
    }

    operator fun get(messageId: String): ChatMessage? = entries[messageId]?.message

    fun put(message: ChatMessage) {
        entries.remove(message.id)?.let { previous ->
            totalWeightBytes -= previous.weightBytes
        }
        val weightBytes = weightOf(message).coerceAtLeast(0L)
        if (maxEntries == 0 || weightBytes > maxWeightBytes) return

        entries[message.id] = Entry(message, weightBytes)
        totalWeightBytes += weightBytes
        while (entries.size > maxEntries || totalWeightBytes > maxWeightBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            totalWeightBytes -= eldest.value.weightBytes
        }
    }

    fun contains(messageId: String): Boolean = entries.containsKey(messageId)
}

internal fun ChatMessage.estimatedHydratedPayloadBytes(): Long {
    var bytes = 512L + preparedMarkdownBytes
    bytes += id.estimatedHeapBytes()
    bytes += parentId.estimatedHeapBytes()
    bytes += text.estimatedHeapBytes()
    bytes += thoughts.estimatedHeapBytes()
    bytes += thoughtTitle.estimatedHeapBytes()
    bytes += modelName.estimatedHeapBytes()
    bytes += runId.estimatedHeapBytes()
    bytes += displayPageId.estimatedHeapBytes()
    bytes += remoteFiles.sumOf(RemoteFile::estimatedHeapBytes)
    bytes += segments.orEmpty().sumOf(MessageSegment::estimatedHeapBytes)
    bytes += attachmentMeta?.items.orEmpty().sumOf(AttachmentItem::estimatedHeapBytes)
    return bytes
}

private fun MessageSegment.estimatedHeapBytes(): Long =
    160L +
        type.estimatedHeapBytes() +
        content.estimatedHeapBytes() +
        toolCallId.estimatedHeapBytes() +
        toolState.estimatedHeapBytes() +
        toolDisplayName.estimatedHeapBytes() +
        toolNote.estimatedHeapBytes()

private fun RemoteFile.estimatedHeapBytes(): Long =
    96L +
        fileId.estimatedHeapBytes() +
        deliveryId.estimatedHeapBytes() +
        name.estimatedHeapBytes() +
        mime.estimatedHeapBytes() +
        source.estimatedHeapBytes()

private fun AttachmentItem.estimatedHeapBytes(): Long =
    96L +
        type.estimatedHeapBytes() +
        fileName.estimatedHeapBytes() +
        mimeType.estimatedHeapBytes()

private fun String?.estimatedHeapBytes(): Long =
    this?.let { value -> 24L + value.length.toLong() * 2L } ?: 0L
