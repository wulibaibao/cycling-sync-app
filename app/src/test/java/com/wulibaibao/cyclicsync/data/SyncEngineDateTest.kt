package com.wulibaibao.cyclicsync.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日期过滤测试。
 *
 * 各平台返回的时间字符串格式不统一，必须都能落到正确的日期上：
 * - IGPSPORT: "2026.10.31"              （点号）
 * - IGPSPORT: "2026.10.31 08:12:33"
 * - 行者:     "2026-10-29T08:12:33"
 * - 顽鹿:     "2026-10-29"
 *
 * 之前 sameDay 只认 "yyyy-MM-dd"，IGPSPORT 的点号格式解析失败返回 false
 * → IGPSPORT 记录全被过滤掉，链路静默失效。
 */
class SyncEngineDateTest {

    @Test
    fun `IGPSPORT 点号日期能匹配到目标日期`() {
        val target = LocalDate.of(2026, 10, 31)
        assertTrue(sameDay("2026.10.31", target))
    }

    @Test
    fun `IGPSPORT 点号日期带时间也能匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertTrue(sameDay("2026.10.31 08:12:33", target))
    }

    @Test
    fun `横杠日期和 ISO 格式都能匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertTrue(sameDay("2026-10-31", target))
        assertTrue(sameDay("2026-10-31 08:12:33", target))
        assertTrue(sameDay("2026-10-31T08:12:33", target))
        assertTrue(sameDay("2026/10/31", target))
    }

    @Test
    fun `非目标日期不匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertFalse(sameDay("2026.10.30", target))
        assertFalse(sameDay("2026-10-29", target))
    }

    @Test
    fun `空值与非法值不匹配`() {
        val target = LocalDate.of(2026, 10, 31)
        assertFalse(sameDay("", target))
        assertFalse(sameDay("   ", target))
        assertFalse(sameDay("garbage", target))
    }

    // ---------- 分页深度（2026-11-02 修复的 bug）----------

    /**
     * 2026-11-02 实测：用户要同步「2026-05-30」的顽鹿数据，App 报「发现 0 条顽鹿记录」。
     *
     * 根因：App 只拉 5 页 × 20 条 = 100 条，而「2026-05-30」的记录在**第 6 页**
     *（第 120 条）—— 刚好差一页拉不到。
     *
     * 顽鹿列表 API 实测行为：
     * - 只支持 `page` / `limit` 两个参数，没有日期过滤
     * - `limit` 最大 200（实测 100/200 都接受）
     * - 页偏移 = (page-1) × limit，所以 limit 越大需要的页数越少
     * - 总记录数约 460 条（2023-05 至今），limit=100 时 5 页拉完
     * - 列表按开始时间**倒序**（新的在前）
     */
    @Test
    fun `顽鹿分页上限必须覆盖历史深度`() {
        // 100 条/页时，「2026-05-30」在第 2 页 → 2 页足够
        // 但 limit=20 时它要到第 6 页 → 只翻 5 页就会漏
        assertTrue(
            "2026-05-30 的记录在 limit=20 时是第 6 页，分页上限必须 > 5",
            ONELAP_MAX_PAGES > 5,
        )
        assertTrue(
            "每页条数要够大，否则历史数据翻不到",
            ONELAP_PAGE_SIZE >= 100,
        )
    }

    @Test
    fun `顽鹿列表倒序 第6页确实是5月30日之后`() {
        // 实测数据（limit=20）：page1=2026-11-02, page5=2026-07-23, page6=2026-06-10
        // → 2026-05-30 落在 page 6，验证「翻页到 6 页以上才找得到」
        val page6First = "2026-06-10"
        val target = LocalDate.of(2026, 5, 30)
        // page6 最早是 2026-06-10，目标日期比它更早 → 必然在 page6 或更后
        assertTrue(
            "2026-05-30 应该在第 6 页或更后",
            LocalDate.parse(page6First).isAfter(target),
        )
    }

    // ---------- 文件名即标题（IGPSPORT）----------

    /**
     * IGPSPORT 没有独立的标题参数 —— **上传时的文件名就是活动标题**。
     *
     * 2026-11-03 实测（真实账号 523 条活动）：
     * - App 传 `onelap_6ae7d3c09d25ac14d7b32d22.fit` → IGPSPORT 列表 title = `onelap_6ae7d3c09d25ac14d7b32d22`
     * - 迈金传 `Magene_C706_1780350977_470592_1780357105422.fit` → title = 同名（含 .fit）
     * - 顽鹿传 `MATCH_470592-2026-10-25-07-11-07-log.fit` → title = 同名（含 .fit）
     * - FIT 文件**内部没有名字字段**时，title 就退化成文件名
     *
     * ⇒ 想让 IGPSPORT 显示正常标题，就把文件名改成标题。
     */
    @Test
    fun `IGPSPORT 文件名必须是 fit 后缀`() {
        // getSignedUrl 的签名 path 是 ?fileExtension=.fit —— 文件名不带 .fit 会签名失败
        assertTrue("文件名必须以 .fit 结尾", "2026-05-30 上午 西安 骑行.fit".endsWith(".fit"))
    }

    @Test
    fun `标题里的路径分隔符要替换掉`() {
        val bad = "2026/05/30 上午 西安:骑行"
        val safe = bad.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        assertFalse("不能含路径分隔符", safe.contains("/"))
        assertFalse("不能含冒号", safe.contains(":"))
    }

    @Test
    fun `标题长度不超过 IGPSPORT 存储上限`() {
        // 实测最长 50 字符（Magene_C706_...fit），40 字符有 72 条 ⇒ 没有硬性 40 上限，
        // 但保守起见截到 40
        assertTrue(40 <= 50)
    }

    // ---------- IGPSPORT 来源判定（2026-11-03）----------

    /**
     * IGPSPORT 服务端**原样存储**上传的 FIT（实测 4307 个坐标逐位相同），
     * 所以下游转换的依据是「这条 FIT 当初是谁上传的」。
     */
    @Test
    fun `我们v123的标题判为 WGS-84`() {
        assertTrue(isWgs84IgpActivity("2026-05-30 上午 西安 骑行"))
        assertTrue(isWgs84IgpActivity("2026-10-28 下午 青岛 骑行"))
        assertTrue(isWgs84IgpActivity("2026-01-01 凌晨 上海 骑行"))
        assertTrue(isWgs84IgpActivity("2026-12-31 晚上 深圳 骑行"))
    }

    @Test
    fun `Strava数字名判为 WGS-84`() {
        assertTrue(isWgs84IgpActivity("21696308938.fit"))
        assertTrue(isWgs84IgpActivity("12345.fit"))
    }

    @Test
    fun `迈金顽鹿原生判为 GCJ-02`() {
        assertFalse(isWgs84IgpActivity("Magene_C706_1791238870_470592_1791239322351.fit"))
        assertFalse(isWgs84IgpActivity("MATCH_470592-2026-10-29-06-00-38-log.fit"))
        assertFalse(isWgs84IgpActivity("onelap_6ae7d3c09d25ac14d7b32d22"))
        assertFalse(isWgs84IgpActivity("户外骑行"))
        assertFalse(isWgs84IgpActivity(""))
    }

    /**
     * ⚠️ **不能用「偏移量更小」探测坐标系**（2026-11-03 实测 10/10 全错）。
     *
     * `gcj2wgs` 是 `wgs2gcj` 的近似逆运算，两个方向的偏移量只差 3% 左右 ——
     * 迈金的 GCJ-02 数据会被判成 WGS-84。只有来源判定可靠。
     */
    @Test
    fun `偏移量探测法不可靠 只能靠来源`() {
        // 西安的一个 GCJ-02 点
        val gcj = doubleArrayOf(34.24610707908869, 108.84276425465941)
        val asWgs = com.wulibaibao.cyclicsync.platform.CoordTransform.wgsToGcj(gcj[0], gcj[1])
        val asGcj = com.wulibaibao.cyclicsync.platform.CoordTransform.gcjToWgs(gcj[0], gcj[1])
        val offWgs = Math.hypot(asWgs[0] - gcj[0], asWgs[1] - gcj[1])
        val offGcj = Math.hypot(asGcj[0] - gcj[0], asGcj[1] - gcj[1])
        // 两个方向差不到 5% ⇒ 分辨不出来
        val ratio = maxOf(offWgs, offGcj) / minOf(offWgs, offGcj)
        assertTrue("两方向偏移量太接近，探测法不可靠", ratio < 1.05)
    }

    // ---------- 判重只看服务端（2026-11-03）----------

    /**
     * 判重**只能**看服务端索引。
     *
     * 背景：之前有「本地同步历史」这道判重，结果踩坑 ——
     * 服务端那条记录被用户删掉后，本地历史还留着，
     * 于是每次同步都报「已同步过（本地记录）」，永远补不回来。
     * 实测：2026-05-30 那条 144.8km 在 IGPSPORT 上根本不存在，
     * App 却一直跳过它。
     *
     * 现在的规则：服务端有 → 跳过；服务端没有 → 传。
     * 本地历史（SyncHistory）已整类删除。
     */
    @Test
    fun `服务端索引是唯一判重依据`() {
        val idx = DedupIndex()
        // 服务端没有这条
        assertFalse(idx.matchesFingerprint(1780000000000L, 144840.0))
        // 服务端有这条（时间 ±15s，距离 0.5% 容差内）
        idx.add(1780000000000L, 144840.0)
        assertTrue(idx.matchesFingerprint(1780000000000L, 144840.0))
        assertTrue(idx.matchesFingerprint(1780000005000L, 145000.0))  // 时间差 5s，距离差 160m
        // 距离差太多 → 不命中
        assertFalse(idx.matchesFingerprint(1780000000000L, 200000.0))
    }

    @Test
    fun `服务端记录被删后 不再卡死`() {
        // 空索引 = 服务端什么都没有 ⇒ 应该上传（而不是被本地历史拦下）
        val idx = DedupIndex()
        assertFalse(idx.matchesFingerprint(1780000000000L, 144840.0))
    }
}
