package com.newoether.agora.ui.components

import com.newoether.agora.model.McpConnectionStatus
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class FairyPresenceTest {

    @Test
    fun `non-connected links map to offline or connecting`() {
        assertEquals(
            FairyPresence.CONNECTING,
            fairyPresence(McpConnectionStatus.CONNECTING, Participant.MODEL, MessageStatus.THINKING, true),
        )
        listOf(McpConnectionStatus.IDLE, McpConnectionStatus.ERROR).forEach { link ->
            assertEquals(
                FairyPresence.OFFLINE,
                fairyPresence(link, Participant.MODEL, MessageStatus.SENDING, true),
            )
        }
    }

    @Test
    fun `generating assistant tail speaks while its text grows and thinks otherwise`() {
        listOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING).forEach { status ->
            assertEquals(
                "$status growing",
                FairyPresence.SPEAKING,
                fairyPresence(McpConnectionStatus.CONNECTED, Participant.MODEL, status, tailTextGrowing = true),
            )
            assertEquals(
                "$status still",
                FairyPresence.THINKING,
                fairyPresence(McpConnectionStatus.CONNECTED, Participant.MODEL, status, tailTextGrowing = false),
            )
        }
    }

    @Test
    fun `user tail waiting for the reply is thinking`() {
        assertEquals(
            FairyPresence.THINKING,
            fairyPresence(McpConnectionStatus.CONNECTED, Participant.USER, MessageStatus.SUCCESS, false),
        )
    }

    @Test
    fun `terminal assistant tails and empty sessions are idle`() {
        listOf(MessageStatus.SUCCESS, MessageStatus.STOPPED, MessageStatus.ERROR).forEach { status ->
            assertEquals(
                "$status",
                FairyPresence.IDLE,
                fairyPresence(McpConnectionStatus.CONNECTED, Participant.MODEL, status, tailTextGrowing = true),
            )
        }
        assertEquals(FairyPresence.IDLE, fairyPresence(McpConnectionStatus.CONNECTED, null, null, false))
    }

    @Test
    fun `glow ranges follow the presence table`() {
        assertEquals(0.30f..0.55f, fairyGlowRange(FairyPresence.IDLE))
        assertEquals(0.30f..0.85f, fairyGlowRange(FairyPresence.THINKING))
        assertEquals(0.30f..0.85f, fairyGlowRange(FairyPresence.SPEAKING))
        assertEquals(0.15f..0.275f, fairyGlowRange(FairyPresence.CONNECTING))
        assertEquals(null, fairyGlowRange(FairyPresence.OFFLINE))
    }
}
