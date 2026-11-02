package com.wulibaibao.cyclicsync.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标转换 + FIT 字节级改写的回归测试。
 *
 * 背景（2026-11-01/02 实测）：
 * - 顽鹿 / 迈金 / IGPSPORT 存的 FIT 坐标都是 **GCJ-02**
 * - 行者底图按 **WGS-84** 显示 → 上传前必须转，否则偏 400-500m（实测 459m）
 * - 行者把 FIT 里 `sub_sport=58 (virtual_activity)` 的活动显示成**「虚拟骑行」**
 *   而不是「骑行」→ 必须改成 `generic (0)`
 * - 上传参数 `sport` 必须传 `1`（传 2/3/4/9 会被判成虚拟骑行）
 *
 * 测试样本是**脱敏后**的真实 FIT 结构（坐标全部改成西安固定点，不含真实轨迹）：
 * - `synth_onelap_virtual.fit` —— 顽鹿格式（小端）
 * - `synth_igp_virtual.fit` —— IGPSPORT 格式（**大端**，且带 developer fields）
 */
class CoordTransformTest {

    private fun load(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assertNotNull("测试样本 $name 缺失", stream)
        return stream.readBytes()
    }

    private val onelapSample: ByteArray by lazy { load("synth_onelap_virtual.fit") }
    private val igpSample: ByteArray by lazy { load("synth_igp_virtual.fit") }

    // ---------- 转换算法 ----------

    @Test
    fun `境外坐标不做转换`() {
        val r = CoordTransform.gcjToWgs(35.6762, 139.6503)
        assertEquals(35.6762, r[0], 1e-9)
        assertEquals(139.6503, r[1], 1e-9)
    }

    @Test
    fun `西安坐标 GCJ转WGS 位移在400到500米之间`() {
        val r = CoordTransform.gcjToWgs(34.247861, 108.848710)
        val moved = CoordTransform.distanceMeters(34.247861, 108.848710, r[0], r[1])
        assertTrue("实际位移 $moved m，应在 400~500m", moved in 400.0..500.0)
        // 方向：GCJ-02 偏东南，转 WGS-84 应该往西北
        assertTrue("纬度应变大（往北）", r[0] > 34.247861)
        assertTrue("经度应变小（往西）", r[1] < 108.848710)
    }

    @Test
    fun `WGS转GCJ 是 GCJ转WGS 的逆运算`() {
        val gcj = doubleArrayOf(34.247861, 108.848710)
        val wgs = CoordTransform.gcjToWgs(gcj[0], gcj[1])
        val back = CoordTransform.wgsToGcj(wgs[0], wgs[1])
        assertEquals(gcj[0], back[0], 1e-7)
        assertEquals(gcj[1], back[1], 1e-7)
    }

    // ---------- FIT 改写 ----------

    @Test
    fun `顽鹿 FIT 小端 能改写全部坐标`() {
        val r = FitCoordRewriter.prepareForXingzhe(onelapSample)
        assertNotNull("FIT 解析失败", r)
        r!!
        assertEquals("应改写 5763 个点", 5763, r.pointsChanged)
        assertTrue("位移应在 400~500m，实际 ${r.movedMeters}", r.movedMeters in 400.0..500.0)
    }

    @Test
    fun `IGPSPORT FIT 大端 也能解析并改写`() {
        // ★ 这个测试就是 v1.2.0 的 bug：IGPSPORT 的 FIT 是大端 + 带 developer fields，
        //   旧解析器直接返回 null，导致 IGPSPORT→行者 完全没转坐标。
        val r = FitCoordRewriter.prepareForXingzhe(igpSample)
        assertNotNull("IGPSPORT（大端）FIT 解析失败 —— 解析器没处理大端字节序", r)
        r!!
        // 7254 = 4836 个 record 点 + lap/session 里的起止点坐标
        assertEquals("应改写 record + lap/session 坐标", 7254, r.pointsChanged)
        assertTrue("位移应在 400~500m，实际 ${r.movedMeters}", r.movedMeters in 400.0..500.0)
    }

    @Test
    fun `virtual_activity 被改成 generic`() {
        for ((name, bytes) in listOf("顽鹿" to onelapSample, "IGPSPORT" to igpSample)) {
            val r = FitCoordRewriter.prepareForXingzhe(bytes)!!
            assertTrue("$name 应检测到 virtual_activity", r.virtualActivityFixed)
        }
    }

