package com.wulibaibao.cyclicsync.platform

/**
 * FIT 文件的坐标改写器（字节级原地修改）。
 *
 * 为什么不引入 FIT 库：Android 端只需要改坐标字段，而 FIT 的坐标是
 * **半圆整数**（semicircle，1 度 = 2^31），可以按字节定位后原地改写，
 * 不需要重新编码整个文件（重新编码会丢字段、且 garmin-fit-sdk 无 Android 版）。
 *
 * 实现要点：
 * 1. 走一遍 FIT 的 definition / data message，记录每个坐标字段的**字节偏移**。
 * 2. 只改 `record`（gmn=20）的 position_lat(fdn=0) / position_long(fdn=1)，
 *    以及 `lap`(gmn=19) / `session`(gmn=18) 里的 start/end/nec/swc 坐标。
 * 3. 改完必须**重算文件尾 CRC**（FIT NORMAL CRC，poly 0xA001），
 *    否则平台会拒收或解析失败。
 *
 * FIT 格式要点（小端）：
 * - header 14 字节：`header_size(1) proto(1) profile(2) data_size(4) ".FIT"(4) header_crc(2)`
 * - definition message：`0x40|local(1) reserved(1) architecture(1) global_msg_num(2) num_fields(1)`
 *   然后每个 field：`field_def_num(1) size(1) base_type(1)`
 * - data message：`0x00|local(1)`（或 `0x80|local` 压缩时间戳头）后跟各字段值
 */
object FitCoordRewriter {

    // ---- base_type ----
    private const val BASE_UINT8 = 0x02
    private const val BASE_UINT16 = 0x84
    private const val BASE_UINT32 = 0x85
    private const val BASE_SINT32 = 0x86

    // ---- global message numbers ----
    private const val GMN_SESSION = 18
    private const val GMN_LAP = 19
    private const val GMN_RECORD = 20

    // 坐标字段定义号（FIT profile）
    private const val FDN_POSITION_LAT = 0
    private const val FDN_POSITION_LONG = 1
    private const val FDN_START_POSITION_LAT = 2
    private const val FDN_START_POSITION_LONG = 3
    private const val FDN_END_POSITION_LAT = 7
    private const val FDN_END_POSITION_LONG = 8
    private const val FDN_NEC_LAT = 48
    private const val FDN_NEC_LONG = 59
    private const val FDN_SWC_LAT = 122
    private const val FDN_SWC_LONG = 123

    // sub_sport（FIT profile 枚举）：58 = virtual_activity —— 行者会显示成「虚拟骑行」
    private const val FDN_SESSION_SUB_SPORT = 6
    private const val FDN_LAP_SUB_SPORT = 24
    private const val SUB_SPORT_GENERIC = 0
    private const val SUB_SPORT_VIRTUAL_ACTIVITY = 58

    private const val HEADER_SIZE = 14
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0
    private const val DEG_TO_SEMICIRCLE = 2147483648.0 / 180.0

    /** 一次改写的统计。 */
    data class Result(
        val bytes: ByteArray,
        val pointsChanged: Int,
        val firstLat: Double?,
        val firstLon: Double?,
        val movedMeters: Double,
        /** 是否把 sub_sport 从 virtual_activity 改成了 generic */
        val virtualActivityFixed: Boolean = false,
        /**
         * 转换后的首个轨迹点（**目标坐标系**），null = 无坐标。
         *
         * 目标坐标系是 WGS-84 时（`targetIsGcj = false`）它就是 WGS-84，
         * 可以直接拿去反查城市（城市表是 WGS-84 的）。
         */
        val wgsFirstLat: Double? = null,
        val wgsFirstLon: Double? = null,
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Result && pointsChanged == other.pointsChanged &&
                movedMeters == other.movedMeters &&
                virtualActivityFixed == other.virtualActivityFixed &&
                bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            (bytes.contentHashCode() * 31 + pointsChanged) * 31 + movedMeters.hashCode()
    }

