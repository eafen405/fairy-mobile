package com.newoether.agora.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ApplicationUiSourceContractTest : UiSourceContractFixture() {

    @Test
    fun `singular transcription ellipsis exists in every locale`() {
        val directories = listOf(
            "values", "values-zh",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue(
                "$directory transcription_ellipsis_single",
                strings.contains("name=\"transcription_ellipsis_single\""),
            )
        }
    }

    @Test
    fun `Skills UI strings keep locale and delete placeholder parity`() {
        val directories = listOf(
            "values", "values-zh",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue("$directory skills_create_hint", strings.contains("name=\"skills_create_hint\""))
            assertTrue("$directory skills_create", strings.contains("name=\"skills_create\""))
            assertTrue("$directory skills_edit", strings.contains("name=\"skills_edit\""))
            assertTrue(
                "$directory skills_delete_message placeholder",
                stringValue(strings, "skills_delete_message").contains("%1\$s"),
            )
        }
    }

    @Test
    fun `PDF page bitmaps are initialized opaque white before both framework render paths`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/util/PdfPageRenderer.kt",
        )

        assertTrue(source.contains("private const val MAX_PAGES = 5"))
        assertTrue(source.contains("private const val TARGET_LONG_EDGE = 1536"))
        assertTrue(source.contains("private fun createPageBitmap(width: Int, height: Int): Bitmap"))
        assertEquals(1, Regex("Bitmap\\.createBitmap\\(").findAll(source).count())
        assertTrue(source.contains("eraseColor(Color.WHITE)"))
        assertEquals(
            2,
            Regex("bitmap = createPageBitmap\\(width, height\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(
            2,
            Regex(
                "page\\.render\\(bitmap, null, null, " +
                    "PdfRenderer\\.Page\\.RENDER_MODE_FOR_DISPLAY\\)",
            ).findAll(source).count(),
        )
        assertEquals(
            2,
            Regex("Bitmap\\.CompressFormat\\.JPEG, 80").findAll(source).count(),
        )
        assertTrue(source.contains("for (index in selectedPages.sorted())"))
        assertTrue(source.contains("onProgress?.invoke(index + 1, effectiveTotal)"))
        assertEquals(4, Regex("paths\\.forEach \\{ File\\(it\\)\\.delete\\(\\) \\}")
            .findAll(source).count())
        assertTrue(source.contains(
            "): List<String> = renderAllPages(context, uri.toString(), maxPages, onProgress)",
        ))
        assertTrue(source.contains("val descriptor = openDescriptor(context, source) ?: return emptyList()"))
    }

    @Test
    fun `generation settings description names only localized LLM parameters`() {
        val expected = linkedMapOf(
            "values" to "LLM parameters",
            "values-zh" to "LLM 参数",
        )

        expected.forEach { (directory, value) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertEquals(
                "$directory settings_generation_desc",
                value,
                stringValue(strings, "settings_generation_desc"),
            )
        }
    }

    @Test
    fun `ordinary segment detail does not repeat message error while Compact keeps its error`() {
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val segmentDetail = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
        )

        val compactDetail = messageItem.substringAfter("if (showCompactDetail) {")
        val ordinarySegmentDetail = segmentDetail
            .substringAfter("internal fun MessageSegmentDetailHost(")
            .substringBefore("internal fun usesVirtualizedSegmentDetail(")

        assertTrue(compactDetail.contains("errorText = detailErrorText"))
        assertFalse(ordinarySegmentDetail.contains("errorText ="))
    }

    @Test
    fun `generation error and stopped bars share neutral body text presentation`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/GenerationErrorBar.kt",
        )
        val errorBar = source
            .substringAfter("internal fun GenerationErrorBar(")
            .substringBefore("internal fun StoppedGenerationBar(")
        val stoppedBar = source.substringAfter("internal fun StoppedGenerationBar(")

        assertTrue(errorBar.contains("GenerationTerminalText("))
        assertTrue(stoppedBar.contains("GenerationTerminalText("))
        assertTrue(source.contains("style = ChatType.body"))
        assertTrue(source.contains(
            "color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)",
        ))
    }

    @Test
    fun `editing a user message scrolls its turn to focus with reduced motion fallback`() {
        val messageList = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt",
        )
        val scrollActor = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/RobustLazyListScroll.kt",
        )
        val editFocus = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/MessageListEditScrollEffect.kt",
        ).substringAfter("LaunchedEffect(\n        conversationId,\n        editingMessageId,")

        assertTrue(messageList.contains(
            "val editingMessageIdState = remember(conversationId) { mutableStateOf<String?>(null) }",
        ))
        assertTrue(messageList.contains("editingMessageIdState = editingMessageIdState,"))
        assertTrue(editFocus.contains("messageListTurnIndex(turns, messageId)"))
        assertTrue(editFocus.contains("withFrameNanos { }"))
        assertTrue(editFocus.contains("cancelMutationAnchoring()"))
        assertTrue(editFocus.contains("140.dp.toPx()"))
        assertTrue(editFocus.contains("state.scrollToItem("))
        assertTrue(editFocus.contains("state.smoothSeekToItem("))
        assertTrue(scrollActor.contains("scroll(MutatePriority.Default)"))
    }

    @Test
    fun `user edit size owner includes the branch selector`() {
        val user = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )
        val stableBlock = user
            .substringAfter("Column(\n        horizontalAlignment = Alignment.End,")
            .substringBefore("DropdownMenu(")

        assertEquals(1, Regex("Modifier\\.animateContentSize").findAll(user).count())
        assertTrue(stableBlock.contains("Modifier.animateContentSize"))
        assertTrue(user.substringAfter(stableBlock).contains("if (showBranchSelector"))
    }

    @Test
    fun `Context and Thinking segment labels are localized in every supported locale`() {
        val keys = listOf(
            "context_title",
            "context_desc",
            "thinking_segment_display_mode",
            "thinking_segment_display_mode_desc",
            "thinking_segment_display_card",
            "thinking_segment_display_bottom_sheet",
            "thinking_segments_title",
        )
        val expected = linkedMapOf(
            "values-zh" to listOf(
                "上下文", "上下文管理", "思考片段",
                "选择思考片段的打开位置", "卡片", "底部面板", "思考片段",
            ),
        )

        expected.forEach { (directory, values) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            keys.zip(values).forEach { (key, value) ->
                assertEquals("$directory $key", value, stringValue(strings, key))
            }
        }
    }

    private fun stringValue(xml: String, key: String): String {
        val regex = Regex("""<string name="$key">([^<]*)</string>""")
        return requireNotNull(regex.find(xml)) { "Missing $key" }.groupValues[1]
    }
}
