package com.wulibaibao.cyclicsync.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 本地同步历史。
 *
 * 记录「某条记录的指纹 已经同步到某条链路」，
 * 防止重复同步时目标平台列表接口还没刷新导致重复上传。
 *
 * 与服务端索引互为补充：
 *   - 服务端索引（DedupIndex）：权威，但可能延迟
 *   - 本地历史：即时，但可能与服务端不一致（服务端删了记录本地还留着）
 *
 * 所以本地只跳过「已成功上传」的记录；服务端有记录时也跳过。
 * 两者都没有才上传。
 */
internal class SyncHistory(context: Context) {
    companion object {
        private const val FILE = "sync_history.json"
        private const val MAX_ENTRIES = 500
    }

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, FILE)

    /** key = "链路|开始时间毫秒|距离米" → value = 上传时间戳 */
    private val entries = mutableMapOf<String, Long>()

    suspend fun load() = withContext(Dispatchers.IO) {
        entries.clear()
        try {
            if (!file.exists()) return@withContext
            val arr = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return@withContext
            arr.forEach { el ->
                val o = el.jsonObject
                val k = o["k"]?.jsonPrimitive?.content ?: return@forEach
                val v = o["v"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                entries[k] = v
            }
        } catch (e: Exception) {
            // 历史文件损坏 → 忽略，重新积累
        }
    }

    private fun key(dir: SyncDirection, startMs: Long?, distanceM: Double): String {
        // 时间不可解析时退化为距离+名称无法用，这里用 null 标记（不参与判重）
        return "${dir.name}|$startMs|${distanceM.toInt()}"
    }

    /** 这条记录是否已经成功同步过 */
    fun isSynced(dir: SyncDirection, startMs: Long?, distanceM: Double): Boolean {
        if (startMs == null) return false
        return entries.containsKey(key(dir, startMs, distanceM))
    }

    /** 记录一次成功同步 */
    suspend fun markSynced(dir: SyncDirection, startMs: Long?, distanceM: Double) =
        withContext(Dispatchers.IO) {
            if (startMs == null) return@withContext
            entries[key(dir, startMs, distanceM)] = System.currentTimeMillis()
            // 超出上限时丢弃最老的
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.minByOrNull { it.value } ?: break
                entries.remove(oldest.key)
            }
            persist()
        }

    private fun persist() {
        try {
            val arr = buildJsonArray {
                entries.forEach { (k, v) ->
                    add(buildJsonObject {
                        put("k", JsonPrimitive(k))
                        put("v", JsonPrimitive(v))
                    })
                }
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // 写失败不影响同步主流程
        }
    }
}
