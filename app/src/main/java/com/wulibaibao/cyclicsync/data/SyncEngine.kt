package com.wulibaibao.cyclicsync.data

import com.wulibaibao.cyclicsync.platform.CityResolver
import com.wulibaibao.cyclicsync.platform.FitCoordRewriter
import com.wulibaibao.cyclicsync.platform.IgpClient
import com.wulibaibao.cyclicsync.platform.OneLapClient
import com.wulibaibao.cyclicsync.platform.XingzheClient
import kotlinx.coroutines.flow.Flow
import com.wulibaibao.cyclicsync.ui.Platform
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 同步方向 */
/**
 * 同步方向。
 *
 * 支持四条链路：IGPSPORT ↔ 顽鹿 互传，以及两者 → 行者。
 */
enum class SyncDirection(val label: String) {
    ONELAP_TO_XINGZHE("顽鹿 → 行者"),
    IGP_TO_XINGZHE("IGPSPORT → 行者"),
    ONELAP_TO_IGP("顽鹿 → IGPSPORT"),
    IGP_TO_ONELAP("IGPSPORT → 顽鹿"),
    ;

    /** 数据源平台 */
    val source: Platform
        get() = when (this) {
            ONELAP_TO_XINGZHE, ONELAP_TO_IGP -> Platform.ONELAP
            IGP_TO_XINGZHE, IGP_TO_ONELAP -> Platform.IGP
        }

    /** 目标平台 */
    val target: Platform
        get() = when (this) {
            ONELAP_TO_XINGZHE, IGP_TO_XINGZHE -> Platform.XINGZHE
            ONELAP_TO_IGP -> Platform.IGP
            IGP_TO_ONELAP -> Platform.ONELAP
        }

    /**
     * 分组色 —— 每条链路一个固定颜色，日志按方向分组时用同色背景包裹。
     */
    val groupColor: androidx.compose.ui.graphics.Color
        get() = when (this) {
            ONELAP_TO_XINGZHE -> androidx.compose.ui.graphics.Color(0xFF2E7D32) // 绿
            IGP_TO_XINGZHE -> androidx.compose.ui.graphics.Color(0xFF1565C0) // 蓝
            ONELAP_TO_IGP -> androidx.compose.ui.graphics.Color(0xFFE65100) // 橙
            IGP_TO_ONELAP -> androidx.compose.ui.graphics.Color(0xFF6A1B9A) // 紫
        }
}

/** 同步进度事件 */
sealed class SyncEvent {
    data class Info(val message: String) : SyncEvent()
    data class Step(val direction: SyncDirection, val text: String) : SyncEvent()
    data class Ride(
        val direction: SyncDirection,
        val date: String,
        val name: String,
        val distanceKm: Double,
        val status: String,
        /** 完整错误信息（失败时才有）—— 供复制用 */
        val errorDetail: String = "",
    ) : SyncEvent()
    data class Summary(
        val direction: SyncDirection,
        val uploaded: Int,
        val skipped: Int,
        val failed: Int,
    ) : SyncEvent()
    data class Done(val totalUploaded: Int, val totalSkipped: Int, val totalFailed: Int) : SyncEvent()
    data class Fatal(val message: String) : SyncEvent()
}

/**
 * 同步引擎。
 *
 * 链路全部在设备内实现 —— 凭据不出设备，不依赖任何服务器。
 * 顽鹿是主源：它的 FIT 文件是原始码表文件（带功率/踏频/温度），
 * 所以「顽鹿 → X」能保留完整数据；反向「IGPS → 顽鹿」会损失逐点功率。
 */
