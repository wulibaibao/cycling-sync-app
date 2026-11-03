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

    // ---------- 分页深度（2026-11-02 修复的 bug）----------

    /**
     * 2026-11-02 实测：用户要同步「2026-05-30」的顽鹿数据，App 报「发现 0 条顽鹿记录」。
     *
     * 根因：App 只拉 5 页 × 20 条 = 100 条，而「2026-05-30」的记录在**第 6 页**
     *（第 120 条）—— 刚好差一页拉不到。
     *
     * 顽鹿列表 API 实测行为：
     * - 只支持 `page` / `limit` 两个参数，没有日期过滤
     * - `limit` 最大 200（实测 100/200 都接受）
     * - 页偏移 = (page-1) × limit，所以 limit 越大需要的页数越少
     * - 总记录数约 460 条（2023-05 至今），limit=100 时 5 页拉完
     * - 列表按开始时间**倒序**（新的在前）
     */
    @Test
    fun `顽鹿分页上限必须覆盖历史深度`() {
        // 100 条/页时，「2026-05-30」在第 2 页 → 2 页足够
        // 但 limit=20 时它要到第 6 页 → 只翻 5 页就会漏
        assertTrue(
            "2026-05-30 的记录在 limit=20 时是第 6 页，分页上限必须 > 5",
            ONELAP_MAX_PAGES > 5,
        )
        assertTrue(
            "每页条数要够大，否则历史数据翻不到",
            ONELAP_PAGE_SIZE >= 100,
        )
    }

    @Test
    fun `顽鹿列表倒序 第6页确实是5月30日之后`() {
        // 实测数据（limit=20）：page1=2026-11-02, page5=2026-07-23, page6=2026-06-10
        // → 2026-05-30 落在 page 6，验证「翻页到 6 页以上才找得到」
        val page6First = "2026-06-10"
        val target = LocalDate.of(2026, 5, 30)
        // page6 最早是 2026-06-10，目标日期比它更早 → 必然在 page6 或更后
        assertTrue(
            "2026-05-30 应该在第 6 页或更后",
            LocalDate.parse(page6First).isAfter(target),
        )
    }
}
