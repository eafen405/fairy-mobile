package com.newoether.agora.speech

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class SpeechInputPhase { IDLE, AWAITING_PERMISSION, PREPARING, LISTENING, CAPTURED, CANCELLING, FINALIZING, ERROR }

internal enum class SpeechInputFailure { PERMISSION, UNAVAILABLE, NO_MATCH, NETWORK, AUDIO, CLIENT, BUSY, FAILED }

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
    var failureCode by mutableStateOf<Int?>(null)
        private set

    private var engine: SpeechRecognitionEngine? = null
    private var preSpeech = ""
    private var base = ""
    private var segment = ""
    private var pendingFinal: String? = null
    private var beforeCancel = SpeechInputPhase.LISTENING

    /** Keyboard edits and draft-changing controls suspend while a session owns the composer. */
    val exclusive: Boolean get() = phase in setOf(
        SpeechInputPhase.PREPARING, SpeechInputPhase.LISTENING, SpeechInputPhase.CAPTURED,
        SpeechInputPhase.CANCELLING, SpeechInputPhase.FINALIZING,
    )

    private val listener = object : SpeechRecognitionEngine.Listener {
        override fun onCaptureStarted() {
            if (phase == SpeechInputPhase.PREPARING) phase = SpeechInputPhase.LISTENING
        }
        override fun onCaptureLimitReached() {
            if (phase == SpeechInputPhase.LISTENING) phase = SpeechInputPhase.CAPTURED
            else if (phase == SpeechInputPhase.CANCELLING) beforeCancel = SpeechInputPhase.CAPTURED
        }
        override fun onPartialResult(text: String) {
            if (phase != SpeechInputPhase.LISTENING && phase != SpeechInputPhase.CANCELLING) return
            segment = text
            writeDraft(base + segment)
        }
        override fun onFinalResult(text: String) {
            when (phase) {
                SpeechInputPhase.CANCELLING -> discard()
                SpeechInputPhase.FINALIZING ->
                    if (text.isBlank()) fail(SpeechInputFailure.NO_MATCH) else commit(text)
                SpeechInputPhase.LISTENING ->
                    // The provider ended speech while the press is still held: stage
                    // the result but keep the gesture's slide-to-cancel right until
                    // release.
                    if (text.isBlank()) fail(SpeechInputFailure.NO_MATCH)
                    else {
                        pendingFinal = text
                        segment = text
                        writeDraft(base + segment)
                        engine?.destroy()
                        engine = null
                    }
                else -> {}
            }
        }
        override fun onError(error: SpeechInputFailure, code: Int?) {
            if (phase == SpeechInputPhase.CANCELLING) discard()
            else if (phase in setOf(SpeechInputPhase.PREPARING, SpeechInputPhase.LISTENING,
                    SpeechInputPhase.CAPTURED, SpeechInputPhase.FINALIZING)) {
                fail(error, code)
            }
        }
    }

    fun pressStarted() {
        if (phase != SpeechInputPhase.IDLE && phase != SpeechInputPhase.ERROR) return
        failure = null
        failureCode = null
        if (!hasPermission()) {
            phase = SpeechInputPhase.AWAITING_PERMISSION
            requestPermission()
            return
        }
        val created = try {
            engineFactory()
        } catch (denied: SecurityException) {
            fail(SpeechInputFailure.PERMISSION)
            return
        } catch (error: Exception) {
            fail(SpeechInputFailure.CLIENT)
            return
        }
        if (created == null) {
            phase = SpeechInputPhase.ERROR
            failure = SpeechInputFailure.UNAVAILABLE
            return
        }
        preSpeech = readDraft()
        base = if (preSpeech.isEmpty() || preSpeech.last().isWhitespace()) preSpeech else "$preSpeech "
        segment = ""
        engine = created
        phase = SpeechInputPhase.PREPARING
        try {
            created.start(listener)
        } catch (denied: SecurityException) {
            fail(SpeechInputFailure.PERMISSION)
        } catch (error: Exception) {
            fail(SpeechInputFailure.CLIENT)
        }
    }

    fun pressMoved(upwardPx: Float) {
        if (phase in setOf(SpeechInputPhase.LISTENING, SpeechInputPhase.CAPTURED) && upwardPx >= cancelThresholdPx) {
            beforeCancel = phase
            phase = SpeechInputPhase.CANCELLING
        } else if (phase == SpeechInputPhase.CANCELLING && upwardPx < cancelThresholdPx) {
            phase = beforeCancel
        }
    }

    fun pressReleased() {
        when (phase) {
            SpeechInputPhase.PREPARING -> discard()
            SpeechInputPhase.LISTENING, SpeechInputPhase.CAPTURED -> {
                val staged = pendingFinal
                if (staged != null) commit(staged)
                else {
                    phase = SpeechInputPhase.FINALIZING
                    try {
                        engine?.stopListening()
                    } catch (denied: SecurityException) {
                        fail(SpeechInputFailure.PERMISSION)
                    } catch (error: Exception) {
                        fail(SpeechInputFailure.CLIENT)
                    }
                }
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
            failureCode = null
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

    private fun fail(kind: SpeechInputFailure, code: Int? = null) {
        // A session that never reached the engine left no segment behind; only an
        // established take needs its draft restored.
        if (engine != null) writeDraft(preSpeech)
        teardown()
        phase = SpeechInputPhase.ERROR
        failure = kind
        failureCode = code
    }

    private fun teardown() {
        engine?.destroy()
        engine = null
        segment = ""
        pendingFinal = null
    }
}
