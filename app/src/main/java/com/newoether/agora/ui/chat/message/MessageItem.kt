package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.ui.chat.ConversationSearchMatch
import com.newoether.agora.ui.chat.conversationSearchMatchRanges
import com.newoether.agora.ui.components.*
import androidx.compose.ui.res.stringResource

/**
 * One rendered conversation row: the right-aligned user bubble or the full-width
 * Fairy (model/error) content. Remote conversations are read-only — the user
 * bubble exposes copy/select-text/info through its long-press menu and model
 * messages render the Fairy timeline with no action row.
 */
@Composable
internal fun MessageItem(
    message: ChatMessage,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    modifier: Modifier = Modifier,
    animateEntrance: Boolean = false,
    outerPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    includeAssistantOuterSpacing: Boolean = true,
    isStreaming: Boolean = false,
    emblemAnimating: Boolean = false,
    isSwitching: Boolean = false,
    userBubbleSizeAnimationReady: Boolean = true,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    thinkingSegmentDisplayMode: String = ThinkingSegmentDisplayModes.DEFAULT,
    autoExpandActiveGroup: Boolean = true,
    parseInlineDollarMath: Boolean = false,
    groupedSegmentAutoExpansionController: GroupedSegmentAutoExpansionController =
        remember { GroupedSegmentAutoExpansionController() },
    showActions: Boolean = true,
    actionCopyText: String? = message.text,
    onSegmentDetailRequest: (String, List<Int>, Boolean) -> Unit = { _, _, _ -> },
    onHeightChanged: (Int) -> Unit = {},
    searchQuery: String = "",
    activeSearchMatch: ConversationSearchMatch? = null,
    onSearchMatchPosition: (
        key: String,
        measurementEpoch: String?,
        centerYInRoot: Float,
    ) -> Unit = { _, _, _ -> },
    onLayoutMutationStarted: (String) -> Unit = {},
    onLayoutMutationSettled: (String) -> Unit = {},
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    var showUserTextSelection by remember(message.id) { mutableStateOf(false) }

    if (showInfoDialog) {
        MessageInfoDialog(
            message = message,
            onDismiss = { showInfoDialog = false }
        )
    }

    val alignment = when (message.participant) {
        Participant.USER -> Alignment.End
        Participant.MODEL -> Alignment.Start
        Participant.ERROR -> Alignment.CenterHorizontally
    }

    val fairyTokens = com.newoether.agora.ui.theme.LocalFairyTokens.current
    val backgroundColor = when (message.participant) {
        Participant.USER -> fairyTokens.userBubble
        Participant.MODEL -> Color.Transparent
        Participant.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val textColor = when (message.participant) {
        Participant.USER -> fairyTokens.textPrimary
        Participant.MODEL -> fairyTokens.textPrimary
        Participant.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val shape = when (message.participant) {
        Participant.USER -> RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        Participant.MODEL -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        Participant.ERROR -> RoundedCornerShape(12.dp)
    }

    val searchHighlight = searchQuery.takeIf { it.isNotBlank() }?.let { query ->
        val active = activeSearchMatch?.takeIf { it.messageId == message.id }
        val matchRanges = conversationSearchMatchRanges(message, query)
        val matchKeys = matchRanges.map { range ->
            "${message.id}:${range.first}:${range.last + 1}"
        }
        SearchHighlightSpec(
            query = query,
            activeRange = active?.let { it.start until it.endExclusive },
            activeKey = active?.key,
            matchKeys = matchKeys,
            sourceRanges = matchRanges,
            onMatchPosition = onSearchMatchPosition,
        )
    }
    val markdownAssets = rememberChatMarkdownAssets(
        textColor,
        parseInlineDollarMath,
        preparedMarkdown = message.preparedMarkdown,
    )
    val markdownRenderContext = markdownAssets.renderContext
    val thoughtMarkdownRenderContext = markdownAssets.thoughtRenderContext

    val entranceModifier = generationLifecycleAppearanceModifier(
        animationKey = "message:${message.id}",
        animate = animateEntrance && !isSwitching,
        durationMillis = MESSAGE_ENTER_DURATION_MS,
        forceOpaque = message.segments.orEmpty().any { it.type == "tool" },
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged {
                onHeightChanged(it.height)
            }
            .padding(outerPadding)
            .then(entranceModifier),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment,
        ) {
            if (message.participant == Participant.USER) {
                UserMessageBubble(
                    message = message,
                    shape = shape,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    sizeAnimationReady = userBubbleSizeAnimationReady,
                    showActions = showActions,
                    actionCopyText = actionCopyText,
                    onSelectText = { showUserTextSelection = true },
                    onShowInfo = { showInfoDialog = true },
                    searchHighlight = searchHighlight,
                )
            } else {
                val assistantContent: @Composable (
                    ChatMarkdownRenderContext,
                    Boolean,
                ) -> Unit = { ctx, inBubble ->
                    AssistantMessageContent(
                        message = message,
                        includeOuterSpacing = includeAssistantOuterSpacing,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                        toolCallDisplayMode = toolCallDisplayMode,
                        thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                        autoExpandActiveGroup = autoExpandActiveGroup &&
                            ThinkingSegmentDisplayModes.allowsAutoExpand(
                                thinkingSegmentDisplayMode,
                                toolCallDisplayMode,
                            ),

                        groupedSegmentAutoExpansionController =
                            groupedSegmentAutoExpansionController,
                        thoughtExpandedStates = thoughtExpandedStates,
                        renderContext = ctx,
                        searchHighlight = searchHighlight,
                        fillAvailableWidth = !inBubble,
                        onSegmentSelected = { indices, showListFirst ->
                            onSegmentDetailRequest(message.id, indices, showListFirst)
                        },
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        setThoughtBlockHeight = {},
                    )
                }
                if (message.participant == Participant.MODEL) {
                    // Fairy speaks full width on the page: no avatar, no
                    // bubble, a 2 dp accent bar down the whole message
                    // (16 dp from the screen edge, 12 dp to the text).
                    val relaySources = message.remoteFiles
                        .mapNotNullTo(linkedSetOf()) {
                            it.source?.takeIf(String::isNotBlank)
                        }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = FairySpeechStartInset, end = FairySpeechEndInset),
                    ) {
                        relaySources.forEach { source ->
                            FairyRelayBadge(
                                source,
                                Modifier.padding(start = FairyAccentBarWidth + FairyAccentTextGap, bottom = 6.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fairyAccentBar(active = emblemAnimating)
                                .padding(start = FairyAccentBarWidth + FairyAccentTextGap - AssistantMessageHorizontalInset),
                        ) {
                            assistantContent(markdownRenderContext, false)
                        }
                    }
                } else {
                    assistantContent(markdownRenderContext, false)
                }
            }
        }
    }

    if (showUserTextSelection) {
        SegmentDetailSheet(
            message = message,
            selectedSegmentIndex = 0,
            selectedSegmentIndices = listOf(0),
            isStreaming = false,
            markdownRenderContext = thoughtMarkdownRenderContext,
            titleOverride = stringResource(R.string.select_text),
            directSelectableTextContent = message.text,
            onDismiss = { showUserTextSelection = false },
        )
    }
}
