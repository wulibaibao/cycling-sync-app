package com.wulibaibao.cyclicsync.platform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * IGPSPORT 客户端。
 *
 * 登录：手机号 + 短信验证码 → token（有过期时间，可 refresh）
 * 列表：GET /service/api/v1/.../activities
 * 上传：三步 OSS 流程 —— getSignedUrl → PUT 到 OSS → 通知注册
 */
internal class IgpClient {
    companion object {
        const val PROD = "https://prod.zh.igpsport.com"
        const val APP = "https://app.zh.igpsport.com"
        const val BASE = "$PROD/service"

        /** 短信验证码 */
        const val SMS_SEND = "$BASE/auth/account/SendVerificationCode"

        /** 短信登录 */
        const val SMS_LOGIN = "$BASE/auth/account/login/phone"

        /** 账号密码登录 */
        const val LOGIN = "$BASE/auth/account/login"

        /** token 刷新（GET，参数名驼峰 refreshToken） */
        const val REFRESH = "$BASE/auth/token/refresh"

        /** 活动模块 */
        const val ACT_BASE = "$BASE/web-gateway/web-analyze/activity"
        const val ACTIVITIES = "$ACT_BASE/queryMyActivity"
        const val UPLOAD_URL = "$ACT_BASE/uploadByOss"
        const val DOWNLOAD_URL = "$ACT_BASE/getDownloadUrl/"

        /** OSS 预签名 */
        const val SIGNED_URL = "$BASE/sportg/third-party-server/oss/getSignedUrl?fileExtension=.fit"

        const val APP_VERSION = "8.07.08"

        /** 必须与 web 端一致的 UA —— 服务端会校验 */
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        const val APP_ID = "igpsport-web"

        /** FIT 上传 MIME */
        const val FIT_MIME = "application/octet-stream"
    }

    var token: String = ""
    var refreshToken: String = ""
    var phone: String = ""
    var expiresAt: Long = 0L

    fun hasValidToken(): Boolean =
        token.isNotEmpty() && (expiresAt == 0L || System.currentTimeMillis() / 1000 < expiresAt - 300)

    /** Bearer 认证头 */
    private fun bearer(): Map<String, String> =
        if (token.isNotEmpty()) mapOf("Authorization" to "Bearer $token") else emptyMap()

