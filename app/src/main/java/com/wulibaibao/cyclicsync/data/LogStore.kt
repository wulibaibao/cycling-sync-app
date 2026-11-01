package com.wulibaibao.cyclicsync.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 同步日志持久化。
 *
 * 日志之前只存在内存的 `mutableStateListOf` 里 —— App 被杀掉（划掉/内存不足/系统回收）
 * 就全没了，用户反馈「每次杀掉应用日志就看不到了」。
 *
 * 这里把日志写到 `filesDir/sync_log.json`：
 * - 追加写入（每次同步追加，不覆盖）
 * - 最多保留 MAX_ENTRIES 条，超出丢最老的
 * - 单条日志失败不影响写入
 */
internal class LogStore(context: Context) {
    companion object {
        private const val FILE = "sync_log.json"
        private const val MAX_ENTRIES = 500

    internal fun serialize(e: SyncEvent): JsonObject = buildJsonObject {
            when (e) {
                is SyncEvent.Info -> {
                    put("t", JsonPrimitive("info"))
                    put("m", JsonPrimitive(e.message))
                }
                is SyncEvent.Step -> {
                    put("t", JsonPrimitive("step"))
                    put("d", JsonPrimitive(e.direction.name))
                    put("x", JsonPrimitive(e.text))
                }
                is SyncEvent.Ride -> {
                    put("t", JsonPrimitive("ride"))
                    put("d", JsonPrimitive(e.direction.name))
                    put("dt", JsonPrimitive(e.date))
                    put("n", JsonPrimitive(e.name))
                    put("km", JsonPrimitive(e.distanceKm))
                    put("s", JsonPrimitive(e.status))
                    put("err", JsonPrimitive(e.errorDetail))
                }
                is SyncEvent.Summary -> {
                    put("t", JsonPrimitive("sum"))
                    put("d", JsonPrimitive(e.direction.name))
                    put("u", JsonPrimitive(e.uploaded))
                    put("sk", JsonPrimitive(e.skipped))
                    put("f", JsonPrimitive(e.failed))
                }
                is SyncEvent.Done -> {
                    put("t", JsonPrimitive("done"))
                    put("u", JsonPrimitive(e.totalUploaded))
                    put("sk", JsonPrimitive(e.totalSkipped))
                    put("f", JsonPrimitive(e.totalFailed))
                }
                is SyncEvent.Fatal -> {
                    put("t", JsonPrimitive("fatal"))
                    put("m", JsonPrimitive(e.message))
                }
            }
        }

        internal fun parseEvent(o: JsonObject): SyncEvent? {
            fun str(k: String): String = o[k]?.jsonPrimitive?.content ?: ""
            fun int(k: String): Int = o[k]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            fun dbl(k: String): Double = o[k]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            fun dir(k: String): SyncDirection =
                SyncDirection.entries.firstOrNull { it.name == str(k) } ?: SyncDirection.entries.first()

            return when (str("t")) {
                "info" -> SyncEvent.Info(str("m"))
                "step" -> SyncEvent.Step(dir("d"), str("x"))
                "ride" -> SyncEvent.Ride(
                    direction = dir("d"),
                    date = str("dt"),
                    name = str("n"),
                    distanceKm = dbl("km"),
                    status = str("s"),
                    errorDetail = str("err"),
                )
                "sum" -> SyncEvent.Summary(dir("d"), int("u"), int("sk"), int("f"))
                "done" -> SyncEvent.Done(int("u"), int("sk"), int("f"))
                "fatal" -> SyncEvent.Fatal(str("m"))
                else -> null
            }
        }
    }

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, FILE)

    /** 读全部日志（按写入顺序） */
    suspend fun load(): List<SyncEvent> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SyncEvent>()
        try {
            cache = null
            if (!file.exists()) return@withContext out
            val text = file.readText()
            if (text.isBlank()) return@withContext out
            val arr = Json.parseToJsonElement(text) as? JsonArray ?: return@withContext out
            arr.forEach { el ->
                val o = el.jsonObject
                LogStore.parseEvent(o)?.let { out += it }
            }
        } catch (e: Exception) {
            // 日志文件损坏 → 忽略，不能让 App 崩在这里
        }
        out
    }

    /**
     * 内存里的日志快照 —— 避免每次追加都全量读文件。
     *
     * 之前 append 每次都 readRaw() + 解析 + 写全量 → 同步 500 条就是 O(n²)，
     * 而且每个事件都开一个 IO 协程，慢到能把界面卡死。
     */
    private var cache: MutableList<JsonObject>? = null
    private var cacheDirty = false

    /** 追加一条并落盘 */
    suspend fun append(event: SyncEvent) = withContext(Dispatchers.IO) {
        try {
            val list = cache ?: readRaw().also { cache = it }
            list += LogStore.serialize(event)
            // 超上限时丢最老的
            while (list.size > MAX_ENTRIES) list.removeAt(0)
            writeAll(list)
            cacheDirty = false
        } catch (e: Exception) {
            // 写失败不影响同步主流程
        }
    }

    /** 批量追加 —— 一次落盘，避免每条事件都开一个 IO 协程 + 写一次文件 */
    suspend fun appendAll(events: List<SyncEvent>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        try {
            val list = cache ?: readRaw().also { cache = it }
            events.forEach { list += LogStore.serialize(it) }
            while (list.size > MAX_ENTRIES) list.removeAt(0)
            writeAll(list)
            cacheDirty = false
        } catch (e: Exception) {
        }
    }

    /**
     * 清空 —— **同步**版本。
     *
     * 之前用的是 suspend clear()，在新一轮同步开始时 launch 出去异步执行；
     * 但同步事件是同时在写的 —— clear 可能后执行，把刚写的日志删掉（竞态）。
     * 文件操作很小（一个文件），同步做更安全。
     */
    fun clearSync() {
        try {
            cache = null
            cacheDirty = false
            if (file.exists()) file.delete()
        } catch (e: Exception) {
        }
    }

    /** 清空（挂起版本，保留给 Compose 侧使用） */
    suspend fun clear() = withContext(Dispatchers.IO) { clearSync() }

    private fun writeAll(list: List<JsonObject>) {
        file.writeText(buildJsonArray { list.forEach { add(it) } }.toString())
    }

    private fun readRaw(): MutableList<JsonObject> {
        val out = mutableListOf<JsonObject>()
        try {
            if (!file.exists()) return out
            val arr = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return out
            arr.forEach { el ->
                (el as? JsonObject)?.let { out += it }
            }
        } catch (e: Exception) {
        }
        return out
    }

}
