package com.newoether.agora.ui

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ApprovedFeatureSourceContractTest : UiSourceContractFixture() {

    @Test
    fun contextProgressTweensLocallyAndSnapsForReducedMotion() {
        val root = sourceRoot()
        val controls = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatBottomBarComponents.kt",
        )
        val sharedProgress = source(
            root,
            "com/newoether/agora/ui/motion/MotionAwareProgressIndicators.kt",
        )

        assertTrue(controls.contains("val contextProgress by animateFloatAsState("))
        assertTrue(controls.contains("motionPolicy.allowContinuousMotion"))
        assertTrue(controls.contains("tween(durationMillis = 400)"))
        assertTrue(controls.contains("snap()"))
        assertTrue(controls.contains("progress = { if (available) contextProgress else 0f }") && controls.contains("progress = { contextProgress }"))
        assertFalse(sharedProgress.contains("animateFloatAsState"))
    }

    @Test
    fun toolResultImageContextRowKeepsANonProtocolIdPrefix() {
        val root = sourceRoot()
        val toolMessages = source(root, "com/newoether/agora/api/util/ToolMessages.kt")

        // The API-only image-context row must never start with a protocol prefix: provider
        // serializers branch on tool_/result_ and would silently drop the row (view_image
        // results would display in the UI but never reach the model).
        assertTrue(toolMessages.contains("id = \"image_context_\$digest\""))
        assertFalse(toolMessages.contains("tool_image_context_"))
    }

    @Test
    fun backgroundShellJobDoesNotOccupyTheGroupLoadingIndicator() {
        val root = sourceRoot()
        val presentation = source(
            root,
            "com/newoether/agora/ui/chat/message/ToolPresentation.kt",
        )
        val labels = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemToolLabels.kt",
        )

        // isActive drives the group loading bar; a detached background job must not occupy it.
        assertTrue(presentation.contains(
            "state == ToolPresentationState.CALLING ||\n            state == ToolPresentationState.RUNNING"
        ))
        assertFalse(presentation.contains(
            "state == ToolPresentationState.BACKGROUND_RUNNING\n"
        ))
        // The card still shows the background status (matched before isActive).
        assertTrue(labels.contains(
            "presentation.state == ToolPresentationState.BACKGROUND_RUNNING ->"
        ))
    }

    @Test
    fun expandedTimelineSegmentsKeepSpacingWithoutVisibleDividers() {
        val root = sourceRoot()
        val timeline = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemTimeline.kt",
        )

        assertTrue(timeline.contains("if (idx < segs.lastIndex)"))
        assertTrue(timeline.contains("modifier = Modifier.padding(vertical = 2.dp)"))
        assertTrue(timeline.contains("color = Color.Transparent"))
        assertFalse(timeline.contains("outlineVariant.copy(alpha = 0.2f)"))
    }

    @Test
    fun developerCapturePageKeepsApprovedUiAndCanonicalOwners() {
        val capture = source(
            sourceRoot(),
            "com/newoether/agora/ui/settings/SettingsDeveloperCapturePage.kt",
        )
        val modes = capture
            .substringAfter("private enum class CaptureViewMode {")
            .substringBefore("}")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val eventCard = capture
            .substringAfter("private fun CaptureEventCard(")
            .substringBefore("private fun CaptureEventContent(")

        assertEquals(listOf("SUMMARY,", "RAW,"), modes)
        assertTrue(capture.contains("PillTabSwitcher("))
        assertFalse(capture.contains("SingleChoiceSegmentedButtonRow"))
        assertFalse(capture.contains("SegmentedButton("))
        assertTrue(capture.contains("actions = {"))
        assertTrue(capture.contains("Icons.Default.MoreVert"))
        assertTrue(capture.contains("containerColor = MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(capture.contains("tonalElevation = 16.dp"))
        assertTrue(capture.contains("shape = RoundedCornerShape(12.dp)"))
        assertTrue(
            capture.contains("R.string.developer_options_clear_diagnostics_action"),
        )
        assertTrue(capture.contains("R.string.developer_options_clear_diagnostics)"))
        assertEquals(3, Regex("\\bCaptureExportMenuItem\\(").findAll(capture).count())
        assertFalse(capture.contains("DiagnosticExportFormat.RAW_JSON"))
        assertTrue(capture.contains("DiagnosticExportFormat.REDACTED_JSON"))
        assertTrue(capture.contains("DiagnosticExportFormat.SUMMARY_TEXT"))
        assertTrue(capture.contains("FloatingActionButton("))
        assertEquals(2, Regex("\\bSmallFloatingActionButton\\(").findAll(capture).count())
        assertEquals(3, Regex("shape = CircleShape").findAll(capture).count())
        assertTrue(capture.contains("horizontalArrangement = Arrangement.End"))
        assertTrue(capture.contains(".padding(end = 24.dp, bottom = 24.dp)"))
        assertFalse(capture.contains(".padding(horizontal = 16.dp)"))
        assertFalse(capture.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
        assertEquals(2, Regex("Modifier\\.padding\\(bottom = 12\\.dp\\)").findAll(capture).count())
        assertTrue(capture.contains("targetState = captureRunning"))
        assertTrue(capture.contains("DeveloperDiagnostics.startCapture()"))
        assertTrue(capture.contains("DeveloperDiagnostics.pauseCapture()"))
        assertTrue(capture.contains("captureActionEnabled = !snapshot.capacityLimitReached"))
        assertTrue(capture.contains("onClick = { requestDirectionalScroll(toTop = true) }"))
        assertTrue(capture.contains("onClick = { requestDirectionalScroll(toTop = false) }"))
        assertTrue(capture.contains("R.string.developer_options_capture_capacity_incomplete"))
        assertEquals(1, Regex("Modifier\\.semantics \\{ disabled\\(\\) \\}").findAll(capture).count())
        assertTrue(capture.contains("MaterialTheme.colorScheme.surfaceVariant"))
        assertTrue(capture.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(capture.contains("CaptureCrossfadeDurationMillis = 250"))
        assertEquals(2, Regex("\\bCrossfade\\(").findAll(capture).count())
        assertEquals(1, Regex("\\bAnimatedContent\\(").findAll(capture).count())
        assertEquals(2, Regex("\\bAnimatedVisibility\\(").findAll(capture).count())
        assertEquals(2, Regex("expandVertically\\(").findAll(capture).count())
        assertEquals(2, Regex("shrinkVertically\\(").findAll(capture).count())
        assertEquals(2, Regex("targetState = viewMode").findAll(capture).count())
        assertTrue(capture.contains("items(snapshot.events, key = DiagnosticEvent::sequence)"))
        assertFalse(capture.contains("snapshot.events.reversed"))
        assertFalse(capture.contains("snapshot.events.asReversed"))
        assertTrue(eventCard.contains("Surface("))
        assertTrue(eventCard.contains("shape = RoundedCornerShape(24.dp)"))
        assertTrue(
            eventCard.contains(
                "LocalAgoraMotionPolicy.current.allowSpatialTransitions",
            ),
        )
        assertTrue(eventCard.contains("AnimatedContent("))
        assertTrue(eventCard.contains("targetState = viewMode"))
        assertTrue(eventCard.contains("fadeIn("))
        assertTrue(eventCard.contains("fadeOut("))
        assertTrue(eventCard.contains("SizeTransform("))
        assertTrue(eventCard.contains("clip = false"))
        assertTrue(eventCard.contains("if (allowSpatialTransitions)"))
        assertTrue(
            eventCard.contains("tween(CaptureCrossfadeDurationMillis)"),
        )
        assertTrue(eventCard.contains("snap()"))
        assertFalse(eventCard.contains("Modifier.animateContentSize("))
        assertTrue(eventCard.contains("SettingsItem("))
        assertFalse(eventCard.contains("leadingContent"))
        assertFalse(capture.contains("FontFamily"))
        assertFalse(capture.contains("fontFamily ="))
        assertFalse(capture.contains("collectIsDraggedAsState()"))
        assertFalse(capture.contains("directionalScrollJob"))
        assertFalse(capture.contains("directionalScrollRequestId"))
        assertFalse(capture.contains("directionalScrollActive"))
        assertFalse(capture.contains("scrollUpEnabled"))
        assertFalse(capture.contains("scrollDownEnabled"))
        assertFalse(capture.contains("allowProgrammaticScrollMotion"))
        assertFalse(capture.contains("animateToAbsoluteTop"))
        assertFalse(capture.contains("animateToAbsoluteBottom"))
        assertTrue(capture.contains("CaptureEdgeTolerance = 2.dp"))
        listOf(
            "val edgeTolerancePx = with(density) { CaptureEdgeTolerance.roundToPx() }",
            "listState.firstVisibleItemIndex == 0",
            "listState.firstVisibleItemScrollOffset <= edgeTolerancePx.coerceAtLeast(0)",
            "val lastVisibleItem = layoutInfo.visibleItemsInfo.maxByOrNull { it.index }",
            "lastVisibleItem?.index == layoutInfo.totalItemsCount - 1",
            "lastVisibleItem.offset + lastVisibleItem.size <=",
            "layoutInfo.viewportEndOffset + edgeTolerancePx.coerceAtLeast(0)",
            "val canScrollUp = !atTop",
            "val canScrollDown = !atBottom",
        ).forEach { edgeContract ->
            assertTrue(capture.contains(edgeContract))
        }
        assertFalse(capture.contains("listState.canScrollBackward"))
        assertFalse(capture.contains("listState.canScrollForward"))
        assertTrue(capture.contains("listState.scrollToItem(0)"))
        assertTrue(capture.contains("listState.scrollToItem(lastIndex)"))
        assertFalse(capture.contains("estimatedItemSizePx"))
        assertFalse(capture.contains("remainingItems * averageVisibleSizePx"))
        assertTrue(capture.contains("visible = hasNavigableEvents && canScrollUp"))
        assertTrue(capture.contains("visible = hasNavigableEvents && canScrollDown"))
        assertFalse(capture.contains("followLatest"))
        assertFalse(capture.contains("scrollToLatestCaptureEvent"))
        assertFalse(capture.contains("animateScrollToItem"))
        assertFalse(capture.contains("R.string.developer_options_capture_jump_latest"))
        assertFalse(capture.contains("R.string.developer_options_capture_export_raw_json"))
        assertTrue(capture.contains("item(key = \"capture-fab-spacer\")"))
        assertTrue(capture.contains("Spacer(Modifier.height(80.dp))"))
        assertTrue(capture.contains("val rawEventDetails = remember(event) { event.rawDetails() }"))
        assertTrue(capture.contains("CaptureViewMode.RAW -> rawEventDetails"))
        assertTrue(capture.contains("captureEventJson.encodeToString(DiagnosticEvent.serializer(), this)"))
        assertTrue(capture.contains("DeveloperDiagnostics.snapshots.collectAsState()"))
        assertTrue(capture.contains("DeveloperDiagnostics.clear()"))
        assertTrue(capture.contains("DeveloperDiagnostics.flush()"))
        assertFalse(capture.contains("CaptureToolbar("))
        assertFalse(capture.contains("CaptureIconAction("))
        listOf(
            "\"Start\"",
            "\"Pause\"",
            "\"Clear\"",
            "\"Export\"",
            "\"Summary\"",
            "\"Raw\"",
            "\"Scroll to Top\"",
            "\"Scroll to Bottom\"",
            "\"No captured events.\"",
        ).forEach { hardCodedLabel ->
            assertFalse("Capture page still contains $hardCodedLabel", capture.contains(hardCodedLabel))
        }
        assertFalse(capture.contains("DiagnosticCaptureStore"))
        assertFalse(capture.contains("DiagnosticEventBuffer"))
        assertFalse(capture.contains("noBackupFilesDir"))
    }

    @Test
    fun developerResourcesExposeOnlyFinalLocalizedKeySet() {
        val resourceRoot = File(sourceRoot().parentFile, "res")
        val localeFiles = resourceRoot.listFiles()
            ?.filter { directory ->
                directory.isDirectory &&
                    (directory.name == "values" || directory.name.startsWith("values-"))
            }
            ?.map { directory -> File(directory, "strings.xml") }
            ?.filter(File::isFile)
            ?.sortedBy { file -> checkNotNull(file.parentFile).name }
            .orEmpty()
        val expectedKeys = setOf(
            "developer_options_already_enabled_message",
            "developer_options_capture",
            "developer_options_capture_clear_confirm",
            "developer_options_capture_clear_message",
            "developer_options_capture_counters",
            "developer_options_capture_capacity_incomplete",
            "developer_options_capture_description",
            "developer_options_capture_empty",
            "developer_options_capture_export_redacted_json",
            "developer_options_capture_export_summary_text",
            "developer_options_capture_http_request_summary",
            "developer_options_capture_http_response_summary",
            "developer_options_capture_more_actions",
            "developer_options_capture_parsed_event_summary",
            "developer_options_capture_pause",
            "developer_options_capture_play",
            "developer_options_capture_raw",
            "developer_options_capture_scroll_to_bottom",
            "developer_options_capture_scroll_to_top",
            "developer_options_capture_session",
            "developer_options_capture_state_idle",
            "developer_options_capture_state_paused",
            "developer_options_capture_state_running",
            "developer_options_capture_status_summary",
            "developer_options_capture_summary",
            "developer_options_capture_wire_line_summary",
            "developer_options_clear_diagnostics",
            "developer_options_clear_diagnostics_action",
            "developer_options_debug_model",
            "developer_options_debug_model_description",
            "developer_options_disable_confirm",
            "developer_options_disable_message",
            "developer_options_disable_title",
            "developer_options_enabled_message",
            "developer_options_export_failed",
            "developer_options_export_share_title",
            "developer_options_features_group",
            "developer_options_mode_description",
            "developer_options_taps_remaining",
            "developer_options_title",
        )
        val developerKey = Regex("""<string name="(developer_options_[^"]+)"""")

        assertEquals(2, localeFiles.size)
        localeFiles.forEach { file ->
            val keys = developerKey.findAll(file.readLocaleStringResourceSources())
                .map { match -> match.groupValues[1] }
                .toList()
            assertEquals(
                "${checkNotNull(file.parentFile).name} contains duplicate Developer keys",
                keys.size,
                keys.toSet().size,
            )
            assertEquals(
                "${checkNotNull(file.parentFile).name} has an unexpected Developer key set",
                expectedKeys,
                keys.toSet(),
            )
        }
    }

}