class SyncEngine(
    private val account: PlatformAccount,
) {

    private val onelap = OneLapClient()
    private val igp = IgpClient()
    private val xingzhe = XingzheClient()

    private var onelapLoggedIn = false
    private var igpReady = false
    private var xzReady = false

    /** 登录所有已配置的平台，返回错误信息（null=全成功） */
    suspend fun loginAll(): String? {
        if (account.onelapConfigured) {
            onelap.login(account.onelapAccount, account.onelapPassword)
                .onSuccess { onelapLoggedIn = true }
                .onFailure { return "顽鹿登录失败: ${it.message}" }
        }
        if (account.igpConfigured) {
            igp.token = account.igpToken
            igp.refreshToken = account.igpRefreshToken
            igp.expiresAt = account.igpExpiresAt
            igp.ensureToken()
                .onSuccess { igpReady = true }
                .onFailure { return "IGPSPORT 登录失败: ${it.message}" }
        }
        if (account.xingzheConfigured) {
            xingzhe.sessionId = account.xingzheSessionId
            xingzhe.username = account.xingzheUsername
            xingzhe.userId = account.xingzheUserId
            xingzhe.enuid = account.xingzheEnuid
            // session 有效性靠实际请求判断 —— 先探一次活动列表
            val probe = xingzhe.listPrivateWorkouts(offset = 0, limit = 1)
            if (probe.isFailure) {
                return "行者登录已失效: ${probe.exceptionOrNull()?.message}"
            }
            xzReady = true
        }
        return null
    }

    /** 执行同步。targetDate 限定只同步当天（或指定日期）的记录 */
    fun sync(
        directions: List<SyncDirection>,
        targetDate: java.time.LocalDate = java.time.LocalDate.now(),
    ): Flow<SyncEvent> = flow {
        var totalUploaded = 0
        var totalSkipped = 0
        var totalFailed = 0

        val loginError = loginAll()
        if (loginError != null) {
            emit(SyncEvent.Fatal(loginError))
            return@flow
        }

        for (dir in directions) {
            var up = 0
            var skip = 0
            var fail = 0
            try {
                when (dir) {
                    SyncDirection.ONELAP_TO_XINGZHE -> {
                        require(onelapLoggedIn) { "顽鹿未登录" }
                        require(xzReady) { "行者未登录" }
                        val rides = fetchOnelapRides(targetDate)
                        emit(SyncEvent.Step(dir, "发现 ${rides.size} 条顽鹿记录"))
                        // 行者已有记录（按 md5 / 名称判重）
                        val existing = fetchXingzheExisting()
                        for (ride in rides) {
                            val r = processRideToXingzhe(ride, existing)
                            when (r) {
                                is RideResult.Uploaded -> {
                                    up++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "已上传"))
                                }
                                is RideResult.Skipped -> {
                                    skip++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, r.reason))
                                }
                                is RideResult.Failed -> {
                                    fail++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "失败", errorDetail = r.reason))
                                }
                            }
                        }
                    }

                    SyncDirection.IGP_TO_XINGZHE -> {
                        require(igpReady) { "IGPSPORT 未登录" }
                        require(xzReady) { "行者未登录" }
                        val actsRes = igp.listActivities(
                            pageSize = 50,
                            beginTime = targetDate.toString(),
                        )
                        val acts = actsRes.getOrElse {
                            fail++
                            emptyList()
                        }
                        if (actsRes.isFailure) {
                            emit(
                                SyncEvent.Step(
                                    dir,
                                    "IGPSPORT 列表失败: ${actsRes.exceptionOrNull()?.message}",
                                ),
                            )
                        }
                        val actsOnDate = acts.filter { sameDay(it.startTime, targetDate) }
                        emit(
                            SyncEvent.Step(
                                dir,
                                "发现 ${acts.size} 条 IGPSPORT 记录，${actsOnDate.size} 条在 $targetDate",
                            ),
                        )
                        val existing = fetchXingzheExisting()
                        for (act in actsOnDate) {
                            val r = processIgpToXingzhe(act, existing)
                            when (r) {
                                is RideResult.Uploaded -> {
                                    up++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "已上传"))
                                }
                                is RideResult.Skipped -> {
                                    skip++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, r.reason))
                                }
                                is RideResult.Failed -> {
                                    fail++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "失败", errorDetail = r.reason))
                                }
                            }
                        }
                    }
                    SyncDirection.ONELAP_TO_IGP -> {
                        require(onelapLoggedIn) { "顽鹿未登录" }
                        require(igpReady) { "IGPSPORT 未登录" }
                        val rides = fetchOnelapRides(targetDate)
                        emit(SyncEvent.Step(dir, "发现 ${rides.size} 条顽鹿记录"))
                        val existing = fetchIgpExisting()
                        for (ride in rides) {
                            val r = processRideToIgp(ride, existing)
                            when (r) {
                                is RideResult.Uploaded -> {
                                    up++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "已上传"))
                                }
                                is RideResult.Skipped -> {
                                    skip++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, r.reason))
                                }
                                is RideResult.Failed -> {
                                    fail++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "失败", errorDetail = r.reason))
                                }
                            }
                        }
                    }

                    SyncDirection.IGP_TO_ONELAP -> {
                        require(igpReady) { "IGPSPORT 未登录" }
                        require(onelapLoggedIn) { "顽鹿未登录" }
                        val actsRes = igp.listActivities(
                            pageSize = 50,
                            beginTime = targetDate.toString(),
                        )
                        val acts = actsRes.getOrElse {
                            fail++
                            emptyList()
                        }
                        if (actsRes.isFailure) {
                            emit(
                                SyncEvent.Step(
                                    dir,
                                    "IGPSPORT 列表失败: ${actsRes.exceptionOrNull()?.message}",
                                ),
                            )
                        }
                        val actsOnDate = acts.filter { sameDay(it.startTime, targetDate) }
                        emit(
                            SyncEvent.Step(
                                dir,
                                "发现 ${acts.size} 条 IGPSPORT 记录，${actsOnDate.size} 条在 $targetDate",
                            ),
                        )
                        val existing = fetchOnelapExisting()
                        for (act in actsOnDate) {
                            val r = processIgpToOnelap(act, existing)
                            when (r) {
                                is RideResult.Uploaded -> {
                                    up++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "已上传"))
                                }
                                is RideResult.Skipped -> {
                                    skip++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, r.reason))
                                }
                                is RideResult.Failed -> {
                                    fail++
                                    emit(SyncEvent.Ride(dir, r.date, r.name, r.distanceKm, "失败", errorDetail = r.reason))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                fail++
                emit(SyncEvent.Step(dir, "链路异常: ${e.message}"))
            }
            emit(SyncEvent.Summary(dir, up, skip, fail))
            totalUploaded += up
            totalSkipped += skip
            totalFailed += fail
        }

        emit(SyncEvent.Done(totalUploaded, totalSkipped, totalFailed))
    }

    // ---------------------------------------------------------- 内部实现

    private suspend fun fetchOnelapRides(
        targetDate: java.time.LocalDate? = null,
    ): List<com.wulibaibao.cyclicsync.platform.OneLapRide> {
        val out = mutableListOf<com.wulibaibao.cyclicsync.platform.OneLapRide>()
        var page = 1
        // ⚠️ 别把页数限制太小：2026-11-02 实测「2026-05-30」的记录在**第 6 页**（第 120 条），
        //    原来限制 5 页 × 20 条 = 100 条 → 刚好差一页拉不到 → 同步显示「发现 0 条记录」。
        //    顽鹿的列表 API 支持 limit 最大 200（实测），这里用 100 条/页、最多 30 页兜底。
        val pageSize = ONELAP_PAGE_SIZE
        val maxPages = ONELAP_MAX_PAGES
        var extraPages = 0
        while (page <= maxPages) {
            val batchRes = onelap.listRides(page = page, limit = pageSize)
            if (batchRes.isFailure) break
            val batch = batchRes.getOrThrow()
            if (batch.isEmpty()) break
            if (targetDate == null) {
                out += batch
            } else {
                val hits = batch.filter { sameDay(it.startTime, targetDate) }
                out += hits
                // 列表按时间倒序，找到目标日期后多翻 2 页再停 —— 万一某条记录时间字段异常，
                // 不会漏掉同一天的另一条。
                if (hits.isNotEmpty()) {
                    extraPages++
                    if (extraPages >= 3) break
                }
            }
            if (batch.size < pageSize) break
            page++
        }
        return out
    }

    private suspend fun fetchXingzheExisting(): DedupIndex {
        val idx = DedupIndex()
        var offset = 0
        while (offset < 200) {
            val batchRes = xingzhe.listPrivateWorkouts(offset = offset, limit = 20)
            if (batchRes.isFailure) break
            val batch = batchRes.getOrThrow()
            if (batch.isEmpty()) break
            batch.forEach { o ->
                idx.addAux(o["md5"]?.toString()?.trim('"'))
                idx.addAux(o["name"]?.toString()?.trim('"'))
                // 指纹：开始时间 + 距离
                val startMs = DedupIndex.parseTime(
                    o["startTime"]?.toString()?.trim('"')
                        ?: o["start_time"]?.toString()?.trim('"')
                        ?: o["createTime"]?.toString()?.trim('"')
                        ?: "",
                )
                val dist = o["distance"]?.toString()?.trim('"')?.toDoubleOrNull()
                    ?: o["totalDistance"]?.toString()?.trim('"')?.toDoubleOrNull()
                    ?: 0.0
                if (startMs != null && dist > 0) idx.add(startMs, dist)
            }
            offset += 20
        }
        return idx
    }

    private suspend fun fetchIgpExisting(): DedupIndex {
        val idx = DedupIndex()
        igp.listActivities(pageSize = 100).getOrNull()?.forEach { a ->
            idx.addAux(a.rideId)
            idx.addAux(a.name)
            val startMs = DedupIndex.parseTime(a.startTime)
            if (startMs != null) idx.add(startMs, a.distanceM)
        }
        return idx
    }

    private suspend fun fetchOnelapExisting(): DedupIndex {
        val idx = DedupIndex()
        var page = 1
        while (page <= ONELAP_MAX_PAGES) {
            val batchRes = onelap.listRides(page = page, limit = ONELAP_PAGE_SIZE)
            if (batchRes.isFailure) break
            val batch = batchRes.getOrThrow()
            if (batch.isEmpty()) break
            batch.forEach { r ->
                idx.addAux(r.id)
                idx.addAux(r.name)
                val startMs = DedupIndex.parseTime(r.startTime)
                if (startMs != null) idx.add(startMs, r.distanceKm * 1000.0)
            }
            if (batch.size < 20) break
            page++
        }
        return idx
    }

    /**
     * 坐标系适配 —— 把 FIT 转成**行者**要的 WGS-84。
     *
     * 先探测源坐标系（IGPSPORT 里混杂，见 [adaptCoordsToOnelap] 的表格）：
     * - 已经是 WGS-84 → 不动坐标，只改 sub_sport
     * - 是 GCJ-02 → 转成 WGS-84，同时改 sub_sport
     *
     * 探不出来时按「转」处理 —— 行者是四条链路里唯一确定要 WGS-84 的目标。
     */
    private fun adaptCoordsToXingzhe(fit: ByteArray, isWgsSource: Boolean): ByteArray {
        val r = FitCoordRewriter.prepareForXingzhe(
            fit = fit,
            fixSubSport = true,
            convertCoords = isWgsSource.not(),
            targetIsGcj = false,
        ) ?: return fit
        if (r.pointsChanged == 0 && !r.virtualActivityFixed) return fit
        return r.bytes
    }

    /**
     * 坐标系适配 —— 把 FIT 里的 GCJ-02 坐标转成行者用的 WGS-84。
     *
     * 实测结论（2026-11-01 行者侧；2026-11-03 修正 IGPSPORT 侧）：
     * | 源 FIT 坐标 | 目标平台 | 需要转换 |
     * |---|---|---|
     * | 顽鹿 / 迈金 = GCJ-02 | 行者（WGS-84 底图） | ✅ gcj2wgs |
     * | 顽鹿 / 迈金 = GCJ-02 | IGPSPORT（**收 WGS-84**） | ✅ gcj2wgs |
     * | IGPSPORT 下载的 FIT | 行者（WGS-84 底图） | ✅ gcj2wgs |
     * | IGPSPORT 下载的 FIT | 顽鹿（GCJ-02 底图） | ❌ 不转 |
     *
     * ⚠️ **IGPSPORT 收 WGS-84**（2026-11-03 拆它网页版 bundle 实测）：
     * 底图是高德（GCJ-02），但轨迹数据字段名叫 `wgs84TrackJsonPath` ——
     * 服务端统一存 WGS-84，前端拿到后再转高德画图。
     * 之前误判成「IGPSPORT 不用转」，只验了存储层（下载回来的 FIT 与源逐位相同）
     * 没验显示层 —— **存储保真 ≠ 显示原样**。
     *
     * 不转的话整条路线偏 400-500m（实测 459m），路线会跑到马路外的建筑区和绿地。
     *
     * 注意：**转换后 md5 会变**，所以 md5 判重必须在转换之后算。
     */

    /**
     * 行者的原生标题格式：「日期 时段 城市 类型」
     * 例：2026-10-28 上午 西安 骑行
     *
     * - 日期：活动开始日期
     * - 时段：服务端按开始时间算（<06 凌晨 / <12 上午 / <18 下午 / ≥18 晚上），
     *   但客户端要先填对，客户端不填的话服务端也不补
     * - 城市：**必须客户端上传**，服务端不反查（实测不传就是空）
     * - 类型：sport=1 → 骑行（传 2/3/4/9 会被判成「虚拟骑行」）
     *
     * @param fit 转换后的 FIT（取首点坐标反查城市）
     * @param startTimeMs 活动开始时间（毫秒）
     */
    /**
     * 生成活动标题：「日期 时段 城市 骑行」
     *
     * 例：2026-10-28 上午 西安 骑行
     *
     * - **行者**：标题走 `name` 上传参数（`title` 会被忽略），城市走 `city` 参数
     * - **IGPSPORT**：没有独立的标题参数 —— **上传时的文件名就是标题**
     *   （实测：传 `onelap_xxx.fit` → IGPSPORT 显示 `onelap_xxx`；去 `.fit` 后缀）
     *
     * @return Pair(标题, 城市)
     */
    private fun buildActivityTitle(fit: ByteArray, startTimeMs: Long?): Pair<String, String> {
        val zone = java.time.ZoneId.systemDefault()
        // 时间为空时用「现在」，别用 0L（那是 1970 年，标题会变成 1970-xx-xx）
        val start = java.time.Instant.ofEpochMilli(startTimeMs ?: System.currentTimeMillis())
            .atZone(zone)
        val datePart = start.toLocalDate().toString()
        val hour = start.hour
        val period = when {
            hour < 6 -> "凌晨"
            hour < 12 -> "上午"
            hour < 18 -> "下午"
            else -> "晚上"
        }
        // 城市：按转换后的首点坐标（GCJ-02 的坐标拿去匹配城市表会偏 ~500m，
        // 正好在匹配半径边缘，所以用转换后的 WGS-84 坐标）
        val city = FitCoordRewriter.prepareForXingzhe(fit)?.let { r ->
            if (r.wgsFirstLat != null && r.wgsFirstLon != null) {
                CityResolver.resolve(r.wgsFirstLat, r.wgsFirstLon)
            } else null
        } ?: CityResolver.resolveFromRaw(fit)
        val title = buildString {
            append(datePart)
            append(' ')
            append(period)
            if (!city.isNullOrBlank()) {
                append(' ')
                append(city)
            }
            append(" 骑行")
        }
        return title to (city ?: "")
    }

    /** 兼容旧名。 */
    private fun buildXingzheTitle(fit: ByteArray, startTimeMs: Long?): Pair<String, String> =
        buildActivityTitle(fit, startTimeMs)

    private fun adaptCoordsToOnelap(fit: ByteArray, isWgsSource: Boolean): ByteArray {
        if (!isWgsSource) return fit   // 本来就是 GCJ-02，不动
        val r = FitCoordRewriter.prepareForXingzhe(
            fit = fit,
            fixSubSport = false,
            convertCoords = true,
            targetIsGcj = true,
        ) ?: return fit
        return r.bytes
    }

    private suspend fun processRideToXingzhe(
        ride: com.wulibaibao.cyclicsync.platform.OneLapRide,
        existing: DedupIndex,
    ): RideResult {
        val date = formatDate(ride.startTime)
        val rideStartMs = DedupIndex.parseTime(ride.startTime)
        val rideDistM = ride.distanceKm * 1000.0
        // 判重：只看服务端索引（时间±15s + 距离容差）—— 服务端才是权威。
        // 本地历史不参与判重：服务端记录被删后，本地历史会变成永久地雷，
        // 导致永远跳过、再也补不回来（2026-11-03 实测踩到）。
        if (existing.matchesFingerprint(rideStartMs, rideDistM)) {
            return RideResult.Skipped(date, ride.name, ride.distanceKm, "已存在（时间+距离匹配）")
        }
        val fitKey = onelap.fetchDetail(ride.id).getOrElse {
            return RideResult.Failed(date, ride.name, ride.distanceKm, "取 FIT key 失败: ${it.message}")
        }
        val fit = onelap.downloadFit(fitKey).getOrElse {
            return RideResult.Failed(date, ride.name, ride.distanceKm, "下载 FIT 失败: ${it.message}")
        }
        val fileName = "onelap_${ride.id}.fit"
        // 坐标系适配：行者底图是 WGS-84 —— 需要 WGS-84 的 FIT（不转会偏 459m）。
        // 顽鹿原生 FIT 是 GCJ-02，但用探测更保险（万一顽鹿里混了 WGS-84 的活动）。
        // 同时把 session/lap 的 sub_sport 从 virtual_activity 改成 generic，
        // 否则行者会显示成「虚拟骑行」而不是「骑行」。
        // 顽鹿原生 FIT 是 GCJ-02，行者要 WGS-84 ⇒ 需要转
        val fitOut = adaptCoordsToXingzhe(fit, isWgsSource = false)
        // md5 必须在转换之后算 —— 字节变了 md5 就变了
        val md5 = com.wulibaibao.cyclicsync.platform.Crypto.md5Bytes(fitOut)
        // 判重 3：md5 辅助键
        if (existing.matchesAux(md5)) {
            return RideResult.Skipped(date, ride.name, ride.distanceKm, "已存在（md5）")
        }
        // 标题格式：日期 时段 城市 骑行（与行者 App 生成的一致）
        val (title, city) = buildXingzheTitle(fitOut, rideStartMs)
        val outcome = xingzhe.uploadFit(
            fitBytes = fitOut,
            fileName = fileName,
            title = title,
            detail = "来自顽鹿同步",
            city = city,
        ).getOrElse {
            return RideResult.Failed(date, ride.name, ride.distanceKm, "上传失败: ${it.message}")
        }
        return when (outcome) {
            is com.wulibaibao.cyclicsync.platform.UploadOutcome.Uploaded -> {
                RideResult.Uploaded(date, ride.name, ride.distanceKm)
            }
            is com.wulibaibao.cyclicsync.platform.UploadOutcome.Duplicate -> {
                RideResult.Skipped(date, ride.name, ride.distanceKm, "已上传（服务端判重）")
            }
        }
    }

    /**
     * 顽鹿 → IGPSPORT。
     *
     * 流程：取 FIT key → 下载 FIT → 判重 → 三步 OSS 上传。
     */
    private suspend fun processRideToIgp(
        ride: com.wulibaibao.cyclicsync.platform.OneLapRide,
        existing: DedupIndex,
    ): RideResult {
        val date = formatDate(ride.startTime)
        val rideStartMs = DedupIndex.parseTime(ride.startTime)
        val rideDistM = ride.distanceKm * 1000.0
        // 判重：只看服务端索引 —— 服务端才是权威。
        // 本地历史不参与判重：服务端记录被删后，本地历史会变成永久地雷，
        // 导致永远跳过、再也补不回来（2026-11-03 实测踩到：5-30 那条 144.8km
        // 在 IGPSPORT 上根本不存在，却一直报「已同步过（本地记录）」）。
        if (existing.matchesFingerprint(rideStartMs, rideDistM)) {
            // 服务端已有 ⇒ 不写本地历史（本地历史只记「我确实上传过」，
            // 写进去会在服务端记录被删后变成永久地雷）
            return RideResult.Skipped(date, ride.name, ride.distanceKm, "已存在（时间+距离匹配）")
        }
        val fitKey = onelap.fetchDetail(ride.id).getOrElse {
            return RideResult.Failed(date, ride.name, ride.distanceKm, "取 FIT key 失败: ${it.message}")
        }
        val fit = onelap.downloadFit(fitKey).getOrElse {
            return RideResult.Failed(date, ride.name, ride.distanceKm, "下载 FIT 失败: ${it.message}")
        }
        // ★ 坐标转换：GCJ-02 → WGS-84
        //   IGPSPORT 同步 Strava 活动（Strava 是 WGS-84）能正常贴图，说明它按 WGS-84
        //   收数据、在显示时转成自己的底图坐标。传 GCJ-02 的 FIT 进去会偏 ~459m。
        //   （存储层实测原样保存 —— 4307 个坐标逐位相同 —— 但那只是存储，显示才是关键。）
        //
        // ★ sub_sport **不改**：IGPSPORT 的 exerciseType 实测几乎全是 0，
        //   不像行者那样区分「虚拟骑行」，改 sub_sport 没有意义。
        val rewritten = FitCoordRewriter.prepareForXingzhe(
            fit = fit,
            fixSubSport = false,
            convertCoords = true,
        )
        val fitForUpload = rewritten?.bytes ?: fit

        // ⚠️ md5 必须在**转换之后**算 —— 坐标转换改了 FIT 字节，md5 会变。
        //    用转换前的 md5 判重，跟服务端存的（转换后的）版本对不上，第 3 道判重形同虚设。
        val md5 = com.wulibaibao.cyclicsync.platform.Crypto.md5Bytes(fitForUpload)
        // 判重 3：md5 辅助键
        if (existing.matchesAux(md5)) {
            return RideResult.Skipped(date, ride.name, ride.distanceKm, "已存在（md5）")
        }

        // ★ IGPSPORT 没有独立的标题参数 —— **上传时的文件名就是活动标题**
        //   实测：传 `onelap_xxx.fit` → IGPSPORT 列表显示 `onelap_xxx`（去掉 .fit 后缀）
        //   所以这里用「日期 时段 城市 骑行」当文件名，显示出来就是正常标题。
        val (title, _) = buildActivityTitle(fit, rideStartMs)
        // 文件名不能带路径分隔符等特殊字符；IGPSPORT 只取前 40 字符
        val safeTitle = title.replace(Regex("[\\/:*?\"<>|]"), "_").take(40)
        val fitName = if (safeTitle.isBlank()) "onelap_${ride.id}.fit" else "$safeTitle.fit"
        igp.uploadFit(fitForUpload, fitName).getOrElse {
            return RideResult.Failed(date, ride.name, ride.distanceKm, "上传失败: ${it.message}")
        }
        return RideResult.Uploaded(date, ride.name, ride.distanceKm)
    }

    /**
     * IGPSPORT → 顽鹿。
     *
     * 流程：下载 IGPSPORT 原始 FIT → 判重 → 上传顽鹿。
     */
    private suspend fun processIgpToOnelap(
        act: com.wulibaibao.cyclicsync.platform.IgpActivity,
        existing: DedupIndex,
    ): RideResult {
        val date = formatDate(act.startTime)
        val km = act.distanceM / 1000.0
        val name = act.name.ifBlank { "骑行 $date" }
        val actStartMs = DedupIndex.parseTime(act.startTime)
        val actDistM = act.distanceM
        // 判重：只看服务端索引 —— 服务端才是权威（本地历史不参与，
        // 否则服务端记录被删后会变成永久地雷，永远补不回来）。
        if (existing.matchesFingerprint(actStartMs, actDistM)) {
            return RideResult.Skipped(date, name, km, "已存在（时间+距离匹配）")
        }
        val fit = igp.downloadFit(act.rideId).getOrElse {
            return RideResult.Failed(date, name, km, "下载 FIT 失败: ${it.message}")
        }
        // ★ 坐标系适配：IGPSPORT 里存量活动的坐标系**是混杂的**
        //   - 迈金 / 顽鹿 App 原生上传 = GCJ-02
        //   - Strava 官方同步过来 = WGS-84
        //   - v1.2.3 起我们 App 上传的 = WGS-84
        //   顽鹿底图按 GCJ-02 显示 ⇒ 探到 WGS-84 就转，探到 GCJ-02 就别动。
        //   （想当然地统一转一次，会把本来就是 GCJ-02 的那批转歪 459m。）
        val isWgsSource = isWgs84IgpActivity(act.name)
        val fitOut = adaptCoordsToOnelap(fit, isWgsSource)
        val md5 = com.wulibaibao.cyclicsync.platform.Crypto.md5Bytes(fitOut)
        // 判重 3：md5 辅助键
        if (existing.matchesAux(md5)) {
            return RideResult.Skipped(date, name, km, "已存在（md5）")
        }
        // 同理：文件名 = 标题。IGPSPORT 活动名（act.name）优先，空的用生成的标题
        val (genTitle, _) = buildActivityTitle(fit, actStartMs)
        val titleForFile = act.name.ifBlank { genTitle }
        val safeTitle = titleForFile.replace(Regex("[\\/:*?\"<>|]"), "_").take(40)
        val fitName = if (safeTitle.isBlank()) "igp_${act.rideId}.fit" else "$safeTitle.fit"
        onelap.uploadFit(fitOut, fitName).getOrElse {
            return RideResult.Failed(date, name, km, "上传失败: ${it.message}")
        }
        return RideResult.Uploaded(date, name, km)
    }

    /**
     * IGPSPORT → 行者。
     *
     * 流程：下载 IGPSPORT 原始 FIT → 按 md5/名称判重 → 上传行者。
     */
    private suspend fun processIgpToXingzhe(
        act: com.wulibaibao.cyclicsync.platform.IgpActivity,
        existing: DedupIndex,
    ): RideResult {
        val date = formatDate(act.startTime)
        val km = act.distanceM / 1000.0
        val name = act.name.ifBlank { "骑行 $date" }
        val actStartMs = DedupIndex.parseTime(act.startTime)
        val actDistM = act.distanceM
        // 判重：只看服务端索引 —— 服务端才是权威（本地历史不参与，
        // 否则服务端记录被删后会变成永久地雷，永远补不回来）。
        // 判重 2：服务端索引指纹
        // IGPSPORT 的 startTime 只有日期（解析成当天 00:00:00），目标平台的记录带真实时刻
        // （06:00:38、07:02:49…），±15 秒的指纹永远匹配不上 → 会重复上传。
        // 所以源平台时间精度不足时，退化成「同日 + 距离」判重。
        val hit = if (act.dateOnly) {
            existing.matchesSameDay(actStartMs, actDistM)
        } else {
            existing.matchesFingerprint(actStartMs, actDistM)
        }
        if (hit) {
            // 服务端已有 ⇒ 不写本地历史（本地历史只记「我确实上传过」）
            return RideResult.Skipped(
                date, name, km,
                if (act.dateOnly) "已存在（同日+距离匹配）" else "已存在（时间+距离匹配）",
            )
        }
        val fit = igp.downloadFit(act.rideId).getOrElse {
            return RideResult.Failed(date, name, km, "下载 FIT 失败: ${it.message}")
        }
        // 坐标系适配：行者底图是 WGS-84 ⇒ 需要 WGS-84 的 FIT。
        //
        // ⚠️ IGPSPORT 里存量活动的坐标系**是混杂的**（迈金/顽鹿原生 = GCJ-02，
        //    Strava 官方同步 = WGS-84，v1.2.3 起我们上传的 = WGS-84），
        //    所以要先探测：已经是 WGS-84 的别再转（否则转歪 459m）。
        //
        // 同时把 session/lap 的 sub_sport 从 virtual_activity 改成 generic，
        // 否则行者会显示成「虚拟骑行」而不是「骑行」。
        val isWgsSource = isWgs84IgpActivity(act.name)
        val fitOut = adaptCoordsToXingzhe(fit, isWgsSource)
        // md5 必须在转换之后算 —— 字节变了 md5 就变了
        val md5 = com.wulibaibao.cyclicsync.platform.Crypto.md5Bytes(fitOut)
        // 判重 3：md5 辅助键
        if (existing.matchesAux(md5)) {
            return RideResult.Skipped(date, name, km, "已存在（md5）")
        }
        val fileName = "igp_${act.rideId}.fit"
        // 标题格式：日期 时段 城市 骑行（与行者 App 生成的一致）
        val (title, city) = buildXingzheTitle(fitOut, actStartMs)
        val outcome = xingzhe.uploadFit(
            fitBytes = fitOut,
            fileName = fileName,
            title = title,
            detail = "来自 IGPSPORT 同步",
            city = city,
        ).getOrElse {
            return RideResult.Failed(date, name, km, "上传失败: ${it.message}")
        }
        return when (outcome) {
            is com.wulibaibao.cyclicsync.platform.UploadOutcome.Uploaded -> {
                RideResult.Uploaded(date, name, km)
            }
            is com.wulibaibao.cyclicsync.platform.UploadOutcome.Duplicate -> {
                RideResult.Skipped(date, name, km, "已上传（服务端判重）")
            }
        }
    }

    private fun formatDate(raw: String): String {
        return try {
            val ts = raw.toLongOrNull() ?: return raw.take(10)
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts * 1000))
        } catch (e: Exception) {
            raw.take(10)
        }
    }

    sealed class RideResult {
        data class Uploaded(val date: String, val name: String, val distanceKm: Double) : RideResult()
        data class Skipped(
            val date: String,
            val name: String,
            val distanceKm: Double,
            val reason: String,
        ) : RideResult()
        data class Failed(
            val date: String,
            val name: String,
            val distanceKm: Double,
            val reason: String,
        ) : RideResult()
    }
}

