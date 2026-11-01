package com.wulibaibao.cyclicsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志持久化的序列化往返测试。
 *
 * 日志之前只存在内存里 —— App 被杀掉就全没了。
 * 现在写到 filesDir/sync_log.json，所以序列化/反序列化必须无损，
 * 否则重启后看到的日志会缺字段或串类型。
 */
class LogStoreTest {

    @Test
    fun `Step 事件往返不变`() {
        val e = SyncEvent.Step(SyncDirection.IGP_TO_XINGZHE, "发现 3 条记录")
        val back = LogStore.parseEvent(LogStore.serialize(e))
        assertNotNull(back)
        assertEquals(e, back)
    }

    @Test
    fun `Ride 事件往返不变（含错误详情）`() {
        val e = SyncEvent.Ride(
            direction = SyncDirection.ONELAP_TO_IGP,
            date = "2026-10-31",
            name = "上海必骑丨环淀山湖",
            distanceKm = 28.24,
            status = "失败",
            errorDetail = "OSS 上传失败 HTTP 403: SignatureDoesNotMatch",
        )
        val back = LogStore.parseEvent(LogStore.serialize(e))
        assertNotNull(back)
        assertEquals(e, back)
    }

    @Test
    fun `Summary 与 Done 事件往返不变`() {
        val s = SyncEvent.Summary(SyncDirection.IGP_TO_ONELAP, 2, 1, 3)
        assertEquals(s, LogStore.parseEvent(LogStore.serialize(s)))

        val d = SyncEvent.Done(5, 6, 7)
        assertEquals(d, LogStore.parseEvent(LogStore.serialize(d)))
    }

    @Test
    fun `Info 与 Fatal 事件往返不变`() {
        val i = SyncEvent.Info("开始同步")
        assertEquals(i, LogStore.parseEvent(LogStore.serialize(i)))

        val f = SyncEvent.Fatal("网络不可用")
        assertEquals(f, LogStore.parseEvent(LogStore.serialize(f)))
    }

    @Test
    fun `未知类型返回 null 不崩`() {
        val bad = kotlinx.serialization.json.buildJsonObject {
            put("t", kotlinx.serialization.json.JsonPrimitive("unknown_kind"))
        }
        assertEquals(null, LogStore.parseEvent(bad))
    }

    @Test
    fun `所有方向都能正确反序列化`() {
        SyncDirection.entries.forEach { d ->
            val e = SyncEvent.Step(d, "x")
            assertEquals(d, (LogStore.parseEvent(LogStore.serialize(e)) as SyncEvent.Step).direction)
        }
        assertTrue(SyncDirection.entries.size >= 4)
    }
}