    @Test
    fun `改写后不该再有 virtual_activity`() {
        for (bytes in listOf(onelapSample, igpSample)) {
            val r = FitCoordRewriter.prepareForXingzhe(bytes)!!
            val again = FitCoordRewriter.prepareForXingzhe(r.bytes)!!
            assertTrue("二次改写不应再检测到 virtual_activity", !again.virtualActivityFixed)
        }
    }

    @Test
    fun `改写后 CRC 正确`() {
        for (bytes in listOf(onelapSample, igpSample)) {
            val r = FitCoordRewriter.prepareForXingzhe(bytes)!!
            val crc = FitCoordRewriter.fitCrc(r.bytes, r.bytes.size - 2)
            val stored = ((r.bytes[r.bytes.size - 2].toInt() and 0xFF) shl 8) or
                (r.bytes[r.bytes.size - 1].toInt() and 0xFF)
            assertEquals("文件尾 CRC 不匹配，平台会拒收", stored, crc)
        }
    }

    @Test
    fun `改写只动坐标和sub_sport字节`() {
        val r = FitCoordRewriter.prepareForXingzhe(onelapSample)!!
        var diff = 0
        for (i in r.bytes.indices) {
            if (r.bytes[i] != onelapSample[i]) diff++
        }
        // 半圆整数 32 位，但位移仅 ~460m ⇒ 高位不变，只有低位变化
        // ⇒ 每坐标约 2 字节变：5763 点 × 2 坐标 × 2 字节 ≈ 23052
        // 再加 sub_sport 1 字节 + CRC 2 字节
        assertTrue("改动字节数异常: $diff", diff in 20000..46106)
        assertTrue("改动过多，说明误改了非目标字节: $diff", diff <= 5763L * 2 * 4 + 8)
    }

    @Test
    fun `非 FIT 文件返回 null`() {
        assertNull(FitCoordRewriter.prepareForXingzhe(ByteArray(100)))
        assertNull(FitCoordRewriter.prepareForXingzhe("hello world".toByteArray()))
    }

    @Test
    fun `境外活动不会被误改`() {
        val r = CoordTransform.gcjToWgs(35.6762, 139.6503)
        assertEquals(35.6762, r[0], 1e-9)
        assertEquals(139.6503, r[1], 1e-9)
        val r2 = CoordTransform.gcjToWgs(40.7128, -74.0060)
        assertEquals(40.7128, r2[0], 1e-9)
        assertEquals(-74.0060, r2[1], 1e-9)
    }

    // ---------- 城市反查 ----------

    @Test
    fun `城市反查 西安`() {
        assertEquals("西安", CityResolver.resolve(34.3416, 108.9398))
        // 市区范围内都该命中西安
        assertEquals("西安", CityResolver.resolve(34.2479, 108.8487))
        assertEquals("西安", CityResolver.resolve(34.30, 108.90))
    }

    @Test
    fun `城市反查 其它城市`() {
        assertEquals("杭州", CityResolver.resolve(30.2741, 120.1551))
        assertEquals("青岛", CityResolver.resolve(36.0671, 120.3826))
        assertEquals("北京", CityResolver.resolve(39.9042, 116.4074))
        assertEquals("上海", CityResolver.resolve(31.2304, 121.4737))
    }

    @Test
    fun `城市反查 超出范围返回 null`() {
        // 南海某处 —— 最近的表内城市（海口/三亚）也超过 35km
        assertNull(CityResolver.resolve(15.0, 114.0))
        // 西藏无人区
        assertNull(CityResolver.resolve(33.0, 88.0))
    }

    @Test
    fun `城市反查 从 FIT 首点取坐标`() {
        assertEquals("西安", CityResolver.resolveFromRaw(onelapSample))
    }

    @Test
    fun `改写后首点坐标可用于反查城市`() {
        val r = FitCoordRewriter.prepareForXingzhe(onelapSample)!!
        assertNotNull("应能拿到转换后的首点", r.wgsFirstLat)
        assertNotNull(r.wgsFirstLon)
        assertEquals("西安", CityResolver.resolve(r.wgsFirstLat!!, r.wgsFirstLon!!))
    }
}
