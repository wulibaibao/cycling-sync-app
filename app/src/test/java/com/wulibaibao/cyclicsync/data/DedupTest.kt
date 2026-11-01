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

    /**
     * IGPSPORT 的 startTime 是点号格式 "2026.10.31"。
     *
     * 之前 parseTime 只认 "yyyy-MM-dd"，点号格式解析失败返回 null →
     * 该平台所有记录的指纹都算不出来 → 判重失效 + 日期过滤把记录全滤掉
     * （表现为「发现 1 条，0 条在 2026-10-31」）。
     */
    @Test
    fun `parseTime 兼容 IGPSPORT 点号日期格式`() {
        val dotted = DedupIndex.parseTime("2026.10.31")
        val dashed = DedupIndex.parseTime("2026-10-31")
        assertTrue("点号格式应能解析", dotted != null)
        assertEquals("点号与横杠应解析到同一天", dashed, dotted)
    }

    @Test
    fun `parseTime 兼容各种平台时间格式`() {
        val expected = DedupIndex.parseTime("2026-10-31 08:12:33")
        assertTrue(expected != null)
        listOf(
            "2026.10.31 08:12:33",
            "2026/10/31 08:12:33",
            "2026-10-31T08:12:33",
        ).forEach { s ->
            assertEquals("格式 $s 应解析一致", expected, DedupIndex.parseTime(s))
        }
    }

    @Test
    fun `parseTime 非法输入返回 null`() {
        assertNull(DedupIndex.parseTime(""))
        assertNull(DedupIndex.parseTime("   "))
        assertNull(DedupIndex.parseTime("not-a-date"))
    }


    /**
     * IGPSPORT 的 startTime 只有日期 → 解析成当天 00:00:00。
     * 目标平台的记录带真实时刻（06:00:38），差好几个小时，
     * ±15 秒的指纹匹配不上 → 会重复上传。
     *
     * 所以 dateOnly 的源必须走 matchesSameDay（同日 + 距离）。
     */
    @Test
    fun `同日不同时刻 距离相同 → 同日判重命中`() {
        val idx = DedupIndex()
        // 目标平台记录：2026-10-29 06:00:38（东八区）
        val real = DedupIndex.parseTime("2026-10-29 06:00:38")!!
        idx.add(real, 40_205.0)

        // IGPSPORT 侧：只有日期 → 00:00:00
        val dateOnly = DedupIndex.parseTime("2026.10.29")!!

        assertFalse("±15 秒指纹不应命中（差 6 小时）", idx.matchesFingerprint(dateOnly, 40_205.0))
        assertTrue("同日判重应命中", idx.matchesSameDay(dateOnly, 40_205.0))
    }

    @Test
    fun `不同日 距离相同 → 同日判重不命中`() {
        val idx = DedupIndex()
        idx.add(DedupIndex.parseTime("2026-10-29 06:00:38"), 40_205.0)
        val other = DedupIndex.parseTime("2026.10.30")!!
        assertFalse(idx.matchesSameDay(other, 40_205.0))
    }

    @Test
    fun `同日但距离差太多 → 同日判重不命中`() {
        val idx = DedupIndex()
        idx.add(DedupIndex.parseTime("2026-10-29 06:00:38"), 40_205.0)
        val dateOnly = DedupIndex.parseTime("2026.10.29")!!
        assertFalse("距离差 10km 超过容差", idx.matchesSameDay(dateOnly, 50_000.0))
    }

}
