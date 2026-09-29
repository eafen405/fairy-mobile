package com.newoether.agora.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Display-only attachment metadata on a Remote message. Bytes never live on
 * this device — inbound records carry name/type/size only, and outbound picks
 * upload through the Remote upload channel before the message is sent.
 */
@Serializable
data class AttachmentMeta(val items: List<AttachmentItem> = emptyList())

@Serializable
data class AttachmentItem(
    val type: String,               // "image" or "file"
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
enum class AttachmentImportState {
    @SerialName("processing")
    PROCESSING,

    @SerialName("ready")
    READY,

    @SerialName("failed")
    FAILED,
}

/** One pending Remote composer attachment: a private draft copy awaiting upload. */
@Serializable
data class SelectedAttachment(
    /** Stable identity for list keys and draft bookkeeping; generated per pick. */
    val localId: String = java.util.UUID.randomUUID().toString(),
    val uri: String,
    val type: String,               // "image" or "file"
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val localPath: String? = null,
    val importState: AttachmentImportState = AttachmentImportState.READY,
)
