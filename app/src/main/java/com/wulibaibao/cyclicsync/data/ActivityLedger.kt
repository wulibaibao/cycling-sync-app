package com.wulibaibao.cyclicsync.data

import android.content.Context
import com.wulibaibao.cyclicsync.ui.Platform
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ActivitySyncStatus {
    NOT_SYNCED,
    SYNCING,
    SYNCED,
    FAILED
}

data class TargetSyncState(
    val status: ActivitySyncStatus = ActivitySyncStatus.NOT_SYNCED,
    val syncedAtMs: Long = 0L,
    val errorDetail: String = "",
    val targetWorkoutId: String = "",
)

data class UnifiedActivity(
    val id: String,
    val source: Platform,
    val title: String,
    val startTimeRaw: String,
    val startTimeMs: Long,
    val distanceKm: Double,
    val durationSec: Long,
    val speedKmH: Double,
    val hasPower: Boolean = false,
    val hasCadence: Boolean = false,
    val hasHr: Boolean = false,
    val targetStates: Map<Platform, TargetSyncState> = emptyMap(),
    val selected: Boolean = false,
) {
    val formattedDate: String get() {
        if (startTimeMs <= 0L) return startTimeRaw.take(16)
        return try {
            val instant = Instant.ofEpochMilli(startTimeMs)
            val zdt = instant.atZone(ZoneId.of("Asia/Shanghai"))
            val today = LocalDate.now()
            val actDate = zdt.toLocalDate()
            val timeStr = zdt.format(DateTimeFormatter.ofPattern("HH:mm"))
            when {
                actDate == today -> "今天 $timeStr"
                actDate == today.minusDays(1) -> "昨天 $timeStr"
                actDate == today.minusDays(2) -> "前天 $timeStr"
                actDate.year == today.year -> zdt.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                else -> zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
        } catch (_: Exception) {
            startTimeRaw.take(16)
        }
    }

    val durationFormatted: String get() {
        if (durationSec <= 0L) return "--"
        val h = durationSec / 3600
        val m = (durationSec % 3600) / 60
        val s = durationSec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    /** 是否已同步到指定平台 */
    fun isSyncedTo(target: Platform): Boolean =
        targetStates[target]?.status == ActivitySyncStatus.SYNCED

    /** 是否在给定的目标平台中有待同步项 */
    fun isPendingFor(targets: Collection<Platform>): Boolean {
        if (targets.isEmpty()) return targetStates.values.any { it.status != ActivitySyncStatus.SYNCED }
        return targets.any { t -> targetStates[t]?.status != ActivitySyncStatus.SYNCED }
    }
}

/**
 * 活动同步台账持久化存储。
 * 记录每一次同步的源/目标平台、时间指纹、同步结果，支撑实时看板与去重比对。
 */
class SyncLedgerStore(context: Context) {
    companion object {
        private const val FILE_NAME = "sync_ledger_records.json"
        private const val MAX_RECORDS = 1000
    }

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, FILE_NAME)

    data class LedgerEntry(
        val sourcePlatform: String,
        val sourceActivityId: String,
        val targetPlatform: String,
        val startMs: Long,
        val distanceM: Double,
        val status: ActivitySyncStatus,
        val syncedAtMs: Long,
        val errorMsg: String,
        val targetWorkoutId: String,
    )

    private val records = java.util.concurrent.CopyOnWriteArrayList<LedgerEntry>()

    suspend fun load() = withContext(Dispatchers.IO) {
        records.clear()
        try {
            if (!file.exists()) return@withContext
            val arr = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return@withContext
            arr.forEach { el ->
                val o = el.jsonObject
                records += LedgerEntry(
                    sourcePlatform = o["source"]?.jsonPrimitive?.content ?: "",
                    sourceActivityId = o["source_id"]?.jsonPrimitive?.content ?: "",
                    targetPlatform = o["target"]?.jsonPrimitive?.content ?: "",
                    startMs = o["start_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    distanceM = o["distance_m"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    status = try {
                        ActivitySyncStatus.valueOf(o["status"]?.jsonPrimitive?.content ?: "NOT_SYNCED")
                    } catch (_: Exception) {
                        ActivitySyncStatus.NOT_SYNCED
                    },
                    syncedAtMs = o["synced_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    errorMsg = o["error"]?.jsonPrimitive?.content ?: "",
                    targetWorkoutId = o["target_id"]?.jsonPrimitive?.content ?: "",
                )
            }
        } catch (_: Exception) {}
    }

    fun getTargetStatus(source: Platform, sourceId: String, startMs: Long, distanceM: Double, target: Platform): TargetSyncState {
        // 先按精确源活动 ID 查
        val exact = records.findLast {
            it.sourcePlatform == source.name && it.sourceActivityId == sourceId && it.targetPlatform == target.name
        }
        if (exact != null) {
            return TargetSyncState(exact.status, exact.syncedAtMs, exact.errorMsg, exact.targetWorkoutId)
        }
        // 再按时间+距离模糊指纹查 (容差 120 秒，距离 2%)
        val fuzzy = records.findLast {
            it.targetPlatform == target.name &&
                Math.abs(it.startMs - startMs) <= 120_000L &&
                (distanceM == 0.0 || Math.abs(it.distanceM - distanceM) / distanceM <= 0.03)
        }
        if (fuzzy != null) {
            return TargetSyncState(fuzzy.status, fuzzy.syncedAtMs, fuzzy.errorMsg, fuzzy.targetWorkoutId)
        }
        return TargetSyncState(ActivitySyncStatus.NOT_SYNCED)
    }

    suspend fun recordSync(
        source: Platform,
        sourceId: String,
        startMs: Long,
        distanceM: Double,
        target: Platform,
        status: ActivitySyncStatus,
        error: String = "",
        targetWorkoutId: String = "",
    ) = withContext(Dispatchers.IO) {
        records.removeAll {
            it.sourcePlatform == source.name && it.sourceActivityId == sourceId && it.targetPlatform == target.name
        }
        records.add(
            LedgerEntry(
                sourcePlatform = source.name,
                sourceActivityId = sourceId,
                targetPlatform = target.name,
                startMs = startMs,
                distanceM = distanceM,
                status = status,
                syncedAtMs = System.currentTimeMillis(),
                errorMsg = error,
                targetWorkoutId = targetWorkoutId,
            )
        )
        while (records.size > MAX_RECORDS) {
            records.removeAt(0)
        }
        persist()
    }

    private fun persist() {
        try {
            val arr = buildJsonArray {
                records.forEach { r ->
                    add(
                        buildJsonObject {
                            put("source", JsonPrimitive(r.sourcePlatform))
                            put("source_id", JsonPrimitive(r.sourceActivityId))
                            put("target", JsonPrimitive(r.targetPlatform))
                            put("start_ms", JsonPrimitive(r.startMs))
                            put("distance_m", JsonPrimitive(r.distanceM))
                            put("status", JsonPrimitive(r.status.name))
                            put("synced_at", JsonPrimitive(r.syncedAtMs))
                            put("error", JsonPrimitive(r.errorMsg))
                            put("target_id", JsonPrimitive(r.targetWorkoutId))
                        }
                    )
                }
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }
}