    /**
     * 这个 FIT 是不是**虚拟骑行**（trainer / 室内）。
     *
     * 判据：session 或 lap 消息里的 `sub_sport == 58 (virtual_activity)`。
     *
     * 为什么要单独判断：虚拟骑行的轨迹是 trainer 在室内录的，
     * **本来就不是真实 GPS 路径** —— 做 GCJ-02 ↔ WGS-84 转换只会让
     * 这条虚构的轨迹平移几百米，没有任何意义（用户要求：
     * 「顽鹿遇到虚拟骑行不必转格式」）。
     *
     * 只读不写 —— 不改 FIT 里的任何字节。
     */
    fun isVirtualActivity(fit: ByteArray): Boolean {
        if (fit.size < HEADER_SIZE + 2) return false
        if (fit[8] != '.'.code.toByte() || fit[9] != 'F'.code.toByte() ||
            fit[10] != 'I'.code.toByte() || fit[11] != 'T'.code.toByte()
        ) return false
        val positions = findPositions(fit) ?: return false
        for (off in positions.subSports) {
            if (off < 0 || off >= fit.size) continue
            if ((fit[off].toInt() and 0xFF) == SUB_SPORT_VIRTUAL_ACTIVITY) return true
        }
        return false
    }

    private class FieldDef(val defNum: Int, val size: Int, val baseType: Int)

    /** 扫描出来的字段位置。 */
    private class Positions(
        /** 坐标字段偏移，成对排列 (lat, lon) */
        val coords: IntArray,
        /** sub_sport 字段偏移 */
        val subSports: IntArray,
        /** 该 FIT 是否大端字节序（IGPSPORT 的是大端） */
        val bigEndian: Boolean,
    )

