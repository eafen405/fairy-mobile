package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 单账户生命周期：登录/注册即建立唯一连接，恢复后自动进入主会话。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteDeviceLifecycleTest : RemoteViewModelFixture() {
    @Test fun loginSavesCookieAndEntersMainSessionWithoutDirectory() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.login("http://computer/", "alice", "secret"); runCurrent()
        coVerify { client.login("alice", "secret") }
        coVerify { client.connect() }
        coVerify { connections.save(match {
            it.name == "user" && it.address == "http://computer/" && it.token == "cookie-value"
        }, null) }
        assertEquals("http://computer/", vm.state.value.deviceId)
        assertEquals(1, vm.state.value.devices.size)
        vm.selectSession(vm.state.value.sessions.single()); runCurrent()
        assertEquals(session, vm.state.value.session)
        vm.setVisible(false)
    }

    @Test fun inviteRegistrationUsesRegisterThenEntersMainSession() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.login("http://computer/", "alice", "secret", invite = "invite-42"); runCurrent()
        coVerify { client.register("alice", "secret", "invite-42") }
        coVerify(exactly = 0) { client.login(any(), any()) }
        vm.selectSession(vm.state.value.sessions.single()); runCurrent()
        assertEquals(session, vm.state.value.session)
        vm.setVisible(false)
    }

    @Test fun rejectedLoginStaysOnLoginPageWithoutSaving() = runTest(dispatcher) {
        coEvery { client.login(any(), any()) } throws FiloHttpException(401, "unauthorized")
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.login("http://computer/", "alice", "wrong"); runCurrent()
        assertTrue(vm.state.value.devices.isEmpty())
        assertNull(vm.state.value.session)
        assertEquals(RemoteFailure.AUTHENTICATION, vm.state.value.failure)
        coVerify(exactly = 0) { connections.save(any(), any()) }
        coVerify(exactly = 0) { client.connect() }
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(false)
    }

    @Test fun restoredCookieSelectsSoleConnectionAndEntersMainSession() = runTest(dispatcher) {
        coEvery { connections.load() } returns listOf(RemoteConnection("alice", "http://computer/", "cookie"))
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        assertFalse(vm.state.value.restoring)
        assertEquals(1, vm.state.value.devices.size)
        assertNull(vm.state.value.deviceId)
        vm.setVisible(true); runCurrent()
        vm.selectDevice(vm.state.value.devices.single().id); runCurrent()
        assertEquals("http://computer/", vm.state.value.deviceId)
        vm.selectSession(vm.state.value.sessions.single()); runCurrent()
        assertEquals(session, vm.state.value.session)
        vm.setVisible(false)
    }

    @Test fun expiredCookieClearsConnectionAndReturnsToLoginWithPrefill() = runTest(dispatcher) {
        coEvery { connections.load() } returns listOf(RemoteConnection("alice", "http://computer/", "expired"))
        coEvery { client.connect() } throws FiloHttpException(401, "unauthorized")
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); runCurrent()
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        assertTrue(vm.state.value.addingDevice)
        assertNull(vm.state.value.session)
        assertEquals("alice", vm.editorConnection()?.name)
        assertEquals("expired", vm.editorConnection()?.token)
        coVerify { connections.remove("http://computer/") }
        vm.setVisible(false)
    }

    @Test fun logoutExpiresConnectionAndReturnsToLogin() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); loginAndSelect(vm)
        vm.logout(); runCurrent()
        coVerify { client.logout() }
        coVerify { connections.remove("http://computer/") }
        assertEquals(1, vm.state.value.devices.size)
        assertTrue(vm.state.value.addingDevice)
        assertNull(vm.state.value.session)
        vm.setVisible(false)
    }

    @Test fun sendDuringActiveTurnAppendsToCurrentTurn() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        every { client.events(any()) } returns events
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { RemoteSendReceipt("turn", arg(2)) }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); runCurrent()
        events.emit(bodyPage(emptyList(), null, emptyList(), RemoteRuntime("active", "turn"))); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "follow-up"); vm.send(); runCurrent()
        coVerify { client.send("session", "follow-up", any(), emptyList()) }
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        vm.setVisible(false)
    }

    @Test fun echoedUserMessageWithoutClientIdStillConfirmsDelivery() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        every { client.events(any()) } returns events
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { RemoteSendReceipt("turn", arg(2)) }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.selectSession(session); runCurrent()
        val running = RemoteRuntime("active", "turn")
        events.emit(bodyPage(emptyList(), null, emptyList(), running)); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        // Fairy 页面里的用户消息不回显 clientId —— 按正文长度匹配确认送达。
        events.emit(bodyPage(listOf(RemoteMessage("e1", "turn", null, "user", "hello", 1)),
            null, emptyList(), running)); runCurrent()
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        vm.setVisible(false)
    }

    @Test fun stopWithoutActiveTurnIsANoOp() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        loginAndSelect(vm); vm.setVisible(true); runCurrent()
        vm.stop(); runCurrent()
        coVerify(exactly = 0) { client.stop(any(), any()) }
        vm.setVisible(false)
    }

    @Test fun failedSessionListKeepsLoginCredentialAndRetries() = runTest(dispatcher) {
        coEvery { connections.load() } returns listOf(RemoteConnection("alice", "http://computer/", "cookie"))
        coEvery { client.sessions(any()) } throws IOException("offline")
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice("http://computer/"); runCurrent()
        val notice = vm.notices.first()
        assertEquals("read_failed", notice.stage)
        assertTrue(notice.canRetryRead)
        assertEquals(1, vm.state.value.devices.size)
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        vm.retryNotice(notice); runCurrent()
        vm.selectSession(vm.state.value.sessions.single()); runCurrent()
        assertEquals(session, vm.state.value.session)
        assertNull(vm.state.value.failure)
        vm.setVisible(false)
    }

    @Test fun savedOfflineDeviceShowsConnectionFailureOnItsRow() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { connections.load() } returns listOf(RemoteConnection("alice", "http://computer/", "cookie"))
        coEvery { client.connect() } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); runCurrent()
        assertEquals(RemoteDeviceStatus.CONNECTING, vm.state.value.devices.single().status)
        gate.completeExceptionally(IOException("offline")); runCurrent()
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        assertEquals(RemoteFailure.NETWORK, vm.state.value.devices.single().failure)
        assertEquals(1, vm.state.value.devices.size)
        vm.setVisible(false)
    }
}
