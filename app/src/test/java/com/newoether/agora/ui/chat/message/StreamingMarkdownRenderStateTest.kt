package com.newoether.agora.ui.chat.message

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingMarkdownRenderStateTest {
    /** Holds real Markdown parsing so upstream input can advance while one parse is in flight. */
    private class HeldParserDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun completeParse() { pending.removeFirst().run() }
        val hasPendingParse get() = pending.isNotEmpty()
    }

    @Test fun continuousInputPublishesCompletedPrefixesAndConflatesPendingWork() = runTest {
        val parser = HeldParserDispatcher()
        val state = StreamingMarkdownRenderState(GFMFlavourDescriptor(), false, "", true,
            StreamingTailFadeTracker(), parser, { 1_000L + testScheduler.currentTime })
        val worker = backgroundScope.launch { state.run() }
        var text = ""
        var lastDisplayedLength = 0
        // Simulate 200 single-character tokens/s with each parse taking 40 ms. Parsing must
        // publish while the upstream remains active, rather than wait for an input-free window.
        repeat(25) {
            repeat(8) {
                text += "字"
                state.offer(text, true, null)
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(5L)
            }
            assertTrue(parser.hasPendingParse)
            parser.completeParse()
            testScheduler.runCurrent()
            val displayedLength = state.snapshot.value.inputContent.length
            assertTrue("Completed parse was discarded during continuous input", displayedLength > lastDisplayedLength)
            assertTrue("Display lag grew beyond 80 ms at 200 tokens/s", text.length - displayedLength <= 16)
            lastDisplayedLength = displayedLength
        }
        parser.completeParse()
        testScheduler.runCurrent()
        assertEquals(text, state.snapshot.value.inputContent)
        assertFalse(parser.hasPendingParse)
        state.close()
        worker.cancel()
    }

    @Test fun replacementInvalidatesParseEvenWhenNewestTextSharesItsPrefix() = runTest {
        val parser = HeldParserDispatcher()
        val state = StreamingMarkdownRenderState(GFMFlavourDescriptor(), false, "", true,
            StreamingTailFadeTracker(), parser, { 1_000L + testScheduler.currentTime })
        val worker = backgroundScope.launch { state.run() }
        state.offer("old", true, null)
        testScheduler.runCurrent()
        state.offer("replacement", true, null)
        state.offer("old new generation", true, null)
        parser.completeParse()
        testScheduler.runCurrent()
        assertEquals("", state.snapshot.value.inputContent)
        parser.completeParse()
        testScheduler.runCurrent()
        assertEquals("old new generation", state.snapshot.value.inputContent)
        state.close()
        worker.cancel()
    }

    @Test fun pendingTerminalRejectsInFlightStreamingParseAndPublishesCompleteBody() = runTest {
        val parser = HeldParserDispatcher()
        val state = StreamingMarkdownRenderState(GFMFlavourDescriptor(), false, "", true,
            StreamingTailFadeTracker(), parser, { 1_000L + testScheduler.currentTime })
        val worker = backgroundScope.launch { state.run() }
        state.offer("partial", true, null)
        testScheduler.runCurrent()
        state.offer("partial complete", false, null)
        parser.completeParse()
        testScheduler.runCurrent()
        assertEquals("", state.snapshot.value.inputContent)
        parser.completeParse()
        testScheduler.runCurrent()
        assertEquals("partial complete", state.snapshot.value.inputContent)
        assertFalse(state.snapshot.value.isStreaming)
        state.close()
        worker.cancel()
    }

    @Test fun longBodyUsesDisplayFrameCadenceAndTerminalBypassesRemainingWait() = runTest {
        val parser = HeldParserDispatcher()
        val state = StreamingMarkdownRenderState(GFMFlavourDescriptor(), false, "", true,
            StreamingTailFadeTracker(), parser, { 1_000L + testScheduler.currentTime })
        val worker = backgroundScope.launch { state.run() }
        val text = "字".repeat(8_000)
        state.offer(text, true, null)
        testScheduler.runCurrent()
        parser.completeParse()
        testScheduler.runCurrent()
        state.offer(text + "a", true, null)
        testScheduler.runCurrent()
        assertFalse(parser.hasPendingParse)
        testScheduler.advanceTimeBy(16L)
        testScheduler.runCurrent()
        assertTrue("Long replies must not wait 120 ms", parser.hasPendingParse)
        parser.completeParse()
        testScheduler.runCurrent()
        state.offer(text + "ab", true, null)
        testScheduler.runCurrent()
        assertFalse(parser.hasPendingParse)
        state.offer(text + "abc", false, null)
        testScheduler.advanceTimeBy(16L)
        testScheduler.runCurrent()
        parser.completeParse()
        testScheduler.runCurrent()
        assertEquals(text + "abc", state.snapshot.value.inputContent)
        assertFalse(state.snapshot.value.isStreaming)
        state.close()
        worker.cancel()
    }
}
