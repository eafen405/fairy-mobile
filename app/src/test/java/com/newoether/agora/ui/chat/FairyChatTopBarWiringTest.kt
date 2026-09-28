package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FairyChatTopBarWiringTest {
    @Test
    fun `ordinary chat uses the shared Fairy window state`() {
        val source = sourceFile("ChatApp.kt")

        val state = sourceFile("FairyChatWindowState.kt")

        assertTrue(source.contains("rememberChatFairyWindowState("))
        assertTrue(source.contains("fairyWindow = fairyWindow"))
        assertTrue(state.contains("connection = McpConnectionStatus.CONNECTED"))
        assertTrue(state.contains("expanded = !isNewChatMode"))
        assertTrue(state.contains("speechPulse = textGrowth.pulse"))
        assertTrue(source.contains("forceBrandTitle = true"))
        assertTrue(source.contains("FairyWindowBarHeight + 12.dp"))
    }

    private fun sourceFile(name: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(
                directory,
                "app/src/main/java/com/newoether/agora/ui/chat/$name",
            )
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: error("Unable to locate $name")
        }
    }
}
