package com.newoether.agora.remote

/** Native control fixtures include the same body-page metadata returned by Filo. */
internal fun bodyPage(
    messages: List<RemoteMessage>, nextCursor: String?,
    runtime: RemoteRuntime? = null,
) = RemoteConversationPage(messages, nextCursor, runtime = runtime, nodes = messages.map { message ->
    RemoteMessageNode(message.id, message.turnId, message.clientId, message.role, message.timestamp,
        java.security.MessageDigest.getInstance("SHA-256").digest(message.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }, message.text.length,
        groupId = message.groupId, error = message.error,
        messageId = message.messageId, attachments = message.attachments, files = message.files,
        relayFrom = message.relayFrom,
        activity = message.activity?.let { RemoteNodeActivity(it.type, it.state, it.durationMs, label = it.label) })
})
