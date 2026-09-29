package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownMessageSourceContractTest {


    @Test
    fun `finalized virtualized Thinking detail remains selectable without auto scroll`() {
        val detail = source(locateMainSourceRoot(), "SegmentDetailSheet.kt")
        val virtualizedDetail = detail
            .substringAfter("val detailPageContent")
            .substringAfter("if (usesVirtualizedSingleMarkdown)")
            .substringBefore("} else {")

        assertTrue(virtualizedDetail.contains("NoAutoScrollSelectionContainer("))
        assertTrue(virtualizedDetail.contains("LazyMarkdownTextContent("))
        assertTrue(detail.contains("selectionEnabled = !isStreaming"))
    }




    @Test
    fun `low level incremental renderer has exactly one UI caller`() {
        val root = locateMainSourceRoot()
        val consumers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("IncrementalStreamingMarkdownContent(") }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("IncrementalStreamingMarkdown.kt", "StreamingMarkdownMessage.kt"),
            consumers,
        )
    }

    private fun source(root: File, name: String): String =
        File(root, "com/newoether/agora/ui/chat/message/$name").readText()

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
