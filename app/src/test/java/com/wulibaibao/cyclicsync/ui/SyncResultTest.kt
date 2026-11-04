package com.wulibaibao.cyclicsync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同步结果提示的文案。 */
class SyncResultTest {

    @Test
    fun `成功时列出各项数量`() {
        val text = syncResultText(SyncResult.Success(uploaded = 3, skipped = 2, failed = 0))
        assertTrue(text, text.contains("同步完成"))
        assertTrue(text, text.contains("上传 3"))
        assertTrue(text, text.contains("跳过 2"))
        // 失败数为 0 时不显示这一项
        assertTrue(text, !text.contains("失败 0"))
    }

    @Test
    fun `全为 0 时只显示同步完成`() {
        val text = syncResultText(SyncResult.Success(uploaded = 0, skipped = 0, failed = 0))
        assertEquals("同步完成", text)
    }

    @Test
    fun `失败数大于 0 时显示`() {
        val text = syncResultText(SyncResult.Success(uploaded = 1, skipped = 0, failed = 4))
        assertTrue(text, text.contains("失败 4"))
    }

    @Test
    fun `失败结果显示原因`() {
        val text = syncResultText(SyncResult.Failure("登录失败"))
        assertEquals("同步失败：登录失败", text)
    }
}