/**
 * 判断时间字符串是否落在目标日期。
 *
 * 各平台的时间格式不一样，必须都兼容：
 * - IGPSPORT: "2026.10.31"              （点分隔）
 * - IGPSPORT: "2026.10.31 08:12:33"
 * - 行者:     "2026-10-29T08:12:33"
 * - 顽鹿:     "2026-10-29"
 *
 * 之前只认 "yyyy-MM-dd"，IGPSPORT 的点号格式解析失败 → 返回 false →
 * 该平台所有记录都被过滤掉，表现为「发现 1 条，0 条在 2026-10-31」。
 */
/**
 * 顽鹿列表 API 每页条数。
 *
 * 2026-11-02 实测：API 支持 limit 最大 200，limit 越大需要的页数越少
 * （页偏移 = (page-1) × limit）。用 100 条/页，5 页就能拉完约 460 条历史记录。
 */
internal const val ONELAP_PAGE_SIZE = 100

/**
 * 顽鹿列表最大翻页数。
 *
 * ⚠️ 别改小！2026-11-02 实测：用户要同步「2026-05-30」的数据，App 报「发现 0 条顽鹿记录」，
 * 因为「2026-05-30」在**第 6 页**（limit=20 时），而原来只翻 5 页 —— 刚好差一页漏掉。
 */
