package com.newoether.agora.speech

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class SpeechInputPhase { IDLE, AWAITING_PERMISSION, LISTENING, CANCELLING, FINALIZING, ERROR }

internal enum class SpeechInputFailure { PERMISSION, UNAVAILABLE, NO_MATCH, NETWORK, FAILED }

/**
 * Exclusive speech-input mode for one composer owner. Public events mirror the
 * gesture lifecycle: [pressStarted] on hold, [pressMoved] for the slide-to-cancel
 * threshold, [pressReleased] on release, plus [onPermissionResult] and [dispose].
 *
 * The draft outside the speech segment is never touched: on [pressStarted] the
 * current draft is snapshotted, partial text replaces the active segment on top
 * of it, a non-blank final result commits as editable text, and cancellation,
 * errors, or disposal restore the snapshot. Nothing is ever submitted.
 */
internal class SpeechSessionController(
    private val engineFactory: () -> SpeechRecognitionEngine?,
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val readDraft: () -> String,
    private val writeDraft: (String) -> Unit,
    private val cancelThresholdPx: Float,
) {
    var phase by mutableStateOf(SpeechInputPhase.IDLE)
        private set
    var failure by mutableStateOf<SpeechInputFailure?>(null)
        private set

    private var engine: SpeechRecognitionEngine? = null
    private var preSpeech = ""
    private var base = ""
    private var segment = ""

    /** Keyboard edits and draft-changing controls suspend while a session owns the composer. */
    val exclusive: Boolean get() = phase in setOf(
        SpeechInputPhase.LISTENING, SpeechInputPhase.CANCELLING, SpeechInputPhase.FINALIZING,
    )

    private val listener = object : SpeechRecognitionEngine.Listener {
        override fun onPartialResult(text: String) {
            if (phase != SpeechInputPhase.LISTENING && phase != SpeechInputPhase.CANCELLING) return
            segment = text
            writeDraft(base + segment)
        }
        override fun onFinalResult(text: String) {
            // A provider-side end of speech is terminal even while the user is still holding.
            if (phase == SpeechInputPhase.CANCELLING) discard()
            else if (phase == SpeechInputPhase.LISTENING || phase == SpeechInputPhase.FINALIZING) {
                if (text.isBlank()) fail(SpeechInputFailure.NO_MATCH) else commit(text)
            }
        }
        override fun onError(error: SpeechRecognitionError) {
            if (phase == SpeechInputPhase.CANCELLING) discard()
            else if (phase == SpeechInputPhase.LISTENING || phase == SpeechInputPhase.FINALIZING) {
                fail(error.toFailure())
            }
        }
    }

    fun pressStarted() {
        if (phase != SpeechInputPhase.IDLE && phase != SpeechInputPhase.ERROR) return
        failure = null
        if (!hasPermission()) {
            phase = SpeechInputPhase.AWAITING_PERMISSION
            requestPermission()
            return
        }
        val created = engineFactory()
        if (created == null) {
            phase = SpeechInputPhase.ERROR
            failure = SpeechInputFailure.UNAVAILABLE
            return
        }
        preSpeech = readDraft()
        base = if (preSpeech.isEmpty() || preSpeech.last().isWhitespace()) preSpeech else "$preSpeech "
        segment = ""
        engine = created
        phase = SpeechInputPhase.LISTENING
        try {
            created.start(listener)
        } catch (denied: SecurityException) {
            fail(SpeechInputFailure.PERMISSION)
        } catch (error: Exception) {
            fail(SpeechInputFailure.FAILED)
        }
    }

    fun pressMoved(upwardPx: Float) {
        if (phase == SpeechInputPhase.LISTENING && upwardPx >= cancelThresholdPx) {
            phase = SpeechInputPhase.CANCELLING
        } else if (phase == SpeechInputPhase.CANCELLING && upwardPx < cancelThresholdPx) {
            phase = SpeechInputPhase.LISTENING
        }
    }

    fun pressReleased() {
        when (phase) {
            SpeechInputPhase.LISTENING -> {
                phase = SpeechInputPhase.FINALIZING
                engine?.stopListening()
            }
            SpeechInputPhase.CANCELLING -> discard()
            else -> {}
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (phase != SpeechInputPhase.AWAITING_PERMISSION) return
        // The permission dialog ends the press sequence; a grant readies the next hold.
        if (granted) phase = SpeechInputPhase.IDLE
        else fail(SpeechInputFailure.PERMISSION)
    }

    fun dismissError() {
        if (phase == SpeechInputPhase.ERROR) {
            phase = SpeechInputPhase.IDLE
            failure = null
        }
    }

    /**
     * The provider should always answer [stopListening] with a final result or an
     * error; if it never does, the composer must not stay exclusive forever.
     */
    fun finalizeExpired() {
        if (phase == SpeechInputPhase.FINALIZING) fail(SpeechInputFailure.FAILED)
    }

    /** Owning UI leaving composition or the owner switching: end the session and restore the draft. */
    fun dispose() {
        if (exclusive) discard() else {
            engine?.destroy()
            engine = null
        }
        if (phase == SpeechInputPhase.AWAITING_PERMISSION) phase = SpeechInputPhase.IDLE
    }

    private fun commit(text: String) {
        writeDraft(base + text)
        teardown()
        phase = SpeechInputPhase.IDLE
    }

    private fun discard() {
        writeDraft(preSpeech)
        engine?.cancel()
        teardown()
        phase = SpeechInputPhase.IDLE
    }

    private fun fail(kind: SpeechInputFailure) {
        // A session that never reached the engine left no segment behind; only an
        // established take needs its draft restored.
        if (engine != null) writeDraft(preSpeech)
        teardown()
        phase = SpeechInputPhase.ERROR
        failure = kind
    }

    private fun teardown() {
        engine?.destroy()
        engine = null
        segment = ""
    }

    private fun SpeechRecognitionError.toFailure(): SpeechInputFailure = when (this) {
        SpeechRecognitionError.PERMISSION -> SpeechInputFailure.PERMISSION
        SpeechRecognitionError.NO_MATCH -> SpeechInputFailure.NO_MATCH
        SpeechRecognitionError.NETWORK -> SpeechInputFailure.NETWORK
        SpeechRecognitionError.UNAVAILABLE -> SpeechInputFailure.UNAVAILABLE
        SpeechRecognitionError.FAILED -> SpeechInputFailure.FAILED
    }
}
