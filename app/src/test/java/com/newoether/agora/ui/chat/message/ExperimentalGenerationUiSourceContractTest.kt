package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalGenerationUiSourceContractTest {

    @Test
    fun `Thinking card uses compact chrome one trailing rotating arrow and synchronized motion`() {
        val root = locateMainSourceRoot()
        val timeline = source(root, "message/MessageItemTimeline.kt") +
            source(root, "message/TimelineSegmentsContent.kt")
        val assistant = source(root, "message/AssistantMessageContent.kt")
        val presentation = source(root, "message/ThinkingSegmentPresentation.kt")
        val mutedText = source(root, "message/StreamingMutedText.kt")

        assertTrue(timeline.contains("CompactSegmentIcon.LOADING"))
        assertTrue(timeline.contains("compactSegmentShowsLoading("))
        assertTrue(timeline.contains("generationActive: Boolean"))
        assertTrue(timeline.contains("isCurrentCard: Boolean"))
        assertTrue(timeline.contains("isCurrentCard = blockEnd > lastVisibleSegmentIndex"))
        assertTrue(assistant.contains("val generationActive ="))
        assertTrue(assistant.contains("generationActive = generationActive"))
        assertTrue(assistant.contains("isCurrentCard = !hasAnswerContent"))
        assertTrue(timeline.contains("targetState = collapsedIcon"))
        assertTrue(timeline.contains("CircularProgressIndicator("))
        assertTrue(timeline.contains("BoxWithConstraints("))
        assertTrue(timeline.contains("private fun StartAnchoredHorizontalOverflowHost("))
        assertTrue(timeline.contains(
            ".wrapContentWidth(Alignment.Start, unbounded = true)"
        ))
        assertEquals(
            3,
            timeline.windowed("StartAnchoredHorizontalOverflowHost".length)
                .count { it == "StartAnchoredHorizontalOverflowHost" },
        )
        assertTrue(timeline.contains("rememberTextMeasurer("))
        assertTrue(timeline.contains("label = \"compactSegmentWidth\""))
        assertTrue(timeline.contains(".width(cardWidth)"))
        assertTrue(timeline.contains("durationMillis = 400"))
        assertTrue(timeline.contains("easing = LinearOutSlowInEasing"))
        assertTrue(presentation.contains("THINKING_COLLAPSED_WIDTH_ALLOWANCE_DP = 6"))
        assertTrue(presentation.contains("AUXILIARY_CARD_START_EXTENSION_DP = 4"))
        assertTrue(timeline.contains("maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp"))
        assertFalse(timeline.contains("maxWidth + AUXILIARY_CARD_START_EXTENSION_DP.dp"))
        assertTrue(timeline.contains(".offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)"))
        assertTrue(timeline.contains("val contentLayoutWidth ="))
        assertTrue(timeline.contains("wrapContentSize(Alignment.TopStart, unbounded = true)"))
        assertTrue(timeline.contains("requiredWidth(contentLayoutWidth)"))
        assertFalse(timeline.contains("animateContentSize("))
        assertTrue(timeline.contains("RoundedCornerShape(18.dp)"))
        assertTrue(timeline.contains("padding(start = 12.dp, top = 10.dp, bottom = 10.dp)"))
        assertTrue(timeline.contains(".padding(horizontal = 10.dp, vertical = 8.dp)"))
        assertTrue(timeline.contains("+ titleWidth + 4.dp + 18.dp + 12.dp +"))
        assertTrue(timeline.contains("Spacer(modifier = Modifier.width(26.dp))"))
        assertTrue(timeline.contains("align(Alignment.TopEnd)"))
        assertTrue(timeline.contains("padding(top = 10.dp, end = 8.dp)"))
        val loadingBranch = timeline
            .substringAfter("CompactSegmentIcon.LOADING -> CircularProgressIndicator(")
            .substringBefore("CompactSegmentIcon.TOOL -> Icon(")
        assertTrue(loadingBranch.contains("Modifier.size(16.dp)"))
        assertTrue(timeline.contains("fontSize = 13.sp"))
        assertTrue(timeline.contains("lineHeight = 22.sp"))
        assertTrue(timeline.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue(timeline.contains("Modifier.weight(1f)"))
        assertTrue(timeline.contains("strokeWidth = 2.dp"))
        assertEquals(1, timeline.windowed("Icons.Default.KeyboardArrowDown".length)
            .count { it == "Icons.Default.KeyboardArrowDown" })
        assertFalse(timeline.contains("Icons.Default.KeyboardArrowRight"))
        assertFalse(timeline.contains("Icons.Default.KeyboardArrowUp"))
        assertTrue(timeline.contains("rotationZ = disclosureRotation"))
        assertTrue(presentation.contains("thinking_for_seconds_ellipsis"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_CODE_POINTS = 42"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_ALPHA_BANDS = 6"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_NEWEST_ALPHA = 0.38f"))
        val thoughtPreview = mutedText.substringAfter("internal fun StreamingThoughtPreviewText(")
        assertTrue(thoughtPreview.contains("StreamingMutedText("))
        assertEquals(2, Regex("StreamingThoughtPreviewText\\(").findAll(timeline).count())
    }

    @Test
    fun `Timeline and Thinking sheet rows reuse grouping while keeping their own outer insets`() {
        val root = locateMainSourceRoot()
        val timeline = source(root, "message/MessageItemTimeline.kt") +
            source(root, "message/TimelineSegmentsContent.kt")
        val detail = source(root, "message/SegmentDetailSheet.kt")
        val segments = source(root, "message/MessageItemSegments.kt")

        assertTrue(segments.contains("internal enum class SegmentGroupPosition"))
        assertTrue(timeline.contains("SEGMENT_GROUP_GAP_DP = 2"))
        assertTrue(segments.contains("rememberAnimatedSegmentGroupShape("))
        assertTrue(segments.contains("SEGMENT_GROUP_OUTER_RADIUS_DP = 24"))
        assertTrue(segments.contains("SEGMENT_GROUP_INNER_RADIUS_DP = 5"))
        assertTrue(timeline.contains("timelineSegmentGroupPosition(segments, index)"))
        assertTrue(timeline.contains(
            "val groupShape = rememberAnimatedSegmentGroupShape(groupPosition)"
        ))
        assertTrue(timeline.contains("shape = groupShape"))
        assertTrue(timeline.contains("extendIntoMessageInsets: Boolean = false"))
        assertTrue(timeline.contains("extendIntoMessageInsets = true"))
        assertFalse(timeline.contains(
            "requiredWidth(maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp)"
        ))
        assertTrue(timeline.contains("val requestedCardWidth = if (extendIntoMessageInsets)"))
        assertTrue(timeline.contains(".width(requestedCardWidth)"))
        assertTrue(timeline.contains(".offset(x = requestedCardOffset)"))
        assertTrue(timeline.contains(".offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)"))
        assertTrue(detail.contains("segmentGroupPosition("))
        assertTrue(detail.contains("groupPosition = groupPosition"))
        assertTrue(detail.contains("neutralPalette = true"))
        assertFalse(detail.contains("extendIntoMessageInsets = true"))
    }

    @Test
    fun `Thinking sheet matches Settings chrome and uses primary card icons`() {
        val root = locateMainSourceRoot()
        val timeline = source(root, "message/MessageItemTimeline.kt") +
            source(root, "message/TimelineSegmentsContent.kt")
        val detail = source(root, "message/SegmentDetailSheet.kt")
        val presentation = source(root, "message/ThinkingSegmentPresentation.kt")
        val sharedBackButton = File(
            root,
            "com/newoether/agora/ui/components/CircularBackButton.kt",
        ).readText()

        assertTrue(timeline.contains("compactSegmentDisplayTitle("))
        assertTrue(detail.contains("showSegmentListPage ->"))
        assertTrue(detail.contains("compactSegmentDisplayTitle("))
        assertFalse(detail.contains("stringResource(R.string.thinking_segments_title)"))
        assertTrue(presentation.contains("thinking_for_seconds_ellipsis"))
        assertTrue(presentation.contains("produceState("))
        assertTrue(timeline.contains("neutralPalette: Boolean = false"))
        val palette = timeline
            .substringAfter("neutralPalette: Boolean = false")
            .substringBefore("BoxWithConstraints")
        assertTrue(palette.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)"
        ))
        assertTrue(palette.contains("MaterialTheme.colorScheme.surface"))
        assertTrue(palette.contains(
            "val iconTint = if (neutralPalette) MaterialTheme.colorScheme.primary"
        ))
        assertFalse(palette.contains("seg.type == \"tool\""))
        assertTrue(timeline.contains("if (neutralPalette) 1.dp else 2.dp"))
        assertTrue(timeline.contains(
            "tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)"
        ))
        val backButton = detail
            .substringAfter("CircularBackButton(")
            .substringBefore(")")
        assertTrue(backButton.contains("containerColor ="))
        assertFalse(backButton.contains("contentColor ="))
        assertFalse(backButton.contains("tonalElevation ="))
        assertTrue(sharedBackButton.contains(
            "containerColor: Color = MaterialTheme.colorScheme.surface"
        ))
        assertTrue(sharedBackButton.contains(
            "contentColor: Color = MaterialTheme.colorScheme.onSurface"
        ))
        assertTrue(sharedBackButton.contains("tonalElevation: Dp = 6.dp"))
    }



    @Test
    fun `thinking tool errors and stopped states reuse shared neutral terminal text`() {
        val toolResult = source(locateMainSourceRoot(), "message/ToolResultContent.kt")
        val detail = toolResult
            .substringAfter("internal fun ToolDetailContent(")
            .substringBefore("internal fun toolDetailHorizontalPadding(")
        val errorContent = toolResult
            .substringAfter("private fun ToolErrorContent(")
            .substringBefore("private fun ToolMutedContent(")
        val terminalText = source(locateMainSourceRoot(), "message/GenerationErrorBar.kt")
            .substringAfter("internal fun GenerationTerminalText(")
            .substringBefore("internal fun GenerationErrorBar(")
        assertTrue(detail.contains("ToolPresentationState.FAILED -> ToolErrorContent("))
        assertTrue(detail.contains("ToolPresentationState.STOPPED -> GenerationTerminalText("))
        assertTrue(errorContent.contains("GenerationTerminalText("))
        assertTrue(errorContent.contains("selectable = true"))
        assertTrue(errorContent.contains("fillWidth = true"))
        assertFalse(errorContent.contains("Surface("))
        assertFalse(errorContent.contains("surfaceVariant"))
        assertFalse(errorContent.contains("ChatType.thoughtBody"))
        assertTrue(terminalText.contains("style = ChatType.body"))
        assertTrue(
            terminalText.contains(
                "color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)",
            ),
        )
    }

    @Test
    fun `answer and thought Markdown use centered one point one line height`() {
        val assets = source(locateMainSourceRoot(), "message/MessageBubbleAssets.kt")

        assertTrue(assets.contains("MARKDOWN_LINE_HEIGHT_MULTIPLIER = 1.1f"))
        assertTrue(assets.contains("scaledMarkdownTextStyle("))
        assertTrue(assets.contains("lineHeightStyle = LineHeightStyle("))
        assertTrue(assets.contains("alignment = LineHeightStyle.Alignment.Center"))
        assertTrue(assets.contains("trim = LineHeightStyle.Trim.Both"))
        assertTrue(assets.contains("ChatType.body.copy(fontWeight = bodyFontWeight)"))
        assertTrue(assets.contains("val thoughtMarkdownBodyStyle = scaledMarkdownTextStyle(ChatType.thoughtBody)"))
        assertTrue(assets.contains("h1 = scaledMarkdownTextStyle(ChatType.mdH1)"))
        assertTrue(assets.contains("h6 = scaledMarkdownTextStyle(ChatType.mdH6)"))
        assertTrue(assets.contains("code = scaledMarkdownTextStyle(ChatType.code)"))
        assertTrue(assets.contains("h1 = scaledMarkdownTextStyle(ChatType.thH1)"))
        assertTrue(assets.contains("h6 = scaledMarkdownTextStyle(ChatType.thH6)"))
        assertTrue(assets.contains("code = scaledMarkdownTextStyle(ChatType.thoughtCode)"))
        assertTrue(assets.contains("plainTextStyle = markdownBodyStyle"))
        assertTrue(assets.contains("plainTextStyle = thoughtMarkdownBodyStyle"))
    }

    @Test
    fun `list item paragraph boundaries restore markdown block spacing`() {
        val assets = source(locateMainSourceRoot(), "message/MessageBubbleAssets.kt")

        assertTrue(assets.contains("model.node.needsListParagraphSpacer()"))
        assertTrue(assets.contains("Modifier.padding(top = LocalMarkdownPadding.current.block)"))
    }


    @Test
    fun `Select Text reuses the sheet shell with twelve dp raw-content top inset`() {
        val root = locateMainSourceRoot()
        val item = source(root, "message/MessageItem.kt")
        val detail = source(root, "message/SegmentDetailSheet.kt")

        assertTrue(item.contains("showUserTextSelection"))
        assertTrue(item.contains("titleOverride = stringResource(R.string.select_text)"))
        assertTrue(item.contains("directSelectableTextContent = message.text"))
        assertTrue(detail.contains("directSelectableTextContent: String? = null"))
        assertTrue(detail.contains("NoAutoScrollSelectionContainer("))
        assertTrue(detail.contains("SearchHighlightedPlainText("))
        assertTrue(detail.contains("padding(top = 12.dp, bottom = 32.dp)"))
        assertTrue(detail.contains("SmoothBottomSheet("))
        assertTrue(detail.contains("rememberSmoothBottomSheetState("))
        assertFalse(detail.contains("Dialog("))
        assertFalse(detail.contains("detectVerticalDragGestures("))
        assertFalse(detail.contains("snapshotFlow"))
    }

    private fun source(root: File, relative: String): String =
        File(root, "com/newoether/agora/ui/chat/$relative").readText()

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
