package com.wulibaibao.cyclicsync.platform

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.random.Random

/**
 * 平台侧静态签名凭据。
 *
 * 这些是第三方平台的静态签名常量，不是用户账号凭据。但它们属于第三方
 * 平台的私有凭据，**不进版本库** —— 构建时从 `local.properties` 读取：
 *
 * ```properties
 * igp.hmac.key=...
 * onelap.sign.key=...
 * ```
 *
 * 没配置时用占位符，功能会失败但不会泄露真值。
 * 详见 README「构建配置」一节。
 */
internal object PlatformKeys {
    /** IGPSPORT: base64(HMAC-SHA256(key, signStr)) */
    val IGP_HMAC_KEY: String = PlatformKeysGenerated.IGP_HMAC_KEY

    /** 顽鹿 OneLap: md5(排序参数串 + "&key=" + key) */
    val ONELAP_SIGN_KEY: String = PlatformKeysGenerated.ONELAP_SIGN_KEY
}

internal object Crypto {
    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun hmacSha256Base64(key: String, signStr: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return base64Bytes(mac.doFinal(signStr.toByteArray(Charsets.UTF_8)))
    }

    fun md5Bytes(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    fun base64(s: String): String = base64Bytes(s.toByteArray(Charsets.UTF_8))

    /** 纯 Kotlin Base64 —— 不依赖 android.util.Base64（API 26+）也不需要 JVM mock */
    fun base64Bytes(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(ENC[(n ushr 18) and 0x3F])
            sb.append(ENC[(n ushr 12) and 0x3F])
            sb.append(ENC[(n ushr 6) and 0x3F])
            sb.append(ENC[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ENC[(n ushr 18) and 0x3F])
                sb.append(ENC[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ENC[(n ushr 18) and 0x3F])
                sb.append(ENC[(n ushr 12) and 0x3F])
                sb.append(ENC[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    private const val ENC =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}

internal object Nonce {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** 16 位随机字母数字 —— 顽鹿签名要求 */
    fun alnum16(): String = (1..16)
        .map { ALPHABET[Random.nextInt(ALPHABET.length)] }
        .joinToString("")

    fun uuid(): String = UUID.randomUUID().toString()
}

/**
 * 顽鹿 OneLap 签名。
 *
 * sig = md5("&".join(sorted(params ∪ {nonce, timestamp}).map { "$it=$v" }) + "&key=$KEY")
 * body 不参与签名；nonce 为 16 位随机字母数字；timestamp 为 unix 秒。
 */
internal /**
 * 顽鹿签名。
 *
 * ⚠️ nonce/timestamp 必须只生成一次 —— 签名串和 HTTP header 里必须是同一个值。
 * 如果这里重新生成，header 的 nonce 与签名用的 nonce 不一致 → 服务端验签失败 403。
 */
fun onelapSignature(params: Map<String, Any?>): String {
    val merged = params.toMutableMap()
    // 调用方已放入 nonce/timestamp 就沿用，没有才生成
    if (!merged.containsKey("nonce")) merged["nonce"] = Nonce.alnum16()
    if (!merged.containsKey("timestamp")) merged["timestamp"] = System.currentTimeMillis() / 1000

    val query = merged.entries
        .sortedBy { it.key }
        .joinToString("&") { (k, v) -> "$k=$v" }

    return Crypto.md5Hex("$query&key=${PlatformKeys.ONELAP_SIGN_KEY}")
}

/**
 * 顽鹿签名的确定性版本 —— nonce/timestamp 由调用方给定。
 * 单元测试用它对比 Python 实现的输出。
 */
internal fun onelapSignatureWith(
    params: Map<String, Any?>,
    nonce: String,
    timestamp: Long,
): String {
    val merged = params.toMutableMap()
    merged["nonce"] = nonce
    merged["timestamp"] = timestamp
    val query = merged.entries
        .sortedBy { it.key }
        .joinToString("&") { (k, v) -> "$k=$v" }
    return Crypto.md5Hex("$query&key=${PlatformKeys.ONELAP_SIGN_KEY}")
}

/**
 * IGPSPORT 签名。
 *
 * signStr = method\npath(含query)\ntimestamp\nnonce\nsha256hex(body)
 * sig = base64(HMAC-SHA256(key, signStr))
 */
internal fun igpSignature(
    method: String,
    path: String,
    body: String,
    timestamp: Long = System.currentTimeMillis() / 1000,
    nonce: String = Nonce.uuid(),
): Pair<String, Map<String, String>> {
    val signStr = "$method\n$path\n$timestamp\n$nonce\n${Crypto.sha256Hex(body)}"
    val sig = Crypto.hmacSha256Base64(PlatformKeys.IGP_HMAC_KEY, signStr)
    return sig to mapOf(
        "x-signature" to sig,
        "x-timestamp" to timestamp.toString(),
        "x-nonce" to nonce,
    )
}

/** 两点距离（米），用于上传后校验 */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

internal fun Double.approxEq(other: Double, toleranceRatio: Double = 0.02): Boolean =
    abs(this - other) <= maxOf(50.0, other * toleranceRatio)
