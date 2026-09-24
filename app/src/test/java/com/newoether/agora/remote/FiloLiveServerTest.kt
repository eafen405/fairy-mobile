package com.newoether.agora.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Live-wire acceptance against a real Fairy deployment, driven by env vars:
 *   FAIRY_LIVE_ORIGIN — e.g. http://127.0.0.1:3777/
 *   FAIRY_LIVE_USERNAME / FAIRY_LIVE_PASSWORD — existing account (register path
 *   is exercised separately by the UI flow; login is the stable gate here).
 * Skipped when unset — unit runs never require a server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FiloLiveServerTest {
    private val origin = System.getenv("FAIRY_LIVE_ORIGIN")
    private val username = System.getenv("FAIRY_LIVE_USERNAME")
    private val password = System.getenv("FAIRY_LIVE_PASSWORD")

    private fun enabled(): Boolean =
        !origin.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()

    @Test fun liveLoginCookieInfoSessionsSendStopLogout() = runBlocking {
        if (!enabled()) return@runBlocking
        val client = FiloClient(origin!!)

        // 登录拿 cookie —— /api/login 的 Set-Cookie 进入 sessionCredential。
        val account = client.login(username!!, password!!)
        assertEquals(username, account)
        assertFalse(client.sessionCredential.isNullOrBlank())

        // info 闸：protocolVersion=2 / agent=fairy / existing / native-steer / live-messages。
        assertEquals("fairy", client.connect())

        // 主会话单项 + conversation 页可恢复。
        val sessions = client.sessions()
        val main = sessions.sessions.single()
        val page = client.conversation(main.id)
        assertNotNull(page.runtime)

        // 事件流第一帧就是服务端页真相（恢复语义）。
        val live = withTimeout(15_000) { client.events(main.id).first() }
        assertNotNull(live.runtime)

        // 发送：回执受理（服务端回显 clientId、返回真实 turnId）。
        val receipt = client.send(main.id, "live-probe", "live-1", emptyList())
        assertTrue(receipt.turnId.isNotBlank())
        withTimeout(15_000) {
            client.events(main.id).first { it.runtime?.status == "idle" }
        }
        // 对已结束 turn 的 stop 是确定性空操作（不抛、不崩）。
        client.stop(main.id, receipt.turnId)

        // 凭据复用 = 杀进程重进：新 client 持同一 cookie 直接恢复。
        val resumed = FiloClient(origin, client.sessionCredential!!)
        assertEquals(username, resumed.me())
        assertEquals(main.id, resumed.sessions().sessions.single().id)

        // 登出：服务端吊销 cookie，后续 /api/me 401 → 客户端回登录页。
        resumed.logout()
        assertNull(resumed.sessionCredential)
        try {
            resumed.me()
            fail("revoked cookie must be rejected")
        } catch (expected: IOException) { /* 401 surfaces as FiloHttpException */ }
    }
}
