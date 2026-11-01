package com.wulibaibao.cyclicsync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 通用 HTTP 结果 */
internal data class HttpResult(
    val code: Int,
    val body: String,
    val bytes: ByteArray? = null,
    val setCookie: String = "",
) {
    val ok: Boolean get() = code in 200..299
}

/** 极简 HTTP 客户端（不引三方库，减少 APK 体积与依赖风险） */
internal object Http {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        cookies: Map<String, String> = emptyMap(),
        timeoutMs: Int = 60_000,
        /**
         * 显式清掉 Content-Type。
         *
         * Android 的 HttpURLConnection 在 doOutput=true 时会自动补
         * `Content-Type: application/x-www-form-urlencoded` —— 而 OSS 预签名 URL
         * 把 Content-Type 算进了签名，多这个 header 就 403 SignatureDoesNotMatch。
         *
         * 传 true 时发一个空 Content-Type 覆盖掉默认值（OSS 接受空值）。
         */
        suppressContentType: Boolean = false,
    ): HttpResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (cookies.isNotEmpty()) {
                    setRequestProperty(
                        "Cookie",
                        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                    )
                }
                if (suppressContentType) {
                    // 覆盖 Android 自动加的默认 Content-Type
                    setRequestProperty("Content-Type", "")
                } else if (contentType != null) {
                    setRequestProperty("Content-Type", contentType)
                }
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }
            val code = conn.responseCode
            val encoding = conn.getHeaderField("Content-Encoding")?.lowercase() ?: ""
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { readEncoded(it, encoding) } ?: ByteArray(0)
            val cookies = readSetCookie(conn)
            HttpResult(code, String(raw, Charsets.UTF_8), raw, cookies)
        } catch (e: Exception) {
            HttpResult(-1, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }


    /**
     * 读 Set-Cookie。
     *
     * 不同设备/不同 JDK 版本返回的 header key 大小写不一致
     * （"set-cookie" / "Set-Cookie"），两种都试。
     */
    private fun readSetCookie(conn: java.net.HttpURLConnection): String {
        val fields = conn.getHeaderFields()
        val list = fields.entries
            .filter { it.key != null && it.key.equals("set-cookie", ignoreCase = true) }
            .flatMap { it.value ?: emptyList() }
        return list.joinToString("; ")
    }

    /**
     * 按 Content-Encoding 解码响应体。
     *
     * ⚠️ 必须看响应头，不能只判断 stream 类型 —— 有些实现返回的不是
     * GZIPInputStream 实例，导致 gzip 内容被当明文读出乱码。
     */
    private fun readEncoded(stream: java.io.InputStream, encoding: String): ByteArray {
        val src: java.io.InputStream = when {
            encoding.contains("gzip") -> java.util.zip.GZIPInputStream(stream)
            encoding.contains("deflate") -> java.util.zip.InflaterInputStream(stream)
            else -> java.io.BufferedInputStream(stream)
        }
        return src.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    /** multipart/form-data 上传（行者 FIT） */
    suspend fun multipart(
        url: String,
        headers: Map<String, String> = emptyMap(),
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        fileBytes: ByteArray,
        timeoutMs: Int = 120_000,
    ): HttpResult = withContext(Dispatchers.IO) {
        val boundary = "----CyclingSync" + Nonce.alnum16()
        val out = ByteArrayOutputStream()
        fun writeField(name: String, value: String) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray())
        }
        fields.forEach { (k, v) -> writeField(k, v) }
        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n"
                .toByteArray(),
        )
        out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
        out.write(fileBytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                // ⚠️ Content-Type 必须**最后**设 —— setRequestProperty 是后设覆盖先设。
                // 如果调用方传了 application/json，boundary 会被抹掉，
                // 服务端回 415 Unsupported media type，文件根本没传上去。
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                setFixedLengthStreamingMode(out.size())
            }
            val payload = out.toByteArray()
            conn.outputStream.use { os -> os.write(payload) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes() ?: ByteArray(0)
            val cookies = readSetCookie(conn)
            HttpResult(code, String(raw, Charsets.UTF_8), raw, cookies)
        } catch (e: Exception) {
            HttpResult(-1, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}

internal val JsonAny = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** 从任意 JSON 结构里递归找第一个 key 匹配的字面值 */
internal fun JsonObject.findString(vararg names: String): String? {
    fun walk(node: kotlinx.serialization.json.JsonElement): String? {
        when (node) {
            is JsonObject -> {
                for ((k, v) in node) {
                    if (names.any { it.equals(k, ignoreCase = true) } && v is JsonPrimitive) {
                        if (v.isString || v.content != "null") return v.content
                    }
                }
                node.values.forEach { walk(it)?.let { return it } }
            }
            is kotlinx.serialization.json.JsonArray -> {
                node.forEach { walk(it)?.let { return it } }
            }
            else -> Unit
        }
        return null
    }
    return walk(this)
}
