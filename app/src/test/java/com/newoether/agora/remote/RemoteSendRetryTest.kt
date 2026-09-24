package com.newoether.agora.remote

import android.app.Application
import android.net.Uri
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Send-attempt identity: retry reuses clientId and finished uploadIds, changed
 * input is a new attempt, and delivery confirmation is identity-first — an
 * equal-length or attachment attempt can never claim another user's message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
internal class RemoteSendRetryTest : RemoteViewModelFixture() {
    private val store = mockk<RemoteAttachmentStore>()

    private fun viewModel() = RemoteViewModel(connections, projectionDispatcher = dispatcher,
        attachmentStore = store) { _, _ -> client }

    private fun upload(id: Char) = RemoteUpload(id = "$id".repeat(32), name = "f",
        mime = "application/octet-stream", size = 4, sha256 = "s".repeat(64), path = "upload:" + "$id".repeat(32))

    private fun TestScope.attach(vm: RemoteViewModel, owner: String, count: Int, size: Long = 4): List<SelectedAttachment> {
        every { store.declaredSize(any()) } returns 4
        coEvery { store.import(any(), any()) } coAnswers {
            SelectedAttachment(localId = secondArg(), uri = firstArg<Uri>().toString(), type = "file",
                fileName = "f-${secondArg<String>().take(4)}", mimeType = "application/octet-stream",
                fileSize = size, localPath = "/tmp/${secondArg<String>()}", importState = AttachmentImportState.READY)
        }
        every { store.remove(any()) } returns Unit
        vm.addAttachments(owner, (1..count).map { Uri.parse("content://pick/$it") })
        runCurrent()
        return vm.state.value.attachments[owner].orEmpty()
    }

    private fun TestScope.open(): Pair<RemoteViewModel, String> {
        val vm = viewModel(); runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        return vm to vm.state.value.owner!!
    }

    private fun nodeMessage(id: String, clientId: String? = null, text: String = "hello",
        messageId: String? = null) = RemoteMessage(id, "turn", clientId, "user", text, 1, messageId = messageId)

