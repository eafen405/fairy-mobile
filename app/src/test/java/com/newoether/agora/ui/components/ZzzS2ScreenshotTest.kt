package com.newoether.agora.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RemoteFile
import com.newoether.agora.ui.chat.message.MessageItem
import com.newoether.agora.ui.chat.message.SegmentAppearanceRegistry
import com.newoether.agora.ui.chat.message.ThinkingDots
import com.newoether.agora.ui.chat.message.FairyBubble
import com.newoether.agora.ui.theme.AgoraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
class ZzzS2ScreenshotTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(
        name: String,
        freezeClock: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        if (freezeClock) compose.mainClock.autoAdvance = false
        compose.setContent { AgoraTheme { content() } }
        if (freezeClock) {
            // Infinite animations (progress spin, breath) never go idle; pump a
            // fixed number of frames instead of waitForIdle().
            repeat(30) { compose.mainClock.advanceTimeBy(16) }
        } else {
            compose.waitForIdle()
        }
        val image: Bitmap = compose.activity.window.decorView.drawToBitmap()
        val file = ZzzScreenshots.savePng(name, image)
        assertTrue("screenshot not written: $file", file.isFile && file.length() > 0)
    }

    @Composable
    private fun item(
        text: String,
        participant: Participant,
        status: MessageStatus = MessageStatus.SUCCESS,
        segments: List<MessageSegment>? = null,
        images: List<String> = emptyList(),
        remoteFiles: List<RemoteFile> = emptyList(),
        emblemAnimating: Boolean = false,
        isLoading: Boolean = false,
    ) {
        MessageItem(
            message = ChatMessage(
                id = "shot-${'$'}text",
                text = text,
                participant = participant,
                status = status,
                segments = segments,
                images = images,
                remoteFiles = remoteFiles,
            ),
            onEdit = { _, _ -> },
            segmentAppearanceRegistry = remember { SegmentAppearanceRegistry() },
            emblemAnimating = emblemAnimating,
            isLoading = isLoading,
        )
    }

    @Test
    fun `conversation question and answer`() {
        shot("s2-qa") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter)) {
                    item(
                        text = "帮我复核一下这次的改动。",
                        participant = Participant.USER,
                    )
                    item(
                        text = "主人，我复核了一遍。文件层面没有变化。",
                        participant = Participant.MODEL,
                    )
                }
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
    fun `conversation question and answer zh`() {
        shot("s2-qa-zh") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter)) {
                    item(
                        text = "帮我复核一下这次的改动。",
                        participant = Participant.USER,
                    )
                    item(
                        text = "主人，我复核了一遍。文件层面没有变化。",
                        participant = Participant.MODEL,
                    )
                }
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
    fun `thinking tail zh`() {
        shot("s2-thinking-tail-zh", freezeClock = true) {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    item(
                        text = "先整理一下日志文件。",
                        participant = Participant.USER,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        FairyEmblem(animating = false, size = 40.dp, breathOverride = 1f)
                        Spacer(Modifier.width(8.dp))
                        FairyBubble { ThinkingDots() }
                    }
                }
            }
        }
    }

    @Test
    fun `long markdown with code and table`() {
        val md = """
            |主人，这是对比结果：
            |
            || 项目 | 旧值 | 新值 |
            || ---- | ---- | ---- |
            || 背景 | 渐变 | 斜纹 |
            || 主色 | 蓝 | 黄 |
            |
            |```kotlin
            |val bubble = FairyBubble(color = tokens.fairyBubble)
            |val tail = drawBubbleTail(color, tailAtTopEnd = true)
            |```
            |
            |以上是全部内容。
        """.trimMargin()
        shot("s2-markdown") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    item(text = md, participant = Participant.MODEL)
                }
            }
        }
    }

    @Test
    fun `thinking tail with emblem at breath peak`() {
        shot("s2-thinking-tail") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    item(
                        text = "先整理一下日志文件。",
                        participant = Participant.USER,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        FairyEmblem(animating = false, size = 40.dp, breathOverride = 1f)
                        Spacer(Modifier.width(8.dp))
                        FairyBubble { ThinkingDots() }
                    }
                }
            }
        }
    }

    @Test
    fun `inline activity chip`() {
        shot("s2-activity", freezeClock = true) {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    item(
                        text = "",
                        participant = Participant.MODEL,
                        status = MessageStatus.TOOL_CALLING,
                        segments = listOf(
                            MessageSegment(type = "tool", content = "shell: ls -la"),
                        ),
                        emblemAnimating = true,
                        isLoading = true,
                    )
                }
            }
        }
    }

    @Test
    fun `relayed message with file card`() {
        shot("s2-relay-file") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    item(
                        text = "这是书房同步过来的笔记。",
                        participant = Participant.MODEL,
                        remoteFiles = listOf(
                            RemoteFile(
                                fileId = "file-1",
                                name = "notes-2025.md",
                                bytes = 42_400,
                                mime = "text/markdown",
                                source = "书房",
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `user message with attachment`() {
        shot("s2-user-attachment") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    item(
                        text = "主人要我处理这张截图。",
                        participant = Participant.USER,
                        images = listOf("/tmp/nonexistent.png"),
                    )
                }
            }
        }
    }
}
