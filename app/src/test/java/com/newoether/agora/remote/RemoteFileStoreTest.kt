package com.newoether.agora.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class RemoteFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun storeIn(dir: java.io.File = temporary.newFolder()) = RemoteFileStore(dir)

    /** Delivers [bytes], then either ends cleanly or fails once [failAfter] bytes were read. */
    private fun streamOf(bytes: ByteArray, failAfter: Int = Int.MAX_VALUE) = object : InputStream() {
        private var pos = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= failAfter) throw IOException("stream failed")
            val count = minOf(len, bytes.size - pos)
            if (count <= 0) return -1
            bytes.copyInto(b, off, pos, pos + count)
            pos += count
            return count
        }
    }

    private fun sinkTo(out: ByteArrayOutputStream, failOnWrite: Boolean = false,
        failOnClose: Boolean = false, failOnOpen: Boolean = false): Pair<RemoteFileSink, () -> Boolean> {
        var abandoned = false
        val sink = object : RemoteFileSink {
            override fun open(): OutputStream {
                if (failOnOpen) throw IOException("provider refused the document")
                return object : OutputStream() {
                    override fun write(b: Int) = out.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (failOnWrite) throw IOException("provider write failed")
                        out.write(b, off, len)
                    }
                    override fun close() {
                        if (failOnClose) throw IOException("provider close failed")
                        super.close()
                    }
                }
            }
            override fun abandon() { abandoned = true }
        }
        return sink to { abandoned }
    }

    @Test fun stagedBytesVerifyAgainstDeclaredLengthAndCardSize() = runTest {
        val store = storeIn()
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        val staged = store.stage(streamOf(bytes), bytes.size.toLong(), bytes.size.toLong(),
            "report final.pdf", "application/pdf")
        assertEquals(bytes.size.toLong(), staged.bytes)
        assertArrayEquals(bytes, staged.file.readBytes())
        assertEquals("report final.pdf", staged.name)
        assertEquals("application/pdf", staged.mime)
        assertTrue(staged.token.isNotBlank())
    }

    @Test fun declaredLengthContradictingTheCardNeverReadsTheBody() = runTest {
        val dir = temporary.newFolder()
        val store = storeIn(dir)
        var read = false
        val input = object : InputStream() {
            override fun read(): Int { read = true; return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read = true; return -1 }
        }
        val failure = runCatching { store.stage(input, 100, 999, "f", null) }.exceptionOrNull()
        assertTrue(failure is RemoteFileException)
        assertFalse(read)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun truncatedStreamDeletesThePartialStagedFile() = runTest {
        val dir = temporary.newFolder()
        val store = storeIn(dir)
        val failure = runCatching {
            store.stage(streamOf(ByteArray(50) { 1 }), expectedBytes = 100, declaredLength = -1, "f", null)
        }.exceptionOrNull()
        assertTrue(failure is RemoteFileException)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun oversizedStreamDeletesTheStagedFile() = runTest {
        val dir = temporary.newFolder()
        val store = storeIn(dir)
        val failure = runCatching {
            store.stage(streamOf(ByteArray(150) { 1 }), expectedBytes = 100, declaredLength = 100, "f", null)
        }.exceptionOrNull()
        // A declared length that disagrees is caught first; both paths must stage nothing.
        assertNotNull(failure)
        assertTrue(dir.listFiles().isNullOrEmpty())
        val undeclared = runCatching {
            store.stage(streamOf(ByteArray(150) { 1 }), expectedBytes = 100, declaredLength = -1, "f", null)
        }.exceptionOrNull()
        assertTrue(undeclared is RemoteFileException)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun streamFailingAfterEveryByteStillDeletesStaging() = runTest {
        val dir = temporary.newFolder()
        val store = storeIn(dir)
        val bytes = ByteArray(64) { 7 }
        val failure = runCatching {
            store.stage(streamOf(bytes, failAfter = bytes.size), bytes.size.toLong(), -1, "f", null)
        }.exceptionOrNull()
        // All expected bytes arrived but the stream never ended cleanly: not a success.
        assertTrue(failure is IOException)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun cancellationDeletesTheStagedFile() = runBlocking {
        val dir = temporary.newFolder()
        val store = storeIn(dir)
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 1.toByte()); return len
            }
        }
        val job = async { store.stage(endless, REMOTE_FILE_LIMIT, -1, "x", null) }
        delay(30)
        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException || failure is RemoteFileException)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun overLimitCardSizeIsRejectedBeforeStaging() = runTest {
        val dir = temporary.newFolder()
        val store = storeIn(dir)
        val failure = runCatching {
            store.stage(streamOf(ByteArray(1)), REMOTE_FILE_LIMIT + 1, -1, "f", null)
        }.exceptionOrNull()
        assertTrue(failure is RemoteContentLimitException)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun exportReportsSuccessOnlyAfterTheTargetFullyWritesAndCloses() = runTest {
        val store = storeIn()
        val bytes = ByteArray(70_000) { (it % 13).toByte() }
        val staged = store.stage(streamOf(bytes), bytes.size.toLong(), bytes.size.toLong(), "f", null)
        val out = ByteArrayOutputStream()
        val (sink, abandoned) = sinkTo(out)
        store.export(staged, sink)
        assertArrayEquals(bytes, out.toByteArray())
        assertFalse(abandoned())
    }

    @Test fun providerOpenFailureAbandonsAndNeverClaimsSuccess() = runTest {
        val store = storeIn()
        val staged = store.stage(streamOf(byteArrayOf(1, 2, 3)), 3, 3, "f", null)
        val (sink, abandoned) = sinkTo(ByteArrayOutputStream(), failOnOpen = true)
        val failure = runCatching { store.export(staged, sink) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(abandoned())
    }

    @Test fun providerWriteFailureAbandonsThePartialTarget() = runTest {
        val store = storeIn()
        val staged = store.stage(streamOf(ByteArray(1024) { 3 }), 1024, 1024, "f", null)
        val (sink, abandoned) = sinkTo(ByteArrayOutputStream(), failOnWrite = true)
        val failure = runCatching { store.export(staged, sink) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(abandoned())
    }

    @Test fun providerCloseFailureIsNotASuccessfulSave() = runTest {
        val store = storeIn()
        val bytes = ByteArray(128) { 9 }
        val staged = store.stage(streamOf(bytes), bytes.size.toLong(), bytes.size.toLong(), "f", null)
        val out = ByteArrayOutputStream()
        val (sink, abandoned) = sinkTo(out, failOnClose = true)
        val failure = runCatching { store.export(staged, sink) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(abandoned())
        // The bytes may have reached the provider, but a failed close is reported as failure.
    }

    @Test fun discardAndStoreRecreationNeverKeepStagedBytes() = runTest {
        val dir = temporary.newFolder()
        val first = storeIn(dir)
        val staged = first.stage(streamOf(byteArrayOf(5, 6)), 2, 2, "f", null)
        assertTrue(staged.file.exists())
        first.discard(staged)
        assertFalse(staged.file.exists())
        val again = first.stage(streamOf(byteArrayOf(7)), 1, 1, "g", null)
        assertTrue(again.file.exists())
        storeIn(dir) // A fresh store wipes everything the previous operation left behind.
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun wireNamesAreDisplayTextOnly() {
        // Separators/control characters are stripped, but the name is never a path anyway.
        assertEquals("..a.txt", sanitizeRemoteFileName("../a.txt"))
        assertEquals("abc", sanitizeRemoteFileName("a/b\\c"))
        assertEquals("ab", sanitizeRemoteFileName("a\u0000b"))
        assertEquals("a.b", sanitizeRemoteFileName("  a.b  "))
        assertEquals("file", sanitizeRemoteFileName(""))
        assertEquals("file", sanitizeRemoteFileName(" \u0000 "))
        assertEquals("file", sanitizeRemoteFileName("..."))
        assertEquals("无扩展名", sanitizeRemoteFileName("无扩展名"))
        assertEquals(128, sanitizeRemoteFileName("x".repeat(500) + ".bin").length)
    }
}
