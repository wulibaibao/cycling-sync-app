package com.wulibaibao.cyclicsync.platform

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

/**
 * 顽鹿 OneLap 客户端。
 *
 * 登录：账号密码 → md5(password) → POST /api/login → token + cookie
 * 列表：POST /api/otm/ride_record/list {page,limit} + 签名头
 * FIT：GET /api/otm/ride_record/analysis/fit_content/{base64(fit_key)}
 */
internal class OneLapClient {
    companion object {
        const val BASE = "https://www.onelap.cn"
        const val LOGIN_API = "$BASE/api/login"
        const val LIST_API = "$BASE/api/otm/ride_record/list"
        const val DETAIL_API = "$BASE/api/otm/ride_record/analysis/{id}"
        const val FIT_API = "$BASE/api/otm/ride_record/analysis/fit_content/{key}"
        const val UPLOAD_API = "$BASE/api/otm/ride_record/upload/fit"
    }

    var token: String = ""
    var cookies: Map<String, String> = emptyMap()
    var uid: String? = null

    private fun authHeaders(params: Map<String, Any?> = emptyMap()): Map<String, String> {
        // nonce/timestamp 必须同时进签名串和 header —— 所以在这里一次性生成
        val merged = params.toMutableMap()
        val nonce = Nonce.alnum16()
        val ts = System.currentTimeMillis() / 1000
        merged["nonce"] = nonce
        merged["timestamp"] = ts

        val h = mutableMapOf(
            "sign" to onelapSignature(merged),
            "nonce" to nonce,
            "timestamp" to ts.toString(),
        )
        if (token.isNotEmpty()) {
            h["Authorization"] = token
            h["Token"] = token
        }
        return h
    }

