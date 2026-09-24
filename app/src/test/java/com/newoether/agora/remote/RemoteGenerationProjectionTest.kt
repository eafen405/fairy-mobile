package com.newoether.agora.remote

import com.newoether.agora.model.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class RemoteGenerationProjectionTest {
    private val answer = RemoteMessage("answer", "turn", null, "assistant", "Answer", 1)

    @Test fun nativeUserAuthoritySurvivesPagingButAnUnacknowledgedUserDoesNotStartTheIndicator() {
        val pending = RemoteRuntime("active", "turn")
        assertFalse(pending.hasVisibleGeneration(listOf(answer)))
        val native = pending.copy(activeTurnHasUserMessage = true)
        assertTrue(native.hasVisibleGeneration(listOf(answer)))
        assertEquals(MessageStatus.SENDING, projectRemoteMessages(listOf(answer), native).single().status)
        assertFalse(native.copy(status = "idle").hasVisibleGeneration(listOf(answer)))
        assertTrue(pending.hasVisibleGeneration(listOf(answer.copy(role = "user"))))
    }

    @Test fun completedBoundedActivityWithoutTextOrImagesRendersLabelAndCompletedState() {
        val image = answer.copy(text = "", activity = RemoteActivity("tool",
            state = "succeeded", durationMs = 30, label = "查看图片"))
        val segment = projectRemoteMessages(listOf(image)).single().segments!!.single()
        val presentation = com.newoether.agora.ui.chat.message.ToolPresentationResolver.resolve(segment)
        // The wire carries no tool identity, so the chip renders as a generic activity.
        assertEquals(com.newoether.agora.ui.chat.message.ToolKind.UNKNOWN, presentation.kind)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.COMPLETED, presentation.state)
        assertFalse(presentation.isActive)
        assertEquals("查看图片", segment.toolDisplayName)
        assertEquals(30L, segment.durationMs)
        assertNull(segment.toolName)
        assertNull(segment.toolResult)
        assertTrue(segment.toolImages.isEmpty())
    }

    @Test fun activityLifecycleComesOnlyFromTheWireState() {
        fun presentation(state: String? = null) =
            com.newoether.agora.ui.chat.message.ToolPresentationResolver.resolve(
                projectRemoteMessages(listOf(answer.copy(text = "", activity =
                    RemoteActivity("tool", state = state))))
                    .single().segments!!.single())
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.CALLING, presentation().state)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.RUNNING, presentation("running").state)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.COMPLETED, presentation("succeeded").state)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.FAILED, presentation("failed").state)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.STOPPED, presentation("stopped").state)
    }

    @Test fun activityNoteReachesFailurePresentationOnlyOnFailureOrStop() {
        fun presentation(state: String) =
            com.newoether.agora.ui.chat.message.ToolPresentationResolver.resolve(
                projectRemoteMessages(listOf(answer.copy(text = "", activity =
                    RemoteActivity("tool", state, label = "搜索网络", note = "网络请求失败"))))
                    .single().segments!!.single())
        assertEquals("网络请求失败", presentation("failed").errorMessage)
        assertEquals("网络请求失败", presentation("stopped").errorMessage)
        assertNull(presentation("succeeded").errorMessage)
        assertNull(presentation("running").errorMessage)
    }

    @Test fun thoughtActivityNeverContributesVisibleReasoningText() {
        val thought = answer.copy(text = "Private reasoning the client must not show",
            activity = RemoteActivity("thought", durationMs = 4))
        assertTrue(projectRemoteMessages(listOf(thought)).isEmpty())
        val projected = projectRemoteMessages(listOf(thought, answer.copy(id = "a"))).single()
        assertEquals(listOf("answer"),
            com.newoether.agora.ui.chat.message.mergeAdjacentSegments(projected.segments!!).map { it.type })
        assertEquals("Answer", projected.text)
        assertNull(projected.thoughts)
    }

    @Test fun pagingPreservesTheAssistantBubbleIdentity() {
        val newer = answer.copy(id = "newer", groupId = "native-group")
        val older = answer.copy(id = "older", groupId = "native-group")
        assertEquals("native-group", projectRemoteMessages(listOf(newer)).single().id)
        assertEquals("native-group", projectRemoteMessages(listOf(older, newer)).single().id)
    }
}
