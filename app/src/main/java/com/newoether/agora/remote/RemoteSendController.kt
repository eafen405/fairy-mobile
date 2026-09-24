package com.newoether.agora.remote

import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the Remote send attempt lifecycle: identity assignment, upload reuse,
 * submission and delivery confirmation. All closures are provided by
 * [RemoteViewModel] so a late coroutine can never write into a stale owner.
 */
internal class RemoteSendController(
    private val state: MutableStateFlow<RemoteState>,
    private val scope: CoroutineScope,
    private val attachmentStore: RemoteAttachmentStore?,
    private val scrollRequests: ScrollRequestCoordinator,
    private val client: (RemoteState) -> FiloClient?,
    private val epoch: () -> Long,
    private val sendBlocked: () -> Boolean,
    private val stale: (Long, FiloClient) -> Boolean,
    private val trace: (String, Exception) -> RemoteFailure?,
    private val refresh: () -> Unit,
) {
    fun acknowledgeUnknown(owner: String) {
        // 用户确认知情后仍保留 attempt：原样重发复用同一 clientId 与已完成 uploadIds。
        if (state.value.attempts[owner]?.delivery == RemoteDelivery.UNKNOWN) {
            val attempt = state.value.attempts.getValue(owner)
            state.value = state.value.copy(attempts = state.value.attempts +
                (owner to attempt.copy(delivery = RemoteDelivery.RESENDABLE)))
        }
    }

    fun send() {
        val snapshot = state.value
        if (sendBlocked()) return
        val owner = snapshot.owner ?: return
        val client = client(snapshot) ?: return
        val text = snapshot.drafts[owner].orEmpty()
        val attachments = snapshot.attachments[owner].orEmpty()
        if (text.isBlank() && attachments.isEmpty()) return
        val prior = snapshot.attempts[owner]
        if (prior?.delivery in setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN)) return
        if (attachments.any { it.importState != com.newoether.agora.model.AttachmentImportState.READY }) {
            trace("send_failed", RemoteAttachmentException("Finish or remove pending attachments before sending")); return
        }
        if (attachments.size > REMOTE_ATTACHMENT_COUNT) {
            trace("send_failed", RemoteAttachmentException("Choose up to $REMOTE_ATTACHMENT_COUNT attachments")); return
        }
        if (attachments.any { (it.fileSize ?: 0L) > REMOTE_ATTACHMENT_LIMIT } ||
            attachments.sumOf { it.fileSize ?: 0L } > REMOTE_ATTACHMENT_TOTAL_LIMIT) {
            trace("send_failed", RemoteContentLimitException()); return
        }
        val selected = epoch()
        // Resubmitting the identical input keeps the attempt identity and skips the
        // uploads that already completed; any content change is a new attempt.
        val attempt = prior?.takeIf {
            it.delivery in setOf(RemoteDelivery.REJECTED, RemoteDelivery.RESENDABLE) &&
                it.text == text &&
                it.attachments.map { item -> item.localId } == attachments.map { item -> item.localId }
        }?.copy(delivery = RemoteDelivery.SUBMITTING)
            ?: RemoteAttempt(UUID.randomUUID().toString(), text, RemoteDelivery.SUBMITTING,
                attachments = attachments)
        state.value = state.value.copy(attempts = state.value.attempts + (owner to attempt))
        scope.launch {
            var uploadComplete = false
            try {
                val uploads = attempt.attachments.map { item ->
                    attempt.uploads[item.localId] ?: client.upload(item).id.also { uploadId ->
                        // Persist each finished uploadId so an UNKNOWN retry never re-uploads it.
                        val current = state.value.attempts[owner]
                        if (current?.clientId == attempt.clientId) {
                            state.value = state.value.copy(attempts = state.value.attempts +
                                (owner to current.copy(uploads = current.uploads + (item.localId to uploadId))))
                        }
                    }
                }
                uploadComplete = true
                if (stale(selected, client)) throw FiloInputException()
                var sessionId = snapshot.session!!.id
                var receipt: RemoteSendReceipt? = null
                if (snapshot.isDraft) {
                    // One mutation: the session is created together with this first message.
                    val settingsModel = snapshot.settingsModel
                    val settings = if (settingsModel == null) null else if (settingsModel.reasoningEfforts != null) RemoteSettings(
                        settingsModel.id, snapshot.selectedEffort, snapshot.selectedServiceTier, updateServiceTier = true,
                    ) else RemoteSettings(model = settingsModel.id)
                    val created = client.create(text, attempt.clientId, uploads, settings)
                    receipt = created.receipt
                    // Creation includes the first turn, even if its owner is no longer selected.
                    state.value = state.value.copy(
                        sessionOwners = state.value.sessionOwners + ("${snapshot.deviceId}/${created.session.id}" to owner),
                    )
                    if (stale(selected, client) ||
                        state.value.attempts[owner]?.clientId != attempt.clientId) {
                        // Keep the accepted outcome on the original owner without replacing the selection.
                        markAccepted(owner, attempt.clientId, receipt.messageId)
                        return@launch
                    }
                    sessionId = created.session.id
                    state.value = state.value.copy(
                        session = created.session,
                        lastKnownModel = snapshot.selectedModel,
                        sessionOwners = state.value.sessionOwners + ("${snapshot.deviceId}/${created.session.id}" to owner),
                    )
                    refresh()
                } else if (stale(selected, client)) throw FiloInputException()
                if (!snapshot.isDraft) receipt = client.send(sessionId, text, attempt.clientId, uploads)
                markAccepted(owner, attempt.clientId, receipt?.messageId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = trace("send_failed", error)
                if (state.value.owner == owner && failure == RemoteFailure.SESSION_BUSY) {
                    state.value = state.value.copy(failure = failure)
                }
                val current = state.value.attempts[owner]
                if (current?.clientId == attempt.clientId && current.delivery == RemoteDelivery.SUBMITTING) {
                    val rejected = !uploadComplete || error is FiloInputException ||
                        error is FiloHttpException && error.status in setOf(400, 401, 403, 404, 409, 413, 415, 429)
                    state.value = state.value.copy(attempts = state.value.attempts +
                        (owner to current.copy(delivery = if (rejected) RemoteDelivery.REJECTED else RemoteDelivery.UNKNOWN)))
                }
            }
        }
    }

    fun markAccepted(owner: String, clientId: String, messageId: String?) {
        val current = state.value.attempts[owner]
        if (current?.clientId != clientId || current.delivery != RemoteDelivery.SUBMITTING) return
        val accepted = current.copy(delivery = RemoteDelivery.ACCEPTED,
            messageId = messageId ?: current.messageId)
        state.value = state.value.copy(attempts = state.value.attempts + (owner to accepted))
        confirmDelivery(owner, accepted)
    }

    /**
     * Identity confirmation for an admitted message. A node carrying clientId or
     * messageId is authoritative; the text-length fallback only applies to a
     * legacy plain-text attempt on a node that carries no identity at all — an
     * attachment attempt or an identified receipt can never mis-confirm a
     * different equal-length message.
     */
    private fun RemoteMessageNode.matchesAttempt(attempt: RemoteAttempt): Boolean {
        clientId?.let { return it == attempt.clientId }
        messageId?.let { return attempt.messageId != null && it == attempt.messageId }
        return attempt.messageId == null && attempt.attachments.isEmpty() &&
            textLength == attempt.text.length
    }

    fun confirmDelivery(owner: String, attempt: RemoteAttempt, fresh: List<RemoteMessageNode> = state.value.nodes) {
        if (state.value.attempts[owner]?.clientId != attempt.clientId) return
        if (state.value.attempts[owner]?.delivery == RemoteDelivery.DELIVERED || state.value.owner != owner) return
        val message = fresh.lastOrNull { it.role == "user" && it.matchesAttempt(attempt) } ?: return
        state.value.attachments[owner].orEmpty().forEach { attachmentStore?.remove(it) }
        state.value = state.value.copy(
            attachments = state.value.attachments - owner,
            drafts = if (state.value.drafts[owner] == attempt.text) state.value.drafts - owner else state.value.drafts,
            attempts = state.value.attempts + (owner to attempt.copy(delivery = RemoteDelivery.DELIVERED)),
        )
        scrollRequests.requestAbsoluteBottomAfter(owner, message.nativeId ?: message.id)
    }
}