    /**
     * 把 FIT 改成适合上传到行者的样子：
     * - 坐标 GCJ-02 → WGS-84（行者底图是 WGS-84，不转的话偏 400-500m）
     * - sub_sport 58 (virtual_activity) → 0 (generic)
     *   —— 源 FIT 带 virtual_activity 的话，行者会显示成「虚拟骑行」而不是「骑行」
     *
     * 返回 null 表示文件不是有效 FIT。
     */
    fun prepareForXingzhe(
        fit: ByteArray,
        /** 是否把 sub_sport 从 virtual_activity(58) 改成 generic(0) */
        fixSubSport: Boolean = true,
        /** 是否做坐标转换 */
        convertCoords: Boolean = true,
        /** 目标坐标系。GCJ-02 → WGS-84 时传 false；WGS-84 → GCJ-02 时传 true。 */
        targetIsGcj: Boolean = false,
    ): Result? {
        if (fit.size < HEADER_SIZE + 2) return null
        if (fit[8] != '.'.code.toByte() || fit[9] != 'F'.code.toByte() ||
            fit[10] != 'I'.code.toByte() || fit[11] != 'T'.code.toByte()
        ) return null

        val out = fit.copyOf()
        val positions = findPositions(out) ?: return null

        // ---- 1. sub_sport: virtual_activity → generic ----
        var fixedVirtual = false
        if (fixSubSport) {
            for (off in positions.subSports) {
                if (off < 0 || off >= out.size) continue
                if ((out[off].toInt() and 0xFF) == SUB_SPORT_VIRTUAL_ACTIVITY) {
                    out[off] = SUB_SPORT_GENERIC.toByte()
                    fixedVirtual = true
                }
            }
        }

        var changed = 0
        var firstLat: Double? = null
        var firstLon: Double? = null
        var firstMoved = 0.0
        var wgsFirstLat: Double? = null
        var wgsFirstLon: Double? = null

        var i = 0
        while (i + 1 < positions.coords.size) {
            val latOff = positions.coords[i]
            val lonOff = positions.coords[i + 1]
            if (latOff + 3 >= out.size || lonOff + 3 >= out.size) {
                i += 2
                continue
            }
            val rawLat = readUInt32(out, latOff, positions.bigEndian)
            val rawLon = readUInt32(out, lonOff, positions.bigEndian)
            val latDeg = rawLat * SEMICIRCLE_TO_DEG
            val lonDeg = rawLon * SEMICIRCLE_TO_DEG
            // 无效值（0xFFFFFFFF）跳过
            if (rawLat == 0xFFFFFFFFL || rawLon == 0xFFFFFFFFL) {
                i += 2
                continue
            }
            if (CoordTransform.isOutOfChina(latDeg, lonDeg)) {
                i += 2
                continue
            }
            if (!convertCoords) {
                // 只取首点用于城市反查，不改坐标
                if (firstLat == null) {
                    firstLat = latDeg
                    firstLon = lonDeg
                    firstMoved = 0.0
                    wgsFirstLat = latDeg
                    wgsFirstLon = lonDeg
                }
                i += 2
                continue
            }
            val conv = if (targetIsGcj) {
                CoordTransform.wgsToGcj(latDeg, lonDeg)
            } else {
                CoordTransform.gcjToWgs(latDeg, lonDeg)
            }
            val newLat = (conv[0] * DEG_TO_SEMICIRCLE).toInt()
            val newLon = (conv[1] * DEG_TO_SEMICIRCLE).toInt()
            writeUInt32(out, latOff, newLat.toLong() and 0xFFFFFFFFL, positions.bigEndian)
            writeUInt32(out, lonOff, newLon.toLong() and 0xFFFFFFFFL, positions.bigEndian)
            changed++
            if (firstLat == null) {
                firstLat = latDeg
                firstLon = lonDeg
                firstMoved = CoordTransform.distanceMeters(latDeg, lonDeg, conv[0], conv[1])
            }
            // 转换后的首点（目标坐标系）。城市反查用 WGS-84 时才用它。
            if (wgsFirstLat == null) {
                wgsFirstLat = conv[0]
                wgsFirstLon = conv[1]
            }
            i += 2
        }

        if (changed == 0 && !fixedVirtual) {
            return Result(out, 0, null, null, 0.0, false, wgsFirstLat, wgsFirstLon)
        }

        fixCrc(out)
        return Result(out, changed, firstLat, firstLon, firstMoved, fixedVirtual, wgsFirstLat, wgsFirstLon)
    }

    /** 只做坐标转换（旧接口语义保留）。 */
    fun gcjToWgs(fit: ByteArray): Result? = prepareForXingzhe(fit)

    /**
     * 取 FIT 里第一个轨迹点的**原始**坐标（不转换）。
     * 用于城市反查的兜底路径。
     */
    fun firstRawPoint(fit: ByteArray): DoubleArray? {
        if (fit.size < HEADER_SIZE + 2) return null
        val pos = findPositions(fit) ?: return null
        val coords = pos.coords
        var i = 0
        while (i + 1 < coords.size) {
            val latOff = coords[i]
            val lonOff = coords[i + 1]
            if (latOff + 3 < fit.size && lonOff + 3 < fit.size) {
                val rawLat = readUInt32(fit, latOff, pos.bigEndian)
                val rawLon = readUInt32(fit, lonOff, pos.bigEndian)
                if (rawLat != 0xFFFFFFFFL && rawLon != 0xFFFFFFFFL) {
                    return doubleArrayOf(rawLat * SEMICIRCLE_TO_DEG, rawLon * SEMICIRCLE_TO_DEG)
                }
            }
            i += 2
        }
        return null
    }

