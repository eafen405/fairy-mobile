package com.newoether.agora.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Attachment metadata on a Remote message; original bytes load on demand. */
@Serializable
data class AttachmentMeta(val items: List<AttachmentItem> = emptyList())

@Serializable
data class AttachmentItem(
    val type: String,               // "image" or "file"
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    val remote: RemoteAttachmentRef? = null,
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
