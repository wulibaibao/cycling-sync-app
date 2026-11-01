package com.wulibaibao.cyclicsync.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 同步判重索引。
 *
 * 判重主键是「骑行指纹」= 开始时间 + 距离：
 *   - 时间容差 ±15 秒
 *   - 距离容差 max(30 米, 0.5%)
 *
 * 与 Python 版 strava-to-xingzhe-sync.py 的 server_has_activity 同策略 ——
 * 同一条骑行在任何平台都是同一组时间/距离，比名字和 md5 可靠得多
 * （名字可被用户改，md5 只有行者返回）。
 *
 * 辅助键（md5 / 名称）作为第二道防线。
 */
internal class DedupIndex {
    companion object {
        const val TIME_TOL_MS = 15_000L
        const val DIST_TOL_MIN = 30.0
        const val DIST_TOL_RATIO = 0.005

        /**
         * 所有中国平台（顽鹿 / IGPSPORT / 行者）都用北京时间。
         * 字符串一律按 Asia/Shanghai 解析，epoch 按绝对值处理 ——
         * 否则设备时区不是东八区时，跨平台指纹对比会整体错位。
         */
        private val TZ = TimeZone.getTimeZone("Asia/Shanghai")

        /** 解析各种时间格式 → epoch 毫秒；失败返回 null */
        fun parseTime(s: String): Long? {
            if (s.isBlank()) return null
            val trimmed = s.trim()
            // 纯数字 → 可能是 epoch 秒或毫秒
            trimmed.toLongOrNull()?.let { n ->
                return if (n > 1_000_000_000_000L) n else n * 1000L
            }
            // IGPSPORT 用点号分隔日期（"2026.10.31"），先归一化成横杠
            val normalized = trimmed.replace('/', '.').replace('.', '-')
            val patterns = listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd",
            )
            for (p in patterns) {
                for (candidate in listOf(trimmed, normalized)) {
                    try {
                        val fmt = SimpleDateFormat(p, Locale.US)
                        fmt.isLenient = false
                        fmt.timeZone = if (p.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TZ
                        return fmt.parse(candidate)?.time
                    } catch (e: Exception) {
                        // 试下一个
                    }
                }
            }
            return null
        }
    }

    /** 已有记录的指纹 */
    private data class Fingerprint(val startMs: Long, val distanceM: Double)

    private val prints = mutableListOf<Fingerprint>()

    /** 辅助键（md5 / 名称） */
    private val auxKeys = mutableSetOf<String>()

    /** 加入一条已有记录 */
    fun add(startMs: Long?, distanceM: Double, auxKey: String? = null) {
        if (startMs != null) prints += Fingerprint(startMs, distanceM)
        if (!auxKey.isNullOrBlank()) auxKeys += auxKey
    }

    fun addAux(key: String?) {
        if (!key.isNullOrBlank()) auxKeys += key
    }

    /** 指纹命中（时间 ±15s 且距离在容差内） */
    fun matchesFingerprint(startMs: Long?, distanceM: Double): Boolean {
        if (startMs == null) return false
        val tol = maxOf(DIST_TOL_MIN, distanceM * DIST_TOL_RATIO)
        return prints.any {
            kotlin.math.abs(it.startMs - startMs) <= TIME_TOL_MS &&
                kotlin.math.abs(it.distanceM - distanceM) <= tol
        }
    }

    /**
     * 同日 + 距离命中 —— 用于**时间精度不足**的源。
     *
     * IGPSPORT 的 startTime 只有日期（"2026.10.31"），解析出来是当天 00:00:00；
     * 而目标平台的记录带真实时刻（06:00:38、07:02:49…）。
     * 两者差好几个小时，±15 秒的指纹永远匹配不上 → 每次同步都当新记录重复上传。
     *
     * 所以当源平台只有日期时，退化成「同一天 + 距离在容差内」就算命中。
     */
    fun matchesSameDay(
        dayMs: Long?,
        distanceM: Double,
        tz: java.util.TimeZone = TimeZone.getTimeZone("Asia/Shanghai"),
    ): Boolean {
        if (dayMs == null) return false
        val tol = maxOf(DIST_TOL_MIN, distanceM * DIST_TOL_RATIO)
        val cal = java.util.Calendar.getInstance(tz)
        cal.timeInMillis = dayMs
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH)
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return prints.any {
            val c = java.util.Calendar.getInstance(tz)
            c.timeInMillis = it.startMs
            c.get(java.util.Calendar.YEAR) == y &&
                c.get(java.util.Calendar.MONTH) == m &&
                c.get(java.util.Calendar.DAY_OF_MONTH) == d &&
                kotlin.math.abs(it.distanceM - distanceM) <= tol
        }
    }

    /** 辅助键命中 */
    fun matchesAux(key: String?): Boolean = !key.isNullOrBlank() && auxKeys.contains(key)

    val size: Int get() = prints.size
}
