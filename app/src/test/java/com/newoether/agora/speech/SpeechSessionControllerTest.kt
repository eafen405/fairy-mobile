package com.newoether.agora.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechSessionControllerTest {
    private val threshold = 80f

    private class FakeEngine : SpeechRecognitionEngine {
        var listener: SpeechRecognitionEngine.Listener? = null
        var started = 0
        var stopped = 0
        var cancelled = 0
        var destroyed = 0
        var startFailure: Exception? = null
        override fun start(listener: SpeechRecognitionEngine.Listener) {
            startFailure?.let { throw it }
            started++
            this.listener = listener
        }
        override fun stopListening() { stopped++ }
        override fun cancel() { cancelled++ }
        override fun destroy() { destroyed++ }
    }

    private class Harness(
        permission: Boolean = true,
        engine: FakeEngine? = FakeEngine(),
        draft: String = "",
    ) {
        var draft = draft
        var permission = permission
        var requested = 0
        val engine = engine
        val controller = SpeechSessionController(
            engineFactory = { this.engine },
            hasPermission = { this.permission },
            requestPermission = { requested++ },
            readDraft = { this.draft },
            writeDraft = { this.draft = it },
            cancelThresholdPx = 80f,
        )
        fun partial(text: String) = engine?.listener?.onPartialResult(text)
        fun final(text: String) = engine?.listener?.onFinalResult(text)
        fun error(error: SpeechRecognitionError) = engine?.listener?.onError(error)
    }

    @Test fun pressStartsListeningAndKeepsTheDraft() {
        val h = Harness(draft = "existing draft")
        h.controller.pressStarted()
        assertEquals(SpeechInputPhase.LISTENING, h.controller.phase)
        assertTrue(h.controller.exclusive)
        assertEquals(1, h.engine?.started)
        assertEquals("existing draft", h.draft)
    }

    @Test fun partialResultsReplaceTheActiveSegmentOnTopOfTheDraft() {
        val h = Harness(draft = "hello")
        h.controller.pressStarted()
        h.partial("wor")
        assertEquals("hello wor", h.draft)
        h.partial("world")
        assertEquals("hello world", h.draft)
    }

    @Test fun releaseCommitsTheFinalResultAsEditableDraft() {
        val h = Harness(draft = "hello")
        h.controller.pressStarted()
        h.partial("wor")
        h.controller.pressReleased()
        assertEquals(SpeechInputPhase.FINALIZING, h.controller.phase)
        assertEquals(1, h.engine?.stopped)
        h.final("world")
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
        assertFalse(h.controller.exclusive)
        assertEquals("hello world", h.draft)
        assertEquals(1, h.engine?.destroyed)
    }

    @Test fun finalWithoutSeparatorKeepsEmptyDraftClean() {
        val h = Harness(draft = "")
        h.controller.pressStarted()
        h.final(" spoken")
        assertEquals(" spoken", h.draft)
    }

    @Test fun slidingUpPastTheThresholdAndReleasingRestoresTheDraft() {
        val h = Harness(draft = "keep me")
        h.controller.pressStarted()
        h.partial("junk")
        h.controller.pressMoved(threshold)
        assertEquals(SpeechInputPhase.CANCELLING, h.controller.phase)
        h.controller.pressReleased()
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
        assertEquals("keep me", h.draft)
        assertEquals(1, h.engine?.cancelled)
        assertEquals(1, h.engine?.destroyed)
    }

    @Test fun slidingBackBelowTheThresholdReleasesIntoCommit() {
        val h = Harness()
        h.controller.pressStarted()
        h.controller.pressMoved(threshold + 1)
        h.controller.pressMoved(threshold - 1)
        assertEquals(SpeechInputPhase.LISTENING, h.controller.phase)
        h.controller.pressReleased()
        h.final("kept")
        assertEquals("kept", h.draft)
    }

    @Test fun aBlankFinalNeverSubmitsAndRestoresTheDraft() {
        val h = Harness(draft = "draft")
        h.controller.pressStarted()
        h.controller.pressReleased()
        h.final("  ")
        assertEquals(SpeechInputPhase.ERROR, h.controller.phase)
        assertEquals(SpeechInputFailure.NO_MATCH, h.controller.failure)
        assertEquals("draft", h.draft)
    }

    @Test fun recognitionErrorsRestoreTheDraftAndExposeAFailure() {
        val h = Harness(draft = "draft")
        h.controller.pressStarted()
        h.partial("gone")
        h.error(SpeechRecognitionError.NETWORK)
        assertEquals(SpeechInputPhase.ERROR, h.controller.phase)
        assertEquals(SpeechInputFailure.NETWORK, h.controller.failure)
        assertEquals("draft", h.draft)
    }

    @Test fun errorWhileCancellingStillCancelsQuietly() {
        val h = Harness(draft = "draft")
        h.controller.pressStarted()
        h.controller.pressMoved(threshold)
        h.error(SpeechRecognitionError.FAILED)
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
        assertEquals("draft", h.draft)
    }

    @Test fun missingPermissionGoesThroughTheRuntimeRequestFlow() {
        val h = Harness(permission = false, draft = "draft")
        h.controller.pressStarted()
        assertEquals(SpeechInputPhase.AWAITING_PERMISSION, h.controller.phase)
        assertEquals(1, h.requested)
        assertEquals(0, h.engine?.started ?: 0)
        h.controller.onPermissionResult(granted = false)
        assertEquals(SpeechInputPhase.ERROR, h.controller.phase)
        assertEquals(SpeechInputFailure.PERMISSION, h.controller.failure)
        assertEquals("draft", h.draft)
    }

    @Test fun grantedPermissionReadiesTheNextHold() {
        val h = Harness(permission = false)
        h.controller.pressStarted()
        h.controller.onPermissionResult(granted = true)
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
        h.permission = true
        h.controller.pressStarted()
        assertEquals(SpeechInputPhase.LISTENING, h.controller.phase)
    }

    @Test fun unavailableRecognitionSurfacesAFailure() {
        val h = Harness(engine = null)
        h.controller.pressStarted()
        assertEquals(SpeechInputPhase.ERROR, h.controller.phase)
        assertEquals(SpeechInputFailure.UNAVAILABLE, h.controller.failure)
    }

    @Test fun aSecondPressCannotStartWhileExclusive() {
        val h = Harness()
        h.controller.pressStarted()
        h.controller.pressStarted()
        assertEquals(1, h.engine?.started)
        assertEquals(SpeechInputPhase.LISTENING, h.controller.phase)
    }

    @Test fun ownerDisposalRestoresTheDraftAndDestroysTheRecognizer() {
        val h = Harness(draft = "typed")
        h.controller.pressStarted()
        h.partial("mid")
        h.controller.dispose()
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
        assertEquals("typed", h.draft)
        assertEquals(1, h.engine?.destroyed)
    }

    @Test fun aProviderFinalDuringTheHoldCommitsImmediately() {
        val h = Harness()
        h.controller.pressStarted()
        h.final("auto ended")
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
        assertEquals("auto ended", h.draft)
        h.controller.pressReleased()
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
    }

    @Test fun dismissedErrorsClearAndTheNextPressRetries() {
        val h = Harness(draft = "draft")
        h.controller.pressStarted()
        h.error(SpeechRecognitionError.FAILED)
        h.controller.dismissError()
        assertEquals(SpeechInputPhase.IDLE, h.controller.phase)
        assertNull(h.controller.failure)
        h.controller.pressStarted()
        assertEquals(SpeechInputPhase.LISTENING, h.controller.phase)
        assertEquals(2, h.engine?.started)
    }

    @Test fun aStartThrowMapsToAFailureWithoutLosingTheDraft() {
        val engine = FakeEngine().apply { startFailure = SecurityException("denied") }
        val h = Harness(engine = engine, draft = "draft")
        h.controller.pressStarted()
        assertEquals(SpeechInputPhase.ERROR, h.controller.phase)
        assertEquals(SpeechInputFailure.PERMISSION, h.controller.failure)
        assertEquals("draft", h.draft)
    }
}