/**
 * 判断一条 IGPSPORT 活动的 FIT 是不是 **WGS-84** 坐标。
 *
 * IGPSPORT 服务端**原样存储**上传的 FIT（实测下载回来的 FIT 与源文件
 * 4307 个坐标逐位相同，0.000m 偏移）—— 所以下游转换的依据是
 * 「这条 FIT 当初是谁上传的」。
 *
 * | 来源 | title 形态 | FIT 坐标系 |
 * |---|---|---|
 * | 迈金 / 顽鹿 App 原生 | `Magene_C706_*.fit` / `MATCH_470592-*.fit` | **GCJ-02** |
 * | Strava 官方同步 | `21696308938.fit`（纯数字） | WGS-84 |
 * | 我们 App v1.2.3 起 | `2026-10-28 上午 西安 骑行` | **WGS-84** |
 * | 我们 App v1.2.0-1.2.2 | `onelap_<rid>` | GCJ-02（当时没转） |
 *
 * ⚠️ **不要用「偏移量更小」来探测**（2026-11-03 实测行不通）：
 * `gcj2wgs` 是 `wgs2gcj` 的近似逆运算，两个方向的偏移量只差 3% 左右，
 * 迈金的 GCJ-02 数据会被判成 WGS-84 —— **10/10 全错**。
 * 只能靠来源判定。
 */
