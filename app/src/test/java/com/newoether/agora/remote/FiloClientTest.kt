package com.newoether.agora.remote

import com.newoether.agora.model.Participant
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.chat.message.ToolKind
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
    private val token = "a".repeat(64)
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test fun archiveUsesAuthenticatedNativeReceiptAndRejectsLegacyDeleteSuccess() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        var reply = """{"archived":true,"cwd":"C:/project"}"""
        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            assertEquals("$FAIRY_LOGIN_COOKIE=$token", exchange.requestHeaders.getFirst("Cookie"))
            assertEquals("{}", exchange.requestBody.reader().readText())
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            client.archiveSession(id)
            reply = """{"deleted":true}"""
            try { client.archiveSession(id); fail("Legacy deletion is not an archive receipt") }
            catch (_: IllegalArgumentException) { }
            assertEquals(List(2) { "POST /api/mobile/v1/sessions/$id/archive" }, requests)
        } finally { server.stop(0) }
    }

    @Test fun sessionListDecodesLightweightStatusAndOldServerFallback() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/mobile/v1/sessions") { exchange ->
            assertEquals("$FAIRY_LOGIN_COOKIE=$token", exchange.requestHeaders.getFirst("Cookie"))
            val bytes = """{"sessions":[
                {"id":"active","title":"Task","cwd":"C:/work","updatedAt":2,"status":"active"},
                {"id":"unknown","title":"Old","cwd":"C:/work","updatedAt":1}
            ],"nextCursor":null}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val page = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token).sessions()
            assertEquals(listOf("active", "unknown"), page.sessions.map { it.id })
            assertEquals(listOf("active", null), page.sessions.map { it.status })
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

    @Test fun nativeActivityUsesSharedSegmentsAndKeepsInterleaving() {
        val records = listOf(
            RemoteMessage("u", "turn", null, "user", "hello", 10),
            RemoteMessage("r", "turn", null, "assistant", "Public summary", 11, RemoteActivity("thought")),
            RemoteMessage("a", "turn", null, "assistant", "Checking", 12),
            RemoteMessage("t", "turn", null, "assistant", "", 13,
                RemoteActivity("tool", "succeeded", 30, label = "执行命令")),
            RemoteMessage("r2", "turn", null, "assistant", "Result summary", 14, RemoteActivity("thought")),
            RemoteMessage("a2", "turn", null, "assistant", "Done", 15),
            RemoteMessage("a3", "turn", null, "assistant", "Details", 16),
        )
        val projected = projectRemoteMessages(records)
        assertEquals(listOf("u", "r"), projected.map { it.id })
        val answer = projected.last()
        assertEquals("u", answer.parentId)
        assertEquals("turn", answer.runId)
        assertEquals("Checking\n\nDone\n\nDetails", answer.text)
        assertNull(answer.thoughtTimeMs)
        assertEquals(MessageStatus.SUCCESS, answer.status)
        val segments = mergeAdjacentSegments(answer.segments!!)
        // Thought records carry no visible content across the client feedback boundary.
        assertEquals(listOf("answer", "tool", "answer"), segments.map { it.type })
        assertEquals("Done\n\nDetails", segments.last().content)
        val tool = segments[1]
        assertEquals("t", tool.toolCallId)
        assertEquals(30L, tool.durationMs)
        assertEquals("执行命令", tool.toolDisplayName)
        assertNull(tool.toolName)
        val presentation = ToolPresentationResolver.resolve(tool)
        assertEquals(ToolKind.UNKNOWN, presentation.kind)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertNull(presentation.exitCode)
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

    @Test fun refreshedToolPayloadKeepsSheetIdentityAndRecordedLifecycle() {
        val summary = RemoteMessage("r", "turn", null, "assistant", "Checking", 10, RemoteActivity("thought"))
        val tool = RemoteMessage("t", "turn", null, "assistant", "", 10,
            RemoteActivity("tool", "running", label = "执行命令"))
        val before = projectRemoteMessages(listOf(summary, tool)).single()
        val running = before.segments!!.single()
        assertNull(running.toolResult)
        assertNull(running.toolProgress)
        assertEquals(ToolPresentationState.RUNNING, ToolPresentationResolver.resolve(running).state)
        val finished = tool.copy(activity = tool.activity!!.copy(
            state = "failed", durationMs = 55, note = "命令超时"))
        val refreshed = listOf(summary, finished,
            RemoteMessage("a", "turn", null, "assistant", "Reported", 11))
        val after = projectRemoteMessages(refreshed).single()
        assertEquals(before.id, after.id)
        val terminal = after.segments!!.first { it.type == "tool" }
        assertEquals(running.toolCallId, terminal.toolCallId)
        assertNull(terminal.toolProgress)
        assertEquals(55L, terminal.durationMs)
        val presentation = ToolPresentationResolver.resolve(terminal)
        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("命令超时", presentation.errorMessage)
        assertEquals("Reported", after.text)
        val older = RemoteMessage("older", "previous", null, "user", "Earlier", 1)
        assertEquals(after.id, projectRemoteMessages(listOf(older) + refreshed).last().id)
    }

    @Test fun blankSummariesDoNotCreateEmptyThoughtBlocksOrFakeDurations() {
        val summary = RemoteMessage("r", "turn", null, "assistant", " ", 10, RemoteActivity("thought"))
        assertTrue(projectRemoteMessages(listOf(summary)).isEmpty())
        val answer = RemoteMessage("a", "turn", null, "assistant", "Answer", 11)
        val projected = projectRemoteMessages(listOf(summary, answer)).single()
        assertEquals(listOf("answer"), mergeAdjacentSegments(projected.segments!!).map { it.type })
        assertNull(projected.thoughtTimeMs)
    }

    @Test fun historyOptsIntoActivityAndBoundedPayloadsNeverSurfaceInternals() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val queries = mutableListOf<String>()
        val plain = """{"id":"a","turnId":"turn","clientId":null,"role":"assistant","text":"Answer","timestamp":10}"""
        // The bounded wire carries only label/state/duration — no tool internals.
        val bounded = """{"id":"t","turnId":"turn","clientId":null,"role":"assistant","text":"",
            "timestamp":11,"activity":{"type":"tool","state":"succeeded","durationMs":30,"label":"搜索网络"}}"""
        // A pre-boundary (or hostile) payload may still smuggle internals; decode drops them.
        val internals = """{"id":"o","turnId":"turn","clientId":null,"role":"assistant","text":"",
            "timestamp":12,"activity":{"type":"tool","state":"failed","durationMs":4,"label":"搜索网络",
            "note":"网络请求失败","toolName":"mcp/tools","arguments":"{\"command\":\"rm -rf /\"}",
            "result":"{\"structuredContent\":{\"count\":2}}","imagePath":"C:/private.png"}}"""
        // An old payload without label/note still renders — with the generic fallback.
        val legacyShape = """{"id":"l","turnId":"turn","clientId":null,"role":"assistant","text":"",
            "timestamp":13,"activity":{"type":"tool","toolName":"view_image","state":"succeeded",
            "imagePath":"C:/old.png"}}"""
        server.createContext("/api/mobile/v1/sessions/$id") { exchange ->
            queries.add(exchange.requestURI.query)
            val message = listOf(plain, bounded, internals, legacyShape)[queries.size - 1]
            val bytes = """{"messages":[$message],"nextCursor":null,"queued":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            assertNull(client.conversation(id).messages.single().activity)
            val loaded = client.conversation(id, "cursor + next").messages.single()
            assertEquals(RemoteActivity("tool", "succeeded", 30, label = "搜索网络"), loaded.activity)
            assertEquals("includeActivity=true&includeMetadata=true", queries.first())
            assertEquals("cursor=cursor + next&includeActivity=true&includeMetadata=true", queries[1])
            val tool = projectRemoteMessages(listOf(loaded)).single().segments!!.single()
            assertEquals("搜索网络", tool.toolDisplayName)
            assertNull(tool.toolName)
            assertNull(ToolPresentationResolver.resolve(tool).rawResult)
            // Internals present on the wire are dropped: nothing raw reaches the model or UI.
            val smuggled = client.conversation(id, "cursor").messages.single()
            val segment = projectRemoteMessages(listOf(smuggled)).single().segments!!.single()
            assertEquals("搜索网络", segment.toolDisplayName)
            assertEquals("网络请求失败", segment.toolNote)
            assertNull(segment.toolName)
            assertNull(segment.toolArgs)
            assertNull(segment.toolResult)
            assertNull(segment.toolProgress)
            assertNull(segment.toolResultText)
            assertNull(segment.toolStructuredResult)
            val presentation = ToolPresentationResolver.resolve(segment)
            assertEquals(ToolPresentationState.FAILED, presentation.state)
            assertEquals("网络请求失败", presentation.errorMessage)
            assertNull(presentation.rawArguments)
            assertNull(presentation.rawResult)
            assertNull(presentation.liveOutput)
            // An old payload carries internals but no label/note: still renders as a
            // generic completed activity chip with nothing internal exposed.
            val old = client.conversation(id, "last").messages.single()
            val oldSegment = projectRemoteMessages(listOf(old)).single().segments!!.single()
            assertNull(oldSegment.toolDisplayName)
            assertNull(oldSegment.toolNote)
            assertNull(oldSegment.toolName)
            val oldPresentation = ToolPresentationResolver.resolve(oldSegment)
            assertEquals(ToolPresentationState.COMPLETED, oldPresentation.state)
            assertNull(oldPresentation.rawResult)
        } finally { server.stop(0) }
    }

    @Serializable
    private data class LegacyActivity(
        val type: String, val toolName: String? = null, val arguments: String? = null,
        val result: String? = null, val state: String? = null, val durationMs: Long? = null,
        val imagePath: String? = null)
    @Serializable
    private data class LegacyMessage(
        val id: String, val turnId: String, val clientId: String?, val role: String,
        val text: String, val timestamp: Long, val activity: LegacyActivity? = null,
        val groupId: String? = null,
        val nativeId: String? = null, val textOffset: Int = 0, val textContinues: Boolean = false,
        val imageLinks: List<String> = emptyList(), val error: Boolean = false)
    @Serializable
    private data class LegacyQueuedMessage(val id: String, val clientId: String, val text: String)
    @Serializable
    private data class LegacyRuntime(
        val status: String, val activeTurnId: String? = null, val model: String? = null,
        val contextTokens: Int? = null, val contextWindow: Int? = null,
        val completedTurnId: String? = null,
        val effort: String? = null, val serviceTier: String? = null,
        val serviceTierKnown: Boolean = false, val activeTurnHasUserMessage: Boolean = false)
    @Serializable
    private data class LegacyNodeActivity(
        val type: String, val state: String? = null, val durationMs: Long? = null,
        val hasImage: Boolean = false)
    @Serializable
    private data class LegacyNode(
        val id: String, val turnId: String, val clientId: String?, val role: String, val timestamp: Long,
        val revision: String, val textLength: Int,
        val groupId: String? = null, val nativeId: String? = null,
        val textOffset: Int = 0, val textContinues: Boolean = false,
        val activity: LegacyNodeActivity? = null, val hasContent: Boolean? = null,
        val imageCount: Int = 0, val error: Boolean = false)
    @Serializable
    private data class LegacyPage(
        val messages: List<LegacyMessage>, val nextCursor: String?,
        val queued: List<LegacyQueuedMessage>, val runtime: LegacyRuntime? = null,
        val pageCursor: String? = null, val continuationCursor: String? = null,
        val nodes: List<LegacyNode> = emptyList())

    private fun legacyValidate(page: LegacyPage) {
        require(page.messages.all { it.role == "user" || it.role == "assistant" })
        require(page.messages.map { it.id }.toSet().size == page.messages.size)
        page.messages.forEach { message -> message.activity?.let { activity ->
            require(message.role == "assistant" && activity.type in setOf("thought", "tool"))
            require(activity.type != "tool" || !activity.toolName.isNullOrBlank())
            require(activity.durationMs == null || activity.durationMs >= 0)
            require(activity.state == null || activity.state in setOf("running", "succeeded", "failed", "stopped"))
        } }
    }

    @Test fun preBoundaryDecoderReadsGeneratedFairybotPageWithSafeToolNameAliases() {
        val json = Json { ignoreUnknownKeys = true }
        val fixture = requireNotNull(javaClass.getResource("/remote/client-boundary-page.json"))
            .readText()
        val payload = json.parseToJsonElement(fixture).jsonObject.getValue("page").toString()
        val decoded = json.decodeFromString<LegacyPage>(payload)
        legacyValidate(decoded)
        assertEquals("Hello Fairy", decoded.messages.first().text)
        assertEquals("Hello back", decoded.messages.last().text)
        assertEquals("idle", decoded.runtime?.status)
        assertEquals("turn-1", decoded.runtime?.completedTurnId)
        val activities = decoded.messages.mapNotNull { it.activity }
        assertEquals(listOf("检索记忆", "一项操作", "整理待跟进事项"), activities.map { it.toolName })
        assertEquals(listOf("succeeded", "failed", "stopped"), activities.map { it.state })
        assertEquals(listOf(1L, 1L, null), activities.map { it.durationMs })
        activities.forEach { activity ->
            assertEquals("tool", activity.type)
            assertNull(activity.arguments)
            assertNull(activity.result)
            assertNull(activity.imagePath)
        }
        val current = json.decodeFromString<RemoteConversationPage>(payload)
        assertEquals(activities.map { it.toolName }, current.messages.mapNotNull { it.activity?.label })
        assertEquals("操作未成功", current.messages.mapNotNull { it.activity }.single { it.state == "failed" }.note)
        assertEquals(decoded.messages.map { it.id }, decoded.nodes.map { it.id })
        decoded.nodes.zip(decoded.messages).forEach { (node, message) ->
            assertTrue(node.revision.isNotBlank())
            assertEquals(message.turnId, node.turnId)
            assertEquals(message.text.length, node.textLength)
            assertEquals(message.activity?.state, node.activity?.state)
            assertEquals(message.activity?.durationMs, node.activity?.durationMs)
            assertEquals(true, node.hasContent)
            assertEquals(0, node.imageCount)
            assertFalse(node.activity?.hasImage == true)
        }
        val wirePage = json.parseToJsonElement(payload).jsonObject
        val wireMessages = json.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(
            wirePage.getValue("messages").toString())
        wireMessages.mapNotNull { it["activity"]?.jsonObject }.forEach { activity ->
            assertEquals(activity.getValue("label"), activity.getValue("toolName"))
        }
        listOf("PRIVATE_", "/host/private", "memory_search", "open_thread").forEach { marker ->
            assertTrue(fixture.contains(marker))
            assertFalse(payload.contains(marker))
        }
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

    @Test fun authenticatedDirectCreationCarriesTheFirstMessageAndSettingsAndNeverCreatesEmpty() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var method = ""
        var path = ""
        var body = ""
        server.createContext("/api/mobile/v1/sessions") { exchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
            body = exchange.requestBody.reader().readText()
            val bytes = """{"id":"$id","title":"New","cwd":"C:/work","updatedAt":2,"turnId":"turn-1","clientId":"$id"}""".toByteArray()
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            val created = client.create("hello", id, settings = RemoteSettings(model = "chosen", updateServiceTier = true))
            assertEquals("POST", method)
            assertEquals("/api/mobile/v1/sessions", path)
            assertEquals(id, created.session.id)
            assertTrue(body.contains("\"text\":\"hello\""))
            assertTrue(body.contains("\"clientId\":\"$id\""))
            assertTrue(body.contains("\"attachments\":[]"))
            assertTrue(body.contains("\"settings\":{\"model\":\"chosen\",\"serviceTier\":null}"))
            try { client.create("", "11111111-1111-4111-8111-111111111111"); fail("Blank first message must be refused") }
            catch (_: FiloInputException) { }
            try { client.create("hi", "11111111-1111-4111-8111-111111111111", settings = RemoteSettings()); fail("Settings without a model must be refused") }
            catch (_: FiloInputException) { }
        } finally { server.stop(0) }
        Unit
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
            null, emptyList(), RemoteRuntime("active", "turn", "model", 42, 256000))
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
    @Test fun oneHttpRequestReturnsCatalogAndExactRowStatus() = runBlocking {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val page = RemoteSessionPage(
            listOf(RemoteSession(id, "Task", "/workspace", 1)), null,
            listOf(RemoteSessionStatus(id, "active", activeTurnId = "native-turn")),
        )
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val bytes = Json.encodeToString(page).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = applicationFixtureClient("http://127.0.0.1:" + server.address.port + "/", token).sessions("page-two")
            assertEquals("native-turn", result.statuses.single().activeTurnId)
            assertEquals("page-two", result.sessions.single().listCursor)
            assertEquals(listOf("/api/mobile/v1/sessions?cursor=page-two"), requests)
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
