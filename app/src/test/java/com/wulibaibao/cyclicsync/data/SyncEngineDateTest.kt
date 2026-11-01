package com.wulibaibao.cyclicsync.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日期过滤测试。
 *
 * 各平台返回的时间字符串格式不统一，必须都能落到正确的日期上：
 * - IGPSPORT: "2026.10.31"              （点号）
 * - IGPSPORT: "2026.10.31 08:12:33"
 * - 行者:     "2026-10-29T08:12:33"
 * - 顽鹿:     "2026-10-29"
 *
 * 之前 sameDay 只认 "yyyy-MM-dd"，IGPSPORT 的点号格式解析失败返回 false
 * → IGPSPORT 记录全被过滤掉，链路静默失效。
 */
class SyncEngineDateTest {

    @Test
    fun `IGPSPORT 点号日期能匹配到目标日期`() {
        val target = LocalDate.of(2026, 10, 31)
        assertTrue(sameDay("2026.10.31", target))
    }

    @Test
    fun `IGPSPORT 点号日期带时间也能匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertTrue(sameDay("2026.10.31 08:12:33", target))
    }

    @Test
    fun `横杠日期和 ISO 格式都能匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertTrue(sameDay("2026-10-31", target))
        assertTrue(sameDay("2026-10-31 08:12:33", target))
        assertTrue(sameDay("2026-10-31T08:12:33", target))
        assertTrue(sameDay("2026/10/31", target))
    }

    @Test
    fun `非目标日期不匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertFalse(sameDay("2026.10.30", target))
        assertFalse(sameDay("2026-10-29", target))
    }

    @Test
    fun `空值与非法值不匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertFalse(sameDay("", target))
        assertFalse(sameDay("   ", target))
        assertFalse(sameDay("garbage", target))
    }
}