internal fun isWgs84IgpActivity(title: String): Boolean {
    val t = title.trim()
    if (t.isEmpty()) return false
    // 我们 v1.2.3 起上传的：标题是「日期 时段 城市 骑行」
    if (OUR_IGP_TITLE_PATTERN.matches(t)) return true
    // Strava 官方同步：纯数字 + .fit
    if (STRAVA_IGP_TITLE_PATTERN.matches(t)) return true
    // 其余（迈金 Magene_C706_*.fit / 顽鹿 MATCH_*.fit / onelap_<rid>）= GCJ-02
    return false
}

internal const val ONELAP_MAX_PAGES = 30

/**
 * 我们 App v1.2.3 起上传到 IGPSPORT 的活动标题格式。
 *
 * 例：`2026-10-28 上午 西安 骑行`（日期 时段 城市 骑行）
 */
internal val OUR_IGP_TITLE_PATTERN =
    Regex("""^\d{4}-\d{2}-\d{2}\s+(凌晨|上午|下午|晚上)\s+.+\s+骑行$""")

/**
 * Strava 官方同步到 IGPSPORT 的活动标题格式 —— 纯数字 + `.fit`。
 *
 * 例：`21696308938.fit`
 */
internal val STRAVA_IGP_TITLE_PATTERN = Regex("""^\d+\.fit$""")

internal fun sameDay(timeStr: String, target: java.time.LocalDate): Boolean {
    if (timeStr.isBlank()) return false
    val raw = timeStr.trim().substringBefore(' ')
        .substringBefore('T')
        .replace('/', '.')
        .replace('.', '-')
    return try {
        java.time.LocalDate.parse(
            raw,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        ) == target
    } catch (e: Exception) {
        false
    }
}
