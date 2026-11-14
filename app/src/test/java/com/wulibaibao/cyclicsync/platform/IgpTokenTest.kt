package com.wulibaibao.cyclicsync.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IGPSPORT token 生命周期测试。
 *
 * ★ 核心事实（2026-11-14 实测）：
 * IGPSPORT 的 **refresh_token 只有 7 天有效期**，而且服务端要求
 * **刷新时 access_token 仍处于有效状态**。
 *
 * 只在 access_token 过期后才去刷新 ⇒ refresh_token 自己也过期了 ⇒
 * 服务端返回 401（空响应体）⇒ 只能重新登录。
 *
 * 所以必须在 access_token 过期**之前**主动刷新。
 */
class IgpTokenTest {

    private val now = System.currentTimeMillis() / 1000

    private fun client(
        token: String = "t",
        refreshToken: String = "r",
        expiresIn: Long,
        refreshExpiresIn: Long? = null,
    ) = IgpClient().apply {
        this.token = token
        this.refreshToken = refreshToken
        this.expiresAt = now + expiresIn
        this.refreshExpiresAt = now + (refreshExpiresIn ?: expiresIn)
    }

    @Test
    fun `token 有效期内不刷新`() {
        // 7 天有效期，还剩 6 天多 → 离过期点超过 5 天窗口 → 不刷
        val c = client(expiresIn = 6 * 24 * 3600L + 3600L)
        assertFalse("还剩 6 天，不该提前刷", c.needsProactiveRefresh())
    }

    @Test
    fun `access_token 剩不到 5 天就提前刷新`() {
        // 7 天有效期，剩 4 天 → 该提前刷
        val c = client(expiresIn = 4 * 24 * 3600L)
        assertTrue("剩 4 天就该提前刷", c.needsProactiveRefresh())
    }

    @Test
    fun `refresh_token 自己也过期了就不提前刷`() {
        // access_token 只剩 4 天，但 refresh_token 已经死了 → 刷了也没用
        val c = client(
            expiresIn = 4 * 24 * 3600L,
            refreshExpiresIn = -60L,
        )
        assertFalse("refresh_token 已死，提前刷没意义", c.needsProactiveRefresh())
    }

    @Test
    fun `没有 refresh_token 就不提前刷`() {
        val c = IgpClient().apply {
            token = "t"
            refreshToken = ""
            expiresAt = now + 24 * 3600L
        }
        assertFalse(c.needsProactiveRefresh())
    }

    @Test
    fun `expiresAt 为 0 表示未知 不提前刷`() {
        val c = IgpClient().apply {
            token = "t"
            refreshToken = "r"
            expiresAt = 0L
        }
        assertFalse(c.needsProactiveRefresh())
    }

    @Test
    fun `refresh_token 有效性判断`() {
        val alive = client(expiresIn = 6 * 24 * 3600L)
        assertTrue(alive.hasValidRefreshToken())

        val dead = client(expiresIn = 6 * 24 * 3600L, refreshExpiresIn = -1L)
        assertFalse(dead.hasValidRefreshToken())

        val none = IgpClient()
        assertFalse(none.hasValidRefreshToken())
    }

    @Test
    fun `access_token 有效性判断`() {
        val ok = client(expiresIn = 3600L)
        assertTrue(ok.hasValidToken())

        val expired = client(expiresIn = -10L)
        assertFalse(expired.hasValidToken())
    }

    @Test
    fun `提前刷新窗口是 5 天`() {
        assertTrue(IgpClient.PROACTIVE_REFRESH_WINDOW == 5L * 24 * 3600)
    }
}
