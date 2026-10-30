package com.wulibaibao.cyclicsync.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OSS 上传规则回归测试。
 *
 * OSS 预签名 URL 把 Content-Type 算进了签名 —— 客户端多一个 Content-Type 头
 * 就是 403 SignatureDoesNotMatch（真机实测）。
 *
 * Android 的 HttpURLConnection（底层 OkHttp）在带 body 时会自动补
 * `Content-Type: application/x-www-form-urlencoded`（AOSP HttpURLConnectionImpl:
 * `if (headers.get("Content-Type") == null)`），所以 OSS PUT 必须走手写 Socket 的 RawHttp。
 *
 * 这些断言锁住这条规则，防止以后有人「顺手」改回 HttpURLConnection。
 */
class OssSigningRuleTest {

    @Test
    fun `OSS PUT 必须用 RawHttp 而不是 HttpURLConnection`() {
        val src = readSource("IgpClient.kt")
        val putIdx = src.indexOf("RawHttp.putBinary")
        assertTrue("IgpClient 的 OSS 上传没有走 RawHttp", putIdx > 0)

        // OSS PUT 那一段不能出现 Http.request
        val block = src.substring(putIdx, minOf(putIdx + 600, src.length))
        assertFalse(
            "OSS PUT 不能用 Http.request —— Android 会自动加 Content-Type 导致 403",
            block.contains("Http.request"),
        )
    }

    @Test
    fun `RawHttp 不能发送 Content-Type`() {
        val src = readSource("RawHttp.kt")
        val reqIdx = src.indexOf("val req = buildString")
        assertTrue("找不到 RawHttp 的请求构造", reqIdx > 0)
        val block = src.substring(reqIdx, minOf(reqIdx + 800, src.length))
        assertFalse(
            "RawHttp 不能发 Content-Type —— OSS 签名里没有它",
            block.contains("Content-Type"),
        )
    }

    @Test
    fun `Http 的 suppressContentType 仍然存在`() {
        val src = readSource("Http.kt")
        assertTrue("Http.request 缺少 suppressContentType 参数", src.contains("suppressContentType"))
    }

    /** 从项目源码目录读文件（单元测试运行时 cwd 是模块目录） */
    private fun readSource(file: String): String {
        val candidates = listOf(
            "src/main/java/com/wulibaibao/cyclicsync/platform/$file",
            "../app/src/main/java/com/wulibaibao/cyclicsync/platform/$file",
        )
        for (p in candidates) {
            val f = java.io.File(p)
            if (f.exists()) return f.readText()
        }
        throw AssertionError("找不到源码文件 $file")
    }
}
