package com.wulibaibao.cyclicsync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 日期选择器的纯逻辑测试（UI 本身靠真机看）。 */
class DatePickerSheetTest {

    @Test
    fun `可选范围是过去五年到今天`() {
        val today = LocalDate.of(2026, 11, 3)
        val (start, end) = datePickerRange(today)
        assertEquals(YearMonth.of(2021, 11), start)
        assertEquals(YearMonth.of(2026, 11), end)
    }

    @Test
    fun `未来日期不可选`() {
        val today = LocalDate.of(2026, 11, 3)
        assertTrue(isDateSelectable(LocalDate.of(2026, 11, 3), today))
        assertTrue(isDateSelectable(LocalDate.of(2026, 11, 2), today))
        assertFalse(isDateSelectable(LocalDate.of(2026, 11, 4), today))
        assertFalse(isDateSelectable(LocalDate.of(2027, 1, 1), today))
    }

    @Test
    fun `星期头周一开头`() {
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), weekHeaderLabels())
    }

    @Test
    fun `邻月日期比本月日期淡得多`() {
        val inMonth = dayCellAlpha(inMonth = true, enabled = true)
        val adjacent = dayCellAlpha(inMonth = false, enabled = true)
        assertEquals(1.0f, inMonth, 0.001f)
        // 邻月必须明显更淡 —— 这是用户区分「本月」和「邻月」的唯一视觉线索
        assertTrue("邻月 alpha $adjacent 应该远小于本月 $inMonth", adjacent < inMonth / 2)
    }

    @Test
    fun `未来的本月日期比邻月稍明显`() {
        val future = dayCellAlpha(inMonth = true, enabled = false)
        val adjacent = dayCellAlpha(inMonth = false, enabled = true)
        assertTrue("未来本月 $future 应该比邻月 $adjacent 明显", future > adjacent)
        assertTrue(future < 1.0f)
    }
}
