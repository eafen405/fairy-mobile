package com.newoether.agora.remote

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGroupPaginationTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>()
    private val connections = mockk<RemoteConnectionStore>(relaxed = true)
    private val session = RemoteSession("history", "History", "/workspace", 1)
    private fun tool(index: Int) = RemoteMessage("tool-$index", "turn", null, "assistant", "", 1,
        activity = RemoteActivity("tool", state = "succeeded", label = "执行命令"))
    private fun packet(range: IntRange, next: String?, continuation: String?, bookmark: String) =
        bodyPage(range.map(::tool), next).copy(continuationCursor = continuation, pageCursor = bookmark)

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
        every { client.events(any()) } answers {
            val id = firstArg<String>()
            flow { emit(client.conversation(id)); awaitCancellation() }
        }
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.open(): RemoteViewModel {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent()
        vm.setVisible(true)
        vm.login("http://computer/", "user", "pass"); runCurrent()
        vm.selectDevice("http://computer/"); runCurrent()
        vm.selectSession(session); runCurrent()
        return vm
    }

    @Test fun initialPacketDisplaysImmediatelyAndIgnoresLegacyGroupContinuationUntilPaging() = runTest(dispatcher) {
        val prefix = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("history", null) } returns packet(128..255, "prefix", "prefix", "tail-bookmark")
        coEvery { client.conversation("history", "prefix") } coAnswers { prefix.await() }
        val vm = open()
        assertFalse(vm.state.value.loading)
        assertEquals((128..255).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        val tail = vm.state.value.messageGroups.single()
        assertEquals("prefix", vm.state.value.historyCursor)
        coVerify(exactly = 0) { client.conversation("history", "prefix") }
        vm.loadMore(); runCurrent()
        assertTrue(vm.state.value.loadingMore)
        assertEquals(listOf(tail), vm.state.value.messageGroups)
        prefix.complete(packet(0..127, "earlier-answer", null, "prefix-bookmark")); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        assertEquals((0..255).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        assertEquals("earlier-answer", vm.state.value.historyCursor)
        assertEquals(tail, vm.state.value.messageGroups.last())
        assertEquals(128, vm.cachedMessage(vm.state.value.owner!!, tail.stub.id)!!.segments!!.size)
        assertTrue(vm.state.value.nodes.take(128).all { it.pageCursor == "prefix-bookmark" })
        assertTrue(vm.state.value.nodes.drop(128).all { it.pageCursor == "tail-bookmark" })
        coVerify(exactly = 0) { client.conversation("history", "earlier-answer") }
        vm.setVisible(false)
    }

    @Test fun shortToolOnlyLatestPageDoesNotFetchAnyOlderPageBeforeUpwardPaging() = runTest(dispatcher) {
        coEvery { client.conversation("history", null) } returns packet(80..95, "older", null, "tail")
        coEvery { client.conversation("history", "older") } returns packet(64..79, "earlier", null, "older")
        val vm = open()
        assertFalse(vm.state.value.loading)
        assertEquals((80..95).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        assertEquals("older", vm.state.value.historyCursor)
        coVerify(exactly = 0) { client.conversation("history", "older") }
        vm.loadMore(); runCurrent()
        assertEquals((64..95).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        coVerify(exactly = 1) { client.conversation("history", "older") }
        coVerify(exactly = 0) { client.conversation("history", "earlier") }
        vm.setVisible(false)
    }

    @Test fun olderPacketsPublishSeparatelyAndPreserveTheExistingAnswerDuringADeferredLoad() = runTest(dispatcher) {
        val answer = RemoteMessage("answer", "turn", null, "assistant", "Answer", 2)
        coEvery { client.conversation("history", null) } returns bodyPage(listOf(answer), "older")
        coEvery { client.conversation("history", "older") } returns packet(128..255, "prefix", "prefix", "tail-bookmark")
        val prefix = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("history", "prefix") } coAnswers { prefix.await() }
        val vm = open()
        vm.state.first { it.messageGroups.isNotEmpty() }
        val existing = vm.state.value.messageGroups.single()
        vm.loadMore(); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        assertEquals(existing, vm.state.value.messageGroups.last())
        assertEquals(128, vm.state.value.messageGroups.first().nodes.size)
        assertEquals("prefix", vm.state.value.historyCursor)
        coVerify(exactly = 0) { client.conversation("history", "prefix") }
        val published = vm.state.value.messageGroups
        vm.loadMore(); runCurrent()
        assertTrue(vm.state.value.loadingMore)
        assertEquals(published, vm.state.value.messageGroups)
        prefix.complete(packet(0..127, null, null, "prefix-bookmark")); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        assertNull(vm.state.value.historyCursor)
        assertEquals(existing, vm.state.value.messageGroups.last())
        assertEquals(128, vm.state.value.messageGroups.first().nodes.size)
        assertEquals(3, vm.state.value.messageGroups.size)
        vm.setVisible(false)
    }

    @Test fun activeReconnectBridgesSettledMessagesBeforeReplacingTemporaryNodes() = runTest(dispatcher) {
        val active = RemoteRuntime("active", activeTurnId = "turn", model = "fairy")
        val user = RemoteMessage("live-u", "turn", null, "user", "hello", 1)
        val reply = RemoteMessage("live-a", "turn", null, "assistant", "same reply", 2)
        val activity = tool(1).copy(id = "live-tool-c1")
        val suffix = reply.copy(id = "live-b", timestamp = 3)
        coEvery { client.conversation("history", null) } returns
            bodyPage(listOf(user, reply, activity, suffix), null, active)
        val vm = open()
        assertEquals(listOf("live-u", "live-a", "live-tool-c1", "live-b"), vm.state.value.nodes.map { it.id })

        val persistedUser = user.copy(id = "e1")
        val persistedReply = reply.copy(id = "e2")
        val persistedActivity = activity.copy(id = "e3")
        coEvery { client.conversation("history", null) } returns
            bodyPage(listOf(persistedActivity, suffix), "before:e3", active)
        coEvery { client.conversation("history", "before:e3") } returns
            bodyPage(listOf(persistedUser, persistedReply), null, active)
        vm.refresh(); runCurrent()
        assertEquals(listOf("e1", "e2", "e3", "live-b"), vm.state.value.nodes.map { it.id })
        coVerify(exactly = 1) { client.conversation("history", "before:e3") }

        coEvery { client.conversation("history", null) } returns bodyPage(
            listOf(persistedUser, persistedReply, persistedActivity, suffix.copy(id = "e4")), null,
            RemoteRuntime("idle", completedTurnId = "turn"))
        vm.refresh(); runCurrent()
        assertEquals(listOf("e1", "e2", "e3", "e4"), vm.state.value.nodes.map { it.id })
        val owner = vm.state.value.owner!!
        val answer = vm.state.value.messageGroups.last()
        assertEquals(2, vm.cachedMessage(owner, answer.stub.id)!!.segments!!.count { it.content == "same reply" })
        vm.setVisible(false)
    }

    @Test fun omittedAnchorBridgesSettledBodiesOnceAndKeepsLaterLiveUpdatesLocal() = runTest(dispatcher) {
        val pages = MutableSharedFlow<RemoteConversationPage>(replay = 1)
        val prior = RemoteMessage("e0", "old-turn", null, "assistant", "older", 1)
        pages.tryEmit(bodyPage(listOf(prior), null))
        every { client.events(any()) } returns pages
        val vm = open()
        val active = RemoteRuntime("active", activeTurnId = "turn", model = "fairy")
        val user = RemoteMessage("live-u", "turn", null, "user", "hello", 2)
        val longReply = RemoteMessage("live-a", "turn", null, "assistant", "x".repeat(5000), 3)
        val suffix = RemoteMessage("live-b", "turn", null, "assistant", "next", 4)
        pages.emit(bodyPage(listOf(prior, user, longReply, suffix), null, active)); runCurrent()

        val persistedUser = user.copy(id = "e1")
        val persistedReply = longReply.copy(id = "e2")
        coEvery { client.conversation("history", "at:e2") } returns
            bodyPage(listOf(persistedReply), "before:e2", active)
        coEvery { client.conversation("history", "before:e2") } returns
            bodyPage(listOf(prior, persistedUser), null, active)
        pages.emit(bodyPage(listOf(suffix), "at:e2", active)); runCurrent()
        assertEquals(listOf("e0", "e1", "e2", "live-b"), vm.state.value.nodes.map { it.id })

        pages.emit(bodyPage(listOf(suffix.copy(text = "next updated")), "at:e2", active)); runCurrent()
        assertEquals(listOf("e0", "e1", "e2", "live-b"), vm.state.value.nodes.map { it.id })
        coVerify(exactly = 1) { client.conversation("history", "at:e2") }
        coVerify(exactly = 1) { client.conversation("history", "before:e2") }
        val owner = vm.state.value.owner!!
        val answer = vm.state.value.messageGroups.last()
        assertTrue(vm.cachedMessage(owner, answer.stub.id)!!.text.contains("x".repeat(5000)))
        assertTrue(vm.cachedMessage(owner, answer.stub.id)!!.text.contains("next updated"))
        vm.setVisible(false)
    }
}
