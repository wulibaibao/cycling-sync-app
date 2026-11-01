package com.wulibaibao.cyclicsync.platform

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02（火星坐标系）↔ WGS-84 转换。
 *
 * 国内平台坐标系约定（实测得出）：
 *  - 顽鹿 / 迈金 / IGPSPORT 存的 FIT 坐标是 **GCJ-02**
 *  - 行者（imxingzhe.com）底图按 **WGS-84** 显示
 *
 * 所以 顽鹿→行者、IGPSPORT→行者 必须做 GCJ-02 → WGS-84 转换，
 * 否则路线整体偏移 400-500m（实测 459m）。
 * 顽鹿→IGPSPORT **不转**（两边都是 GCJ-02，实测差 0.00m）。
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
}