    /** 不带签名的 header —— 发短信验证码和活动列表走这个 */
    private fun plainHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to UA,
        "origin" to "https://app.zh.igpsport.com",
        "referer" to "https://app.zh.igpsport.com/",
    )

    /**
     * 签名 header。
     *
     * ⚠️ path 必须带 `/service` 前缀 —— 例如
     * `/service/auth/account/login/phone`。
     * 漏掉 `/service` 会导致服务端返回
     * `{"code":1021,"message":"signature verification failed"}`（HTTP 403）。
     */
    private fun authHeaders(method: String, path: String, body: String): Map<String, String> {
        val (_, h) = igpSignature(method, path, body)
        val out = h.toMutableMap()
        out["Content-Type"] = "application/json"
        out["Accept"] = "application/json, text/plain, */*"
        out["Accept-Language"] = "zh-Hans"
        out["User-Agent"] = UA
        out["timezone"] = "Asia/Shanghai"
        out["qiwu-app-version"] = APP_VERSION
        out["x-access-key"] = "AKIDWebClient"
        out["x-platform"] = "web"
        out["origin"] = "https://app.zh.igpsport.com"
        out["referer"] = "https://app.zh.igpsport.com/"
        return out + bearer()
    }

    /** 发短信验证码 */
    suspend fun sendSmsCode(phone: String): Result<Unit> {
        val path = "/auth/account/SendVerificationCode"
        val body = buildJsonObject {
            put("type", JsonPrimitive(1))
            put("username", JsonPrimitive(phone))
        }.toString()
        val r = Http.request(
            method = "POST",
            url = SMS_SEND,
            headers = plainHeaders(),
            body = body.toByteArray(),
        )
        // ⚠️ 这个接口用 HTTP 403 表达业务失败（如 "Account does not exist"），
        // 所以必须先解析 body 里的 code/message，不能只看 HTTP 状态码。
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))
        }
        val code = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        val msg = obj["message"]?.jsonPrimitive?.content
            ?: obj["msg"]?.jsonPrimitive?.content ?: ""
        if (code != 0) {
            return Result.failure(Exception(msg.ifBlank { "发送失败 (code=$code)" }))
        }
        return Result.success(Unit)
    }

    /** 短信验证码登录 */
    suspend fun loginBySms(phone: String, smsCode: String): Result<Unit> {
        val path = "/service/auth/account/login/phone"
        val body = buildJsonObject {
            put("appId", JsonPrimitive(APP_ID))
            put("phone", JsonPrimitive(phone))
            put("code", JsonPrimitive(smsCode))
        }.toString()
        val r = Http.request(
            method = "POST",
            url = SMS_LOGIN,
            headers = authHeaders("POST", path, body),
            body = body.toByteArray(),
        )
        // 同样：403 可能是业务错误（验证码错误/过期），先解析 body
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))
        }
        val code = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        val msg = obj["message"]?.jsonPrimitive?.content
            ?: obj["msg"]?.jsonPrimitive?.content ?: ""
        if (code != 0) {
            return Result.failure(Exception(msg.ifBlank { "登录失败 (code=$code)" }))
        }
        val d = obj["data"] as? JsonObject ?: return Result.failure(Exception("无 data"))
        token = d["access_token"]?.jsonPrimitive?.content
            ?: d["accessToken"]?.jsonPrimitive?.content
            ?: return Result.failure(Exception("未返回 token"))
        refreshToken = d["refresh_token"]?.jsonPrimitive?.content
            ?: d["refreshToken"]?.jsonPrimitive?.content
            ?: ""
        val expiresIn = d["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: d["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L
        expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        this.phone = phone
        return Result.success(Unit)
    }

    /**
     * 账号密码登录。
     *
     * POST /service/auth/account/login  body {type:1, username, password}
     * 必须签名（用 authHeaders）。
     *
     * ⚠️ 服务端用 HTTP 403 表达业务失败（如 code=1002 密码错误），
     * 所以必须解析 body 里的 code/message，不能只看状态码。
     */
    suspend fun loginByPassword(username: String, password: String): Result<Unit> {
        val path = "/service/auth/account/login"
        val body = buildJsonObject {
            put("type", JsonPrimitive(1))
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
        }.toString()
        val r = Http.request(
            method = "POST",
            url = LOGIN,
            headers = authHeaders("POST", path, body),
            body = body.toByteArray(),
        )
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))
        }
        val code = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        val msg = obj["message"]?.jsonPrimitive?.content
            ?: obj["msg"]?.jsonPrimitive?.content ?: ""
        if (code != 0) {
            return Result.failure(Exception(msg.ifBlank { "登录失败 (code=$code)" }))
        }
        val d = obj["data"] as? JsonObject ?: return Result.failure(Exception("无 data"))
        token = d["access_token"]?.jsonPrimitive?.content
            ?: d["accessToken"]?.jsonPrimitive?.content
            ?: return Result.failure(Exception("未返回 token"))
        refreshToken = d["refresh_token"]?.jsonPrimitive?.content
            ?: d["refreshToken"]?.jsonPrimitive?.content ?: ""
        val expiresIn = d["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: d["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        this.phone = username
        return Result.success(Unit)
    }

    /**
     * 刷新 access token。
     *
     * 端点: GET /service/auth/token/refresh?refreshToken=***
     * 参数名是驼峰 `refreshToken`（用 `refresh_token` 会报 Parameter is required）
     * 必须带 Authorization: Bearer —— 无 Bearer 时服务端返回 HTML 错误页（非 JSON）
     * refresh_token 每次刷新后会被轮换，必须保存新值
     */
    suspend fun refresh(): Result<Unit> {
        if (refreshToken.isEmpty()) return Result.failure(Exception("无 refreshToken"))
        // 签名 path 含 query（Python 的 signed_headers 对 URL 做 urlparse 后带 ?query）
        val encoded = java.net.URLEncoder.encode(refreshToken, "UTF-8")
        val path = "/service/auth/token/refresh?refreshToken=$encoded"
        val r = Http.request(
            method = "GET",
            url = REFRESH + "?refreshToken=$encoded",
            headers = authHeaders("GET", path, ""),
        )
        if (!r.ok) return Result.failure(Exception("刷新失败 HTTP ${r.code}"))
        val obj = JsonAny.parseToJsonElement(r.body).jsonObject
        val d = obj["data"] as? JsonObject ?: return Result.failure(Exception("刷新无 data"))
        token = d["access_token"]?.jsonPrimitive?.content ?: d["accessToken"]?.jsonPrimitive?.content ?: token
        refreshToken = d["refresh_token"]?.jsonPrimitive?.content ?: d["refreshToken"]?.jsonPrimitive?.content ?: refreshToken
        val expiresIn = d["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: d["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        return Result.success(Unit)
    }

    /** 确保 token 可用（过期则刷新） */
    suspend fun ensureToken(): Result<Unit> {
        if (hasValidToken()) return Result.success(Unit)
        if (refreshToken.isEmpty()) return Result.failure(Exception("token 过期且无 refreshToken，请重新登录"))
        val r = refresh()
        if (r.isFailure) return r
        return if (hasValidToken()) Result.success(Unit)
        else Result.failure(Exception("刷新后 token 仍无效"))
    }

    /** 拉活动列表（GET + query 参数，与 web-analyze 模块一致） */
    suspend fun listActivities(pageSize: Int = 20, beginTime: String? = null): Result<List<IgpActivity>> {
        ensureToken().onFailure { return Result.failure(it) }
        val path = "/service/web-gateway/web-analyze/activity/queryMyActivity"
        val query = buildString {
            append("?pageNo=1&pageSize=").append(pageSize).append("&sort=1&reqType=0")
            beginTime?.let { append("&beginTime=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        }
        val r = Http.request(
            method = "GET",
            url = ACTIVITIES + query,
            headers = plainHeaders() + bearer(),
        )
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(200)}"))
        val obj = JsonAny.parseToJsonElement(r.body).jsonObject
        // 业务错误
        val bizCode = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        if (bizCode != 0) {
            val msg = obj["message"]?.jsonPrimitive?.content ?: obj["msg"]?.jsonPrimitive?.content
            return Result.failure(Exception("IGPS 业务错误 $bizCode: ${msg ?: ""}"))
        }
        // ⚠️ 某天没有记录时服务端返回 {"code":0,"data":null} —— 那是空列表，不是错误
        // ⚠️ 但 token 失效时 data 是**字符串**（"The Token has expired on ..."），
        //    之前被当成空列表静默吞掉 → 日志显示「发现 0 条 IGPSPORT 记录」，看不出是登录失效。
        //    这两种要区分开：null 才是真空，字符串是错误。
        val rawData = obj["data"]
        if (rawData == null || rawData is kotlinx.serialization.json.JsonNull) {
            return Result.success(emptyList())
        }
        if (rawData !is JsonObject) {
            val hint = rawData.jsonPrimitive.content
            // token 过期 → 先尝试刷新再重试一次
            if (hint.contains("expired", ignoreCase = true) ||
                hint.contains("token", ignoreCase = true)
            ) {
                refresh()
                if (hasValidToken()) {
                    return listActivities(pageSize, beginTime)
                }
            }
            return Result.failure(Exception("IGPSPORT 返回异常: $hint"))
        }
        val dataObj = rawData
        val list = dataObj["rows"]
            ?: return Result.failure(Exception("无 data.rows"))
        val out = mutableListOf<IgpActivity>()
        (list as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
            val o = el.jsonObject
            val rawStart = o["startTime"]?.jsonPrimitive?.content
                ?: o["rideDate"]?.jsonPrimitive?.content ?: ""
            out += IgpActivity(
                rideId = o["rideId"]?.jsonPrimitive?.content ?: o["id"]?.jsonPrimitive?.content ?: "",
                name = o["title"]?.jsonPrimitive?.content ?: o["name"]?.jsonPrimitive?.content ?: "",
                startTime = rawStart,
                distanceM = o["rideDistance"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: o["distance"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                // 时长字段是 totalMovingTime / recordTime（秒），不是 rideTime
                durationSec = o["totalMovingTime"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
                    ?: o["recordTime"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
                    ?: o["rideTime"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: o["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                dataStatus = o["dataStatus"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                // "2026.10.31" 这种只有日期的格式
                dateOnly = !rawStart.contains(':') && !rawStart.contains('T'),
            )
        }
        return Result.success(out)
    }

    /**
     * 下载原始 FIT 文件。
     *
     * GET /service/web-gateway/web-analyze/activity/getDownloadUrl/?rideId=xxx
     * → 返回 {data: {downloadUrl: "..."}}，再去下载 FIT 二进制。
     */
    suspend fun downloadFit(rideId: String): Result<ByteArray> {
        ensureToken().onFailure { return Result.failure(it) }
        // ⚠️ rideId 是路径参数，不是 query —— 用 "?rideId=" 会返回 500「服务器异常」（实测）。
        val path = "/service/web-gateway/web-analyze/activity/getDownloadUrl/$rideId"
        val url = DOWNLOAD_URL + java.net.URLEncoder.encode(rideId, "UTF-8")
        val r = Http.request(
            method = "GET",
            url = url,
            headers = authHeaders("GET", path, ""),
        )
        if (!r.ok) return Result.failure(Exception("getDownloadUrl HTTP ${r.code}: ${r.body.take(150)}"))
        val obj = JsonAny.parseToJsonElement(r.body).jsonObject
        // data 可能是纯字符串 URL，也可能是对象
        val downloadUrl = when {
            obj["data"] is kotlinx.serialization.json.JsonPrimitive ->
                obj["data"]?.jsonPrimitive?.content
            else -> (obj["data"] as? JsonObject)?.let { d ->
                d["downloadUrl"]?.jsonPrimitive?.content
                    ?: d["download_url"]?.jsonPrimitive?.content
                    ?: d["url"]?.jsonPrimitive?.content
            }
        } ?: return Result.failure(Exception("getDownloadUrl 缺下载地址"))
        // 直接下载 FIT 二进制
        val fr = Http.request(
            method = "GET",
            url = downloadUrl,
            headers = mapOf("User-Agent" to UA),
            timeoutMs = 180_000,
        )
        if (!fr.ok) return Result.failure(Exception("FIT 下载失败 HTTP ${fr.code}"))
        val bytes = fr.bytes
            ?: return Result.failure(Exception("FIT 下载为空"))
        if (bytes.size < 100) {
            return Result.failure(Exception("FIT 文件异常（仅 ${bytes.size} 字节）"))
        }
        return Result.success(bytes)
    }

    /**
     * 上传 FIT 到 IGPSPORT —— 三步 OSS 流程。
     *
     * 注意：服务端按活动内容/时间/距离指纹判重，
     * 重复上传会返回 success 但列表不变。
     */
    suspend fun uploadFit(fitBytes: ByteArray, fileName: String): Result<String> {
        ensureToken().onFailure { return Result.failure(it) }

        // Step 1: 取 OSS 预签名 URL
        // ⚠️ 签名 path 必须带 query —— 服务端按完整 path+query 验签。
        // 漏掉 "?fileExtension=.fit" 会返回 403 signature verification failed（实测）。
        val path1 = "/service/sportg/third-party-server/oss/getSignedUrl?fileExtension=.fit"
        val body1 = buildJsonObject { put("fileName", JsonPrimitive(fileName)) }.toString()
        val r1 = Http.request(
            method = "GET",
            url = SIGNED_URL,
            headers = authHeaders("GET", path1, ""),
        )
        if (!r1.ok) return Result.failure(Exception("getSignedUrl HTTP ${r1.code}: ${r1.body.take(150)}"))
        val obj1 = JsonAny.parseToJsonElement(r1.body).jsonObject
        if ((obj1["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() != 0) {
            return Result.failure(Exception("getSignedUrl 失败: ${obj1["message"]?.jsonPrimitive?.content}"))
        }
        val d1 = obj1["data"] as? JsonObject ?: return Result.failure(Exception("getSignedUrl 无 data"))
        val ossUrl = d1["signedUrl"]?.jsonPrimitive?.content ?: d1["url"]?.jsonPrimitive?.content
            ?: return Result.failure(Exception("getSignedUrl 缺 OSS URL"))
        val ossName = d1["ossId"]?.jsonPrimitive?.content
            ?: d1["key"]?.jsonPrimitive?.content
            ?: d1["ossName"]?.jsonPrimitive?.content
            ?: (Nonce.uuid() + ".fit")

        // Step 2: PUT 到 OSS
        // ⚠️ 必须用 RawHttp（Socket 手写），不能用 HttpURLConnection ——
        // Android 的 HttpURLConnection 在带 body 时会自动补
        // `Content-Type: application/x-www-form-urlencoded`，
        // 而 OSS 预签名 URL 把 Content-Type 算进签名 → 403 SignatureDoesNotMatch。
        val pr = RawHttp.putBinary(
            url = ossUrl,
            body = fitBytes,
            userAgent = UA,
            timeoutMs = 180_000,
        )
        if (!pr.ok) {
            // OSS 的诊断信息在 XML 里（StringToSign / StringToSignBytes），
            // 截断掉就没法定位签名差异 —— 提取关键字段
            return Result.failure(
                Exception(
                    "OSS 上传失败 HTTP ${pr.code}: ${ossErrorDetail(pr.body)}\n" +
                        "  请求URL=${ossUrl}\n" +
                        "  Content-Length=${fitBytes.size}",
                ),
            )
        }

        // Step 3: 通知 IGPSPORT 注册
        val path3 = "/service/web-gateway/web-analyze/activity/uploadByOss"
        val body3 = buildJsonObject {
            put("fileName", JsonPrimitive(fileName))
            put("ossName", JsonPrimitive(ossName))
        }.toString()
        val r3 = Http.request(
            method = "POST",
            url = UPLOAD_URL,
            headers = authHeaders("POST", path3, body3),
            body = body3.toByteArray(),
        )
        if (!r3.ok) return Result.failure(Exception("uploadByOss HTTP ${r3.code}: ${r3.body.take(150)}"))
        val obj3 = JsonAny.parseToJsonElement(r3.body).jsonObject
        val code3 = (obj3["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        if (code3 != 0) {
            return Result.failure(Exception("uploadByOss 失败: ${obj3["message"]?.jsonPrimitive?.content}"))
        }
        return Result.success(obj3["data"]?.jsonPrimitive?.content ?: r3.body.take(300))
    }

/**
 * 提取 OSS 错误响应里的诊断信息。
 *
 * OSS 的 SignatureDoesNotMatch 会带上服务端算出的 StringToSign，
 * 对比它和客户端实际签的就能定位差异（多了哪个 header、query 顺序等）。
 */
private fun ossErrorDetail(xml: String): String {
    fun tag(name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
    val code = tag("Code") ?: return xml.take(200)
    val msg = tag("Message") ?: ""
    val sts = tag("StringToSign")
    val stsBytes = tag("StringToSignBytes")
    val reqId = tag("RequestId")
    return buildString {
        append(code)
        if (msg.isNotEmpty()) append(": ").append(msg)
        if (reqId != null) append(" | RequestId=").append(reqId)
        if (sts != null) append("\n  StringToSign=").append(sts)
        if (stsBytes != null) append("\n  StringToSignBytes=").append(stsBytes)
    }
}

}

internal data class IgpActivity(
    val rideId: String,
    val name: String,
    val startTime: String,
    val distanceM: Double,
    val durationSec: Long,
    val dataStatus: Int,
    /**
     * startTime 是否只有日期（没有时分秒）。
     *
     * IGPSPORT 的 startTime 格式是 "2026.10.31" —— 解析出来是当天 00:00:00，
     * 而目标平台的记录带真实时刻。判重时不能用 ±15 秒的指纹，
     * 要退化成「同日 + 距离」。
     */
    val dateOnly: Boolean = false,
)