package com.wulibaibao.cyclicsync.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * 行者密码加密测试。
 *
 * RSA 公钥是 X.509 SubjectPublicKeyInfo DER 格式，
 * 长度字段可能是长格式（0x81/0x82 前缀）—— 解析必须正确。
 */
class XingzheCryptoTest {

    @Test
    fun `RSA 公钥 DER 解析正确`() {
        val der = java.util.Base64.getDecoder().decode(
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDmuQkBbijudDAJgfffDeeIButq" +
                "WHZvUwcRuvWdg89393FSdz3IJUHc0rgI/S3WuU8N0VePJLmVAZtCOK4qe4FY/eKm" +
                "WpJmn7JfXB4HTMWjPVoyRZmSYjW4L8GrWmh51Qj7DwpTADadF3aq04o+s1b8LXJa" +
                "8r6+TIqqL5WUHtRqmQIDAQAB",
        )
        val client = XingzheClient()
        val m = XingzheClient::class.java.getDeclaredMethod("parseModulus", ByteArray::class.java)
        m.isAccessible = true
        val modulus = m.invoke(client, der) as BigInteger

        // 标准 1024 位 RSA 公钥
        assertEquals(1024, modulus.bitLength())
        assertTrue("modulus 太小", modulus > BigInteger.ONE.shiftLeft(1000))

        // 用解析出的 modulus 加密，再验证能被私钥还原不了但格式合法
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val enc = cipher.doFinal("testpass".toByteArray())
        assertEquals(128, enc.size) // 1024 bit = 128 byte
    }

    @Test
    fun `密码加密返回 base64 且长度合理`() {
        val client = XingzheClient()
        val enc = client.encryptPassword("hunter2")
        // 1024 位 RSA + PKCS1 → 128 字节 → base64 172 字符
        assertEquals(172, enc.length)
        // 必须是合法 base64
        val decoded = java.util.Base64.getDecoder().decode(enc)
        assertEquals(128, decoded.size)
    }

    @Test
    fun `两次加密结果不同（PKCS1 随机填充）`() {
        val client = XingzheClient()
        val a = client.encryptPassword("same")
        val b = client.encryptPassword("same")
        assertTrue("PKCS1 填充应带随机性", a != b)
    }
}
