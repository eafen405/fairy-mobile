package com.newoether.agora.remote

import kotlinx.serialization.Serializable

@Serializable
internal data class RemoteSession(
    val id: String, val title: String, val cwd: String, val updatedAt: Long,
    @kotlinx.serialization.Transient val listCursor: String? = null,
)
@Serializable
internal data class RemoteMessage(
    val id: String, val turnId: String, val clientId: String?, val role: String,
    val text: String, val timestamp: Long,
    val activity: RemoteActivity? = null,
    val groupId: String? = null,
    val messageId: String? = null,
    val attachments: List<RemoteMessageAttachment> = emptyList(),
    val files: List<RemoteFileRef> = emptyList(),
    val relayFrom: String? = null,
    @kotlinx.serialization.Transient
    val streamingTextDeltas: List<com.newoether.agora.model.StreamingTextDelta> = emptyList(),
    val error: Boolean = false,
)
/**
 * Safe attachment metadata echoed on accepted user messages. Bytes stay on the
 * server; the client only ever renders [name]/[mime]/[bytes].
 */
@Serializable
internal data class RemoteMessageAttachment(
    val type: String = "", val name: String = "", val mime: String? = null,
    val bytes: Long = 0L,
)
/**
 * A file Fairy published to this user. [fileId] is the only field used to build
 * the authenticated download path; it is never a URL.
 */
@Serializable
internal data class RemoteFileRef(
    val fileId: String = "", val deliveryId: String? = null, val name: String = "",
    val bytes: Long = 0L, val mime: String? = null,
)
/**
 * Server-bounded activity projection. The wire only carries a user-facing label, a
 * lifecycle state, and an optional short outcome note on failure/stop. Tool names,
 * arguments, results, and host paths never cross the client feedback boundary; the
 * client drops such fields even if a payload unexpectedly contains them.
 */
@Serializable
internal data class RemoteActivity(
    val type: String, val state: String? = null, val durationMs: Long? = null,
    val label: String? = null, val note: String? = null,
)
@Serializable
internal data class RemoteSessionPage(
    val sessions: List<RemoteSession>, val nextCursor: String?,
)
@Serializable
internal data class RemoteConversationPage(
    val messages: List<RemoteMessage>, val nextCursor: String?,
    val runtime: RemoteRuntime? = null, val pageCursor: String? = null,
    val continuationCursor: String? = null,
    val nodes: List<RemoteMessageNode> = emptyList(),
)
@Serializable
internal data class RemoteRuntime(
    val status: String, val activeTurnId: String? = null, val model: String? = null,
    val contextTokens: Int? = null,
    val completedTurnId: String? = null,
    val activeTurnHasUserMessage: Boolean = false,
) { val isRunning: Boolean get() = status == "active" }
