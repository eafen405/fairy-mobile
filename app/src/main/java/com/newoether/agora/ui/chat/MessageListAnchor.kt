package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The anchored message's top edge sits this far below the content area's top edge. */
internal val AnchoredMessageTopGap = 12.dp

internal const val AnchorScrollSettleTimeoutMs = 8_000L

/**
 * The list's currently active anchor. Once set it survives the request lifecycle: a new anchor,
 * a conversation switch, or closing the page replaces the coordinator state and clears it.
 */
internal data class ChatScrollAnchor(
    val conversationId: String,
    val messageId: String,
)

/**
 * Remaining viewport room below the anchored message once its own block is counted.
 *
 * `anchoredContentPx` is the rendered height of the anchored message plus everything after it,
 * so while the answer grows the reserve shrinks by exactly the same amount and the list's total
 * extent stays constant. Past one viewport of content the reserve is exhausted.
 */
internal fun anchorTailReserve(
    viewportPx: Int,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    anchorGapPx: Int,
    anchoredContentPx: Int,
): Int = (viewportPx - topPaddingPx - bottomPaddingPx - anchorGapPx - anchoredContentPx)
    .coerceAtLeast(0)

/**
 * Rendered height of the region below the anchored message's top edge.
 *
 * The anchored turn carries a top pad of [anchorGapPx] so the 12dp offset is part of its real
 * layout. The tail holder still contributes its minimum height; for a mid-turn anchor only the
 * region below the anchor message counts.
 */
internal fun anchoredTailContentHeightPx(
    anchorMessageId: String?,
    turns: List<MessageListTurn>,
    messageHeights: Map<String, Int>,
    tailHolderKey: String?,
    tailMinHeightPx: Int,
): Int {
    anchorMessageId ?: return 0
    val anchorTurnIndex = messageListTurnIndex(turns, anchorMessageId)
    if (anchorTurnIndex < 0) return 0
    var total = 0
    for (index in anchorTurnIndex until turns.size) {
        val turn = turns[index]
        val measured = turn.messages.sumOf { message -> messageHeights[message.id] ?: 0 }
        // The anchored turn's gap pad sits above the tail holder's minimum height, so the
        // region below the anchored message's top edge is the column height minus the
        // siblings measured above the anchor.
        val rendered = if (turn.key == tailHolderKey) {
            maxOf(tailMinHeightPx, measured)
        } else {
            measured
        }
        total += if (index == anchorTurnIndex) {
            val beforeAnchor = turn.messages
                .takeWhile { message -> message.id != anchorMessageId }
                .sumOf { message -> messageHeights[message.id] ?: 0 }
            (rendered - beforeAnchor).coerceAtLeast(0)
        } else {
            rendered
        }
    }
    return total
}

/** Full sentinel reserve for the current layout inputs, or 0 when no anchor is active. */
internal fun anchorTailReserveForList(
    anchoredMessageId: String?,
    turns: List<MessageListTurn>,
    messageHeights: Map<String, Int>,
    tailHolderKey: String?,
    tailMinHeightPx: Int,
    viewportHeightPx: Int,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    anchorGapPx: Int,
): Int {
    anchoredMessageId ?: return 0
    val anchored = anchoredTailContentHeightPx(
        anchorMessageId = anchoredMessageId,
        turns = turns,
        messageHeights = messageHeights,
        tailHolderKey = tailHolderKey,
        tailMinHeightPx = tailMinHeightPx,
    )
    return anchorTailReserve(
        viewportPx = viewportHeightPx,
        topPaddingPx = topPaddingPx,
        bottomPaddingPx = bottomPaddingPx,
        anchorGapPx = anchorGapPx,
        anchoredContentPx = anchored,
    )
}

