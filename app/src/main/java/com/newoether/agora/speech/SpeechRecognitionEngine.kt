package com.newoether.agora.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

internal enum class SpeechRecognitionError { PERMISSION, NO_MATCH, NETWORK, UNAVAILABLE, FAILED }

/**
 * Client-side seam over a recognition provider. The composer drives a session
 * through [start]/[stopListening]/[cancel] and observes [Listener] events; it
 * never sees provider callbacks. All methods run on the main thread.
 */
internal interface SpeechRecognitionEngine {
    interface Listener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: SpeechRecognitionError)
    }

    fun start(listener: Listener)
    /** Ends capture and asks the provider to deliver a final result. */
    fun stopListening()
    /** Discards the session; no further listener events are delivered. */
    fun cancel()
    fun destroy()
}

internal fun isSpeechRecognitionAvailable(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SpeechRecognizer.isRecognitionAvailable(context)
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0,
        ).isNotEmpty()
    }

internal fun createSpeechRecognitionEngine(
    context: Context,
    locale: () -> Locale,
): SpeechRecognitionEngine? =
    if (isSpeechRecognitionAvailable(context)) AndroidSpeechRecognitionEngine(context, locale) else null

private fun Int.toSpeechError(): SpeechRecognitionError = when (this) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionError.PERMISSION
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechRecognitionError.NO_MATCH
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechRecognitionError.NETWORK
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> SpeechRecognitionError.UNAVAILABLE
    else -> SpeechRecognitionError.FAILED
}

private class AndroidSpeechRecognitionEngine(
    private val context: Context,
    private val locale: () -> Locale,
) : SpeechRecognitionEngine {
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechRecognitionEngine.Listener? = null

    private val bridge = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            listener?.onError(error.toSpeechError())
        }
        override fun onResults(results: Bundle?) {
            listener?.onFinalResult(results.firstRecognizedText())
        }
        override fun onPartialResults(partialResults: Bundle?) {
            listener?.onPartialResult(partialResults.firstRecognizedText())
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun Bundle?.firstRecognizedText(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    override fun start(listener: SpeechRecognitionEngine.Listener) {
        this.listener = listener
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        val active = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(bridge)
            recognizer = it
        }
        active.startListening(intent)
    }

    override fun stopListening() = recognizer?.stopListening() ?: Unit
    override fun cancel() = recognizer?.cancel() ?: Unit
    override fun destroy() {
        listener = null
        recognizer?.destroy()
        recognizer = null
    }
}
