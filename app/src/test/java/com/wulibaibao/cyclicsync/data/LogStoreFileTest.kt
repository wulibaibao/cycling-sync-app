package com.wulibaibao.cyclicsync.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LogStore 的文件读写（不含 Context —— 用临时目录模拟 filesDir）。
 */
class LogStoreFileTest {

    private fun roundTrip(events: List<SyncEvent>): List<SyncEvent> {
        val arr = buildJsonArray { events.forEach { add(LogStore.serialize(it)) } }
        val text = arr.toString()
        val back = mutableListOf<SyncEvent>()
        (Json.parseToJsonElement(text) as JsonArray).forEach { el ->
            LogStore.parseEvent(el.jsonObject)?.let { back += it }
        }
        return back
    }

    @Test
    fun `多条日志写入再读出 顺序与内容都不变`() {
        val events = listOf(
            SyncEvent.Info("开始同步"),
            SyncEvent.Step(SyncDirection.IGP_TO_XINGZHE, "发现 3 条"),
            SyncEvent.Ride(SyncDirection.ONELAP_TO_IGP, "2026-10-31", "骑行", 28.24, "已上传"),
            SyncEvent.Summary(SyncDirection.IGP_TO_XINGZHE, 1, 2, 0),
            SyncEvent.Done(1, 2, 0),
        )
        val back = roundTrip(events)
        assertEquals(events.size, back.size)
        events.forEachIndexed { i, e -> assertEquals(e, back[i]) }
    }

    @Test
    fun `文件名合法且写入后可读回`() {
        val f = File.createTempFile("sync_log", ".json")
        f.delete()
        val dir = f.parentFile
        val out = File(dir, "sync_log.json")
        out.writeText(buildJsonArray { add(LogStore.serialize(SyncEvent.Info("x"))) }.toString())
        assertTrue(out.exists())
        val parsed = Json.parseToJsonElement(out.readText()) as JsonArray
        assertEquals(1, parsed.size)
    }
}