/** Height of everything above the anchored message inside its own turn. */
internal fun anchorMessageTopOffsetInTurnPx(
    turn: MessageListTurn,
    anchorMessageId: String,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float {
    var offset = 0f
    for (message in turn.messages) {
        if (message.id == anchorMessageId) return offset
        offset += messageHeights[message.id]?.toFloat() ?: fallbackHeightPx
    }
    return offset
}

/** Every sibling above the anchored message must be measured for the error to be exact. */
internal fun anchorTurnOffsetIsMeasured(
    turn: MessageListTurn,
    anchorMessageId: String,
    messageHeights: Map<String, Int>,
): Boolean = turn.messages
    .takeWhile { message -> message.id != anchorMessageId }
    .all { message -> messageHeights[message.id] != null }

/**
 * A new tail anchors only when it is a genuinely new MODEL turn that did not answer a local
 * send. A reply shares its runId with the USER message that triggered it — regardless of when
 * the delivery was confirmed — while a proactive or relayed message arrives under a turn
 * identity no local user message carries. [previousTailObserved] separates the baseline capture
 * (conversation open, never anchored) from an already-loaded empty conversation whose first
 * proactive message does anchor.
 */
internal fun shouldAnchorIncomingTurn(
    previousTail: ChatMessage?,
    previousTailObserved: Boolean,
    newTail: ChatMessage?,
    userRunIds: Set<String>,
    hasPendingAttempt: Boolean,
    withinAttachThreshold: Boolean,
): Boolean =
    previousTailObserved &&
        !hasPendingAttempt &&
        withinAttachThreshold &&
        newTail != null &&
        newTail.participant == Participant.MODEL &&
        newTail.id != previousTail?.id &&
        newTail.runId != null &&
        newTail.runId != previousTail?.runId &&
        newTail.runId !in userRunIds

/**
 * Watches the conversation tail and asks for an anchor when a proactive assistant turn lands
 * while the reader is parked at the bottom. Remote owns the trigger; the scroll decision lives
 * in the shared coordinator.
 */
@Composable
internal fun BindIncomingTurnAnchorEffect(
    conversationId: String?,
    messages: List<ChatMessage>,
    enabled: Boolean,
    hasPendingAttempt: Boolean,
    withinAttachThreshold: () -> Boolean,
    onRequestAnchor: (String) -> Unit,
) {
    var previousTail by remember(conversationId) { mutableStateOf<ChatMessage?>(null) }
    var previousTailObserved by remember(conversationId) { mutableStateOf(false) }
    val tail = messages.lastOrNull()
    val userRunIds = remember(messages) {
        messages.mapNotNullTo(mutableSetOf()) { message ->
            message.runId.takeIf { message.participant == Participant.USER }
        }
    }
    LaunchedEffect(conversationId, tail?.id, tail?.runId, enabled) {
        if (
            enabled &&
            shouldAnchorIncomingTurn(
                previousTail = previousTail,
                previousTailObserved = previousTailObserved,
                newTail = tail,
                userRunIds = userRunIds,
                hasPendingAttempt = hasPendingAttempt,
                withinAttachThreshold = withinAttachThreshold(),
            )
        ) {
            tail?.id?.let(onRequestAnchor)
        }
        previousTail = tail
        previousTailObserved = true
    }
}

/**
 * Waits until the anchored message's own turn is a real LazyColumn item. Unlike the generic
 * commit check this resolves the literal target id; a proactive MODEL tail must not resolve to
 * its preceding turn.
 */
internal suspend fun ChatScrollCoordinator.awaitAnchorTargetCommitted(
    messages: State<List<ChatMessage>>,
    targetMessageId: String?,
): Boolean {
    targetMessageId ?: return false
    return withTimeoutOrNull(AnchorScrollSettleTimeoutMs) {
        snapshotFlow {
            messageListTurnIndex(buildMessageListTurns(messages.value), targetMessageId) to
                listState.layoutInfo.totalItemsCount
        }.first { (index, itemCount) -> index >= 0 && index < itemCount - 1 }
        true
    } == true
}

/**
 * Progressively positions [targetMessageId] so its top edge lands at the content-area top plus
 * [AnchoredMessageTopGap]. The anchored turn carries the gap as internal top padding, so in item
 * coordinates the target is `scrollOffset = intraTurnOffset` and the frame error is simply the
 * item's offset plus that same intra-turn height. A user drag owns MutatePriority.UserInput and
 * cancels this actor mid-flight with no corrective pass afterwards.
 */
internal suspend fun ChatScrollCoordinator.anchorScrollToMessage(
    messages: State<List<ChatMessage>>,
    targetMessageId: String,
    density: Density,
    motionPolicy: AgoraMotionPolicy,
): Boolean {
    fun currentTarget(): Pair<Int, Float>? {
        val turns = buildMessageListTurns(messages.value)
        val turnIndex = messageListTurnIndex(turns, targetMessageId)
        if (turnIndex < 0) return null
        val fallbackHeightPx = listState.layoutInfo.visibleItemsInfo
            .map { it.size }
            .filter { it > 1 }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: with(density) { 72.dp.toPx() }
        return turnIndex to anchorMessageTopOffsetInTurnPx(
            turn = turns[turnIndex],
            anchorMessageId = targetMessageId,
            messageHeights = messageHeights,
            fallbackHeightPx = fallbackHeightPx,
        )
    }

    if (!motionPolicy.allowProgrammaticScrollMotion) {
        val target = currentTarget() ?: return false
        listState.scrollToItem(target.first, target.second.roundToInt())
        return true
    }

    return listState.smoothSeekToItem(
        targetIndex = {
            currentTarget()?.first
                ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        },
        targetErrorPx = { visibleTarget ->
            val intraTurn = currentTarget()?.second ?: return@smoothSeekToItem null
            visibleTarget.offset.toFloat() + intraTurn
        },
        estimatedErrorPx = {
            val target = currentTarget() ?: return@smoothSeekToItem null
            estimateScrollDistanceToIndexPx(
                turns = buildMessageListTurns(messages.value),
                targetIndex = target.first,
                density = density,
            )?.let { it + target.second }
        },
        exactTargetReady = {
            val turns = buildMessageListTurns(messages.value)
            val turnIndex = messageListTurnIndex(turns, targetMessageId)
            turnIndex >= 0 &&
                anchorTurnOffsetIsMeasured(turns[turnIndex], targetMessageId, messageHeights)
        },
        minimumStepPx = with(density) { 2.dp.toPx() },
        feedbackSpec = SendFeedbackScrollSpec,
    )
}

internal fun ChatScrollCoordinator.logAnchorTargetMiss(targetMessageId: String?) {
    DebugLog.e("AgoraUI", "Anchor scroll target was not committed: $targetMessageId")
}
