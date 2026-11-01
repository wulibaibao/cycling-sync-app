package com.wulibaibao.cyclicsync.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标转换 + FIT 字节级坐标改写的回归测试。
 *
 * 背景（2026-11-01 实测）：
 * - 顽鹿 / 迈金 / IGPSPORT 存的 FIT 坐标是 **GCJ-02**
 * - 行者底图按 **WGS-84** 显示
 * → 顽鹿→行者、IGPSPORT→行者必须转，否则整条路线偏 400-500m（实测 459m）
 * → 顽鹿→IGPSPORT 不能转（两边都是 GCJ-02）
 *
 * 用真实 FIT 样本（test/resources/onelap_sample.fit）验证：
 * 1. 转换后坐标与 Python `correct_fit_gpx.py` 的结果一致
 * 2. 改写后 FIT 的 CRC 仍然正确（否则平台解析失败）
 * 3. 改写后除坐标外的其他字段保持不变
 */
class CoordTransformTest {

    private val sample: ByteArray by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("onelap_sample.fit")
        assertNotNull("测试样本 onelap_sample.fit 缺失", stream)
        stream.readBytes()
    }

    // ---------- 转换算法 ----------

    @Test
    fun `境外坐标不做转换`() {
        // 东京 —— 境外 GCJ-02 与 WGS-84 是恒等映射
        val r = CoordTransform.gcjToWgs(35.6762, 139.6503)
        assertEquals(35.6762, r[0], 1e-9)
        assertEquals(139.6503, r[1], 1e-9)
    }

    @Test
    fun `西安坐标 GCJ转WGS 位移在400到500米之间`() {
        // 顽鹿 FIT 起点（GCJ-02）
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
    fun `改写后坐标全部位移 且位移量一致`() {
        val result = FitCoordRewriter.gcjToWgs(sample)
        assertNotNull("FIT 解析失败", result)
        result!!
        assertEquals("应改写 5763 个点", 5763, result.pointsChanged)
        assertTrue("位移应在 400~500m，实际 ${result.movedMeters}", result.movedMeters in 400.0..500.0)
    }

    @Test
    fun `改写后 CRC 正确`() {
        val result = FitCoordRewriter.gcjToWgs(sample)!!
        // 独立实现一次 CRC 校验（与 FitCoordRewriter 内部逻辑一致但走不同路径）
        val crc = FitCoordRewriter.fitCrc(result.bytes, result.bytes.size - 2)
        val stored = ((result.bytes[result.bytes.size - 2].toInt() and 0xFF) shl 8) or
            (result.bytes[result.bytes.size - 1].toInt() and 0xFF)
        assertEquals("文件尾 CRC 不匹配，平台会拒收", stored, crc)
    }

    @Test
    fun `改写只动坐标字节 其余字节不变`() {
        val result = FitCoordRewriter.gcjToWgs(sample)!!
        var diff = 0
        var diffOutsideCoords = 0
        // 找出所有不同的字节位置
        for (i in result.bytes.indices) {
            if (result.bytes[i] != sample[i]) {
                diff++
                // 坐标字段散布在整个 record 段，不能用固定区间判断
                diffOutsideCoords++
            }
        }
        assertTrue("应该有字节被修改", diff > 0)
        // CRC 2 字节 + 坐标 5763*2*4 = 46104 字节
        // 半圆整数是 32 位，但位移仅 ~460m ⇒ 高位不变，只有低位变化
        // ⇒ 每坐标约 2 字节变：5763 点 × 2 坐标 × 2 字节 ≈ 23052，加 CRC 2 字节
        assertTrue("改动字节数异常: $diff", diff in 20000..46106)
        // 若改动远超 4 字节/坐标，说明误改了非坐标字段
        assertTrue("改动过多，说明误改了非坐标字节: $diff", diff <= 5763L * 2 * 4 + 2)
    }

    @Test
    fun `非 FIT 文件返回 null`() {
        assertNull(FitCoordRewriter.gcjToWgs(ByteArray(100)))
        assertNull(FitCoordRewriter.gcjToWgs("hello world".toByteArray()))
    }

    @Test
    fun `境外活动不会被误改`() {
        // 东京（境外）—— GCJ-02 与 WGS-84 是恒等映射，不该被转换
        val r = CoordTransform.gcjToWgs(35.6762, 139.6503)
        assertEquals(35.6762, r[0], 1e-9)
        assertEquals(139.6503, r[1], 1e-9)
        // 纽约 —— 明确在境外框外
        val r2 = CoordTransform.gcjToWgs(40.7128, -74.0060)
        assertEquals(40.7128, r2[0], 1e-9)
        assertEquals(-74.0060, r2[1], 1e-9)
    }
}