    /** 密码登录。返回成功与否，错误信息在 message */
    suspend fun login(account: String, password: String): Result<Unit> {
        val pwMd5 = Crypto.md5Hex(password)
        val body = buildJsonObject {
            put("account", JsonPrimitive(account))
            put("password", JsonPrimitive(pwMd5))
        }.toString()

        // 登录也带签名（ridefitsync 的做法）—— 签名串含 account/password/nonce/timestamp
        val nonce = Nonce.alnum16()
        val ts = System.currentTimeMillis() / 1000
        val sign = onelapSignature(
            mapOf(
                "account" to account,
                "password" to pwMd5,
                "nonce" to nonce,
                "timestamp" to ts,
            ),
        )
        val r = Http.request(
            method = "POST",
            url = LOGIN_API,
            headers = mapOf(
                "Content-Type" to "application/json",
                "nonce" to nonce,
                "timestamp" to ts.toString(),
                "sign" to sign,
            ),
            body = body.toByteArray(),
        )
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(200)}"))

        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("响应解析失败: ${r.body.take(150)}"))
        }
        val code = (obj["code"]?.jsonPrimitive?.content ?: "0").toIntOrNull() ?: 0
        if (code !in listOf(0, 200)) {
            val msg = obj["msg"]?.jsonPrimitive?.content ?: obj["message"]?.jsonPrimitive?.content
            return Result.failure(Exception(msg ?: "登录失败 (code=$code)"))
        }
        val data = obj["data"]
        val d = when {
            data is kotlinx.serialization.json.JsonArray && data.isNotEmpty() -> data.first().jsonObject
            data is JsonObject -> data
            else -> return Result.failure(Exception("无 data 字段"))
        }
        val tok = d["token"]?.jsonPrimitive?.content
        if (tok.isNullOrBlank()) return Result.failure(Exception("未返回 token"))
        token = tok
        uid = (d["userinfo"] as? JsonObject)?.get("uid")?.jsonPrimitive?.content
        // 抓全部 Set-Cookie —— 详情/FIT 接口校验 onelap_web_session + XSRF-TOKEN，
        // 只挑 ouid/otoken 会导致后续请求 403。
        val ck = mutableMapOf<String, String>()
        r.setCookie.split(";")
            .map { it.trim() }
            .filter { it.contains("=") && !it.startsWith("Path=") && !it.startsWith("Expires=") &&
                !it.startsWith("Max-Age=") && !it.startsWith("HttpOnly") && !it.startsWith("Secure") &&
                !it.startsWith("SameSite") }
            .forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) ck[k] = v
                }
            }
        uid?.let { ck["ouid"] = it }
        ck["XSRF-TOKEN"] = tok
        ck["otoken"] = tok
        cookies = ck
        return Result.success(Unit)
    }

    /** 拉取骑行记录列表 */
    suspend fun listRides(page: Int = 1, limit: Int = 20): Result<List<OneLapRide>> {
        val payload = buildJsonObject {
            put("page", JsonPrimitive(page))
            put("limit", JsonPrimitive(limit))
        }
        val params = mapOf("page" to page, "limit" to limit)
        val r = Http.request(
            method = "POST",
            url = LIST_API,
            headers = authHeaders(params) + mapOf("Content-Type" to "application/json"),
            body = payload.toString().toByteArray(),
            cookies = cookies,
        )
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(200)}"))
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("列表解析失败: ${r.body.take(150)}"))
        }
        val list = ((obj["data"] as? JsonObject)?.get("list"))
            ?: return Result.failure(Exception("无 data.list"))
        val rides = mutableListOf<OneLapRide>()
        (list as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
            val o = el.jsonObject
            rides += OneLapRide(
                id = o["id"]?.jsonPrimitive?.content ?: o["recordId"]?.jsonPrimitive?.content ?: "",
                name = o["name"]?.jsonPrimitive?.content ?: o["title"]?.jsonPrimitive?.content ?: "",
                startTime = o["start_riding_time"]?.jsonPrimitive?.content
                    ?: o["startRidingTime"]?.jsonPrimitive?.content ?: "",
                distanceKm = o["distance_km"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: o["distanceKm"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            )
        }
        return Result.success(rides)
    }

    /** 拉详情拿 FIT key */
    suspend fun fetchDetail(recordId: String): Result<String> {
        val url = DETAIL_API.replace("{id}", recordId)
        // 注意：详情接口在 Python 实现里是裸 GET（不带签名头）
        val r = Http.request(
            method = "GET",
            url = url,
            headers = buildMap {
                put("Accept", "application/json")
                if (token.isNotEmpty()) {
                    put("Authorization", token)
                    put("Token", token)
                }
            },
            cookies = cookies,
        )
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(150)}"))
        val obj = try {
            JsonAny.parseToJsonElement(r.body).jsonObject
        } catch (e: Exception) {
            return Result.failure(Exception("详情解析失败"))
        }
        val key = obj.findString("fitUrl", "fit_url", "fitKey", "fit", "fileKey", "file_key")
            ?: return Result.failure(Exception("详情里没有 FIT key"))
        return Result.success(key)
    }

    /**
     * 下载 FIT。
     *
     * 关键点（照 SyncOnelapToXoss 的实现）：
     *  1. 方法是 GET（不是 POST）
     *  2. fit_key 必须 base64 编码后再拼进 URL
     */
    suspend fun downloadFit(fitKey: String): Result<ByteArray> {
        val candidates = buildList {
            add(fitKey)
            if (fitKey.startsWith("http")) {
                val path = fitKey.substringAfter("://").substringAfter('/', "")
                if (path.isNotEmpty()) {
                    add(path)
                    add(path.substringAfterLast('/'))
                }
            } else if ("/" in fitKey) {
                add(fitKey.substringAfterLast('/'))
            }
        }.distinct()

        var last: String? = null
        for (cand in candidates) {
            if (cand.isBlank()) continue
            val keyB64 = Crypto.base64(cand)
            val url = FIT_API.replace("{key}", keyB64)
            val r = Http.request(
                method = "GET",
                url = url,
                headers = authHeaders(),
                cookies = cookies,
                timeoutMs = 90_000,
            )
            if (r.code == 200 && r.bytes != null && r.bytes.size > 100) {
                return Result.success(r.bytes)
            }
            last = "HTTP ${r.code} len=${r.bytes?.size ?: 0}"
        }
        return Result.failure(Exception("FIT 下载失败: ${last ?: "无候选"}"))
    }

    /** 上传 FIT 到顽鹿（供 IGPS→顽鹿 等链路使用） */
    suspend fun uploadFit(
        fitBytes: ByteArray,
        fileName: String,
    ): Result<String> {
        val boundary = "----CyclingSync" + Nonce.alnum16()
        val head = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"jilu0\"; filename=\"$fileName\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val body = head + fitBytes + tail

        val r = Http.request(
            method = "POST",
            url = UPLOAD_API,
            headers = authHeaders() + mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Content-Type" to "multipart/form-data; boundary=$boundary",
            ),
            body = body,
            cookies = cookies,
            timeoutMs = 180_000,
        )
        if (!r.ok) return Result.failure(Exception("HTTP ${r.code}: ${r.body.take(200)}"))
        // 解析业务结果：成功时 data.success_count > 0
        return try {
            val obj = JsonAny.parseToJsonElement(r.body).jsonObject
            val code = (obj["code"]?.jsonPrimitive?.content ?: "200").toIntOrNull() ?: 200
            val msg = obj["message"]?.jsonPrimitive?.content
                ?: obj["msg"]?.jsonPrimitive?.content ?: ""
            val d = obj["data"] as? kotlinx.serialization.json.JsonObject
            val successCount = d?.get("success_count")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val failedCount = d?.get("failed_count")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (code == 200 || code == 0 || successCount > 0) {
                Result.success(if (msg.isNotBlank()) msg else "上传成功")
            } else if (failedCount > 0) {
                Result.failure(Exception("上传失败: $msg"))
            } else {
                Result.failure(Exception("上传失败 (code=$code): $msg"))
            }
        } catch (e: Exception) {
            Result.success(r.body.take(200))
        }
    }
}

internal data class OneLapRide(
    val id: String,
    val name: String,
    val startTime: String,
    val distanceKm: Double,
)
