package com.newoether.agora.model

import kotlinx.serialization.Serializable

/** Stable address of a committed inbound attachment, scoped to its session. */
@Serializable
data class RemoteAttachmentRef(
    val messageId: String,
    val index: Int,
    val name: String,
    val mime: String?,
    val bytes: Long,
    val type: String,
)
