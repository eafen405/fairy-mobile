package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.verify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteViewModelTest : RemoteViewModelFixture() {
    @Test fun staleReadCannotReplaceNewSession() = runTest(dispatcher) {
        val gate = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("session", null) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm)
        vm.setVisible(true); runCurrent()
        vm.selectSession(session); runCurrent()
        vm.selectSession(session.copy(id = "other")); runCurrent()
        gate.complete(bodyPage(listOf(RemoteMessage("stale", "t", null, "user", "old", 0)), null))
        runCurrent()
        assertEquals("other", vm.state.value.session?.id)
        assertNull(vm.animatedScrollRequest.value)
        assertTrue(vm.state.value.nodes.isEmpty())
        vm.setVisible(false)
    }

    @Test fun uncertainSendKeepsDraftAndCannotAutomaticallyRetry() = runTest(dispatcher) {
        coEvery { client.send(any(), any(), any(), any()) } throws IOException("Lost response")
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        assertNull(vm.animatedScrollRequest.value)
        coVerify(exactly = 1) { client.send(any(), any(), any(), any()) }
        vm.acknowledgeUnknown(owner)
        assertEquals(RemoteDelivery.RESENDABLE, vm.state.value.attempts[owner]?.delivery)
        vm.setVisible(false)
    }

    @Test fun unavailableRuntimeAllowsExplicitSendAndShowsNativeRejectionWithoutReplay() = runTest(dispatcher) {
        val detail = "Original desktop owner is unavailable; refresh before continuing"
        coEvery { client.send(any(), any(), any(), any()) } throws FiloHttpException(409, detail = detail)
        for (status in listOf("notLoaded", "systemError")) {
            coEvery { client.conversation(any(), any()) } returns bodyPage(emptyList(), null)
                .copy(runtime = RemoteRuntime(status, model = "model"))
            val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
            loginAndSelect(vm)
            vm.selectSession(session); vm.setVisible(true); runCurrent()
            val owner = vm.state.value.owner!!
            assertEquals(status, vm.state.value.runtime?.status)
            vm.editDraft(owner, "hello")
            repeat(2) {
                vm.send(); runCurrent()
                val notice = vm.notices.first()
                assertEquals("send_failed", notice.stage)
                assertEquals(detail, notice.detail)
                assertFalse(notice.canRetryRead)
                assertEquals(RemoteDelivery.REJECTED, vm.state.value.attempts[owner]?.delivery)
                assertEquals("hello", vm.state.value.drafts[owner])
            }
            vm.setVisible(false)
        }
        coVerify(exactly = 4) { client.send(any(), "hello", any()) }
    }

    @Test fun acceptedSendRequestsOneOwnedScrollAndNavigationClearsIt() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.send(any(), any(), any(), any()) } coAnswers {
            RemoteSendReceipt(gate.await(), arg(2)) }
        coEvery { client.conversation(any(), any()) } returns bodyPage(
            listOf(
                RemoteMessage("tail", "turn", null, "assistant", "Previous answer", 1),
                RemoteMessage("tool", "turn", null, "assistant", "", 1,
                    RemoteActivity("tool", state = "succeeded", label = "处理")),
            ), null)
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.SUBMITTING, vm.state.value.attempts[owner]?.delivery)
        assertNull(vm.animatedScrollRequest.value)
        gate.complete("turn"); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        assertNull(vm.animatedScrollRequest.value)
        val clientId = vm.state.value.attempts[owner]!!.clientId
        coEvery { client.conversation(any(), any()) } returns bodyPage(
            listOf(RemoteMessage("sent", "new-turn", clientId, "user", "hello", 2)), null)
        vm.refresh(); vm.state.first { it.attempts[owner]?.delivery == RemoteDelivery.DELIVERED }
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        assertNull(vm.state.value.drafts[owner])
        val request = vm.animatedScrollRequest.value!!
        assertEquals(owner, request.conversationId)
        assertEquals("sent", request.targetMessageId)
        assertEquals(com.newoether.agora.viewmodel.AnimatedScrollDestination.ANCHOR, request.destination)
        vm.completeAnimatedScroll(request.id + 1)
        assertEquals(request, vm.animatedScrollRequest.value)
        vm.completeAnimatedScroll(request.id)
        assertNull(vm.animatedScrollRequest.value)
        vm.refresh(); runCurrent()
        assertNull(vm.animatedScrollRequest.value)
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { RemoteSendReceipt("second-turn", arg(2)) }
        vm.editDraft(owner, "next"); vm.send(); runCurrent()
        assertNull(vm.animatedScrollRequest.value)
        val nextId = vm.state.value.attempts[owner]!!.clientId
        coEvery { client.conversation(any(), any()) } returns bodyPage(
            listOf(RemoteMessage("next", "next-turn", nextId, "user", "next", 3)), null)
        vm.refresh(); runCurrent()
        assertNotNull(vm.animatedScrollRequest.value)
        vm.selectSession(session.copy(id = "other"))
        assertNull(vm.animatedScrollRequest.value)
        vm.setVisible(false)
    }

    @Test fun malformedAcceptedResponseIsUnknownRatherThanRejected() = runTest(dispatcher) {
        coEvery { client.send(any(), any(), any(), any()) } throws SerializationException("Invalid response")
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        vm.setVisible(false)
    }

    @Test fun nativeEventBeforeLostReceiptConfirmsOnceAndDisconnectInvalidatesRuntime() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        every { client.events(any()) } returns events
        val response = CompletableDeferred<String>()
        coEvery { client.send(any(), any(), any(), any()) } coAnswers {
            RemoteSendReceipt(response.await(), arg(2)) }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        val running = RemoteRuntime("active", "turn", "model", 1234)
        events.emit(bodyPage(emptyList(), null, running)); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        val id = vm.state.value.attempts[owner]!!.clientId
        val message = RemoteMessage("sent", "turn", id, "user", "hello", 1)
        events.emit(bodyPage(listOf(message), null, running)); runCurrent()
        val scroll = vm.animatedScrollRequest.value
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        response.completeExceptionally(IOException("Lost receipt")); runCurrent()
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        assertEquals(scroll, vm.animatedScrollRequest.value)
        assertEquals(1234, vm.state.value.runtime?.contextTokens)
        vm.setVisible(false)
        assertNull(vm.state.value.runtime)
        vm.editDraft(owner, "offline"); vm.send(); runCurrent()
        coVerify(exactly = 1) { client.send(any(), any(), any(), any()) }
    }

    @Test fun nativeGenerationChangesAreVisibleBeforeAnyMessageOrLocalSubmission() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        every { client.events(any()) } returns events
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("active", "native-turn")))
        runCurrent()
        assertTrue(vm.state.value.runtime!!.isRunning)
        assertEquals("native-turn", vm.state.value.runtime?.activeTurnId)
        assertTrue(vm.state.value.nodes.isEmpty())
        assertTrue(vm.state.value.attempts.isEmpty())
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("idle")))
        runCurrent()
        assertFalse(vm.state.value.runtime!!.isRunning)
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.stop(any(), any()) }
        vm.setVisible(false)
    }

    @Test fun nativeTurnEndFirstKeepsBusyUntilStopReceiptArrives() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        val receipt = CompletableDeferred<Unit>()
        every { client.events(any()) } returns events
        coEvery { client.stop(any(), any()) } coAnswers { receipt.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("active", "turn")))
        runCurrent(); vm.stop(); runCurrent()
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("idle")))
        runCurrent()
        assertTrue(vm.state.value.isStopping)
        receipt.complete(Unit); runCurrent()
        assertFalse(vm.state.value.isStopping)
        assertFalse(vm.state.value.controlling)
        vm.setVisible(false)
    }

    @Test fun failedStopEndsBusyWithoutInventingIdleOrAutomaticallyRetrying() = runTest(dispatcher) {
        coEvery { client.conversation(any(), any()) } returns bodyPage(
            emptyList(), null, RemoteRuntime("active", "turn"))
        coEvery { client.stop(any(), any()) } throws IOException("Lost Stop receipt")
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        vm.stop(); runCurrent()
        assertFalse(vm.state.value.isStopping)
        assertFalse(vm.state.value.controlling)
        assertEquals(RemoteFailure.NETWORK, vm.state.value.failure)
        assertTrue(vm.state.value.runtime!!.isRunning)
        coVerify(exactly = 1) { client.stop("session", "turn") }
        vm.setVisible(false)
    }

    @Test fun disconnectWhileAwaitingNativeStopClearsBusyAndInvalidatesControls() = runTest(dispatcher) {
        val disconnect = CompletableDeferred<Unit>()
        every { client.events(any()) } returns flow {
            emit(bodyPage(emptyList(), null, RemoteRuntime("active", "turn")))
            disconnect.await()
            throw IOException("Stream disconnected")
        }
        coEvery { client.stop(any(), any()) } returns Unit
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        vm.stop(); runCurrent()
        assertTrue(vm.state.value.isStopping)
        disconnect.complete(Unit); runCurrent()
        assertFalse(vm.state.value.isStopping)
        assertNull(vm.state.value.runtime)
        vm.stop(); vm.send(); runCurrent()
        coVerify(exactly = 1) { client.stop("session", "turn") }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun lateStopReceiptCannotChangeAnotherSessionsGeneration() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        val receipt = CompletableDeferred<Unit>()
        every { client.events(any()) } returns events
        coEvery { client.stop(any(), any()) } coAnswers { receipt.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("active", "old-turn")))
        runCurrent(); vm.stop(); runCurrent()
        vm.selectSession(session.copy(id = "other")); runCurrent()
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("active", "other-turn")))
        runCurrent()
        assertFalse(vm.state.value.isStopping)
        receipt.complete(Unit); runCurrent()
        assertFalse(vm.state.value.isStopping)
        assertEquals("other-turn", vm.state.value.runtime?.activeTurnId)
        assertTrue(vm.state.value.runtime!!.isRunning)
        coVerify(exactly = 1) { client.stop("session", "old-turn") }
        coVerify(exactly = 0) { client.stop("other", any()) }
        vm.setVisible(false)
    }

    @Test fun replacementTurnEndsOnlyTheOriginalPendingStop() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        every { client.events(any()) } returns events
        coEvery { client.stop(any(), any()) } returns Unit
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("active", "old-turn")))
        runCurrent(); vm.stop(); runCurrent()
        assertTrue(vm.state.value.isStopping)
        events.emit(bodyPage(emptyList(), null, RemoteRuntime("active", "new-turn")))
        runCurrent()
        assertFalse(vm.state.value.isStopping)
        assertEquals("new-turn", vm.state.value.runtime?.activeTurnId)
        assertTrue(vm.state.value.runtime!!.isRunning)
        coVerify(exactly = 1) { client.stop("session", "old-turn") }
        coVerify(exactly = 0) { client.stop("session", "new-turn") }
        vm.setVisible(false)
    }

    @Test fun diagnosticsCaptureCategoriesWithoutCredentialsOrExceptionContent() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        mockkObject(DeveloperDiagnostics)
        every { DeveloperDiagnostics.recordHttpStage(any(), any(), any(), any()) } answers {
            events += "${firstArg<Any>()} ${secondArg<String>()} ${arg<String>(3)}"
        }
        try {
            coEvery { connections.load() } returns listOf(RemoteConnection("Computer", "http://computer/", "PRIVATE_TOKEN"))
            coEvery { client.sessions(any()) } throws IOException("PRIVATE_PAYLOAD http://private-host/")
            val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
            vm.setVisible(true); vm.selectDevice("http://computer/"); runCurrent()
            // Authenticated health succeeds: keep the raw network cause in diagnostics,
            // but report a failed session request instead of an unreachable device.
            assertEquals(RemoteFailure.SERVICE, vm.state.value.failure)
            coEvery { client.sessions(any()) } throws FiloHttpException(401)
            vm.refresh(); runCurrent()
            assertEquals(RemoteFailure.AUTHENTICATION, vm.state.value.failure)
            // 401 过期后直接回到登录页：连接保留预填信息，恢复读被 addingDevice 拦住。
            assertTrue(vm.state.value.addingDevice)
            assertNull(vm.state.value.session)
            coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
            vm.refresh(); runCurrent()
            assertEquals(RemoteFailure.AUTHENTICATION, vm.state.value.failure)
            vm.setVisible(false)
            assertTrue(events.any { it.contains("restore_completed") })
            assertTrue(events.any { it.contains("read_failed.NETWORK.IOException") })
            assertTrue(events.any { it.contains("AUTHENTICATION") && it.contains("code=401") })
            assertFalse(events.any { it.contains("PRIVATE_") || it.contains("http://") })
        } finally { unmockkObject(DeveloperDiagnostics) }
    }
    @Test fun olderPageLoadingEndsOnFailureAndDoesNotSurviveNavigation() = runTest(dispatcher) {
        val page = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("history", null) } returns bodyPage(listOf(
            RemoteMessage("recent", "turn", null, "assistant", "Recent history", 2)), "older", RemoteRuntime("notLoaded"))
        coEvery { client.conversation("history", "older") } coAnswers { page.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); loginAndSelect(vm)
        vm.selectSession(session.copy(id = "history")); vm.state.first { it.historyCursor == "older" && it.nodes.isNotEmpty() }
        assertFalse(vm.state.value.loading)
        coVerify(exactly = 0) { client.conversation("history", "older") }
        vm.loadMore(); vm.state.first { it.loadingMore }
        assertTrue(vm.state.value.loadingMore)
        page.completeExceptionally(IOException("offline")); vm.state.first { it.failure == RemoteFailure.NETWORK }
        assertFalse(vm.state.value.loadingMore)
        assertEquals(RemoteFailure.NETWORK, vm.state.value.failure)
        vm.selectSession(null); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        vm.setVisible(false)
    }


    @Test fun unloadedOriginalTaskStillLoadsModelsAndDispatchesExplicitSend() = runTest(dispatcher) {
        coEvery { client.conversation("history", any()) } returns bodyPage(
            emptyList(), null, RemoteRuntime("notLoaded"))
        coEvery { client.send("history", any(), any()) } throws FiloHttpException(409, detail = "Original owner unavailable")
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); loginAndSelect(vm)
        vm.selectSession(session.copy(id = "history")); runCurrent()
        assertEquals("notLoaded", vm.state.value.runtime?.status)
        verify(exactly = 1) { client.events("history") }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.editDraft(vm.state.value.owner!!, "Explicit input")
        vm.send(); runCurrent()
        coVerify(exactly = 1) { client.send("history", "Explicit input", any()) }
        assertEquals("Explicit input", vm.state.value.drafts[vm.state.value.owner])
        assertFalse(vm.state.value.controlling)
        vm.setVisible(false)
    }

    @Test fun openingReadsOneBodyPageAndOlderAdmissionKeepsExistingItemsUnchanged() = runTest(dispatcher) {
        val historical = session.copy(id = "historical")
        val recent = RemoteMessage("recent", "turn", null, "assistant", "Recent history", 2)
        val older = RemoteMessage("older", "old-turn", null, "user", "Earlier history", 1)
        coEvery { client.conversation("historical", null) } returns
            bodyPage(listOf(recent), "older", RemoteRuntime("active", "turn", "model"))
        coEvery { client.conversation("historical", "older") } returns
            bodyPage(listOf(older), null, RemoteRuntime("notLoaded"))
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); loginAndSelect(vm)
        vm.selectSession(historical); vm.state.first { it.nodes.isNotEmpty() }
        assertEquals(listOf(recent.id), vm.state.value.nodes.map { it.id })
        assertFalse(vm.state.value.loading)
        coVerify(exactly = 0) { client.conversation("historical", "older") }
        val group = vm.state.value.messageGroups.single()
        assertEquals(recent.text, vm.cachedMessage(vm.state.value.owner!!, group.stub.id)!!.text)
        vm.loadMore(); runCurrent()
        assertEquals(listOf(older.id, recent.id), vm.state.value.nodes.map { it.id })
        assertEquals(group, vm.state.value.messageGroups.last())
        coVerify(exactly = 1) { client.conversation("historical", null) }
        coVerify(exactly = 1) { client.conversation("historical", "older") }
        verify(exactly = 1) { client.events("historical") }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.stop(any(), any()) }
        vm.setVisible(false)
    }

}
