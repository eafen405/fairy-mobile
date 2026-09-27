package com.newoether.agora.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.remote.RemoteConnectionStore
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteImageCache
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.ui.chat.ChatTopBar
import com.newoether.agora.ui.remote.FairyLogin
import com.newoether.agora.ui.remote.RemoteConnecting
import com.newoether.agora.ui.settings.McpStatusDot
import com.newoether.agora.ui.theme.AgoraTheme
import java.io.File
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
class ZzzS3ScreenshotTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(
        name: String,
        freezeClock: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        if (freezeClock) compose.mainClock.autoAdvance = false
        compose.setContent { AgoraTheme { content() } }
        if (freezeClock) {
            repeat(30) { compose.mainClock.advanceTimeBy(16) }
        } else {
            compose.waitForIdle()
        }
        val image: Bitmap = compose.activity.window.decorView.drawToBitmap()
        val file = ZzzScreenshots.savePng(name, image)
        assertTrue("screenshot not written: $file", file.isFile && file.length() > 0)
    }

    @Composable
    private fun fakeRemote(): RemoteViewModel {
        val context = LocalContext.current.applicationContext
        val imageDirectory = File(context.cacheDir, "remote-images")
        return viewModel {
            RemoteViewModel(
                RemoteConnectionStore(File(context.noBackupFilesDir, "remote-connections.json")),
                com.newoether.agora.tool.ToolImageStore(context, imageDirectory),
                RemoteImageCache(imageDirectory),
                attachmentStore = com.newoether.agora.remote.RemoteAttachmentStore(context),
                fileStore = com.newoether.agora.remote.RemoteFileStore(context),
            )
        }
    }

    @Test
    fun `top bar remote normal`() {
        shot("s3-topbar") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                ChatTopBar(
                    isNewChatMode = false,
                    conversations = emptyList(),
                    currentConversationId = "s1",
                    currentConversationTitle = "测试会话",
                    totalTokens = 0,
                    contextTokenBudget = 0,
                    subtitle = "已连接",
                    subtitleLeading = { McpStatusDot(McpConnectionStatus.CONNECTED) },
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onSystemPromptClick = {},
                    forceBrandTitle = true,
                )
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
    fun `top bar remote normal zh`() {
        shot("s3-topbar-zh") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                ChatTopBar(
                    isNewChatMode = false,
                    conversations = emptyList(),
                    currentConversationId = "s1",
                    currentConversationTitle = "测试会话",
                    totalTokens = 0,
                    contextTokenBudget = 0,
                    subtitle = "已连接",
                    subtitleLeading = { McpStatusDot(McpConnectionStatus.CONNECTED) },
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onSystemPromptClick = {},
                    forceBrandTitle = true,
                )
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
    fun `connecting page zh`() {
        shot("s3-connecting-zh", freezeClock = true) {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                RemoteConnecting(
                    state = RemoteState(loading = true, restoring = false),
                    vm = fakeRemote(),
                )
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
    fun `login page zh`() {
        shot("s3-login-zh") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                FairyLogin(
                    state = RemoteState(restoring = false),
                    vm = fakeRemote(),
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `top bar search mode`() {
        shot("s3-topbar-search") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                ChatTopBar(
                    isNewChatMode = false,
                    conversations = emptyList(),
                    currentConversationId = "s1",
                    totalTokens = 0,
                    contextTokenBudget = 0,
                    searchActive = true,
                    searchQuery = "复核",
                    searchMatchIndex = 0,
                    searchMatchCount = 2,
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onSystemPromptClick = {},
                )
            }
        }
    }

    @Test
    fun `connecting page`() {
        shot("s3-connecting", freezeClock = true) {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                RemoteConnecting(
                    state = RemoteState(loading = true, restoring = false),
                    vm = fakeRemote(),
                )
            }
        }
    }

    @Test
    fun `connecting failure`() {
        shot("s3-connecting-failed") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                RemoteConnecting(
                    state = RemoteState(
                        restoring = false,
                        failure = RemoteFailure.NETWORK,
                    ),
                    vm = fakeRemote(),
                )
            }
        }
    }

    @Test
    fun `login page`() {
        shot("s3-login") {
            Box(Modifier.fillMaxSize()) {
                ZzzBackground()
                FairyLogin(
                    state = RemoteState(restoring = false),
                    vm = fakeRemote(),
                    onBack = {},
                )
            }
        }
    }
}
