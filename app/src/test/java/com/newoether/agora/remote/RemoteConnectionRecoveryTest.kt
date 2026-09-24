package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConnectionRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>()
    private val connections = mockk<RemoteConnectionStore>(relaxed = true)
    private val session = RemoteSession("session", "Existing", "/workspace", 1)
    private val page = bodyPage(emptyList(), null, emptyList(), RemoteRuntime("idle", model = "model"))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { connections.load() } returns emptyList()
        every { client.address } returns "http://computer/"
        coEvery { client.connect() } returns "Computer"
        every { client.sessionCredential } returns "cookie"
        coEvery { client.login(any(), any()) } returns "user"
        coEvery { client.register(any(), any(), any()) } returns "user"
        coEvery { client.logout() } returns Unit
        coEvery { client.me() } returns "user"
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        coEvery { client.models() } returns listOf(RemoteModel("model", "Model", true))
        coEvery { client.conversation(any(), any()) } throws FiloHttpException(503)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.open(): RemoteViewModel {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        backgroundScope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { vm.setVisible(false) }
        }
        runCurrent()
        vm.login("http://computer/", "user", "pass"); runCurrent()
        vm.selectDevice("http://computer/")
        vm.selectSession(session)
        vm.setVisible(true); runCurrent()
        return vm
    }

    private fun TestScope.notices(vm: RemoteViewModel): MutableList<RemoteNotice> {
        val result = mutableListOf<RemoteNotice>()
        backgroundScope.launch(dispatcher) { vm.notices.collect { result += it } }
        runCurrent()
        return result
    }

    @Test fun transientStreamDisconnectRecoversWithoutOfflineOrSnackbar() = runTest(dispatcher) {
        var reads = 0
        every { client.events(any()) } answers { flow {
            if (++reads == 1) throw IOException("connection reset")
            emit(page); awaitCancellation()
        } }
        val vm = open()
        val notices = notices(vm)
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertNull(vm.state.value.failure)
        assertNull(vm.state.value.runtime)
        assertTrue(notices.isEmpty())
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, reads)
        assertEquals("idle", vm.state.value.runtime?.status)
        assertTrue(notices.isEmpty())
        coVerify(exactly = 0) { client.create(any(), any(), any(), any()); client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun persistentSessionReadFailureKeepsHealthyDeviceOnlineAndReportsOnce() = runTest(dispatcher) {
        every { client.events(any()) } returns flow { throw IOException("stream failed") }
        val vm = open()
        val notices = notices(vm)
        repeat(3) { advanceTimeBy(3000); runCurrent() }
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertEquals(RemoteFailure.SERVICE, vm.state.value.failure)
        assertEquals(listOf(RemoteFailure.SERVICE), notices.map { it.failure })
        assertNull(vm.state.value.runtime)
        vm.setVisible(false)
    }

    @Test fun nativeHttpFailureDoesNotTurnAReachableDeviceOffline() = runTest(dispatcher) {
        every { client.events(any()) } returns flow { throw FiloHttpException(503) }
        val vm = open()
        val notices = notices(vm)
        repeat(2) { advanceTimeBy(3000); runCurrent() }
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertEquals(listOf(RemoteFailure.SERVICE), notices.map { it.failure })
        coVerify(exactly = 1) { client.connect() }
        vm.setVisible(false)
    }

    @Test fun protocolFailurePreservesDeviceHealthAndUsesProtocolNotice() = runTest(dispatcher) {
        every { client.events(any()) } returns flow { throw IllegalArgumentException("invalid page") }
        val vm = open()
        val notices = notices(vm)
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertEquals(RemoteFailure.PROTOCOL, notices.single().failure)
        assertNull(vm.state.value.runtime)
        vm.setVisible(false)
    }

    @Test fun trueOutageReportsOfflineThenShowsConnectingUntilNewSnapshot() = runTest(dispatcher) {
        val disconnect = CompletableDeferred<Unit>()
        val reconnected = CompletableDeferred<Unit>()
        var reads = 0
        every { client.events(any()) } answers { flow {
            if (++reads == 1) {
                emit(page); disconnect.await(); throw IOException("offline")
            }
            reconnected.await(); emit(page); awaitCancellation()
        } }
        val vm = open()
        val notices = notices(vm)
        coEvery { client.connect() } throws IOException("offline")
        disconnect.complete(Unit); runCurrent()
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        assertEquals(RemoteFailure.NETWORK, notices.single().failure)
        assertNull(vm.state.value.runtime)
        advanceTimeBy(3000); runCurrent()
        assertEquals(RemoteDeviceStatus.CONNECTING, vm.state.value.devices.single().status)
        reconnected.complete(Unit); runCurrent()
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertNull(vm.state.value.failure)
        assertEquals("idle", vm.state.value.runtime?.status)
        vm.setVisible(false)
    }

    @Test fun failedHealthCheckIsNotHiddenByTransientStreamGrace() = runTest(dispatcher) {
        val disconnect = CompletableDeferred<Unit>()
        every { client.events(any()) } returns flow { disconnect.await(); throw IOException("reset") }
        val vm = open()
        val notices = notices(vm)
        coEvery { client.connect() } throws FiloHttpException(503)
        disconnect.complete(Unit); runCurrent()
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        assertEquals(RemoteFailure.SERVICE, notices.single().failure)
        vm.setVisible(false)
    }

    @Test fun authenticationFailureRemainsImmediateAndCannotEnableControls() = runTest(dispatcher) {
        every { client.events(any()) } returns flow { throw FiloHttpException(401) }
        val vm = open()
        val notices = notices(vm)
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        assertEquals(RemoteFailure.AUTHENTICATION, notices.single().failure)
        assertFalse(vm.state.value.canEditSettings)
        vm.setVisible(false)
    }

    @Test fun staleHealthResultCannotOverwriteNewSelectionOrStartAnotherRead() = runTest(dispatcher) {
        val disconnect = CompletableDeferred<Unit>()
        val health = CompletableDeferred<String>()
        every { client.events(any()) } returns flow { disconnect.await(); throw IOException("reset") }
        val vm = open()
        val notices = notices(vm)
        coEvery { client.connect() } coAnswers { withContext(NonCancellable) { health.await() } }
        disconnect.complete(Unit); runCurrent()
        vm.setVisible(false)
        vm.selectSession(null)
        health.completeExceptionally(IOException("old read failed")); runCurrent()
        assertNull(vm.state.value.session)
        assertNull(vm.state.value.failure)
        assertTrue(notices.isEmpty())
        advanceTimeBy(6000); runCurrent()
        io.mockk.verify(exactly = 1) { client.events(any()) }
    }

    @Test fun successfulSnapshotEndsFailureEpisodeAndAllowsANewNotice() = runTest(dispatcher) {
        val failAgain = CompletableDeferred<Unit>()
        var reads = 0
        every { client.events(any()) } answers { flow {
            if (++reads == 2) { emit(page); failAgain.await() }
            throw FiloHttpException(503)
        } }
        val vm = open()
        val notices = notices(vm)
        assertEquals(1, notices.size)
        advanceTimeBy(3000); runCurrent()
        assertNull(vm.state.value.failure)
        failAgain.complete(Unit); runCurrent()
        assertEquals(2, notices.size)
        vm.setVisible(false)
    }

    @Test fun reconnectKeepsBoundedActivityPresentationWithoutRecoveringInternals() = runTest(dispatcher) {
        val activity = RemoteMessage("tool", "turn", null, "assistant", "", 1,
            RemoteActivity("tool", "failed", 30, label = "搜索网络", note = "网络请求失败"),
            groupId = "tool-group")
        val activityPage = bodyPage(listOf(activity), null, emptyList(), RemoteRuntime("idle"))
        var reads = 0
        every { client.events(any()) } answers { flow {
            if (++reads == 1) throw IOException("connection reset")
            emit(activityPage); awaitCancellation()
        } }
        val vm = open()
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, reads)
        val owner = vm.state.value.owner!!
        val group = vm.state.value.messageGroups.single()
        val segment = vm.cachedMessage(owner, group.stub.id)!!.segments!!.single()
        // Reconnected history replays the same bounded projection: label, state, note —
        // never a recovered tool name, argument, result, or host path.
        assertEquals("搜索网络", segment.toolDisplayName)
        assertEquals("网络请求失败", segment.toolNote)
        assertEquals("failed", segment.toolState)
        assertNull(segment.toolName)
        assertNull(segment.toolArgs)
        assertNull(segment.toolResult)
        vm.setVisible(false)
    }

    @Test fun historyIsReadableWhenStreamFailsButSameOwnersSnapshotIsAvailable() = runTest(dispatcher) {
        every { client.events(any()) } returns flow { throw IOException("stream unavailable") }
        val message = RemoteMessage("answer", "turn", null, "assistant", "Retained native history", 1)
        coEvery { client.conversation("session", null) } returns bodyPage(listOf(message), null, emptyList(), page.runtime)
        val vm = open()
        vm.state.first { it.nodes.isNotEmpty() }
        val notices = notices(vm)
        assertEquals(listOf("answer"), vm.state.value.nodes.map { it.id })
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.runtime)
        assertEquals("model", vm.state.value.selectedModel)
        assertFalse(vm.state.value.canEditSettings)
        assertTrue(notices.isEmpty())
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("answer"), vm.state.value.nodes.map { it.id })
        assertEquals(RemoteFailure.SERVICE, notices.single().failure)
        coVerify(exactly = 1) { client.connect() }
        coVerify(exactly = 0) { client.create(any(), any(), any(), any()); client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }
}
