package com.newoether.agora.remote

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.chat.message.CompactSegmentBlock
import com.newoether.agora.ui.chat.message.SegmentAppearanceRegistry
import com.newoether.agora.ui.chat.message.ToolDetailContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteActivityRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hydratedActivityCardReturnsToThinkingAfterEveryTerminalOutcome() = runTest {
        val message = mutableStateOf(hydrate("running", active = true))
        compose.setContent {
            MaterialTheme {
                val current = message.value
                CompactSegmentBlock(
                    segs = current.segments!!,
                    segmentIndices = listOf(0),
                    message = current,
                    isStreaming = false,
                    useLiveStatus = true,
                    generationActive = true,
                    isCurrentCard = true,
                    expandedStates = remember { mutableStateMapOf() },
                    expansionKey = "activity",
                    segmentAppearanceRegistry = remember { SegmentAppearanceRegistry() },
                    onSegmentClick = {},
                )
            }
        }
        compose.onNodeWithText("Search the web").assertIsDisplayed()
        val thinking = ApplicationProvider.getApplicationContext<Application>().getString(R.string.thinking_ellipsis)
        for (state in listOf("succeeded", "failed", "stopped")) {
            val recovered = hydrate(state, active = true)
            compose.runOnIdle { message.value = recovered }
            compose.onNodeWithText(thinking).assertIsDisplayed()
            val running = hydrate("running", active = true)
            compose.runOnIdle { message.value = running }
            compose.onNodeWithText("Search the web").assertIsDisplayed()
        }
    }

    @Test fun recoveredFailureAndStopRenderOnlyTheBoundedNote() = runTest {
        val message = mutableStateOf(hydrate("failed", active = false))
        compose.setContent {
            MaterialTheme {
                Column { ToolDetailContent(message.value.segments!!.single(), onMediaClick = { _, _ -> }) }
            }
        }
        compose.onNodeWithText("The operation did not finish").assertIsDisplayed()
        val arguments = ApplicationProvider.getApplicationContext<Application>().getString(R.string.arguments_label)
        compose.onNodeWithText(arguments).assertDoesNotExist()
        val stopped = hydrate("stopped", active = false)
        compose.runOnIdle { message.value = stopped }
        compose.onNodeWithText("The operation did not finish").assertIsDisplayed()
        compose.onNodeWithText(arguments).assertDoesNotExist()
    }

    private suspend fun hydrate(activityState: String, active: Boolean): ChatMessage {
        val record = RemoteMessage("activity", "turn", null, "assistant", "", 1,
            activity = RemoteActivity("tool", activityState, label = "Search the web",
                note = "The operation did not finish"))
        val node = RemoteMessageNode("activity", "turn", null, "assistant", 1, activityState, 0,
            activity = RemoteNodeActivity("tool", activityState, label = "Search the web"))
        val runtime = RemoteRuntime(if (active) "active" else "idle", "turn", activeTurnHasUserMessage = true)
        val state = MutableStateFlow(RemoteState(deviceId = "device",
            session = RemoteSession("session", "Task", "", 1), hydrationEnabled = true,
            messageGroups = projectRemoteTopology(listOf(node), runtime)))
        val hydration = RemoteMessageHydration(state,
            { _, _ -> RemoteConversationPage(listOf(record), null, emptyList(), nodes = listOf(node), runtime = runtime) },
            { throw it })
        return hydration.loadMessages(state.value.owner!!, listOf("activity")).single()
    }
}
