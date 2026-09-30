package com.newoether.agora.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.newoether.agora.remote.FiloClient
import com.newoether.agora.remote.FiloHttpException
import com.newoether.agora.remote.RemoteSpeechAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal const val SPEECH_MAX_DURATION_MS = 60_000
internal const val SPEECH_MAX_PCM_BYTES = 1_920_000
private class SpeechCaptureException : Exception()
internal data class CapturedSpeech(val pcm: ByteArray, val limitReached: Boolean)
internal interface SpeechCaptureSource {
    suspend fun capture(recording: AtomicBoolean, onStarted: suspend () -> Unit): CapturedSpeech
    fun stop()
}

internal fun speechWav(pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty() && pcm.size <= SPEECH_MAX_PCM_BYTES && pcm.size % 2 == 0)
    val out = ByteArrayOutputStream(pcm.size + 44)
    fun word(value: Int) {
        repeat(4) { out.write(value ushr (it * 8) and 0xff) }
    }
    fun half(value: Int) {
        out.write(value and 0xff)
        out.write(value ushr 8 and 0xff)
    }
    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    word(pcm.size + 36)
    out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    word(16)
    half(1)
    half(1)
    word(16_000)
    word(32_000)
    half(2)
    half(16)
    out.write("data".toByteArray(Charsets.US_ASCII))
    word(pcm.size)
    out.write(pcm)
    return out.toByteArray()
}

/** Records one take. The transport and recorder are cancelled together on owner disposal. */
internal class CloudSpeechRecognitionEngine(
    private val availability: suspend () -> RemoteSpeechAvailability,
    private val transcribe: suspend (ByteArray) -> String,
    private val source: SpeechCaptureSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SpeechRecognitionEngine {
    constructor(client: FiloClient) : this(client::speechAvailability, client::transcribeSpeech, AndroidSpeechCaptureSource())

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val recording = AtomicBoolean(false)
    private val released = CompletableDeferred<Unit>()
    private var job: Job? = null
    private var listener: SpeechRecognitionEngine.Listener? = null

    override fun start(listener: SpeechRecognitionEngine.Listener) {
        this.listener = listener
        recording.set(true)
        job = scope.launch {
            try {
                val service = availability()
                if (!service.available || service.maxDurationMs <= 0) {
                    if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onError(SpeechInputFailure.UNAVAILABLE)
                    return@launch
                }
                if (!recording.get()) return@launch
                val captured = withContext(Dispatchers.IO) {
                    source.capture(recording) {
                        withContext(dispatcher) {
                            if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onCaptureStarted()
                        }
                    }
                }
                if (captured.limitReached && this@CloudSpeechRecognitionEngine.listener === listener) {
                    listener.onCaptureLimitReached()
                }
                released.await()
                if (this@CloudSpeechRecognitionEngine.listener === listener) {
                    if (captured.pcm.isEmpty()) listener.onError(SpeechInputFailure.NO_MATCH)
                    else {
                        val text = transcribe(speechWav(captured.pcm))
                        if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onFinalResult(text)
                    }
                }
            } catch (_: CancellationException) {
                // Cancellation belongs to the owner; no callback may survive it.
            } catch (error: SecurityException) {
                if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onError(SpeechInputFailure.PERMISSION)
            } catch (error: FiloHttpException) {
                if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onError(when (error.code) {
                    "speech_unavailable" -> SpeechInputFailure.UNAVAILABLE
                    "speech_busy" -> SpeechInputFailure.BUSY
                    "speech_timeout" -> SpeechInputFailure.NETWORK
                    else -> SpeechInputFailure.FAILED
                })
            } catch (_: IOException) {
                if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onError(SpeechInputFailure.NETWORK)
            } catch (_: SpeechCaptureException) {
                if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onError(SpeechInputFailure.AUDIO)
            } catch (_: Exception) {
                if (this@CloudSpeechRecognitionEngine.listener === listener) listener.onError(SpeechInputFailure.AUDIO)
            }
        }
    }

    override fun stopListening() {
        recording.set(false)
        source.stop()
        released.complete(Unit)
    }

    override fun cancel() = destroy()

    override fun destroy() {
        listener = null
        recording.set(false)
        job?.cancel()
        scope.cancel()
        source.stop()
    }
}

private class AndroidSpeechCaptureSource : SpeechCaptureSource {
    @Volatile private var recorder: AudioRecord? = null

    override suspend fun capture(recording: AtomicBoolean, onStarted: suspend () -> Unit): CapturedSpeech {
        val minBuffer = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw SpeechCaptureException()
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 3_200),
        )
        recorder = audio
        try {
            if (!recording.get()) return CapturedSpeech(byteArrayOf(), false)
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw SpeechCaptureException()
            audio.startRecording()
            onStarted()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(3_200)
            val deadline = SystemClock.elapsedRealtime() + SPEECH_MAX_DURATION_MS
            while (recording.get() && SystemClock.elapsedRealtime() < deadline && output.size() < SPEECH_MAX_PCM_BYTES) {
                val count = audio.read(buffer, 0, minOf(buffer.size, SPEECH_MAX_PCM_BYTES - output.size()))
                if (count < 0) {
                    if (!recording.get()) break
                    throw SpeechCaptureException()
                }
                if (count > 0) output.write(buffer, 0, count - count % 2)
            }
            return CapturedSpeech(output.toByteArray(), recording.get())
        } finally {
            recorder = null
            runCatching { audio.stop() }
            audio.release()
        }
    }

    override fun stop() {
        // AudioRecord.stop unblocks a blocking read without holding the UI thread.
        val active = recorder
        if (active != null) Thread { runCatching { active.stop() } }.start()
    }

}
