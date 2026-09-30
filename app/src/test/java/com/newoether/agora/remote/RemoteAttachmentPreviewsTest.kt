package com.newoether.agora.remote

import com.newoether.agora.model.RemoteAttachmentRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteAttachmentPreviewsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val client = mockk<FiloClient>()
    private val state = MutableStateFlow(RemoteState(deviceId = "device",
        session = RemoteSession("session", "Task", "", 1)))
    private val owner = "device/session"
    private val traces = mutableListOf<Pair<String, Exception>>()
    private fun ref(id: String, bytes: Long = 3) =
        RemoteAttachmentRef(id, 0, "photo.png", "image/png", bytes, "image")

    private fun preview(directory: java.io.File, scope: kotlinx.coroutines.CoroutineScope) =
        RemoteAttachmentPreviews(state, RemoteFileStore(directory), { client }, scope,
            { stage, error -> traces += stage to error; null })

    private fun serve(bytes: ByteArray, declared: Long = bytes.size.toLong()) {
        coEvery { client.downloadAttachment(any(), any(), any(), any()) } coAnswers {
            arg<suspend (InputStream, Long, String?) -> Unit>(3)
                .invoke(ByteArrayInputStream(bytes), declared, "image/png")
        }
    }

    @Test fun concurrentReadersShareOneVerifiedFile() = runTest {
        val directory = temporary.newFolder()
        val previews = preview(directory, this)
        val gate = CompletableDeferred<Unit>()
        coEvery { client.downloadAttachment(any(), any(), any(), any()) } coAnswers {
            gate.await()
            arg<suspend (InputStream, Long, String?) -> Unit>(3)
                .invoke(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, "image/png")
        }
        val first = async { previews.prepare(owner, ref("m1")) }
        val second = async { previews.prepare(owner, ref("m1")) }
        runCurrent()
        coVerify(exactly = 1) { client.downloadAttachment("session", "m1", 0, any()) }
        gate.complete(Unit)
        val staged = first.await()
        assertNotNull(staged)
        assertEquals(staged, second.await())
        assertEquals(staged, previews.prepare(owner, ref("m1")))
        coVerify(exactly = 1) { client.downloadAttachment(any(), any(), any(), any()) }
        previews.clear()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun ownerChangeDuringStageCannotPublishOldBytes() = runTest {
        val directory = temporary.newFolder()
        val previews = preview(directory, this)
        coEvery { client.downloadAttachment(any(), any(), any(), any()) } coAnswers {
            arg<suspend (InputStream, Long, String?) -> Unit>(3)
                .invoke(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, "image/png")
            state.value = state.value.copy(session = RemoteSession("other", "Other", "", 1))
            previews.clear()
        }
        assertNull(previews.prepare(owner, ref("m1")))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNull(previews.prepare(owner, ref("m1")))
        assertTrue(traces.isEmpty())
    }

    @Test fun clearCancelsPendingReadAndNewOwnerCanDownload() = runTest {
        val directory = temporary.newFolder()
        val previews = preview(directory, this)
        val gate = CompletableDeferred<Unit>()
        coEvery { client.downloadAttachment(any(), any(), any(), any()) } coAnswers {
            if (secondArg<String>() == "m1") gate.await()
            arg<suspend (InputStream, Long, String?) -> Unit>(3)
                .invoke(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, "image/png")
        }
        val pending = async { previews.prepare(owner, ref("m1")) }
        runCurrent()
        state.value = state.value.copy(session = RemoteSession("other", "Other", "", 1))
        previews.clear()
        assertNull(pending.await())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNotNull(previews.prepare("device/other", ref("m2")))
        coVerify(exactly = 1) { client.downloadAttachment("other", "m2", 0, any()) }
        previews.clear()
    }

    @Test fun failedAndOversizedDownloadsLeaveNoStagedBytes() = runTest {
        val directory = temporary.newFolder()
        val previews = preview(directory, this)
        serve(byteArrayOf(1, 2), declared = 3)
        assertNull(previews.prepare(owner, ref("short")))
        assertEquals("attachment_preview_failed", traces.single().first)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNull(previews.prepare(owner, ref("huge", 64L * 1024 * 1024 + 1)))
        coVerify(exactly = 1) { client.downloadAttachment(any(), any(), any(), any()) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun cacheEvictsOldestEntryAfterSixteenFiles() = runTest {
        val directory = temporary.newFolder()
        val previews = preview(directory, this)
        serve(byteArrayOf(1))
        val oldest = previews.prepare(owner, ref("m0", 1))!!
        for (index in 1..16) assertNotNull(previews.prepare(owner, ref("m$index", 1)))
        assertFalse(oldest.file.exists())
        assertEquals(16, directory.listFiles().orEmpty().size)
        previews.clear()
    }
}
