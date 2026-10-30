package com.wulibaibao.cyclicsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判重逻辑测试 —— 保证重复同步不会重复上传。
 */
class DedupTest {

    private val base = 1_767_225_600_000L // 2026-01-01 左右

    @Test
    fun `同一条记录 时间距离都一致 → 判为已存在`() {
        val idx = DedupIndex()
        idx.add(base, 40_200.0)

        assertTrue(idx.matchesFingerprint(base, 40_200.0))
    }

    @Test
    fun `时间差 10 秒内 距离相同 → 判为已存在`() {
        val idx = DedupIndex()
        idx.add(base, 40_200.0)

        assertTrue(idx.matchesFingerprint(base + 10_000, 40_200.0))
        assertTrue(idx.matchesFingerprint(base - 10_000, 40_200.0))
    }

    @Test
    fun `时间差超过 15 秒 → 不判为已存在`() {
        val idx = DedupIndex()
        idx.add(base, 40_200.0)

        assertFalse(idx.matchesFingerprint(base + 20_000, 40_200.0))
        assertFalse(idx.matchesFingerprint(base - 20_000, 40_200.0))
    }

    @Test
    fun `距离容差 —— 小距离用 30 米下限`() {
        val idx = DedupIndex()
        idx.add(base, 500.0)

        // 容差 = max(30, 500*0.005=2.5) = 30 米
        assertTrue(idx.matchesFingerprint(base, 525.0))
        assertFalse(idx.matchesFingerprint(base, 550.0))
    }

    @Test
    fun `距离容差 —— 大距离用 0_5 百分比`() {
        val idx = DedupIndex()
        idx.add(base, 100_000.0)

        // 0.5% = 500 米容差（大于 30 米下限）
        assertTrue(idx.matchesFingerprint(base, 100_400.0))
        assertFalse(idx.matchesFingerprint(base, 100_600.0))
    }

    @Test
    fun `不同天的记录不判为已存在`() {
        val idx = DedupIndex()
        idx.add(base, 40_200.0)

        assertFalse(idx.matchesFingerprint(base + 86_400_000L, 40_200.0))
    }

    @Test
    fun `md5 辅助键命中`() {
        val idx = DedupIndex()
        idx.addAux("abc123")

        assertTrue(idx.matchesAux("abc123"))
        assertFalse(idx.matchesAux("def456"))
        assertFalse(idx.matchesAux(null))
        assertFalse(idx.matchesAux(""))
    }

    @Test
    fun `时间解析 —— 多种格式解析到同一时刻`() {
        val a = DedupIndex.parseTime("2026-10-29 08:12:33")
        val b = DedupIndex.parseTime("2026-10-29T08:12:33")
        val c = DedupIndex.parseTime("2026-10-29 08:12:33")
        assertTrue(a != null && b != null && c != null)
        // 空格和 T 分隔应等价
        assertEquals(a!!, b!!)
        assertEquals(a, c)
    }

    @Test
    fun `时间解析 —— 空串和垃圾返回 null`() {
        assertNull(DedupIndex.parseTime(""))
        assertNull(DedupIndex.parseTime("   "))
        assertNull(DedupIndex.parseTime("not-a-date"))
    }

    @Test
    fun `空索引任何东西都不命中`() {
        val idx = DedupIndex()
        assertFalse(idx.matchesFingerprint(base, 40_200.0))
        assertFalse(idx.matchesAux("anything"))
    }
}