    /**
     * 扫描 FIT，返回所有坐标字段的字节偏移，按 (lat, lon) 成对排列。
     * 解析失败返回 null。
     *
     * FIT 二进制格式（实测顽鹿 + IGPSPORT 两种样本）：
     * - header 14 字节：`header_size(1) proto(1) profile(2) data_size(4) ".FIT"(4) header_crc(2)`
     * - data message header（1 字节）：
     *     bit7=1 → compressed timestamp header，**local type = (b>>5)&0x03**，后面跟 1 字节时间偏移
     *     bit6=1 → definition message
     *     bit5=1 → 该 definition 带 developer fields
     *     local type = b & 0x0F
     * - definition message：`local(1) reserved(1) architecture(1) global_msg_num(2) num_fields(1)`
     *     然后每个 field：`field_def_num(1) size(1) base_type(1)`
     *     若带 developer fields，再跟 `num_dev_fields(1)` + 每个 dev field 3 字节
     * - **architecture 字节决定字节序**：0 = 小端，非 0 = 大端
     *     （顽鹿的 FIT 是小端，IGPSPORT 的 FIT 是大端 —— 实测，别写死）
     */
    private fun findPositions(data: ByteArray): Positions? {
        val coords = ArrayList<Int>()
        val subSports = ArrayList<Int>()
        val defs = HashMap<Int, Triple<Int, List<FieldDef>, Boolean>>()
        var bigEndianFile = false

        var off = HEADER_SIZE
        val end = data.size - 2
        try {
            while (off < end) {
                val hb = data[off].toInt() and 0xFF
                if (hb and 0x80 != 0) {
                    // compressed timestamp header：local 在 bit5-6，时间偏移在 bit0-4
                    val local = (hb shr 5) and 0x03
                    val def = defs[local] ?: return null
                    off += 2
                    for (f in def.second) {
                        if (isCoordField(def.first, f.defNum)) coords += off
                        if (isSubSportField(def.first, f.defNum)) subSports += off
                        off += f.size
                    }
                } else if (hb and 0x40 != 0) {
                    // definition message
                    val local = hb and 0x0F
                    val isDev = hb and 0x20 != 0
                    // architecture 字节在 local(1) + reserved(1) 之后
                    val bigEndian = data[off + 2].toInt() and 0xFF != 0
                    val gmn = if (bigEndian) {
                        ((data[off + 3].toInt() and 0xFF) shl 8) or (data[off + 4].toInt() and 0xFF)
                    } else {
                        ((data[off + 4].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                    }
                    val numFields = data[off + 5].toInt() and 0xFF
                    var p = off + 6
                    val fields = ArrayList<FieldDef>(numFields)
                    for (i in 0 until numFields) {
                        fields += FieldDef(data[p].toInt() and 0xFF, data[p + 1].toInt() and 0xFF, data[p + 2].toInt() and 0xFF)
                        p += 3
                    }
                    if (isDev) {
                        val numDev = data[p].toInt() and 0xFF
                        p += 1
                        for (i in 0 until numDev) {
                            fields += FieldDef(data[p].toInt() and 0xFF, data[p + 1].toInt() and 0xFF, data[p + 2].toInt() and 0xFF)
                            p += 3
                        }
                    }
                    defs[local] = Triple(gmn, fields, bigEndian)
                    bigEndianFile = bigEndian
                    off = p
                } else {
                    // data message
                    val local = hb and 0x0F
                    val def = defs[local] ?: return null
                    off += 1
                    for (f in def.second) {
                        if (isCoordField(def.first, f.defNum)) coords += off
                        if (isSubSportField(def.first, f.defNum)) subSports += off
                        off += f.size
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return Positions(coords.toIntArray(), subSports.toIntArray(), bigEndianFile)
    }

    /** 判断某个 (消息类型, 字段号) 是否是 sub_sport 字段。 */
    private fun isSubSportField(gmn: Int, defNum: Int): Boolean = when (gmn) {
        GMN_SESSION -> defNum == FDN_SESSION_SUB_SPORT
        GMN_LAP -> defNum == FDN_LAP_SUB_SPORT
        else -> false
    }

    /**
     * 判断某个 (消息类型, 字段号) 是否是坐标字段。
     *
     * ⚠️ **只处理 record 消息**（2026-11-03 修）。
     *
     * 之前还把 LAP / SESSION 的字段号也算进来了，但**不同消息类型的字段号空间是独立的**：
     * LAP 的 field 2 不是 `start_position_lat`，SESSION 的 field 48/59 也不是 nec_lat/long
     * （FIT 规范里 nec_lat 是 48、nec_long 是 59，但各家设备不一定这么写）。
     *
     * 实测踩坑：迈金 C706 的一份 17948 点的 FIT 里，
     * LAP 消息的 field 2 被误判成 start_position_lat ⇒ 坐标字段多出 88 个（44 对）
     * ⇒ 后面 `i += 2` 配对全部错位 ⇒ **只转了一半的坐标**（9193/17948），
     * 而且 CRC 重算基于改坏的字节 ⇒ IGPSPORT 服务端解析失败 ⇒
     * **上传「成功」但活动不生成**（假成功）。
     *
     * record 的 position_lat/long（field 0/1）是规范里唯一定义的轨迹点坐标，
     * 只改它就够了 —— lap/session 的辅助坐标改了也没用（不同平台的底图不用它们）。
     */
    private fun isCoordField(gmn: Int, defNum: Int): Boolean = when (gmn) {
        GMN_RECORD -> defNum == FDN_POSITION_LAT || defNum == FDN_POSITION_LONG
        else -> false
    }

    /** 读 32 位无符号整数，按文件字节序。 */
    private fun readUInt32(data: ByteArray, off: Int, bigEndian: Boolean): Long {
        val b0 = data[off].toLong() and 0xFF
        val b1 = data[off + 1].toLong() and 0xFF
        val b2 = data[off + 2].toLong() and 0xFF
        val b3 = data[off + 3].toLong() and 0xFF
        return if (bigEndian) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }

    /** 写 32 位无符号整数，按文件字节序。 */
    private fun writeUInt32(data: ByteArray, off: Int, value: Long, bigEndian: Boolean) {
        val b0 = (value and 0xFF).toByte()
        val b1 = ((value shr 8) and 0xFF).toByte()
        val b2 = ((value shr 16) and 0xFF).toByte()
        val b3 = ((value shr 24) and 0xFF).toByte()
        if (bigEndian) {
            data[off] = b3; data[off + 1] = b2; data[off + 2] = b1; data[off + 3] = b0
        } else {
            data[off] = b0; data[off + 1] = b1; data[off + 2] = b2; data[off + 3] = b3
        }
    }

    /**
     * FIT NORMAL CRC（多项式 0xA001），重算文件尾 2 字节。
     *
     * ⚠️ **必须小端**（2026-11-03 修）：文件尾两个字节是 **低字节在前**。
     * 之前写成大端（`crc shr 8` 在前）⇒ 读出来的 CRC 值刚好高低字节互换 ⇒
     * fitdecode 报「mismatching CRC in FIT footer」⇒
     * IGPSPORT 服务端解析失败 ⇒ **上传「成功」但活动不生成**（假成功）。
     */
    private fun fixCrc(data: ByteArray) {
        val crc = fitCrc(data, data.size - 2)
        data[data.size - 2] = (crc and 0xFF).toByte()          // 低字节在前
        data[data.size - 1] = ((crc shr 8) and 0xFF).toByte()
    }

    internal fun fitCrc(data: ByteArray, length: Int): Int {
        val table = CRC_TABLE
        var crc = 0
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xFF
            crc = (crc shr 8) xor table[(crc xor b) and 0xFF]
        }
        return crc and 0xFFFF
    }

    private val CRC_TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i
            for (b in 0 until 8) {
                c = if (c and 1 != 0) (c shr 1) xor 0xA001 else c shr 1
            }
            table[i] = c
        }
    }

    @Suppress("unused")
    private fun unusedBaseTypes() {
        // 保留常量引用，避免 IDE 误删
        listOf(BASE_UINT8, BASE_UINT16, BASE_UINT32, BASE_SINT32)
    }
}
