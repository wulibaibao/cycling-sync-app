package com.wulibaibao.cyclicsync.platform

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02（火星坐标系）↔ WGS-84 转换。
 *
 * 平台坐标系约定（实测得出）：
 *  - 顽鹿 / 迈金 存的 FIT 坐标是 **GCJ-02**
 *  - 行者（imxingzhe.com）底图按 **WGS-84** 显示 ⇒ 传行者要转成 WGS-84
 *  - **IGPSPORT 收 WGS-84**（2026-11-03 拆它网页版 bundle 实测：底图是高德=GCJ-02，
 *    但轨迹数据字段名 `wgs84TrackJsonPath` ⇒ 服务端统一存 WGS-84）⇒ 传 IGPSPORT 也要转
 *  - 顽鹿 底图按 GCJ-02 ⇒ 传顽鹿要 GCJ-02
 *
 * 所以 **IGPSPORT 里存量活动的坐标系是混杂的**（迈金/顽鹿原生 = GCJ-02，
 * Strava 官方同步 = WGS-84，v1.2.3 起我们上传的 = WGS-84）——
 * 下载 IGPSPORT 的 FIT 时要用 [detectCoordSystem] 探测，不能想当然。
 *
 * 算法来源：标准「火星坐标」转换公式，迭代求解（与 Python 侧 `correct_fit_gpx.py` 一致）。
 */
object CoordTransform {

    private const val EARTH_R = 6378137.0
    private const val EE = 0.00669342162296594323

    /** 境外坐标不做转换（两种坐标系在境外是恒等映射）。 */
    fun isOutOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transform(x: Double, y: Double): DoubleArray {
        val xy = x * y
        val absX = sqrt(abs(x))
        val xPi = x * Math.PI
        val yPi = y * Math.PI
        var d = 20.0 * sin(6.0 * xPi) + 20.0 * sin(2.0 * xPi)
        var lat = d
        var lng = d
        lat += 20.0 * sin(yPi) + 40.0 * sin(yPi / 3.0)
        lng += 20.0 * sin(xPi) + 40.0 * sin(xPi / 3.0)
        lat += 160.0 * sin(yPi / 12.0) + 320.0 * sin(yPi / 30.0)
        lng += 150.0 * sin(xPi / 12.0) + 300.0 * sin(xPi / 30.0)
        lat *= 2.0 / 3.0
        lng *= 2.0 / 3.0
        lat += -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * xy + 0.2 * absX
        lng += 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * xy + 0.1 * absX
        return doubleArrayOf(lat, lng)
    }

    private fun delta(lat: Double, lon: Double): DoubleArray {
        val t = transform(lon - 105.0, lat - 35.0)
        var dLat = t[0]
        var dLng = t[1]
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((EARTH_R * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / ((EARTH_R / sqrtMagic) * cos(radLat) * Math.PI)
        return doubleArrayOf(dLat, dLng)
    }

    /** GCJ-02 → WGS-84（迭代反解，误差 < 1e-6 度 ≈ 0.1m）。 */
    fun gcjToWgs(gcjLat: Double, gcjLon: Double): DoubleArray {
        if (isOutOfChina(gcjLat, gcjLon)) return doubleArrayOf(gcjLat, gcjLon)
        var lat = gcjLat
        var lon = gcjLon
        for (i in 0 until 30) {
            val oldLat = lat
            val oldLon = lon
            val d = delta(lat, lon)
            lat = gcjLat - d[0]
            lon = gcjLon - d[1]
            if (maxOf(abs(oldLat - lat), abs(oldLon - lon)) < 1e-6) break
        }
        return doubleArrayOf(lat, lon)
    }

    /** WGS-84 → GCJ-02。 */
    fun wgsToGcj(wgsLat: Double, wgsLon: Double): DoubleArray {
        if (isOutOfChina(wgsLat, wgsLon)) return doubleArrayOf(wgsLat, wgsLon)
        val d = delta(wgsLat, wgsLon)
        return doubleArrayOf(wgsLat + d[0], wgsLon + d[1])
    }

    /** 两点间大圆距离（米）。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * 探测一组坐标的坐标系（采样投票法）。
     *
     * 移植自 Python 侧 `strava-to-onelap.py` 的 `detect_coord_system`。
     *
     * 原理：GCJ-02 偏移量随位置变化。对同一个点：
     *  - 把它**当作 WGS-84** 正向转成 GCJ-02，看位移量 `offWgs`
     *  - 把它**当作 GCJ-02** 反解成 WGS-84，看位移量 `offGcj`
     * 真实坐标系下，这两个值不等；且真实的那一个**更小**。
     * margin = (offGcj - offWgs) / max(offWgs, offGcj)
     * margin > 0 ⇒ WGS-84，margin < 0 ⇒ GCJ-02。
     *
     * ⚠️ 方向别搞反 —— 早期版本反了，导致 10/10 全票判错。
     *
     * @param samples 坐标点（度）
     * @return null = 无法判定（境外 / 样本不足 / 票数持平）
     */
    fun detectCoordSystem(samples: List<DoubleArray>): Boolean? {
        val pts = samples.take(MAX_SAMPLES).filter { !isOutOfChina(it[0], it[1]) }
        if (pts.isEmpty()) return null
        var votesWgs = 0
        var votesGcj = 0
        for (p in pts) {
            val lat = p[0]
            val lon = p[1]
            // 当作 WGS-84 → GCJ-02 的位移量
            val asWgs = wgsToGcj(lat, lon)
            val offWgs = Math.hypot(asWgs[0] - lat, asWgs[1] - lon)
            // 当作 GCJ-02 → WGS-84 的位移量
            val asGcj = gcjToWgs(lat, lon)
            val offGcj = Math.hypot(asGcj[0] - lat, asGcj[1] - lon)
            if (offGcj <= 0.0) continue
            val margin = (offGcj - offWgs) / maxOf(abs(offWgs), abs(offGcj))
            if (margin > 0) votesWgs++ else votesGcj++
        }
        if (votesWgs == votesGcj) return null
        return votesWgs > votesGcj
    }

    /** 探测采样点数上限。 */
    private const val MAX_SAMPLES = 10
}
