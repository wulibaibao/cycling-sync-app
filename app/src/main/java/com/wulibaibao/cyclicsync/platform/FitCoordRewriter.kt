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
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Result && pointsChanged == other.pointsChanged &&
                movedMeters == other.movedMeters && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            (bytes.contentHashCode() * 31 + pointsChanged) * 31 + movedMeters.hashCode()
    }

    private class FieldDef(val defNum: Int, val size: Int, val baseType: Int)

    /** 把 FIT 里的 GCJ-02 坐标改写成 WGS-84。返回 null 表示文件不是有效 FIT。 */
    fun gcjToWgs(fit: ByteArray): Result? {
        if (fit.size < HEADER_SIZE + 2) return null
        if (fit[8] != '.'.code.toByte() || fit[9] != 'F'.code.toByte() ||
            fit[10] != 'I'.code.toByte() || fit[11] != 'T'.code.toByte()
        ) return null

        val out = fit.copyOf()
        val positions = findCoordPositions(out) ?: return null

        var changed = 0
        var firstLat: Double? = null
        var firstLon: Double? = null
        var firstMoved = 0.0

        var i = 0
        while (i + 1 < positions.size) {
            val latOff = positions[i]
            val lonOff = positions[i + 1]
            val rawLat = readUInt32(out, latOff)
            val rawLon = readUInt32(out, lonOff)
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
            val wgs = CoordTransform.gcjToWgs(latDeg, lonDeg)
            val newLat = (wgs[0] * DEG_TO_SEMICIRCLE).toInt()
            val newLon = (wgs[1] * DEG_TO_SEMICIRCLE).toInt()
            writeUInt32(out, latOff, newLat.toLong() and 0xFFFFFFFFL)
            writeUInt32(out, lonOff, newLon.toLong() and 0xFFFFFFFFL)
            changed++
            if (firstLat == null) {
                firstLat = latDeg
                firstLon = lonDeg
                firstMoved = CoordTransform.distanceMeters(latDeg, lonDeg, wgs[0], wgs[1])
            }
            i += 2
        }

        if (changed == 0) {
            return Result(out, 0, null, null, 0.0)
        }

        fixCrc(out)
        return Result(out, changed, firstLat, firstLon, firstMoved)
    }

    /**
     * 扫描 FIT，返回所有坐标字段的字节偏移，按 (lat, lon) 成对排列。
     * 解析失败返回 null。
     */
    private fun findCoordPositions(data: ByteArray): IntArray? {
        val positions = ArrayList<Int>()
        val defs = HashMap<Int, Pair<Int, List<FieldDef>>>()

        var off = HEADER_SIZE
        val end = data.size - 2
        try {
            while (off < end) {
                val hb = data[off].toInt() and 0xFF
                if (hb and 0x80 != 0) {
                    // compressed timestamp header: 0x80 | local type, 后面跟 1 字节时间偏移
                    val local = hb and 0x1F
                    val def = defs[local] ?: return null
                    off += 2
                    for (f in def.second) {
                        if (isCoordField(def.first, f.defNum)) positions += off
                        off += f.size
                    }
                } else if (hb and 0x40 != 0) {
                    // definition message
                    val local = hb and 0x3F
                    val reserved = data[off + 1].toInt() and 0xFF
                    val isDev = reserved and 0x20 != 0
                    val gmn = (data[off + 3].toInt() and 0xFF) or
                        ((data[off + 4].toInt() and 0xFF) shl 8)
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
                    defs[local] = Pair(gmn, fields)
                    off = p
                } else {
                    // data message
                    val local = hb and 0x3F
                    val def = defs[local] ?: return null
                    off += 1
                    for (f in def.second) {
                        if (isCoordField(def.first, f.defNum)) positions += off
                        off += f.size
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return positions.toIntArray()
    }

    /** 判断某个 (消息类型, 字段号) 是否是坐标字段。 */
    private fun isCoordField(gmn: Int, defNum: Int): Boolean = when (gmn) {
        GMN_RECORD -> defNum == FDN_POSITION_LAT || defNum == FDN_POSITION_LONG
        GMN_LAP -> defNum == FDN_START_POSITION_LAT || defNum == FDN_START_POSITION_LONG ||
            defNum == FDN_END_POSITION_LAT || defNum == FDN_END_POSITION_LONG
        GMN_SESSION -> defNum == FDN_NEC_LAT || defNum == FDN_NEC_LONG ||
            defNum == FDN_SWC_LAT || defNum == FDN_SWC_LONG
        else -> false
    }

    private fun readUInt32(data: ByteArray, off: Int): Long =
        (data[off].toLong() and 0xFF) or
            ((data[off + 1].toLong() and 0xFF) shl 8) or
            ((data[off + 2].toLong() and 0xFF) shl 16) or
            ((data[off + 3].toLong() and 0xFF) shl 24)

    private fun writeUInt32(data: ByteArray, off: Int, value: Long) {
        data[off] = (value and 0xFF).toByte()
        data[off + 1] = ((value shr 8) and 0xFF).toByte()
        data[off + 2] = ((value shr 16) and 0xFF).toByte()
        data[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** FIT NORMAL CRC（多项式 0xA001），重算文件尾 2 字节。 */
    private fun fixCrc(data: ByteArray) {
        val crc = fitCrc(data, data.size - 2)
        data[data.size - 2] = ((crc shr 8) and 0xFF).toByte()
        data[data.size - 1] = (crc and 0xFF).toByte()
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
