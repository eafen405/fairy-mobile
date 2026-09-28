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
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.citationRecords
import com.newoether.agora.ui.chat.GenerationActivityDot
import com.newoether.agora.ui.chat.shouldShowStreamingTailIndicator
import com.newoether.agora.ui.common.LocalAgoraHaptics

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
    contextAlpha: Modifier,
    isStreaming: Boolean,
    isLoading: Boolean,
    isStopping: Boolean,
    isRegenerationExiting: Boolean,
    isEditingAllowed: Boolean,
    showActions: Boolean,
    includeOuterSpacing: Boolean = true,
    actionCopyText: String?,
    showBranchSelector: Boolean,
    toolCallDisplayMode: String,
    thinkingSegmentDisplayMode: String,
    autoExpandActiveGroup: Boolean,

    groupedSegmentAutoExpansionController: GroupedSegmentAutoExpansionController,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    searchHighlight: SearchHighlightSpec?,
    // In a Fairy bubble the content wraps its own width instead of filling.
    fillAvailableWidth: Boolean = true,
    // When true the action row is rendered by the caller below the bubble.
    actionsOutside: Boolean = false,
    citationUi: AssistantCitationUiState = rememberAssistantCitationUi(message.id),
    branchIndex: Int,
    totalBranches: Int,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: (String) -> Boolean,
    onFork: () -> Unit,
    onShare: () -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onShowInfo: () -> Unit,
    onShowDelete: () -> Unit,
    onSegmentSelected: (List<Int>, Boolean) -> Unit,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    setThoughtBlockHeight: (Int) -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    val uriHandler = LocalUriHandler.current
    val citations = remember(message.text, message.segments) {
        message.citationRecords()
    }
    val onSingleCitationActivate: (CitationRecord) -> Unit = { source ->
        val safeUrl = CitationPolicy.safeHttpUrl(source.url)
        if (safeUrl == null || runCatching { uriHandler.openUri(safeUrl) }.isFailure) {
            citationUi.selectedCitation = source
        }
    }
    val onCitationActivate: (List<CitationRecord>) -> Unit = { sources ->
        if (sources.size > 1) {
            citationUi.showSources = false
            citationUi.groupedSources = sources
        } else {
            sources.singleOrNull()?.let(onSingleCitationActivate)
        }
    }
    citationUi.selectedCitation?.let { source ->
        CitationSourceDetailDialog(
            source = source,
            onDismiss = { citationUi.selectedCitation = null },
        )
    }
    if (citationUi.showSources) {
        CitationSourcesBottomSheet(
            messageId = message.id,
            citations = citations,
            searchSpec = null,
            onActivate = { source ->
                haptics.confirm()
                onSingleCitationActivate(source)
            },
            onDismiss = { citationUi.showSources = false },
        )
    }
    citationUi.groupedSources?.let { groupedSources ->
        CitationSourcesBottomSheet(
            messageId = message.id,
            citations = groupedSources,
            searchSpec = null,
            onActivate = { source ->
                haptics.confirm()
                onSingleCitationActivate(source)
            },
            onDismiss = { citationUi.groupedSources = null },
        )
    }
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
                message.status == MessageStatus.TOOL_CALLING ||
                message.status == MessageStatus.TRANSCRIBING
        )
    val hasAnswerContent =
        message.text.isNotBlank() || mergedSegments.any { it.isVisibleAnswerSegment() }
    val inlineActivityPresentation = assistantInlineActivityPresentation(
        generationActive = generationActive,
        isStopping = isStopping,
        hasAnswer = hasAnswerContent,
        hasVisibleInfoSegment = mergedSegments.any { it.isInfoSegment() },
        retryText = message.retryText,
    )
    val inlineActivityMode = inlineActivityPresentation.mode
    val inlineActivityTransition = updateTransition(
        targetState = inlineActivityMode != AssistantInlineActivityMode.NONE ||
            inlineActivityPresentation.retainLayout,
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
            .then(contextAlpha)
            .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
    ) {
        Column {
            if (includeOuterSpacing) Spacer(modifier = Modifier.height(FormerAssistantStatusSpacerHeight))

            // GenerationManager already publishes a bounded stream cadence. A second UI debounce
            // delayed every chunk, retained a stale text job through Stop, and then replaced the
            // whole document at terminalization. Feed the latest immutable snapshot directly to
            // the off-main Markdown parser.
            val renderedText = message.text

            Column {
                val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                // Only zero out thought height when legacy thought block is not shown
                if (message.segments != null || message.thoughts.isNullOrBlank()) {
                    setThoughtBlockHeight(0)
                }

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
                val hasImageGenerationBoundary =
                    mergedSegments.any { it.isImageGenerationSegment() }
                val orderedFallbackAnswerText =
                    if (
                        hasImageGenerationBoundary &&
                        mergedSegments.none { it.isVisibleAnswerSegment() }
                    ) {
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
                val groupOrderedInfoBlocks =
                    groupAdjacentTimelineTools ||
                        (
                            hasImageGenerationBoundary &&
                                normalizedToolCallDisplayMode != ToolCallDisplayModes.TIMELINE
                            )
                val useTimelineSegments =
                    hasImageGenerationBoundary ||
                        (
                            !useThinkingSheet &&
                                normalizedToolCallDisplayMode != ToolCallDisplayModes.COMPACT &&
                                (
                                    mergedSegments.any { it.type == "answer" } ||
                                        (
                                            groupAdjacentTimelineTools &&
                                                mergedSegments.any { it.isInfoSegment() }
                                            )
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
                        citations = citations,
                        onCitationActivate = onCitationActivate,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        onMediaClick = onMediaClick,
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
                val answerProjection = remember(answerBodyText, citations, isStreaming) {
                    citationMarkdownProjection(
                        answerText = answerBodyText.orEmpty(),
                        citations = citations,
                        isStreaming = isStreaming,
                    )
                }
                val answerContent = answerProjection?.markdown ?: answerBodyText.orEmpty()
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
                        retryText = message.retryText,
                        visibilityTransition = inlineActivityTransition,
                        activityOpacity = inlineActivityOpacity,
                        retainExitLayout = inlineActivityPresentation.retainLayout,
                        terminalText = inlineTerminalText,
                        terminalIsError = errorContent != null,
                        terminalShowLocalContextHelp =
                            errorContent?.showLocalContextHelp == true,
                        precededByCard = terminalImmediatelyFollowsCard,
                    )
                }
                Box(
                    modifier = Modifier
                        .then(if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier)
                        .noOpBringIntoView()
                ) {
                    if (answerContent.isNotEmpty() && !useTimelineSegments) {
                        CitationTerminalProjectionHost(
                            animationKey = "${message.id}:answer",
                            projection = answerProjection,
                            isStreaming = isStreaming,
                            onLayoutMutationStarted = onLayoutMutationStarted,
                            onLayoutMutationSettled = onLayoutMutationSettled,
                            modifier = if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier,
                        ) { presentedProjection, presentedIsStreaming ->
                            val presentedContent =
                                presentedProjection?.markdown ?: answerBodyText.orEmpty()
                            CitationInlineContentHost(
                                projection = presentedProjection,
                                onActivate = onCitationActivate,
                            ) {
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
                                            content = presentedContent,
                                            isStreaming = presentedIsStreaming,
                                            renderContext = renderContext,
                                            modifier = if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier,
                                            selectionEnabled = !presentedIsStreaming,
                                            textDeltas = answerTextDeltas,
                                            fadeTracker = answerFadeTracker,
                                        )
                                    }
                                } else {
                                    StreamingMarkdownMessage(
                                        content = presentedContent,
                                        isStreaming = presentedIsStreaming,
                                        renderContext = renderContext,
                                        modifier = if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier,
                                        selectionEnabled = !presentedIsStreaming,
                                        textDeltas = answerTextDeltas,
                                        fadeTracker = answerFadeTracker,
                                    )
                                }
                            }
                        }
                    }
                }
                }
                var retainedErrorText by remember { mutableStateOf("") }
                var retainedShowLocalContextHelp by remember { mutableStateOf(false) }
                LaunchedEffect(errorContent) {
                    errorContent?.let {
                        retainedErrorText = it.errorText
                        retainedShowLocalContextHelp = it.showLocalContextHelp
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
                        showLocalContextHelp =
                            errorContent?.showLocalContextHelp
                                ?: retainedShowLocalContextHelp,
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
                if (message.participant == Participant.MODEL && message.images.isNotEmpty()) {
                    val genImages = message.images
                    // Generated images are primary output, not input references:
                    // render as a full-width square card, image cropped to fill
                    // with rounded corners, tap to view fullscreen.
                    Column(
                        modifier = Modifier.padding(top = if (renderedText.isNotEmpty()) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genImages.forEachIndexed { idx, path ->
                            coil.compose.AsyncImage(
                                model = path,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { onMediaClick(genImages, idx) },
                                        onLongClick = { haptics.longPress() },
                                        hapticFeedbackEnabled = false,
                                    )
                            )
                        }
                    }
                }
                if (message.participant == Participant.MODEL && showActions && !actionsOutside) {
                    AssistantActionRow(
                        message = message,
                        citations = citations,
                        citationUi = citationUi,
                        isStreaming = isStreaming,
                        isLoading = isLoading,
                        isStopping = isStopping,
                        isRegenerationExiting = isRegenerationExiting,
                        isEditingAllowed = isEditingAllowed,
                        actionCopyText = actionCopyText,
                        showBranchSelector = showBranchSelector,
                        branchIndex = branchIndex,
                        totalBranches = totalBranches,
                        onSwitchBranch = onSwitchBranch,
                        onRegenerate = onRegenerate,
                        onFork = onFork,
                        onShare = onShare,
                        onShowInfo = onShowInfo,
                        onShowDelete = onShowDelete,
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
}
