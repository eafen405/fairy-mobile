package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.model.ThinkingSegmentDisplayModes

internal val AssistantMessageHorizontalInset = 8.dp
private val FormerAssistantStatusSpacerHeight = 6.dp

/**
 * The left-aligned assistant (and error) message content: the streaming status header,
 * the thinking / tool-call timeline or compact segment block, the debounced markdown
 * body, any generated images, the stopped indicator, and the regenerate/overflow
 * action row.
 *
 * Extracted from [MessageItem]. The parent owns the reported-height bookkeeping and the
 * segment-detail sheet, so this composable reports the thought block height through
 * [setThoughtBlockHeight] and surfaces clicked segments through [onSegmentSelected].
 */
@Composable
internal fun AssistantMessageContent(
    message: ChatMessage,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    isStreaming: Boolean,
    includeOuterSpacing: Boolean = true,
    toolCallDisplayMode: String,
    thinkingSegmentDisplayMode: String,
    autoExpandActiveGroup: Boolean,

    groupedSegmentAutoExpansionController: GroupedSegmentAutoExpansionController,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    searchHighlight: SearchHighlightSpec?,
    // In a Fairy bubble the content wraps its own width instead of filling.
    fillAvailableWidth: Boolean = true,
    onSegmentSelected: (List<Int>, Boolean) -> Unit,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    setThoughtBlockHeight: (Int) -> Unit,
) {
    // During generation, eat horizontal nested-scroll so code blocks
    // cannot be panned. Vertical scroll and taps (thinking header,
    // stop button) pass through normally. Text selection is already
    // prevented during streaming by the stable Markdown selection host.
    val horizontalScrollEater = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                Offset(available.x, 0f)
        }
    }
    val segmentsOrNull = message.segments
    val mergedSegments = remember(segmentsOrNull) {
        mergeAdjacentSegments(segmentsOrNull.orEmpty())
    }
    val answerTextDeltas = remember(mergedSegments) {
        mergedSegments
            .filter { it.isVisibleAnswerSegment() }
            .flatMap { it.streamingTextDeltas }
    }
    val answerFadeTracker =
        segmentAppearanceRegistry.streamingFadeTracker("${message.id}:answer")
    val generationActive = message.participant == Participant.MODEL &&
        (
            isStreaming ||
                message.status == MessageStatus.SENDING ||
                message.status == MessageStatus.THINKING ||
                message.status == MessageStatus.TOOL_CALLING
        )
    val hasAnswerContent =
        message.text.isNotBlank() || mergedSegments.any { it.isVisibleAnswerSegment() }
    val inlineActivityPresentation = assistantInlineActivityPresentation(
        generationActive = generationActive,
        // Remote rows are not told a stop is pending; only the tail indicator tracks that.
        isStopping = false,
        hasAnswer = hasAnswerContent,
        hasVisibleInfoSegment = mergedSegments.any { it.isInfoSegment() },
    )
    val inlineActivityMode = inlineActivityPresentation.mode
    val inlineActivityTransition = updateTransition(
        targetState = inlineActivityMode != AssistantInlineActivityMode.NONE,
        label = "AssistantInlineActivityVisibility",
    )
    val inlineActivityOpacity by inlineActivityTransition.animateFloat(
        transitionSpec = { snap() },
        label = "AssistantInlineActivityOpacity",
    ) { visible ->
        if (visible) 1f else 0f
    }
    Box(
        modifier = Modifier
            .then(if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier)
            .padding(horizontal = AssistantMessageHorizontalInset)
            .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
    ) {
        Column {
            if (includeOuterSpacing) Spacer(modifier = Modifier.height(FormerAssistantStatusSpacerHeight))

            // GenerationManager already publishes a bounded stream cadence. A second UI debounce
            // delayed every chunk, retained a stale text job through Stop, and then replaced the
            // whole document at terminalization. Feed the latest immutable snapshot directly to
            // the off-main Markdown parser.
            val renderedText = message.text

                val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                val failedToGenerateText = stringResource(R.string.failed_to_generate)
                val errorContent = remember(
                    message.text,
                    message.status,
                    message.participant,
                    message.modelName,
                    mergedSegments,
                    failedToGenerateText,
                ) {
                    assistantErrorContent(message, mergedSegments, failedToGenerateText)
                }
                // On an errored message with no answer segments the text carries the
                // recoverable answer prefix and must still render below the error bar.
                val orderedFallbackAnswerText =
                    if (mergedSegments.none { it.isVisibleAnswerSegment() }) {
                        errorContent?.answerText ?: renderedText.takeIf { !isError }
                    } else {
                        null
                    }
                val orderedSegments = remember(mergedSegments, orderedFallbackAnswerText) {
                    orderedFallbackAnswerText
                        ?.takeIf { it.isNotBlank() }
                        ?.let { fallback ->
                            mergedSegments + MessageSegment(type = "answer", content = fallback)
                        }
                        ?: mergedSegments
                }
                val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
                val useThinkingSheet =
                    ThinkingSegmentDisplayModes.effectiveMode(
                        thinkingSegmentDisplayMode,
                        normalizedToolCallDisplayMode,
                    ) == ThinkingSegmentDisplayModes.BOTTOM_SHEET
                val groupAdjacentTimelineTools = normalizedToolCallDisplayMode == ToolCallDisplayModes.GROUPED_TIMELINE
                val groupOrderedInfoBlocks = groupAdjacentTimelineTools
                val useTimelineSegments =
                    !useThinkingSheet &&
                        normalizedToolCallDisplayMode != ToolCallDisplayModes.COMPACT &&
                        (
                            mergedSegments.any { it.type == "answer" } ||
                                (
                                    groupAdjacentTimelineTools &&
                                        mergedSegments.any { it.isInfoSegment() }
                                    )
                            )
                val detailSegments = remember(mergedSegments) {
                    mergedSegments.filter { it.type != "answer" && it.type != "error" }
                }
                val compactVisible = !useTimelineSegments && detailSegments.isNotEmpty()
                val sheetCollapsedStates = remember(message.id) {
                    mutableStateMapOf<String, Boolean>()
                }
                val compactAppearanceKey = compactSegmentBlockAppearanceKey(message.id)
                val compactCardAppearanceKey = "$compactAppearanceKey:card"
                val latestVisibleAnswerIndex =
                    mergedSegments.indexOfLast { it.isVisibleAnswerSegment() }
                val latestVisibleAnswer = mergedSegments.getOrNull(latestVisibleAnswerIndex)
                val compactAnswerAppearanceKey = latestVisibleAnswer?.let { segment ->
                    "${segmentAppearanceKey(
                        message.id,
                        latestVisibleAnswerIndex,
                        segment,
                    )}:compact-answer"
                }

                if (useTimelineSegments) {
                    TimelineSegmentsContent(
                        segments = orderedSegments,
                        detailSegments = detailSegments,
                        message = message,
                        isStreaming = isStreaming,
                        generationActive = generationActive,
                        groupAdjacentBlocks = groupOrderedInfoBlocks,
                        autoExpandActiveGroup =
                            groupAdjacentTimelineTools && autoExpandActiveGroup,
                        autoExpansionController = groupedSegmentAutoExpansionController,
                        expandedStates =
                            if (useThinkingSheet) sheetCollapsedStates else thoughtExpandedStates,
                        renderContext = renderContext,
                        searchHighlight = searchHighlight,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        opensDetailSheet = useThinkingSheet,
                        preserveInitialCompactIdentity =
                            normalizedToolCallDisplayMode == ToolCallDisplayModes.COMPACT ||
                                useThinkingSheet,
                        onGroupHeaderClick = if (useThinkingSheet) {
                            { indices -> onSegmentSelected(indices, true) }
                        } else {
                            null
                        },
                        onSegmentClick = { indices ->
                            onSegmentSelected(indices, false)
                        }
                    )
                }

                // Compact segment block: single block, newest title/icon when collapsed.
                // Answer segments are timeline anchors only; compact mode still renders
                // message.text below as the complete answer.
                if (compactVisible) {
                    AnimatedTimelineBlockAppearance(
                        animationKey = compactAppearanceKey,
                        appearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                        forceOpaque = detailSegments.any { it.type == "tool" },
                    ) {
                        CompactSegmentBlock(
                            segs = detailSegments,
                            segmentIndices = detailSegments.indices.toList(),
                            message = message,
                            isStreaming = isStreaming,
                            useLiveStatus = true,
                            generationActive = generationActive,
                            isCurrentCard = !hasAnswerContent,
                            expandedStates = if (useThinkingSheet) sheetCollapsedStates else thoughtExpandedStates,
                            expansionKey = message.id,
                            cardAppearanceKey = compactCardAppearanceKey,
                            segmentAppearanceRegistry = segmentAppearanceRegistry,
                            onExpansionStarted = onLayoutMutationStarted,
                            onExpansionSettled = onLayoutMutationSettled,
                            onSegmentClick = { index ->
                                if (useThinkingSheet) {
                                    onSegmentSelected(detailSegments.indices.toList(), true)
                                } else {
                                    onSegmentSelected(listOf(index), false)
                                }
                            },
                            onHeaderClick = if (useThinkingSheet) {
                                {
                                    onSegmentSelected(
                                        detailSegments.indices.toList(),
                                        true,
                                    )
                                }
                            } else {
                                null
                            },
                            opensDetailSheet = useThinkingSheet,
                            onBlockHeightChanged = setThoughtBlockHeight,
                        )
                    }
                }

                val answerBodyText = errorContent?.answerText ?: renderedText.takeIf { !isError }
                val answerContent = answerBodyText.orEmpty()
                val lastVisibleTerminalPredecessor = if (useTimelineSegments) {
                    mergedSegments.lastOrNull { segment ->
                        segment.isVisibleAnswerSegment() || segment.isInfoSegment()
                    }
                } else {
                    null
                }
                val terminalImmediatelyFollowsCard = if (useTimelineSegments) {
                    lastVisibleTerminalPredecessor?.isInfoSegment() == true
                } else {
                    compactVisible && answerContent.isEmpty()
                }
                val inlineTerminalText = when {
                    hasAnswerContent -> null
                    errorContent != null -> errorContent.errorText
                    !isStreaming && message.status == MessageStatus.STOPPED ->
                        stringResource(R.string.generation_stopped)
                    else -> null
                }
                if (message.participant == Participant.MODEL) {
                    AssistantInlineActivity(
                        mode = inlineActivityMode,
                        visibilityTransition = inlineActivityTransition,
                        activityOpacity = inlineActivityOpacity,
                        retainExitLayout = inlineActivityPresentation.retainLayout,
                        terminalText = inlineTerminalText,
                        terminalIsError = errorContent != null,
                        precededByCard = terminalImmediatelyFollowsCard,
                    )
                }
                Box(
                    modifier = Modifier
                        .then(if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier)
                        .noOpBringIntoView()
                ) {
                    if (answerContent.isNotEmpty() && !useTimelineSegments) {
                        CompositionLocalProvider(
                            LocalSearchHighlightSpec provides searchHighlight,
                        ) {
                            if (compactAnswerAppearanceKey != null) {
                                AnimatedTimelineBlockAppearance(
                                    animationKey = compactAnswerAppearanceKey,
                                    appearanceRegistry = segmentAppearanceRegistry,
                                    isStreaming = isStreaming,
                                ) {
                                    StreamingMarkdownMessage(
                                        content = answerContent,
                                        isStreaming = isStreaming,
                                        renderContext = renderContext,
                                        modifier = if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier,
                                        selectionEnabled = !isStreaming,
                                        textDeltas = answerTextDeltas,
                                        fadeTracker = answerFadeTracker,
                                    )
                                }
                            } else {
                                StreamingMarkdownMessage(
                                    content = answerContent,
                                    isStreaming = isStreaming,
                                    renderContext = renderContext,
                                    modifier = if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier,
                                    selectionEnabled = !isStreaming,
                                    textDeltas = answerTextDeltas,
                                    fadeTracker = answerFadeTracker,
                                )
                            }
                        }
                    }
                }
                var retainedErrorText by remember { mutableStateOf("") }
                LaunchedEffect(errorContent) {
                    errorContent?.let {
                        retainedErrorText = it.errorText
                    }
                }
                AnimatedVisibility(
                    visible = hasAnswerContent && errorContent != null,
                    enter = fadeIn(tween(durationMillis = 180, easing = LinearEasing)),
                    exit = fadeOut(tween(durationMillis = 180, easing = LinearEasing)),
                ) {
                    GenerationErrorBar(
                        errorText = errorContent?.errorText ?: retainedErrorText,
                        precededByCard = terminalImmediatelyFollowsCard,
                    )
                }
                AnimatedVisibility(
                    visible = hasAnswerContent && !isStreaming &&
                        message.status == MessageStatus.STOPPED,
                    enter = fadeIn(tween(durationMillis = 180, easing = LinearEasing)),
                    exit = fadeOut(tween(durationMillis = 180, easing = LinearEasing)),
                ) {
                    StoppedGenerationBar(
                        precededByCard = terminalImmediatelyFollowsCard,
                    )
                }
                if (message.remoteFiles.isNotEmpty()) {
                    RemoteFileCardList(
                        files = message.remoteFiles,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (includeOuterSpacing) Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
