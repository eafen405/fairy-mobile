package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class FiloFileDownloadTest {
    private val token = "a".repeat(64)

    private fun serve(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.start()
        return server
    }

    private fun reply(bytes: ByteArray, mime: String = "application/octet-stream"):
        (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
        exchange.responseHeaders.add("Content-Type", mime)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test fun downloadAuthenticatesAndEncodesTheFileId() = runBlocking {
        val bytes = ByteArray(4097) { (it % 29).toByte() }
        var rawPath: String? = null
        val server = serve { exchange ->
            rawPath = exchange.requestURI.rawPath
            assertEquals("$FAIRY_LOGIN_COOKIE=$token", exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            val received = ByteArrayOutputStream()
            var declared = -2L
            var mime: String? = null
            client.downloadFile("file id/ü") { input, length, type ->
                declared = length; mime = type
                input.copyTo(received)
            }
            assertEquals("/api/mobile/v1/files/file%20id%2F%C3%BC", rawPath)
            assertEquals(bytes.size.toLong(), declared)
            assertEquals("text/plain", mime)
            assertArrayEquals(bytes, received.toByteArray())
        } finally { server.stop(0) }
    }

    @Test fun redirectsAreNeverFollowedSoTheCookieCannotLeakCrossOrigin() = runBlocking {
        val otherHits = AtomicInteger()
        val other = serve { exchange ->
            otherHits.incrementAndGet()
            fail("the redirect target must never be contacted, let alone authenticated")
        }
        val origin = serve { exchange ->
            assertEquals("$FAIRY_LOGIN_COOKIE=$token", exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Location",
                "http://127.0.0.1:${other.address.port}/api/mobile/v1/files/prize")
            exchange.sendResponseHeaders(302, -1)
            exchange.responseBody.close()
        }
        try {
            // A redirect-following transport is injected on purpose: the download path must pin
            // its own no-redirect policy rather than inherit one.
            val transport = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(true).followSslRedirects(true).build()
            val client = FiloClient("http://127.0.0.1:${origin.address.port}/", token, transport)
            val failure = runCatching {
                client.downloadFile("f1") { _, _, _ -> fail("redirect body must not persist") }
            }.exceptionOrNull()
            assertTrue(failure is FiloHttpException)
            assertEquals(0, otherHits.get())
        } finally { origin.stop(0); other.stop(0) }
    }

    @Test fun authorizationAndExistenceFailuresPropagateWithoutPersisting() = runBlocking {
        for (status in listOf(401, 403, 404)) {
            val server = serve { exchange ->
                val body = """{"error":"denied"}""".toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            try {
                val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
                val failure = runCatching {
                    client.downloadFile("f1") { _, _, _ -> fail("a rejected download must not persist") }
                }.exceptionOrNull()
                assertTrue(failure is FiloHttpException)
                assertEquals(status, (failure as FiloHttpException).status)
            } finally { server.stop(0) }
        }
    }

    @Test fun absurdDeclaredLengthIsRejectedBeforeBodyReads() = runBlocking {
        val server = serve { exchange ->
            exchange.sendResponseHeaders(200, REMOTE_FILE_LIMIT + 1)
            exchange.responseBody.use { it.write(byteArrayOf(1)) }
        }
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            val failure = runCatching {
                client.downloadFile("f1") { _, _, _ -> fail("an over-limit file must not persist") }
            }.exceptionOrNull()
            assertTrue(failure is RemoteContentLimitException)
        } finally { server.stop(0) }
    }

    @Test fun persistingFailurePropagatesToTheCaller() = runBlocking {
        val server = serve(reply(byteArrayOf(1, 2, 3)))
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            val failure = runCatching {
                client.downloadFile("f1") { _, _, _ -> throw RemoteFileException("staging refused") }
            }.exceptionOrNull()
            assertTrue(failure is RemoteFileException)
        } finally { server.stop(0) }
    }

    @Test fun serverDisconnectMidBodyPropagatesAsFailure() = runBlocking {
        val server = serve { exchange ->
            exchange.sendResponseHeaders(200, 10_000)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            val failure = runCatching {
                client.downloadFile("f1") { input, _, _ -> input.copyTo(ByteArrayOutputStream()) }
            }.exceptionOrNull()
            // The truncated body surfaces as an IOException; the persist layer decides validity.
            assertTrue(failure is IOException)
        } finally { server.stop(0) }
    }

    @Test fun invalidFileIdsNeverReachTheNetwork() = runBlocking {
        val hits = AtomicInteger()
        val server = serve { hits.incrementAndGet() }
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            for (bad in listOf("", ".", "..")) {
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { client.downloadFile(bad) { _, _, _ -> } }
                }
            }
            assertEquals(0, hits.get())
        } finally { server.stop(0) }
    }
}
