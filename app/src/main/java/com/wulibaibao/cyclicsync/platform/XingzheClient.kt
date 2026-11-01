package com.wulibaibao.cyclicsync.platform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 行者 XOSS 客户端。
 *
 * 认证方式：**密码登录 + RSA 加密 + sessionid Cookie**（不是 OAuth）。
 * 实现参照开源项目 sizaif/ridefitsync 的 xingzhe_service.dart。
 *
 * - 登录：POST /api/v1/user/login/  body {account, password(RSA加密后base64)}
 *   → 响应 data {userid, username, enuid}，Set-Cookie 里取 sessionid
 * - 短信：POST /api/v1/mobile/send_sms/ + /api/v1/user/mobile/login/
 * - 活动：GET /api/v1/pgworkout/?offset=&limit=&sport=3，Cookie: sessionid=xxx
 * - 上传：POST https://imxingzhe.com/api/v1/fit/upload/ multipart
 */
internal class XingzheClient {
    companion object {
        const val BASE = "https://www.imxingzhe.com"
        const val API = "$BASE/api/v1"

        const val LOGIN_API = "$API/user/login/"
        const val SEND_SMS_API = "$API/mobile/send_sms/"
        const val SMS_LOGIN_API = "$API/user/mobile/login/"
        const val ACTIVITIES_API = "$API/pgworkout/"
        const val UPLOAD_API = "https://imxingzhe.com/api/v1/fit/upload/"

        const val BIZ_TYPE = "xingzhe_app_login"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 行者 RSA 公钥（用于密码加密）。
         * 来自 sizaif/ridefitsync / IGPSPORT2XingZhe。
         */
        private val RSA_PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDmuQkBbijudDAJgfffDeeIButq
            WHZvUwcRuvWdg89393FSdz3IJUHc0rgI/S3WuU8N0VePJLmVAZtCOK4qe4FY/eKm
            WpJmn7JfXB4HTMWjPVoyRZmSYjW4L8GrWmh51Qj7DwpTADadF3aq04o+s1b8LXJa
            8r6+TIqqL5WUHtRqmQIDAQAB
            -----END PUBLIC KEY-----
        """.trimIndent()
    }

    /** sessionid —— 实际就是会话 cookie */
    var sessionId: String = ""

    /** 登录后返回的账号信息（用于界面显示，不含密钥） */
    var username: String = ""
    var userId: String = ""
    var enuid: String = ""

    fun isLoggedIn(): Boolean = sessionId.isNotEmpty()

    private fun authHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json;charset=UTF-8",
        "Accept" to "application/json",
        "User-Agent" to UA,
        "Origin" to "https://www.imxingzhe.com",
        "Referer" to "https://www.imxingzhe.com/login",
    )

    /**
     * 带 sessionid 的请求头。
     *
     * ⚠️ 这里**故意不带 Content-Type**：
     * multipart 上传时 Http.multipart 会自己设 `multipart/form-data; boundary=...`，
     * 如果这里先带了 `application/json`，HttpURLConnection.setRequestProperty
     * 是「后设覆盖先设」→ 边界被抹掉 → 服务端 415
     * `Unsupported media type "application/json"` → 文件根本没上传。
     *
     * 普通 JSON 请求用 [sessionJsonHeaders]。
     */
    private fun sessionHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to UA,
        "Origin" to "https://imxingzhe.com",
        "Referer" to "imxingzhe.com/",
        "Cookie" to "sessionid=$sessionId",
    )

    /** 普通 JSON 请求用：显式声明 application/json */
    private fun sessionJsonHeaders(): Map<String, String> =
        sessionHeaders() + ("Content-Type" to "application/json")

    // ------------------------------------------------------------ RSA

    /**
     * 用行者的 RSA 公钥加密密码（PKCS1 v1.5），返回 base64。
     *
     * 失败时降级为明文 base64 —— 与 ridefitsync 的行为一致。
     */
    fun encryptPassword(password: String): String {
        return try {
            val der = Base64.getDecoder().decode(
                RSA_PUBLIC_KEY_PEM
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\\s".toRegex(), ""),
            )
            // X.509 SubjectPublicKeyInfo: SEQUENCE { AlgorithmIdentifier, BIT STRING }
            // 手动解析出 modulus / exponent
            val modulus = parseModulus(der)
            val exponent = BigInteger.valueOf(65537)

            val key = KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(modulus, exponent))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, SecureRandom())
            Base64.getEncoder().encodeToString(
                cipher.doFinal(password.toByteArray(Charsets.UTF_8)),
            )
        } catch (e: Exception) {
            Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * 从 DER 里抽出 RSA modulus（跳过 SPKI 头部）。
     *
     * 结构：SEQUENCE { AlgorithmIdentifier, BIT STRING { RSAPublicKey } }
     * RSAPublicKey = SEQUENCE { INTEGER modulus, INTEGER exponent }
     *
     * ⚠️ 长度字段可能是**长格式**（0x81 = 后面 1 字节是长度，0x82 = 2 字节），
     * 必须处理，否则解析会错位。
     */
    private fun parseModulus(der: ByteArray): BigInteger {
        var i = 0

        fun readLen(): Int {
            var len = der[i++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                require(n in 1..4) { "长度字段过长: $n" }
                len = 0
                repeat(n) { len = (len shl 8) or (der[i++].toInt() and 0xFF) }
            }
            return len
        }

        fun readTag(): Int {
            val t = der[i].toInt() and 0xFF
            i++
            return t
        }

        // 外层 SEQUENCE
        require(readTag() == 0x30) { "不是 SEQUENCE" }
        readLen()
        // AlgorithmIdentifier
        require(readTag() == 0x30) { "不是 AlgorithmIdentifier" }
        val algLen = readLen()
        i += algLen
        // BIT STRING
        require(readTag() == 0x03) { "不是 BIT STRING" }
        val bitLen = readLen()
        i++ // 未使用位
        // 内部 RSAPublicKey SEQUENCE
        require(readTag() == 0x30) { "不是 RSAPublicKey SEQUENCE" }
        readLen()
        // modulus INTEGER
        require(readTag() == 0x02) { "不是 INTEGER modulus" }
        val modLen = readLen()
        val modBytes = der.copyOfRange(i, i + modLen)
        i += modLen
        // exponent INTEGER
        require(readTag() == 0x02) { "不是 INTEGER exponent" }
        val expLen = readLen()
        val expBytes = der.copyOfRange(i, i + expLen)
        val exponent = BigInteger(1, expBytes)
        require(exponent == BigInteger.valueOf(65537)) { "非标准 RSA 公钥指数" }
        return BigInteger(1, modBytes)
    }

    // ------------------------------------------------------------ 登录

    /** 密码登录。成功后 sessionId / username / userId / enuid 会被填充。 */
    suspend fun login(account: String, password: String): Result<Unit> {
        val body = buildJsonObject {
            put("account", JsonPrimitive(account))
            put("password", JsonPrimitive(encryptPassword(password)))
        }.toString()

        val r = Http.request(
            method = "POST",
            url = LOGIN_API,
            headers = authHeaders(),
            body = body.toByteArray(),
        )
        if (r.code == 401 || r.code == 403) {
            return Result.failure(Exception("登录被拒绝（HTTP ${r.code}）"))
        }
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))

        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("响应解析失败: ${r.body.take(150)}"))
        }
        val code = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        if (code != 0) {
            return Result.failure(
                Exception(obj["msg"]?.jsonPrimitive?.content ?: "登录失败 (code=$code)"),
            )
        }
        val d = obj["data"] as? JsonObject
            ?: return Result.failure(Exception("未返回用户信息"))

        // sessionid 从 Set-Cookie 取，取不到就用 enuid
        val sid = extractSessionId(r.body, r.setCookie ?: "")
        val en = d["enuid"]?.jsonPrimitive?.content ?: ""
        if (sid.isEmpty() && en.isEmpty()) {
            return Result.failure(Exception("未获取到会话凭据"))
        }
        sessionId = sid.ifEmpty { en }
        username = d["username"]?.jsonPrimitive?.content ?: ""
        userId = d["userid"]?.jsonPrimitive?.content ?: ""
        enuid = en
        return Result.success(Unit)
    }

    /** 从响应里提取 sessionid —— 优先 Set-Cookie，其次响应体 */
    private fun extractSessionId(body: String, setCookie: String): String {
        val m = Regex("sessionid=([^;,\\s]+)").find(setCookie)
        if (m != null) return m.groupValues[1]
        val b = Regex("\"sessionid\"\\s*:\\s*\"([^\"]+)\"").find(body)
        if (b != null) return b.groupValues[1]
        return ""
    }

    /** 发送短信验证码 */
    suspend fun sendSmsCode(mobile: String): Result<Unit> {
        val body = buildJsonObject {
            put("mobile", JsonPrimitive(mobile))
            put("biz_type", JsonPrimitive(BIZ_TYPE))
        }.toString()
        val r = Http.request(
            method = "POST",
            url = SEND_SMS_API,
            headers = authHeaders(),
            body = body.toByteArray(),
        )
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("响应解析失败"))
        }
        val code = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        if (code != 0) {
            return Result.failure(
                Exception(obj["msg"]?.jsonPrimitive?.content ?: "发送失败 (code=$code)"),
            )
        }
        return Result.success(Unit)
    }

    /** 短信验证码登录 */
    suspend fun loginBySms(mobile: String, code: String): Result<Unit> {
        val body = buildJsonObject {
            put("mobile", JsonPrimitive(mobile))
            put("code", JsonPrimitive(code))
            put("biz_type", JsonPrimitive(BIZ_TYPE))
        }.toString()
        val r = Http.request(
            method = "POST",
            url = SMS_LOGIN_API,
            headers = authHeaders(),
            body = body.toByteArray(),
        )
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("响应解析失败"))
        }
        val code2 = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        if (code2 != 0) {
            return Result.failure(
                Exception(obj["msg"]?.jsonPrimitive?.content ?: "登录失败 (code=$code2)"),
            )
        }
        val d = obj["data"] as? JsonObject
            ?: return Result.failure(Exception("未返回用户信息"))
        val sid = extractSessionId(r.body, r.setCookie ?: "")
        val en = d["enuid"]?.jsonPrimitive?.content ?: ""
        if (sid.isEmpty() && en.isEmpty()) {
            return Result.failure(Exception("未获取到会话凭据"))
        }
        sessionId = sid.ifEmpty { en }
        username = d["username"]?.jsonPrimitive?.content ?: ""
        userId = d["userid"]?.jsonPrimitive?.content ?: ""
        enuid = en
        return Result.success(Unit)
    }

    // ------------------------------------------------------------ 活动

    /** 活动列表（用于判重与盘点） */
    suspend fun listPrivateWorkouts(
        offset: Int = 0,
        limit: Int = 20,
        sport: Int = 3,
    ): Result<List<JsonObject>> {
        if (!isLoggedIn()) return Result.failure(Exception("未登录"))
        val url = "$ACTIVITIES_API?offset=$offset&limit=$limit&sport=$sport"
        val r = Http.request(
            method = "GET",
            url = url,
            headers = sessionJsonHeaders(),
        )
        if (r.code == 401 || r.code == 403) {
            return Result.failure(Exception("登录已过期，请重新登录"))
        }
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))
        val obj = JsonAny.parseToJsonElement(r.body).jsonObject
        // 响应结构：data.data[]（ridefitsync 的解析方式）
        val arr = (obj["data"] as? JsonObject)?.get("data") as? kotlinx.serialization.json.JsonArray
            ?: obj["data"] as? kotlinx.serialization.json.JsonArray
            ?: obj["results"] as? kotlinx.serialization.json.JsonArray
            ?: return Result.success(emptyList())
        return Result.success(arr.map { it.jsonObject })
    }

    // ------------------------------------------------------------ 上传

    /**
     * 上传 FIT。
     *
     * 返回 UploadOutcome：Uploaded / Duplicate
     * 服务端对已存在的 FIT 返回 code=9006（已上传）。
     */
    suspend fun uploadFit(
        fitBytes: ByteArray,
        fileName: String,
        title: String,
        detail: String = "",
    ): Result<UploadOutcome> {
        if (!isLoggedIn()) return Result.failure(Exception("未登录"))
        val md5 = Crypto.md5Bytes(fitBytes)
        // ⚠️ 必须用 sessionHeaders()（不带 Content-Type）——
        // Http.multipart 内部会设 multipart/form-data; boundary=...
        // 提前带 application/json 会覆盖 boundary → 服务端 415 → 文件传不上去。
        val r = Http.multipart(
            url = UPLOAD_API,
            headers = sessionHeaders(),
            fields = mapOf(
                "file_source" to "undefined",
                "fit_filename" to fileName,
                "md5" to md5,
                "name" to fileName,
                "sport" to "3",
                "title" to title.take(32),
                "detail" to detail.take(1500),
            ),
            fileField = "fit_file",
            fileName = fileName,
            fileBytes = fitBytes,
        )
        if (r.code == 401 || r.code == 403) {
            return Result.failure(Exception("登录已过期，请重新登录"))
        }
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(200)}"))
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("上传响应解析失败: ${r.body.take(150)}"))
        }
        val code = obj["code"]?.jsonPrimitive?.content ?: ""
        val msg = obj["msg"]?.jsonPrimitive?.content
            ?: obj["message"]?.jsonPrimitive?.content ?: ""
        val dataId = ((obj["data"] as? JsonObject)?.get("id")?.jsonPrimitive?.content)
            ?: (obj["data"] as? JsonPrimitive)?.content
            ?: ""

        if (code == "9006" || msg.contains("已上传") || msg.contains("重复") || msg.contains("已上传过")) {
            return Result.success(UploadOutcome.Duplicate(dataId, msg))
        }
        if (code.isNotEmpty() && code != "0" && code != "200" && code != "1") {
            return Result.failure(Exception("上传失败 (code=$code): $msg"))
        }
        return Result.success(UploadOutcome.Uploaded(dataId, msg))
    }
}

internal sealed class UploadOutcome {
    data class Uploaded(val id: String, val message: String) : UploadOutcome()
    data class Duplicate(val id: String, val message: String) : UploadOutcome()
}
