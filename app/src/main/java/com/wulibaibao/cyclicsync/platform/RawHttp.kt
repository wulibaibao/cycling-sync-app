package com.wulibaibao.cyclicsync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 手写 HTTP/1.1 客户端 —— 只给 OSS 预签名上传用。
 *
 * 为什么不用 HttpURLConnection：
 *   Android 的 HttpURLConnection（底层 OkHttp）在请求带 body 时会自动补
 *   `Content-Type: application/x-www-form-urlencoded`（见 AOSP
 *   HttpURLConnectionImpl: `if (headers.get("Content-Type") == null)`）。
 *
 *   而 OSS 预签名 URL 把 Content-Type 算进了签名 —— 多一个 header 就是
 *   403 SignatureDoesNotMatch。空字符串能不能挡住在不同 Android 版本上
 *   行为不一致，不能赌。
 *
 *   这里直接用 Socket 手写请求，header 完全可控。
 */
internal object RawHttp {

    /**
     * PUT 二进制到预签名 URL。
     *
     * 只发最少的 header：Host / Content-Length / User-Agent / Accept-Encoding。
     * 绝不发 Content-Type —— OSS 签名里没有它。
     */
    suspend fun putBinary(
        url: String,
        body: ByteArray,
        userAgent: String,
        timeoutMs: Int = 180_000,
    ): RawResult = withContext(Dispatchers.IO) {
        val uri = URI(url)
        val host = uri.host
            ?: return@withContext RawResult(-1, "URL 无 host: $url")
        val port = if (uri.port > 0) uri.port else 443
        val path = buildString {
            append(uri.rawPath ?: "/")
            uri.rawQuery?.let { append("?").append(it) }
        }
        val hostHeader = if (port == 443) host else "$host:$port"

        var socket: Socket? = null
        try {
            // https 需要 TLS 握手（OSS 是 443）
            val ssl: Socket = if (port == 443 || uri.scheme.equals("https", true)) {
                val factory = SSLSocketFactory.getDefault()
                val tls = factory.createSocket() as SSLSocket
                tls.apply {
                    // SNI —— 不设置部分服务端握手会失败
                    val params = sslParameters
                    if (params.serverNames.isNullOrEmpty()) {
                        params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                        sslParameters = params
                    }
                    useClientMode = true
                    connect(java.net.InetSocketAddress(host, port), timeoutMs)
                    setSoTimeout(timeoutMs)
                    setTcpNoDelay(true)
                }
            } else {
                Socket(host, port).apply {
                    setSoTimeout(timeoutMs)
                    setTcpNoDelay(true)
                }
            }
            socket = ssl
            val out: OutputStream = ssl.getOutputStream()
            val req = buildString {
                append("PUT ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("User-Agent: ").append(userAgent).append("\r\n")
                append("Accept: */*\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()

            val resp = readResponse(ssl.getInputStream())
            RawResult(resp.code, resp.body, resp.headers)
        } catch (e: Exception) {
            RawResult(-1, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private data class Parsed(
        val code: Int,
        val body: String,
        val headers: Map<String, String>,
    )

    private fun readResponse(raw: InputStream): Parsed {
        val ins = BufferedInputStream(raw)
        val statusLine = readLine(ins) ?: return Parsed(-1, "", emptyMap())
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(ins) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }

        // body：Content-Length 或 chunked
        val bodyBytes = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> {
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val sizeLine = readLine(ins) ?: break
                    val size = sizeLine.trim().substringBefore(";").toIntOrNull(16) ?: break
                    if (size == 0) { readLine(ins); break }
                    val buf = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = ins.read(buf, read, size - read)
                        if (n < 0) break
                        read += n
                    }
                    out.write(buf, 0, read)
                    readLine(ins) // trailing CRLF
                }
                out.toByteArray()
            }
            else -> {
                val len = headers["content-length"]?.toIntOrNull()
                if (len != null && len > 0) {
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = ins.read(buf, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    buf.copyOf(read)
                } else {
                    ByteArray(0)
                }
            }
        }

        // 解压（服务端可能 gzip）
        val decoded = if (headers["content-encoding"]?.contains("gzip", true) == true) {
            try {
                GZIPInputStream(bodyBytes.inputStream()).use { it.readBytes() }
            } catch (e: Exception) { bodyBytes }
        } else bodyBytes

        return Parsed(code, String(decoded, Charsets.UTF_8), headers)
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }
}

internal data class RawResult(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = code in 200..299
}