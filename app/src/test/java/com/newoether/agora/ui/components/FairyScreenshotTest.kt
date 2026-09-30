package com.newoether.agora.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.R
import com.newoether.agora.model.McpConnectionStatus
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RemoteFile
import com.newoether.agora.remote.RemoteConnectionStore
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.ui.chat.ChatBottomScrollButton
import com.newoether.agora.ui.chat.ChatTopBar
import com.newoether.agora.ui.chat.bottombar.ChatComposerLayout
import com.newoether.agora.ui.chat.bottombar.ComposerSendButton
import com.newoether.agora.ui.chat.message.MessageItem
import com.newoether.agora.ui.chat.message.SegmentAppearanceRegistry
import com.newoether.agora.ui.remote.FairyEmptyGreeting
import com.newoether.agora.ui.remote.FairyLogin
import com.newoether.agora.ui.remote.RemoteAttachmentPicker
import com.newoether.agora.ui.remote.RemoteConnecting
import com.newoether.agora.ui.settings.McpStatusDot
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.ui.theme.LocalFairyTokens
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Visual acceptance frames for the Fairy look, written as PNGs under
 * `app/build/outputs/fairy-screenshots/`. Every frame runs on a frozen clock
 * (the window's frame loop never idles) and renders through drawToBitmap().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
class FairyScreenshotTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, breath: Float? = null, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgoraTheme {
                CompositionLocalProvider(LocalFairyBreathOverride provides breath) { content() }
            }
        }
        repeat(40) { compose.mainClock.advanceTimeBy(16) }
        val image: Bitmap = compose.activity.window.decorView.drawToBitmap()
        val file = FairyScreenshots.savePng(name, image)
        assertTrue("screenshot not written: $file", file.isFile && file.length() > 0)
    }

    @Composable
    private fun fakeRemote(): RemoteViewModel {
        val context = LocalContext.current.applicationContext
        return viewModel {
            RemoteViewModel(
                RemoteConnectionStore(File(context.noBackupFilesDir, "remote-connections.json")),
                attachmentStore = com.newoether.agora.remote.RemoteAttachmentStore(context),
                fileStore = com.newoether.agora.remote.RemoteFileStore(context),
            )
        }
    }

    @Composable
    private fun TopBar(
        presence: FairyPresence,
        expanded: Boolean = false,
        search: Boolean = false,
    ) {
        ChatTopBar(
            subtitle = stringResource(
                when (presence) {
                    FairyPresence.OFFLINE -> R.string.remote_offline
                    FairyPresence.CONNECTING -> R.string.remote_connecting
                    else -> R.string.remote_online
                },
            ),
            subtitleLeading = {
                McpStatusDot(
                    when (presence) {
                        FairyPresence.OFFLINE -> McpConnectionStatus.ERROR
                        FairyPresence.CONNECTING -> McpConnectionStatus.CONNECTING
                        else -> McpConnectionStatus.CONNECTED
                    },
                )
            },
            searchActive = search,
            searchQuery = if (search) "复核" else "",
            searchMatchIndex = if (search) 0 else -1,
            searchMatchCount = if (search) 2 else 0,
            onNavigateBack = {},
            fairyWindow = FairyWindowState(presence = presence, expanded = expanded),
            moreMenuContent = {},
        )
    }

    @Composable
    private fun Item(
        text: String,
        participant: Participant,
        status: MessageStatus = MessageStatus.SUCCESS,
        segments: List<MessageSegment>? = null,
        attachmentMeta: com.newoether.agora.model.AttachmentMeta? = null,
        remoteFiles: List<RemoteFile> = emptyList(),
        working: Boolean = false,
        gapBelow: Int = 12,
    ) {
        MessageItem(
            message = ChatMessage(
                id = "shot-$text-$status",
                text = text,
                participant = participant,
                status = status,
                segments = segments,
                attachmentMeta = attachmentMeta,
                remoteFiles = remoteFiles,
            ),
            segmentAppearanceRegistry = remember { SegmentAppearanceRegistry() },
            outerPadding = androidx.compose.foundation.layout.PaddingValues(bottom = gapBelow.dp),
            includeAssistantOuterSpacing = false,
            emblemAnimating = working,
            isStreaming = working,
        )
    }

    /** Page frame: background, window bar, and a message column under it. */
    @Composable
    private fun Page(
        presence: FairyPresence = FairyPresence.IDLE,
        composer: (@Composable BoxScope.() -> Unit)? = { Composer(TextFieldState()) },
        messages: @Composable () -> Unit,
    ) {
        Box(Modifier.fillMaxSize()) {
            FairyBackground()
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 60.dp + 12.dp),
            ) { messages() }
            TopBar(presence)
            composer?.invoke(this)
        }
    }

    @Composable
    private fun BoxScope.Composer(
        field: TextFieldState,
        showStop: Boolean = false,
        modifier: Modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Box(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            ChatComposerLayout(
                textFieldState = field,
                focusRequester = remember { FocusRequester() },
                onInputFocusChanged = {},
                isExpanded = false,
                isExpandAnimating = false,
                onExpand = {},
                onCollapse = {},
                singleLine = true,
                leadingControls = { RemoteAttachmentPicker("owner", true, fakeRemote()) },
                controls = {
                    ComposerSendButton(
                        isActionable = showStop || field.text.isNotBlank(),
                        isBusy = false,
                        showStop = showStop,
                        onClick = {},
                    )
                },
            )
        }
    }

    @Test
    fun `empty session with expanded window and quick prompts`() {
        shot("fairy-empty-session") {
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                TopBar(FairyPresence.IDLE, expanded = true)
                Box(Modifier.statusBarsPadding().padding(top = 60.dp + 12.dp + 140.dp + 24.dp)) {
                    FairyEmptyGreeting(enabled = true, onQuickPrompt = {})
                }
                Composer(TextFieldState())
            }
        }
    }

    @Test
    fun `plain question and answer`() {
        shot("fairy-qa") {
            Page {
                Item("帮我复核一下这次的改动。", Participant.USER)
                Item(
                    "主人，我复核了一遍。\n\n文件层面没有变化：`workspaces` 下仍是一条，没有新目录、新文件、新脚印。" +
                        "环境变量里也只有我自己。",
                    Participant.MODEL,
                    gapBelow = 28,
                )
                Item("那么明天天气怎么样？", Participant.USER)
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
            || 背景 | 近黑 | 深海军蓝 |
            || 主色 | 黄 | 冰白 |
            |
            |```kotlin
            |val window = FairyWindowState(presence = FairyPresence.IDLE)
            |val gap = fairyTurnGap(user, reply) // 12
            |```
            |
            |> 引用：蓝色只标识 Fairy 的身份与工作态。
            |
            |详情见 [设计说明](https://example.com)。
        """.trimMargin()
        shot("fairy-markdown") {
            Page(composer = null) { Item(md, Participant.MODEL) }
        }
    }

    @Test
    fun `thinking tail at breath peak`() {
        shot("fairy-thinking", breath = 1f) {
            Page(presence = FairyPresence.THINKING) {
                Item("先整理一下日志文件。", Participant.USER)
                Item("", Participant.MODEL, status = MessageStatus.THINKING, working = true)
            }
        }
    }

    @Test
    fun `activity hint running and done`() {
        shot("fairy-activity") {
            Page(presence = FairyPresence.THINKING) {
                Item(
                    "查一下明天的天气。",
                    Participant.USER,
                )
                Item(
                    "主人，天气查到了：明天多云，15 到 22 度。",
                    Participant.MODEL,
                    segments = listOf(
                        MessageSegment(type = "tool", content = "weather: tomorrow"),
                        MessageSegment(type = "answer", content = "主人，天气查到了：明天多云，15 到 22 度。"),
                    ),
                    gapBelow = 28,
                )
                Item("再看看后天。", Participant.USER)
                Item(
                    "",
                    Participant.MODEL,
                    status = MessageStatus.TOOL_CALLING,
                    segments = listOf(MessageSegment(type = "tool", content = "weather: day after")),
                    working = true,
                )
            }
        }
    }

    @Test
    fun `relayed message and file card`() {
        shot("fairy-relay-file") {
            Page {
                Item(
                    "这是书房同步过来的笔记。",
                    Participant.MODEL,
                    remoteFiles = listOf(
                        RemoteFile(
                            fileId = "file-1",
                            name = "notes-2026.md",
                            bytes = 42_400,
                            mime = "text/markdown",
                            source = "书房",
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `single-line composer empty, typed and stop`() {
        shot("fairy-composer") {
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                Column(Modifier.align(Alignment.Center).fillMaxWidth()) {
                    Box { Composer(TextFieldState(), modifier = Modifier) }
                    Box { Composer(TextFieldState("明天提醒我带伞"), modifier = Modifier) }
                    Box { Composer(TextFieldState(), showStop = true, modifier = Modifier) }
                    Box(Modifier.fillMaxWidth().height(64.dp)) {
                        ChatBottomScrollButton(showButton = true, bottomBarHeight = 0.dp, onClick = {})
                    }
                }
            }
        }
    }

    @Test
    fun `top bar search state`() {
        shot("fairy-topbar-search") {
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                TopBar(FairyPresence.IDLE, search = true)
            }
        }
    }

    @Test
    fun `more menu content`() {
        // Popup windows are not part of the decor view; the menu rows are drawn
        // on the same fairyPanel surface the DropdownMenu uses.
        shot("fairy-more-menu") {
            val tokens = LocalFairyTokens.current
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                TopBar(FairyPresence.IDLE)
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 64.dp, end = 8.dp)
                        .width(220.dp)
                        .fairyPanel(tokens.panelShape, tokens.panelOpaque)
                        .padding(vertical = 8.dp),
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.remote_context_usage, "12.4k", "128k"),
                                fontFamily = tokens.labelFontFamily,
                            )
                        },
                        enabled = false,
                        colors = MenuDefaults.itemColors(disabledTextColor = tokens.textMuted),
                        onClick = {},
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        onClick = {},
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_logout)) },
                        leadingIcon = { Icon(Icons.Default.Logout, null) },
                        onClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun `connecting page`() {
        shot("fairy-connecting") {
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                RemoteConnecting(state = RemoteState(loading = true, restoring = false), vm = fakeRemote())
            }
        }
    }

    @Test
    fun `connecting failure page`() {
        shot("fairy-connecting-failed") {
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                RemoteConnecting(
                    state = RemoteState(restoring = false, failure = RemoteFailure.NETWORK),
                    vm = fakeRemote(),
                )
            }
        }
    }

    @Test
    fun `login page`() {
        shot("fairy-login") {
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                FairyLogin(state = RemoteState(restoring = false), vm = fakeRemote(), onBack = {})
            }
        }
    }

    @Test
    fun `window presences side by side`() {
        shot("fairy-window-presences", breath = 1f) {
            Box(Modifier.fillMaxSize()) {
                FairyBackground()
                Column(Modifier.align(Alignment.Center).padding(16.dp)) {
                    listOf(
                        FairyPresence.IDLE,
                        FairyPresence.THINKING,
                        FairyPresence.CONNECTING,
                        FairyPresence.OFFLINE,
                    ).forEach { presence ->
                        FairyScreen(
                            presence = presence,
                            eyeSize = 88.dp,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
    @Test
    fun `user message with attachment`() {
        shot("fairy-user-attachment") {
            Page {
                Item(
                    "主人要我处理这张截图。",
                    Participant.USER,
                    attachmentMeta = com.newoether.agora.model.AttachmentMeta(listOf(
                        com.newoether.agora.model.AttachmentItem(
                            type = "image", fileName = "screenshot.png",
                            mimeType = "image/png",
                        ),
                    )),
                )
            }
        }
    }

}
