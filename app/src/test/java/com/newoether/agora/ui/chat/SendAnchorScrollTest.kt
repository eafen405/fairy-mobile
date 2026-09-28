package com.newoether.agora.ui.chat

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AnimatedScrollDestination
import com.newoether.agora.viewmodel.AnimatedScrollRequest
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Looper
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SendAnchorScrollTest {
    @get:Rule val compose = createComposeRule()

    private class Harness {
        var owner by mutableStateOf("owner")
        var viewport by mutableStateOf(400.dp)
        var messages by mutableStateOf(listOf<ChatMessage>())
        var isLoading by mutableStateOf(false)
        var watchEnabled by mutableStateOf(false)
        var reduceMotion by mutableStateOf(false)
        val requests = MutableStateFlow<AnimatedScrollRequest?>(null)
        val anchorRequestIds = mutableListOf<String>()
        lateinit var scroll: ChatScrollCoordinator
        lateinit var scope: CoroutineScope
        lateinit var density: androidx.compose.ui.unit.Density
        private val requestIds = AtomicLong(0)

        fun requestAnchor(messageId: String) {
            requests.value = AnimatedScrollRequest(
                id = requestIds.incrementAndGet(),
                conversationId = owner,
                targetMessageId = messageId,
                destination = AnimatedScrollDestination.ANCHOR,
            )
        }
    }

    private fun mount(h: Harness) {
        compose.setContent {
            val owner = h.owner
            CompositionLocalProvider(
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = h.reduceMotion),
            ) {
                val scroll = rememberChatScrollCoordinator(owner, 0)
                h.scroll = scroll
                h.scope = rememberCoroutineScope()
                h.density = LocalDensity.current
                val density = LocalDensity.current
                val motion = LocalAgoraMotionPolicy.current
                val request by h.requests.collectAsState()
                val messagesState = rememberUpdatedState(h.messages)
                scroll.BindLayoutObservation(owner, owner, 0, density)
                scroll.BindRequestEffects(
                    currentConversationId = owner,
                    isNewChatMode = false,
                    isLoading = h.isLoading,
                    isStopping = false,
                    isSwitching = false,
                    conversationSearchActive = false,
                    shareSelectionActive = false,
                    regenerationTransition = null,
                    animatedScrollRequest = request,
                    messages = messagesState,
                    density = density,
                    motionPolicy = motion,
                    bottomBarHeight = 100.dp,
                    shareSelectionBarSpace = 0.dp,
                    onAnimatedScrollFinished = { id ->
                        if (h.requests.value?.id == id) h.requests.value = null
                    },
                )
                BindIncomingTurnAnchorEffect(
                    conversationId = owner,
                    messages = h.messages,
                    enabled = h.watchEnabled,
                    hasPendingAttempt = false,
                    withinAttachThreshold = { scroll.isWithinAbsoluteBottomAttachThreshold },
                    onRequestAnchor = { id ->
                        h.anchorRequestIds += id
                        h.requestAnchor(id)
                    },
                )
                MaterialTheme {
                    MessageList(
                        messages = StableMessageList(h.messages),
                        conversationId = owner,
                        modifier = Modifier
                            .height(h.viewport)
                            .onSizeChanged { scroll.recordViewportHeight(it.height) },
                        state = scroll.listState,
                        contentPadding = PaddingValues(top = 140.dp, bottom = 100.dp),
                        viewportHeight = scroll.viewportHeightPx,
                        messageHeights = scroll.messageHeights,
                        streamingAutoFollowEnabled = false,
                        anchoredMessageId = scroll.activeAnchor
                            ?.takeIf { it.conversationId == owner }
                            ?.messageId,
                        initialMessage = { id -> h.messages.firstOrNull { it.id == id } },
                        observeMessage = { id -> flowOf(h.messages.firstOrNull { it.id == id }) },
                        onMessageHydrated = scroll::recordMessageHydrated,
                    )
                }
            }
        }
    }

    private fun pumpFrames(h: Harness, millis: Long = 5_000L) {
        var elapsed = 0L
        while (elapsed < millis && h.requests.value != null) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            elapsed += 16
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun turnIndexOf(h: Harness, messageId: String): Int =
        messageListTurnIndex(buildMessageListTurns(h.messages), messageId)

    private fun anchoredItemOffset(h: Harness): Int {
        val index = turnIndexOf(h, h.scroll.activeAnchor!!.messageId)
        return h.scroll.listState.layoutInfo.visibleItemsInfo
            .first { it.index == index }
            .offset
    }

    private fun anchoredMessageTopGapPx(h: Harness): Float {
        val index = turnIndexOf(h, h.scroll.activeAnchor!!.messageId)
        val item = h.scroll.listState.layoutInfo.visibleItemsInfo.first { it.index == index }
        val turn = buildMessageListTurns(h.messages)[index]
        val intraTurn = anchorMessageTopOffsetInTurnPx(
            turn = turn,
            anchorMessageId = h.scroll.activeAnchor!!.messageId,
            messageHeights = h.scroll.messageHeights,
            fallbackHeightPx = 0f,
        )
        return item.offset.toFloat() + intraTurn
    }

    @Test
    fun sendAnchorParksTheMessageAtContentTopPlusGap() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(
                msg("u1", Participant.USER, "earlier question"),
                msg("m1", Participant.MODEL, "earlier answer"),
                msg("u2", Participant.USER, "next question"),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("u2") }
        pumpFrames(h)

        compose.runOnIdle {
            assertEquals(ChatScrollAnchor("owner", "u2"), h.scroll.activeAnchor)
            // The turn carries the 12dp gap as internal top padding, so the turn's top edge
            // sits flush with the content area top while the message lands 12dp lower.
            assertTrue(
                "anchored message top gap ${anchoredMessageTopGapPx(h)}",
                abs(anchoredMessageTopGapPx(h)) <= 2f,
            )
        }
    }

    @Test
    fun anAnchorOnANonHolderTailFillsTheViewportWithReserve() {
        // A proactive assistant-only tail has no real-user turn, so the tail minimum does not
        // apply and the sentinel reserve alone fills the leftover room.
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(msg("m1", Participant.MODEL, "proactive hello"))
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("m1") }
        pumpFrames(h)
        compose.runOnIdle {
            assertEquals("m1", h.scroll.activeAnchor?.messageId)
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
            // Reserve-filled content ends flush with the physical bottom: no forward scroll.
            assertTrue(!h.scroll.listState.canScrollForward)
            val sentinel = h.scroll.listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == AbsoluteBottomSentinelKey }
            assertNotNull(sentinel)
            assertTrue("sentinel ${sentinel?.size}", (sentinel?.size ?: 0) > 1)
        }
    }

    @Test
    fun firstItemAnchorLandsEvenWithoutScrollHeadroom() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(msg("u1", Participant.USER, "the very first send"))
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("u1") }
        pumpFrames(h)

        compose.runOnIdle {
            assertEquals("u1", h.scroll.activeAnchor?.messageId)
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
        }
    }

    private fun sentinelSizePx(h: Harness): Int? =
        h.scroll.listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == AbsoluteBottomSentinelKey }
            ?.size

    @Test
    fun streamingGrowthKeepsTheFirstVisibleItemStill() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(msg("m1", Participant.MODEL, "seed"))
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("m1") }
        pumpFrames(h)
        var beforeIndex = -1
        var beforeOffset = -1
        var sentinelBefore = -1
        compose.runOnIdle {
            beforeIndex = h.scroll.listState.firstVisibleItemIndex
            beforeOffset = h.scroll.listState.firstVisibleItemScrollOffset
            sentinelBefore = sentinelSizePx(h) ?: -1
        }
        // Appending to the anchored turn grows the rendered extent for real — the height map
        // gains the new message and the sentinel reserve shrinks by that amount.
        compose.runOnIdle {
            h.isLoading = true
            h.messages = h.messages + msg("m2", Participant.MODEL, "growth")
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            val grownHeight = h.scroll.messageHeights["m2"] ?: 0
            assertTrue("appended message measured $grownHeight", grownHeight > 0)
            val sentinelAfter = sentinelSizePx(h) ?: -1
            assertTrue(
                "reserve $sentinelBefore -> $sentinelAfter",
                sentinelBefore > 1 && sentinelAfter < sentinelBefore,
            )
        }
        compose.runOnIdle {
            h.isLoading = false
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(beforeIndex, h.scroll.listState.firstVisibleItemIndex)
            assertEquals(beforeOffset, h.scroll.listState.firstVisibleItemScrollOffset)
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
        }
    }

    private fun bottomButtonVisible(h: Harness): Boolean = shouldShowAbsoluteBottomButton(
        isNewChatMode = false,
        isSwitching = false,
        conversationContentReady = true,
        shareSelectionActive = false,
        hasItems = h.scroll.listState.layoutInfo.totalItemsCount > 1,
        canScrollForward = h.scroll.listState.canScrollForward,
        isNearBottom = h.scroll.isNearAbsoluteBottom,
        isStreamingAutoFollowing = h.scroll.streamingTailController.isAutoFollowing,
        scrollPhase = h.scroll.absoluteBottomScrollPhase,
    )

    @Test
    fun overlongReplyExposesTheBottomButtonAndReachesTheBottomOnClick() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(msg("m1", Participant.MODEL, "seed"))
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("m1") }
        pumpFrames(h)
        compose.runOnIdle {
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
            // The reserve fills the viewport room: the reply does not overflow yet.
            assertTrue(!bottomButtonVisible(h))
        }
        // The reply keeps growing below the anchored message until it overflows the viewport.
        compose.runOnIdle {
            h.messages = h.messages + (2..8).map { index ->
                msg("m$index", Participant.MODEL, "growth $index")
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(h.scroll.listState.canScrollForward)
            assertTrue(!h.scroll.isNearAbsoluteBottom)
            assertTrue(bottomButtonVisible(h))
            h.scope.launch { h.scroll.requestAbsoluteBottomScroll() }
        }
        var elapsed = 0L
        while (elapsed < 10_000L && !h.scroll.isNearAbsoluteBottom) {
            compose.mainClock.advanceTimeBy(50)
            shadowOf(Looper.getMainLooper()).idle()
            elapsed += 50
        }
        compose.runOnIdle {
            assertTrue(h.scroll.isNearAbsoluteBottom)
            // Reaching the bottom never attaches streaming follow.
            assertTrue(!h.scroll.streamingTailController.isAutoFollowing)
        }
        // Content keeps growing off-screen afterwards; the button comes back.
        compose.runOnIdle {
            h.messages = h.messages + (9..16).map { index ->
                msg("m$index", Participant.MODEL, "growth $index")
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(h.scroll.listState.canScrollForward)
            assertTrue(!h.scroll.isNearAbsoluteBottom)
        }
    }

    @Test
    fun reopeningTheConversationDropsTheAnchorAndReserve() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = (1..15).flatMap { index ->
                listOf(
                    msg("u$index", Participant.USER, "question $index\n" + "tall\n".repeat(6)),
                    msg("m$index", Participant.MODEL, "answer $index\n" + "tall\n".repeat(6)),
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("u10") }
        pumpFrames(h)
        compose.runOnIdle { assertNotNull(h.scroll.activeAnchor) }

        compose.runOnIdle { h.owner = "owner-2" }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(h.scroll.activeAnchor)
            val lastVisible = h.scroll.listState.layoutInfo.visibleItemsInfo.lastOrNull()
            // The reserve is gone: the sentinel is the last item again and measures 1dp.
            if (lastVisible?.key == AbsoluteBottomSentinelKey) {
                with(h.density) {
                    assertTrue(
                        "sentinel size ${lastVisible.size}",
                        lastVisible.size <= 1.dp.roundToPx() + 1,
                    )
                }
            }
            // Reopened conversations settle at the bottom exactly like before.
            val messagesState = mutableStateOf(h.messages)
            h.scope.launch { h.scroll.settleOpenedConversation(messagesState) }
        }
        var settleWait = 0
        while (h.scroll.listState.canScrollForward && settleWait < 200) {
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
            settleWait++
        }
        compose.runOnIdle {
            assertTrue(!h.scroll.listState.canScrollForward)
        }
    }

    @Test
    fun reducedMotionPositionsTheAnchorDirectly() {
        val h = Harness()
        h.reduceMotion = true
        mount(h)
        compose.runOnIdle {
            h.messages = (1..30).flatMap { index ->
                listOf(
                    msg("u$index", Participant.USER, "question $index\n" + "tall\n".repeat(6)),
                    msg("m$index", Participant.MODEL, "answer $index\n" + "tall\n".repeat(6)),
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("u25") }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("u25", h.scroll.activeAnchor?.messageId)
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
        }
    }

    @Test
    fun aUserDragCancelsTheAnchorAnimationWithoutCompensation() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = (1..30).flatMap { index ->
                listOf(
                    msg("u$index", Participant.USER, "question $index\n" + "tall\n".repeat(8)),
                    msg("m$index", Participant.MODEL, "answer $index\n" + "tall\n".repeat(8)),
                )
            }
        }
        compose.waitForIdle()
        h.requestAnchor("u25")
        // Pump the looper + frame clock until the progressive seek owns the scroll mutation,
        // then preempt it exactly like a real gesture (waitForIdle would drain the seek first).
        var guard = 0
        while (!h.scroll.listState.isScrollInProgress && guard < 500) {
            compose.mainClock.advanceTimeBy(16)
            shadowOf(Looper.getMainLooper()).idle()
            guard++
        }
        h.scope.launch {
            h.scroll.listState.scroll(MutatePriority.UserInput) { scrollBy(-48f) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        pumpFrames(h)
        compose.runOnIdle {
            assertNull("request should be finished", h.requests.value)
            // The anchor stays recorded but the animation did not complete its travel: the
            // anchored turn never settled at its parked offset.
            assertEquals("u25", h.scroll.activeAnchor?.messageId)
            val item = h.scroll.listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == turnIndexOf(h, "u25") }
            assertTrue(
                "anchor landed at offset ${item?.offset}",
                item == null || abs(item.offset) > 2,
            )
        }
        // No compensation follows the interrupted animation: the scroll position is frozen.
        var parkedIndex = -1
        var parkedOffset = -1
        compose.runOnIdle {
            parkedIndex = h.scroll.listState.firstVisibleItemIndex
            parkedOffset = h.scroll.listState.firstVisibleItemScrollOffset
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(parkedIndex, h.scroll.listState.firstVisibleItemIndex)
            assertEquals(parkedOffset, h.scroll.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun imeViewportShrinkKeepsTheAnchoredMessageAtContentTop() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(
                msg("u1", Participant.USER, "question"),
                msg("m1", Participant.MODEL, "answer"),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { h.requestAnchor("u1") }
        pumpFrames(h)
        compose.runOnIdle { h.viewport = 240.dp }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
        }
    }

    @Test
    fun proactiveAssistantTailAnchorsWhenTheReaderIsAtBottom() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(
                msg("u1", Participant.USER, "question", runId = "turn-1"),
                msg("m1", Participant.MODEL, "answer", runId = "turn-1"),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { h.watchEnabled = true }
        compose.waitForIdle()
        compose.runOnIdle {
            h.messages = h.messages + msg("m2", Participant.MODEL, "proactive", runId = "turn-2")
        }
        pumpFrames(h)
        compose.runOnIdle {
            assertEquals(listOf("m2"), h.anchorRequestIds)
            assertEquals("m2", h.scroll.activeAnchor?.messageId)
            val intraTurn = (h.scroll.messageHeights["u1"] ?: 0) + (h.scroll.messageHeights["m1"] ?: 0)
            val turn = h.scroll.listState.layoutInfo.visibleItemsInfo
                .first { it.index == turnIndexOf(h, "m2") }
            assertTrue(
                "offset ${turn.offset} intra $intraTurn",
                abs(turn.offset.toFloat() + intraTurn) <= 2f,
            )
        }
    }

    @Test
    fun proactiveAssistantTailDoesNotScrollWhenTheReaderIsNotAtBottom() {
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = (1..30).flatMap { index ->
                listOf(
                    msg("u$index", Participant.USER, "question $index\n" + "tall\n".repeat(8), "turn-$index"),
                    msg("m$index", Participant.MODEL, "answer $index\n" + "tall\n".repeat(8), "turn-$index"),
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { h.watchEnabled = true }
        compose.waitForIdle()
        compose.runOnIdle {
            h.scope.launch { h.scroll.listState.scrollToItem(0) }
        }
        compose.waitForIdle()
        var beforeIndex = -1
        var beforeOffset = -1
        compose.runOnIdle {
            beforeIndex = h.scroll.listState.firstVisibleItemIndex
            beforeOffset = h.scroll.listState.firstVisibleItemScrollOffset
        }
        compose.runOnIdle {
            h.messages = h.messages + msg("proactive", Participant.MODEL, "new turn", runId = "turn-proactive")
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(h.anchorRequestIds.isEmpty())
            assertEquals(beforeIndex, h.scroll.listState.firstVisibleItemIndex)
            assertEquals(beforeOffset, h.scroll.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun aBatchedReplyKeepsTheSendConfirmationAnchor() {
        // A single refresh can deliver the confirmed USER message and its MODEL reply
        // together. The send path anchors the user message; the watcher must not replace it
        // with the reply just because the delivery flag already cleared.
        val h = Harness()
        mount(h)
        compose.runOnIdle {
            h.messages = listOf(
                msg("u1", Participant.USER, "question", runId = "turn-1"),
                msg("m1", Participant.MODEL, "answer", runId = "turn-1"),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { h.watchEnabled = true }
        compose.waitForIdle()
        compose.runOnIdle {
            h.requestAnchor("u2")
            h.messages = h.messages + listOf(
                msg("u2", Participant.USER, "next", runId = "turn-2"),
                msg("m2", Participant.MODEL, "reply", runId = "turn-2"),
            )
        }
        pumpFrames(h)
        compose.runOnIdle {
            assertTrue("watcher requests ${h.anchorRequestIds}", h.anchorRequestIds.isEmpty())
            assertEquals("u2", h.scroll.activeAnchor?.messageId)
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
        }
    }

    @Test
    fun anEmptyConversationAnchorsItsFirstProactiveTurn() {
        val h = Harness()
        mount(h)
        compose.runOnIdle { h.messages = emptyList() }
        compose.waitForIdle()
        compose.runOnIdle { h.watchEnabled = true }
        compose.waitForIdle()
        compose.runOnIdle {
            h.messages = listOf(
                msg("m1", Participant.MODEL, "proactive hello", runId = "turn-p"),
            )
        }
        pumpFrames(h)
        compose.runOnIdle {
            assertEquals(listOf("m1"), h.anchorRequestIds)
            assertEquals("m1", h.scroll.activeAnchor?.messageId)
            assertTrue(abs(anchoredMessageTopGapPx(h)) <= 2f)
        }
    }

    private fun msg(
        id: String,
        participant: Participant,
        text: String,
        runId: String? = null,
    ) = ChatMessage(
        id = id,
        text = text,
        participant = participant,
        runId = runId,
    )
}
