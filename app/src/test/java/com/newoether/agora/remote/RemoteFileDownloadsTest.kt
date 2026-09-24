package com.newoether.agora.remote

import android.net.Uri
import com.newoether.agora.model.RemoteFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
internal class RemoteFileDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val client = mockk<FiloClient>()
    private val target: Uri = Uri.parse("content://picked/document")

    private inner class Harness(directory: java.io.File) {
        private val dir = directory
        val state = MutableStateFlow(RemoteState(deviceId = "device", session = RemoteSession("session", "Task", "", 1)))
        val store = RemoteFileStore(dir)
        val traces = mutableListOf<Pair<String, Exception>>()
        val sinks = mutableMapOf<String, FakeSink>()
        var failWrites = false
        var failClose = false

        inner class FakeSink(val out: ByteArrayOutputStream) : RemoteFileSink {
            var abandoned = false
            override fun open(): OutputStream = object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failWrites) throw IOException("provider write failed")
                    out.write(b, off, len)
                }
                override fun close() {
                    if (failClose) throw IOException("provider close failed")
                    super.close()
                }
            }
            override fun abandon() { abandoned = true }
        }

        val downloads = RemoteFileDownloads(state, store, { client }, { stage, error ->
            traces += stage to error; null
        }) { uri -> FakeSink(ByteArrayOutputStream()).also { sinks[uri.toString()] = it } }

        val owner get() = "device/session"
        fun stagedFiles() = dir.listFiles().orEmpty().toList()
    }

    private fun card(bytes: Long, fileId: String = "file-1") =
        RemoteFile(fileId = fileId, deliveryId = "d1", name = "report.pdf", bytes = bytes, mime = "application/pdf")

    private fun serve(bytes: ByteArray, declared: Long = bytes.size.toLong()) {
        coEvery { client.downloadFile(any(), any()) } coAnswers {
            secondArg<suspend (InputStream, Long, String?) -> Unit>()
                .invoke(ByteArrayInputStream(bytes), declared, "application/pdf")
        }
    }

    @Test fun prepareStagesVerifiedBytesAndExportWritesThemToTheProvider() = runTest {
        val harness = Harness(temporary.newFolder())
        val bytes = ByteArray(50_001) { (it % 17).toByte() }
        serve(bytes)
        val staged = harness.downloads.prepare(harness.owner, card(bytes.size.toLong()))
        assertNotNull(staged)
        assertEquals("report.pdf", staged!!.name)
        assertTrue(harness.state.value.savingFiles.isEmpty())
        assertTrue(harness.downloads.export(harness.owner, staged.token, target))
        assertArrayEquals(bytes, harness.sinks.getValue(target.toString()).out.toByteArray())
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test fun rejectedDownloadReportsFailureAndStagesNothing() = runTest {
        val harness = Harness(temporary.newFolder())
        coEvery { client.downloadFile(any(), any()) } throws FiloHttpException(403)
        assertNull(harness.downloads.prepare(harness.owner, card(10)))
        assertEquals(listOf("file_failed"), harness.traces.map { it.first })
        assertTrue(harness.state.value.savingFiles.isEmpty())
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test fun truncatedBodyIsAFailedDownloadNotASave() = runTest {
        val harness = Harness(temporary.newFolder())
        serve(ByteArray(50), declared = 50)
        assertNull(harness.downloads.prepare(harness.owner, card(100)))
        assertEquals(listOf("file_failed"), harness.traces.map { it.first })
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test fun declaredLengthContradictingTheCardFailsBeforePersisting() = runTest {
        val harness = Harness(temporary.newFolder())
        serve(ByteArray(100), declared = 999)
        assertNull(harness.downloads.prepare(harness.owner, card(100)))
        assertEquals(listOf("file_failed"), harness.traces.map { it.first })
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test fun overlongBodyIsAFailedDownloadNotASave() = runTest {
        val harness = Harness(temporary.newFolder())
        serve(ByteArray(150), declared = -1)
        assertNull(harness.downloads.prepare(harness.owner, card(100)))
        assertEquals(listOf("file_failed"), harness.traces.map { it.first })
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test fun ownerChangeDuringDownloadDiscardsTheStagedBytes() = runTest {
        val harness = Harness(temporary.newFolder())
        val bytes = ByteArray(10) { 2 }
        coEvery { client.downloadFile(any(), any()) } coAnswers {
            // The selection moved on while bytes were in flight.
            harness.state.value = harness.state.value.copy(session = RemoteSession("other", "Other", "", 1))
            secondArg<suspend (InputStream, Long, String?) -> Unit>()
                .invoke(ByteArrayInputStream(bytes), bytes.size.toLong(), null)
        }
        assertNull(harness.downloads.prepare(harness.owner, card(bytes.size.toLong())))
        assertTrue(harness.stagedFiles().isEmpty())
        assertTrue(harness.traces.isEmpty())
    }

    @Test fun inFlightDownloadIsNotStartedTwice() = runTest {
        val harness = Harness(temporary.newFolder())
        harness.state.value = harness.state.value.copy(savingFiles = setOf("file-1"))
        assertNull(harness.downloads.prepare(harness.owner, card(10)))
        coVerify(exactly = 0) { client.downloadFile(any(), any()) }
    }

    @Test fun providerWriteFailureIsAnHonestFailedSave() = runTest {
        val harness = Harness(temporary.newFolder())
        serve(ByteArray(64) { 4 })
        val staged = harness.downloads.prepare(harness.owner, card(64))!!
        harness.failWrites = true
        assertFalse(harness.downloads.export(harness.owner, staged.token, target))
        assertTrue(harness.sinks.getValue(target.toString()).abandoned)
        assertEquals(listOf("file_export_failed"), harness.traces.map { it.first })
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test fun providerCloseFailureIsAnHonestFailedSave() = runTest {
        val harness = Harness(temporary.newFolder())
        serve(ByteArray(64) { 4 })
        val staged = harness.downloads.prepare(harness.owner, card(64))!!
        harness.failClose = true
        assertFalse(harness.downloads.export(harness.owner, staged.token, target))
        assertEquals(listOf("file_export_failed"), harness.traces.map { it.first })
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test fun exportOfAnUnknownTokenIsNotASuccess() = runTest {
        val harness = Harness(temporary.newFolder())
        assertFalse(harness.downloads.export(harness.owner, "no-such-token", target))
        assertTrue(harness.traces.isEmpty())
    }

    @Test fun discardAndClearReleaseStagedBytes() = runTest {
        val harness = Harness(temporary.newFolder())
        serve(ByteArray(8) { 1 })
        val first = harness.downloads.prepare(harness.owner, card(8, "file-1"))!!
        harness.downloads.discard(first.token)
        assertTrue(harness.stagedFiles().isEmpty())
        val second = harness.downloads.prepare(harness.owner, card(8, "file-2"))!!
        harness.downloads.clear()
        assertFalse(second.file.exists())
        assertTrue(harness.stagedFiles().isEmpty())
        // Both tokens are gone: a late SAF result can no longer export them.
        assertFalse(harness.downloads.export(harness.owner, first.token, target))
        assertFalse(harness.downloads.export(harness.owner, second.token, target))
    }

    @Test fun prepareRequiresTheSelectingOwner() = runTest {
        val harness = Harness(temporary.newFolder())
        assertNull(harness.downloads.prepare("other/owner", card(10)))
        coVerify(exactly = 0) { client.downloadFile(any(), any()) }
    }
}
