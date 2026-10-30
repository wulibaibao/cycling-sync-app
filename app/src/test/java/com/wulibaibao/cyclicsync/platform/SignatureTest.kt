package com.wulibaibao.cyclicsync.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 签名算法回归测试。
 *
 * 基准值由 Python 实现（onelap_client.py / igp_client.py）用固定
 * nonce/timestamp 生成，落在 test/resources/signature_baseline.json。
 * Kotlin 端必须算出完全相同的值 —— 否则平台会拒绝请求。
 */
class SignatureTest {

    private fun baseline(): Map<String, String> {
        val f = File("src/test/resources/signature_baseline.json")
        assertTrue("基准文件缺失: ${f.absolutePath}", f.exists())
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(f.readText())
        val map = obj as kotlinx.serialization.json.JsonObject
        return map.mapValues { (_, v) ->
            when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content
                else -> v.toString()
            }
        }
    }

    @Test
    fun `顽鹿签名与 Python 实现一致`() {
        val b = baseline()
        val params = mapOf(
            "page" to b["onelap_page"]!!.toInt(),
            "limit" to b["onelap_limit"]!!.toInt(),
        )
        val sig = onelapSignatureWith(
            params = params,
            nonce = b["onelap_nonce"]!!,
            timestamp = b["onelap_timestamp"]!!.toLong(),
        )
        assertEquals("顽鹿签名不匹配", b["onelap"], sig)
    }

    @Test
    fun `IGPSPORT 签名与 Python 实现一致`() {
        val b = baseline()
        val (sig, headers) = igpSignature(
            method = "POST",
            path = b["igp_path"]!!,
            body = b["igp_body"]!!,
            timestamp = b["igp_ts"]!!.toLong(),
            nonce = b["igp_nonce"]!!,
        )
        assertEquals("IGPSPORT 签名不匹配", b["igp"], sig)
        assertEquals(b["igp"], headers["x-signature"])
        assertEquals(b["igp_ts"], headers["x-timestamp"])
        assertEquals(b["igp_nonce"], headers["x-nonce"])
    }

    @Test
    fun `md5 与 sha256 摘要正确`() {
        // 经典测试向量
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Crypto.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Crypto.md5Hex("abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Crypto.sha256Hex("abc"),
        )
    }

    @Test
    fun `base64 编码正确`() {
        // fit_key 必须 base64 后才能拼进下载 URL
        assertEquals("YWJj", Crypto.base64("abc"))
        assertEquals("aGVsbG8=", Crypto.base64("hello"))
    }

    @Test
    fun `nonce 是 16 位字母数字`() {
        repeat(20) {
            val n = Nonce.alnum16()
            assertEquals(16, n.length)
            assertTrue("含非法字符: $n", n.all { it.isLetterOrDigit() })
        }
    }

    @Test
    fun `签名串参数按字典序排序`() {
        val sig = onelapSignatureWith(
            params = mapOf("z" to 1, "a" to 2, "m" to 3),
            nonce = "N",
            timestamp = 1L,
        )
        // 排序错误会导致签名不匹配 —— 这里只验证函数是确定性的
        val sig2 = onelapSignatureWith(
            params = mapOf("m" to 3, "a" to 2, "z" to 1),
            nonce = "N",
            timestamp = 1L,
        )
        assertEquals("参数顺序不应影响签名", sig, sig2)
    }
}
