package com.newoether.agora.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.newoether.agora.remote.RemoteSpeechAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

class CloudSpeechRecognitionEngineTest {
    private class FakeSource : SpeechCaptureSource {
        var captures = 0
        var stopped = 0
        var limit = false
        override suspend fun capture(recording: AtomicBoolean, onStarted: suspend () -> Unit): CapturedSpeech {
            captures++
            onStarted()
            return CapturedSpeech(byteArrayOf(1, 2), limit)
        }
        override fun stop() { stopped++ }
    }

    private class FakeListener : SpeechRecognitionEngine.Listener {
        val startedSignal = CompletableDeferred<Unit>()
        val limitSignal = CompletableDeferred<Unit>()
        var started = 0
        var limit = 0
        var final: String? = null
        var failure: SpeechInputFailure? = null
        override fun onCaptureStarted() { started++; startedSignal.complete(Unit) }
        override fun onCaptureLimitReached() { limit++; limitSignal.complete(Unit) }
        override fun onPartialResult(text: String) {}
        override fun onFinalResult(text: String) { final = text }
        override fun onError(error: SpeechInputFailure, code: Int?) { failure = error }
    }

    @Test fun unavailableServiceNeverOpensMicrophone() = runBlocking {
        val source = FakeSource()
        val listener = FakeListener()
        val engine = CloudSpeechRecognitionEngine(
            { RemoteSpeechAvailability(false, SPEECH_MAX_DURATION_MS) },
            { "unexpected" }, source, Dispatchers.Unconfined,
        )
        engine.start(listener)
        assertEquals(0, source.captures)
        assertEquals(SpeechInputFailure.UNAVAILABLE, listener.failure)
        engine.destroy()
    }

    @Test fun releaseDuringAvailabilityCheckCancelsWithoutStartingCapture() = runBlocking {
        val availability = CompletableDeferred<RemoteSpeechAvailability>()
        val source = FakeSource()
        val listener = FakeListener()
        val engine = CloudSpeechRecognitionEngine(
            { availability.await() }, { "unexpected" }, source, Dispatchers.Unconfined,
        )
        engine.start(listener)
        engine.destroy()
        availability.complete(RemoteSpeechAvailability(true, SPEECH_MAX_DURATION_MS))
        yield()
        assertEquals(0, source.captures)
        assertEquals(0, listener.started)
        assertEquals(null, listener.final)
    }

    @Test fun durationLimitWaitsForReleaseAndCancellationSkipsUpload() = runBlocking {
        val source = FakeSource().apply { limit = true }
        var uploads = 0
        val listener = FakeListener()
        val engine = CloudSpeechRecognitionEngine(
            { RemoteSpeechAvailability(true, SPEECH_MAX_DURATION_MS) },
            { uploads++; "spoken" }, source, Dispatchers.Unconfined,
        )
        engine.start(listener)
        withTimeout(5_000) { listener.limitSignal.await() }
        assertEquals(1, listener.started)
        assertEquals(1, listener.limit)
        assertEquals(0, uploads)
        engine.cancel()
        assertEquals(0, uploads)
        assertEquals(null, listener.final)

        val second = CloudSpeechRecognitionEngine(
            { RemoteSpeechAvailability(true, SPEECH_MAX_DURATION_MS) },
            { uploads++; "spoken" }, FakeSource().apply { limit = true }, Dispatchers.Unconfined,
        )
        val secondListener = FakeListener()
        second.start(secondListener)
        withTimeout(5_000) { secondListener.limitSignal.await() }
        second.stopListening()
        withTimeout(5_000) { while (secondListener.final == null) yield() }
        assertEquals(1, uploads)
        assertEquals("spoken", secondListener.final)
        second.destroy()
    }
    @Test fun wavIsCanonicalMono16kPcm16() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = speechWav(pcm)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(40, little32(wav, 4))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals(16, little32(wav, 16))
        assertEquals(1, little16(wav, 20))
        assertEquals(1, little16(wav, 22))
        assertEquals(16_000, little32(wav, 24))
        assertEquals(32_000, little32(wav, 28))
        assertEquals(2, little16(wav, 32))
        assertEquals(16, little16(wav, 34))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(4, little32(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }

    @Test fun wavRejectsUnboundedOrUnalignedPcm() {
        assertThrows(IllegalArgumentException::class.java) { speechWav(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { speechWav(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { speechWav(ByteArray(SPEECH_MAX_PCM_BYTES + 2)) }
        assertEquals(1_920_044, speechWav(ByteArray(SPEECH_MAX_PCM_BYTES)).size)
    }

    private fun little16(bytes: ByteArray, start: Int) =
        (bytes[start].toInt() and 255) or ((bytes[start + 1].toInt() and 255) shl 8)

    private fun little32(bytes: ByteArray, start: Int) =
        little16(bytes, start) or (little16(bytes, start + 2) shl 16)
}