    @Test fun resendAfterPartialUploadKeepsClientIdAndSkipsCompletedUploads() = runTest(dispatcher) {
        val (vm, owner) = open()
        val items = attach(vm, owner, 2)
        var secondCalls = 0
        coEvery { client.upload(match { it.localId == items[0].localId }) } returns upload('a')
        coEvery { client.upload(match { it.localId == items[1].localId }) } coAnswers {
            if (++secondCalls == 1) throw FiloHttpException(503) else upload('b')
        }
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { RemoteSendReceipt("turn", arg(2)) }
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.REJECTED, vm.state.value.attempts[owner]?.delivery)
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }

        val clientId = vm.state.value.attempts[owner]!!.clientId
        vm.send(); runCurrent()
        assertEquals(clientId, vm.state.value.attempts[owner]?.clientId)
        // The finished upload is never sent again; only the failed item retries.
        coVerify(exactly = 1) { client.upload(match { it.localId == items[0].localId }) }
        coVerify(exactly = 2) { client.upload(match { it.localId == items[1].localId }) }
        coVerify(exactly = 1) {
            client.send(session.id, "hello", clientId, listOf("a".repeat(32), "b".repeat(32))) }
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
    }

    @Test fun unknownResendKeepsClientIdAndNeverReuploads() = runTest(dispatcher) {
        val (vm, owner) = open()
        attach(vm, owner, 1)
        coEvery { client.upload(any()) } returns upload('a')
        val clientIds = mutableListOf<String>()
        coEvery { client.send(any(), any(), any(), any()) } coAnswers {
            clientIds += arg<String>(2)
            if (clientIds.size == 1) throw FiloHttpException(503)
            RemoteSendReceipt("turn", arg(2))
        }
        vm.editDraft(owner, "hi"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)

        // UNKNOWN blocks resubmission until the user acknowledges it.
        vm.send(); runCurrent()
        assertEquals(1, clientIds.size)
        vm.acknowledgeUnknown(owner)
        assertEquals(RemoteDelivery.RESENDABLE, vm.state.value.attempts[owner]?.delivery)
        vm.send(); runCurrent()
        assertEquals(2, clientIds.size)
        assertEquals(1, clientIds.toSet().size)
        coVerify(exactly = 1) { client.upload(any()) }
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
    }

    @Test fun attachmentAttemptNeverConfirmsAnEqualLengthNodeWithoutIdentity() = runTest(dispatcher) {
        val (vm, owner) = open()
        attach(vm, owner, 1)
        coEvery { client.upload(any()) } returns upload('a')
        // A legacy-shaped receipt carries no messageId.
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { RemoteSendReceipt("turn", arg(2)) }
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        val clientId = vm.state.value.attempts[owner]!!.clientId

        // An equal-length user node with no identity must not confirm the attachment send.
        coEvery { client.conversation(session.id, any()) } returns bodyPage(
            listOf(nodeMessage("other", text = "hello")), null, emptyList())
        vm.refresh(); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        assertTrue(vm.state.value.attachments[owner].orEmpty().isNotEmpty())

        // The node echoing this attempt's clientId does confirm it.
        coEvery { client.conversation(session.id, any()) } returns bodyPage(
            listOf(nodeMessage("mine", clientId = clientId)), null, emptyList())
        vm.refresh(); runCurrent()
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        assertNull(vm.state.value.drafts[owner])
        assertTrue(vm.state.value.attachments[owner].orEmpty().isEmpty())
    }

    @Test fun identifiedReceiptConfirmsOnlyTheMatchingMessageId() = runTest(dispatcher) {
        val (vm, owner) = open()
        coEvery { client.send(any(), any(), any(), any()) } coAnswers {
            RemoteSendReceipt("turn", arg(2), messageId = "m-mine") }
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)

        // An equal-length node carrying another message's identity is not this attempt.
        coEvery { client.conversation(session.id, any()) } returns bodyPage(
            listOf(nodeMessage("other", text = "hello", messageId = "m-other")), null, emptyList())
        vm.refresh(); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)

        coEvery { client.conversation(session.id, any()) } returns bodyPage(
            listOf(nodeMessage("mine", text = "hello", messageId = "m-mine")), null, emptyList())
        vm.refresh(); runCurrent()
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
    }

    @Test fun anotherClientIdNeverConfirmsALegacyPlainTextAttempt() = runTest(dispatcher) {
        val (vm, owner) = open()
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { RemoteSendReceipt("turn", arg(2)) }
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)

        coEvery { client.conversation(session.id, any()) } returns bodyPage(
            listOf(nodeMessage("other", clientId = "someone-else", text = "hello")), null, emptyList())
        vm.refresh(); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)

        // The legacy no-identity fallback still applies to a bare equal-length node.
        coEvery { client.conversation(session.id, any()) } returns bodyPage(
            listOf(nodeMessage("mine", text = "hello")), null, emptyList())
        vm.refresh(); runCurrent()
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
    }

    @Test fun editedResubmissionIsANewAttemptIdentity() = runTest(dispatcher) {
        val (vm, owner) = open()
        coEvery { client.send(any(), any(), any(), any()) } throws FiloHttpException(503)
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)
        val original = vm.state.value.attempts[owner]!!.clientId
        vm.acknowledgeUnknown(owner)
        vm.editDraft(owner, "changed"); vm.send(); runCurrent()
        assertNotEquals(original, vm.state.value.attempts[owner]?.clientId)
    }

    @Test fun rejectedResendReusesTheSameClientIdForIdenticalInput() = runTest(dispatcher) {
        val (vm, owner) = open()
        val clientIds = mutableListOf<String>()
        coEvery { client.send(any(), any(), any(), any()) } coAnswers {
            clientIds += arg<String>(2); throw FiloHttpException(400)
        }
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.REJECTED, vm.state.value.attempts[owner]?.delivery)
        vm.send(); runCurrent()
        assertEquals(2, clientIds.size)
        assertEquals(1, clientIds.toSet().size)
    }

    @Test fun lateAcceptanceStaysOnTheOriginalOwner() = runTest(dispatcher) {
        val (vm, owner) = open()
        val gate = CompletableDeferred<RemoteSendReceipt>()
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { gate.await() }
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.SUBMITTING, vm.state.value.attempts[owner]?.delivery)

        vm.selectSession(session.copy(id = "other")); runCurrent()
        val otherOwner = vm.state.value.owner!!
        assertNotEquals(owner, otherOwner)
        gate.complete(RemoteSendReceipt("turn", "unused")); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        assertNull(vm.state.value.attempts[otherOwner])
        // The other conversation's draft is untouched and the original one is intact.
        assertEquals("hello", vm.state.value.drafts[owner])
    }

    @Test fun seventeenthPickIsRefusedBeforeAnyImport() = runTest(dispatcher) {
        val (vm, owner) = open()
        val notices = mutableListOf<RemoteNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        attach(vm, owner, 17)
        assertTrue(vm.state.value.attachments[owner].orEmpty().isEmpty())
        coVerify(exactly = 0) { store.import(any(), any()) }
        assertTrue(notices.any { it.stage == "attachment_failed" })
    }

    @Test fun attachmentLimitsAreEnforcedBeforeAnyNetworkCall() = runTest(dispatcher) {
        val (vm, owner) = open()
        attach(vm, owner, 3, size = REMOTE_ATTACHMENT_LIMIT)
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        // 3 × 128 MiB exceeds the 256 MiB message budget; nothing reaches the wire.
        assertNull(vm.state.value.attempts[owner])
        coVerify(exactly = 0) { client.upload(any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.create(any(), any(), any(), any()) }
    }
}
