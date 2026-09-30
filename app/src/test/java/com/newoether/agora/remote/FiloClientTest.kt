package com.newoether.agora.remote

import com.newoether.agora.model.Participant
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.chat.message.ToolPresentationResolver
import com.newoether.agora.ui.chat.message.ToolPresentationState
import com.newoether.agora.ui.chat.message.mergeAdjacentSegments
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.first

class FiloClientTest {
    @Test fun rapidSnapshotsConvergeToCompleteBodyWithoutWaitingForStreamClosure() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            try {
                repeat(400) { index ->
                    val page = RemoteConversationPage(listOf(RemoteMessage("a", "turn", null,
                        "assistant", "字".repeat(index + 1), 1)), null,
                        runtime = RemoteRuntime(if (index == 399) "idle" else "active", model = "fairy"))
                    exchange.responseBody.write("data: ${Json.encodeToString(page)}\n\n".toByteArray())
                }
                exchange.responseBody.flush()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val page = kotlinx.coroutines.withTimeout(10_000) {
                applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
                    .events(id).first { it.runtime?.status == "idle" }
            }
            assertEquals("字".repeat(400), page.messages.single().text)
        } finally { release.countDown(); server.stop(0) }
    }

    private val token = "a".repeat(64)
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test fun speechUsesAuthenticatedRawWavTransport() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        val wav = ByteArray(48) { it.toByte() }
        server.createContext("/api/mobile/v1/speech") { exchange ->
            assertEquals("$FAIRY_LOGIN_COOKIE=$token", exchange.requestHeaders.getFirst("Cookie"))
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val response = if (exchange.requestMethod == "GET") {
                """{"available":true,"maxDurationMs":60000}"""
            } else {
                assertEquals("audio/wav", exchange.requestHeaders.getFirst("Content-Type"))
                assertArrayEquals(wav, exchange.requestBody.readBytes())
                """{"text":"recognized"}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            assertTrue(client.speechAvailability().available)
            assertEquals("recognized", client.transcribeSpeech(wav))
            assertEquals(listOf("GET /api/mobile/v1/speech", "POST /api/mobile/v1/speech/transcriptions"), requests)
        } finally { server.stop(0) }
    }

    @Test fun speechUnavailablePreservesStableErrorCode() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/mobile/v1/speech/transcriptions") { exchange ->
            exchange.requestBody.close()
            val response = """{"error":"not configured","code":"speech_unavailable"}""".toByteArray()
            exchange.sendResponseHeaders(503, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            try { client.transcribeSpeech(ByteArray(48)); fail("Expected unavailable") }
            catch (error: FiloHttpException) {
                assertEquals(503, error.status)
                assertEquals("speech_unavailable", error.code)
            }
        } finally { server.stop(0) }
    }

    @Test fun sessionListDecodesLightweightStatusAndOldServerFallback() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/mobile/v1/sessions") { exchange ->
            assertEquals("$FAIRY_LOGIN_COOKIE=$token", exchange.requestHeaders.getFirst("Cookie"))
            val bytes = """{"sessions":[
                {"id":"active","title":"Task","cwd":"C:/work","updatedAt":2},
                {"id":"unknown","title":"Old","cwd":"C:/work","updatedAt":1}
            ],"nextCursor":null}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val page = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token).sessions()
            assertEquals(listOf("active", "unknown"), page.sessions.map { it.id })
        } finally { server.stop(0) }
    }

    @Test fun nativeProjectionKeepsIdentityAndReplacesEditedTail() {
        val user = RemoteMessage("u", "turn", "client", "user", "hello", 10)
        val answer = RemoteMessage("a", "turn", null, "assistant", "old", 10)
        val edited = answer.copy(text = "new")
        val messages = projectRemoteMessages(listOf(user, edited))
        assertEquals(listOf("u", "a"), messages.map { it.id })
        assertEquals("u", messages.last().parentId)
        assertEquals("turn", messages.last().runId)
        assertEquals(Participant.MODEL, messages.last().participant)
        assertEquals("new", messages.last().text)
    }

    @Test fun malformedEndpointIsRejectedBeforeNetwork() {
        listOf("http://user:secret@localhost/", "http://localhost/?token=x", "http://localhost/path", "broken").forEach {
            assertThrows(IllegalArgumentException::class.java) { applicationFixtureClient(it, token) }
        }
        // The credential is an opaque fairy_login cookie value — no format gate.
        applicationFixtureClient("http://localhost/", "opaque-cookie-value")
    }

    @Test fun onlyNativeActiveTurnOwnsSharedStreamingPresentation() {
        val user = RemoteMessage("u", "turn", "input", "user", "hello", 1)
        val idle = projectRemoteMessages(listOf(user), RemoteRuntime("idle", model = "model"))
        assertEquals(1, idle.size)
        val pending = projectRemoteMessages(listOf(user), RemoteRuntime("active", "turn", "model"))
        assertEquals(2, pending.size)
        assertEquals(Participant.MODEL, pending.last().participant)
        assertEquals(MessageStatus.SENDING, pending.last().status)
        assertEquals("turn", pending.last().runId)
        assertEquals("u", pending.last().parentId)
        val answer = RemoteMessage("a", "turn", null, "assistant", "Hello", 1)
        val live = projectRemoteMessages(listOf(user, answer), RemoteRuntime("active", "turn", "model"))
        assertEquals(listOf("u", "a"), live.map { it.id })
        assertTrue(com.newoether.agora.ui.chat.shouldShowStreamingTailIndicator(true, false, live.last()))
        val complete = projectRemoteMessages(listOf(user, answer), RemoteRuntime("idle", model = "model"))
        assertEquals(MessageStatus.SUCCESS, complete.last().status)
        assertFalse(com.newoether.agora.ui.chat.shouldShowStreamingTailIndicator(false, false, complete.last()))
        assertEquals(1, projectRemoteMessages(listOf(user), RemoteRuntime("active")).size)
    }

    @Test fun userAndTurnBoundariesRemainHardEvenForActivityOnlyMessages() {
        val records = listOf(
            RemoteMessage("r", "turn1", null, "assistant", "", 1,
                RemoteActivity("tool", "succeeded", label = "搜索网络")),
            RemoteMessage("u", "turn1", null, "user", "Interrupt", 2),
            RemoteMessage("t", "turn1", null, "assistant", "", 3,
                RemoteActivity("tool", state = "stopped", label = "整理文件", note = "已被手动停止")),
            RemoteMessage("a", "turn2", null, "assistant", "Next turn", 4),
        )
        val projected = projectRemoteMessages(records)
        assertEquals(listOf("r", "u", "t", "a"), projected.map { it.id })
        assertEquals(listOf(null, "r", "u", "t"), projected.map { it.parentId })
        assertEquals("", projected.first().text)
        assertNull(projected[2].thoughtTimeMs)
        val stopped = ToolPresentationResolver.resolve(projected[2].segments!!.single())
        assertEquals(ToolPresentationState.STOPPED, stopped.state)
        assertEquals("已被手动停止", stopped.errorMessage)
    }

    @Test fun blankSummariesDoNotCreateEmptyThoughtBlocksOrFakeDurations() {
        val summary = RemoteMessage("r", "turn", null, "assistant", " ", 10, RemoteActivity("thought"))
        assertTrue(projectRemoteMessages(listOf(summary)).isEmpty())
        val answer = RemoteMessage("a", "turn", null, "assistant", "Answer", 11)
        val projected = projectRemoteMessages(listOf(summary, answer)).single()
        assertEquals(listOf("answer"), mergeAdjacentSegments(projected.segments!!).map { it.type })
        assertNull(projected.thoughtTimeMs)
    }

    @Test fun failuresDistinguishTransportAuthenticationAndProtocol() {
        assertEquals(RemoteFailure.NETWORK, classifyRemoteFailure(IOException("unreachable")))
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(FiloHttpException(401)))
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(FiloHttpException(403)))
        assertEquals(RemoteFailure.SERVICE, classifyRemoteFailure(FiloHttpException(502)))
        assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(SerializationException("payload")))
        assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(IllegalArgumentException("incompatible")))
        assertEquals(RemoteFailure.STORAGE, classifyRemoteFailure(RemoteStorageException()))
        val invalid = assertThrows(FiloConfigurationException::class.java) { applicationFixtureClient("broken", token) }
        assertEquals(RemoteFailure.CONFIGURATION, classifyRemoteFailure(invalid))
    }

    @Test fun authenticatedDirectSendUsesExactSessionAndDoesNotFollowRedirects() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        var auth = ""
        var path = ""
        var body = ""
        var query: String? = null
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            auth = exchange.requestHeaders.getFirst("Cookie")
            path = exchange.requestURI.path
            query = exchange.requestURI.query
            body = exchange.requestBody.reader().readText()
            exchange.responseHeaders.add("Location", "/must-not-retry")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            try { client.send(id, "hello", id); fail("Redirect must fail") }
            catch (error: FiloHttpException) { assertEquals(307, error.status) }
            assertEquals(1, requests.get())
            assertEquals("$FAIRY_LOGIN_COOKIE=$token", auth)
            assertEquals("/api/mobile/v1/sessions/$id/messages", path)
            assertNull(query)
            assertTrue(body.contains("\"text\":\"hello\""))
            assertTrue(body.contains("\"clientId\":\"$id\""))
        } finally { server.stop(0) }
    }

    @Test fun connectionAcceptsTheFairyGatewayAndRejectsRetiredOrUnknownModes() = runBlocking {
        for (mode in listOf("existing", "standalone", "unsupported")) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/mobile/v1/info") { exchange ->
                val value = """{"protocolVersion":2,"agent":"fairy","sessionMode":"$mode","messageDelivery":"native-steer","outputMode":"live-messages","supportsLazyMessages":true,"device":"fairy"}""".toByteArray()
                exchange.sendResponseHeaders(200, value.size.toLong())
                exchange.responseBody.use { it.write(value) }
            }
            server.start()
            try {
                val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
                if (mode != "existing") {
                    try { client.connect(); fail("Retired or unknown mode must be rejected") }
                    catch (_: IllegalArgumentException) { }
                } else assertEquals("fairy", client.connect())
            } finally { server.stop(0) }
        }
    }

    @Test fun loginCapturesFairyCookieAndAuthorizesFollowingRequests() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val cookies = mutableListOf<String?>()
        server.createContext("/") { exchange ->
            cookies += exchange.requestHeaders.getFirst("Cookie")
            val value = when (exchange.requestURI.path) {
                "/api/login" -> {
                    exchange.responseHeaders.add("Set-Cookie", "$FAIRY_LOGIN_COOKIE=session-cookie; HttpOnly; Path=/")
                    """{"username":"alice"}"""
                }
                "/api/mobile/v1/info" -> """{"protocolVersion":2,"agent":"fairy","sessionMode":"existing","messageDelivery":"native-steer","outputMode":"live-messages","supportsLazyMessages":true,"device":"fairy"}"""
                else -> """{"code":"not_found","error":"missing"}"""
            }.toByteArray()
            if (exchange.requestURI.path == "/api/mobile/v1/info") {
                assertEquals("$FAIRY_LOGIN_COOKIE=session-cookie", cookies.last())
            }
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/api/login") 200 else 200, value.size.toLong())
            exchange.responseBody.use { it.write(value) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", "")
            assertEquals("alice", client.login("alice", "pw"))
            assertEquals("session-cookie", client.sessionCredential)
            assertEquals("fairy", client.connect())
            assertNull(cookies.first())
        } finally { server.stop(0) }
    }

    @Test fun protocolTwoAndNativeReceiptAndEventStreamUseIndependentAuthenticatedTransport() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val auth = mutableListOf<String>()
        val page = RemoteConversationPage(listOf(RemoteMessage("native-user", "turn", id, "user", "hello", 1)),
            null, RemoteRuntime("active", "turn", "model", 42))
        server.createContext("/") { exchange ->
            auth += exchange.requestHeaders.getFirst("Cookie")
            val value = when (exchange.requestURI.path) {
                "/api/mobile/v1/info" -> """{"protocolVersion":2,"agent":"fairy","sessionMode":"existing","messageDelivery":"native-steer","outputMode":"live-messages","supportsLazyMessages":true,"device":"fairy"}"""
                "/api/mobile/v1/sessions/$id/messages" -> """{"turnId":"turn","clientId":"$id"}"""
                else -> "data: ${Json.encodeToString(page)}\n\n"
            }.toByteArray()
            exchange.sendResponseHeaders(200, value.size.toLong())
            exchange.responseBody.use { it.write(value) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            assertEquals("fairy", client.connect())
            assertEquals("turn", client.send(id, "hello", id).turnId)
            assertEquals(page, client.events(id).first())
            assertEquals(List(3) { "$FAIRY_LOGIN_COOKIE=$token" }, auth)
        } finally { server.stop(0) }
    }
    @Test fun activeTurnCannotShowAssistantIndicatorBeforeItsNativeUserMessage() {
        val runtime = RemoteRuntime("active", "new-turn", "model")
        assertTrue(projectRemoteMessages(emptyList(), runtime).isEmpty())
        val oldUser = RemoteMessage("old-user", "old-turn", null, "user", "old", 1)
        assertEquals(listOf("old-user"), projectRemoteMessages(listOf(oldUser), runtime).map { it.id })
        val user = RemoteMessage("new-user", "new-turn", id, "user", "new", 2)
        val visible = projectRemoteMessages(listOf(oldUser, user), runtime)
        assertEquals(listOf("old-user", "new-user", "remote-active-new-turn"), visible.map { it.id })
        assertEquals("new-user", visible.last().parentId)
    }

    @Test fun presentationRemovesOnlyTerminalLineBreaksAndPreservesNativeRecords() {
        val source = "  first  \n    code\nlast  \r\n"
        val user = RemoteMessage("u", "t", null, "user", "  input\nline  \r\n", 1)
        val answer = RemoteMessage("a", "t", null, "assistant", source, 2)
        val literal = RemoteMessage("b", "t2", null, "assistant", "literal\\n", 3)
        val projected = projectRemoteMessages(listOf(user, answer, literal))
        assertEquals("  input\nline  ", projected[0].text)
        assertEquals(source.trimEnd('\r', '\n'), projected[1].text)
        assertEquals(source.trimEnd('\r', '\n'), projected[1].segments!!.single().content)
        assertEquals("literal\\n", projected[2].text)
        assertTrue(user.text.endsWith("\r\n"))
        assertEquals(source, answer.text)
    }

    @Test fun unnamedSessionUsesLocalizedNewChatWithoutChangingNativeId() {
        val unnamed = RemoteSession("native-id", "native-id", "/workspace", 0)
        assertEquals("New Chat", unnamed.displayTitle("New Chat"))
        assertEquals("native-id", unnamed.id)
        assertEquals("Named task", unnamed.copy(title = "Named task").displayTitle("New Chat"))
    }

    @Test fun nativeStreamingTailUsesOriginalThoughtToolAndAnswerLifecycle() {
        val user = RemoteMessage("u", "turn", null, "user", "go", 1)
        val thought = RemoteMessage("r", "turn", null, "assistant", "Thinking", 2, RemoteActivity("thought"))
        val tool = RemoteMessage("t", "turn", null, "assistant", "", 3,
            RemoteActivity("tool", "running", label = "执行命令"))
        val answer = RemoteMessage("a", "turn", null, "assistant", "Hello", 4)
        val active = RemoteRuntime("active", "turn", "model")
        // A thought record renders no message at all; the run-state placeholder card
        // is the only "思考中" indicator — no reasoning text is ever shown.
        assertEquals(MessageStatus.SENDING, projectRemoteMessages(listOf(user, thought), active).last().status)
        assertEquals(MessageStatus.TOOL_CALLING, projectRemoteMessages(listOf(user, thought, tool), active).last().status)
        val streaming = projectRemoteMessages(listOf(user, thought, tool, answer), active).last()
        val grown = projectRemoteMessages(listOf(user, thought, tool, answer.copy(text = "Hello world")), active).last()
        assertEquals(streaming.id, grown.id)
        assertEquals(MessageStatus.SENDING, grown.status)
        assertEquals("Hello world", grown.text)
        assertEquals(MessageStatus.SUCCESS,
            projectRemoteMessages(listOf(user, thought, tool, answer), RemoteRuntime("idle")).last().status)
    }

}
