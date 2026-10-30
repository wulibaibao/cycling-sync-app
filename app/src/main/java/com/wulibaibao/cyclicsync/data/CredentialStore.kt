package com.wulibaibao.cyclicsync.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.wulibaibao.cyclicsync.ui.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 三个平台的账号配置 */
data class PlatformAccount(
    val onelapAccount: String = "",
    val onelapPassword: String = "",
    val igpPhone: String = "",
    val igpAccount: String = "",
    val igpPassword: String = "",
    val igpToken: String = "",
    val igpRefreshToken: String = "",
    val igpExpiresAt: Long = 0L,
    val xingzheAccount: String = "",
    val xingzhePassword: String = "",
    val xingzheSessionId: String = "",
    val xingzheUsername: String = "",
    val xingzheUserId: String = "",
    val xingzheEnuid: String = "",
    val xingzheMobile: String = "",
) {
    val onelapConfigured: Boolean get() = onelapAccount.isNotBlank() && onelapPassword.isNotBlank()
    val igpConfigured: Boolean get() = igpToken.isNotBlank()
    val xingzheConfigured: Boolean get() = xingzheSessionId.isNotBlank()

    /** 用于界面显示的账号标识 —— 不含任何密钥 */
    fun displayName(p: Platform): String = when (p) {
        Platform.ONELAP -> onelapAccount.ifBlank { "未登录" }
        Platform.IGP -> igpPhone.ifBlank { "未登录" }
        Platform.XINGZHE -> when {
            xingzheUsername.isNotBlank() -> xingzheUsername
            xingzheAccount.isNotBlank() -> xingzheAccount
            xingzheMobile.isNotBlank() -> xingzheMobile
            else -> "未登录"
        }
    }
}

/**
 * 凭据存储。
 *
 * 用 EncryptedSharedPreferences（Android Keystore 加密）—— 账号密码不出设备。
 */
/**
 * 凭据存储。
 *
 * 用 AES-GCM 加密后存在普通 SharedPreferences 里。
 *
 * 为什么不用 EncryptedSharedPreferences：
 * 它的主密钥存在 Android Keystore 里，**卸载重装后 keystore 条目会丢**，
 * 导致密文解不开、配置全没了（用户反馈「重新安装后配置信息就没了」）。
 * 这里改用一把自持密钥（随机生成后存在同一个 SharedPreferences 里），
 * 加密强度依然足够（密钥不出应用沙盒），但重装后只要应用数据目录还在就能解密。
 */
class CredentialStore(context: Context) {
    companion object {
        private const val FILE = "cycling_sync_creds"
        private const val KEY_NAME = "store_key"
        private const val PAYLOAD = "payload"
        private const val IV_NAME = "store_iv"
        private const val KEYSTORE_FILE = "cycling_sync_keystore"
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 自持密钥 —— 内部 prefs + 外部备份各存一份（外部那份卸载后仍在） */
    private val storeKey: SecretKey by lazy {
        val existing = prefs.getString(KEY_NAME, null)
            ?: readExternalKey()
        if (!existing.isNullOrEmpty()) {
            val k = SecretKeySpec(Base64.decode(existing, Base64.NO_WRAP), "AES")
            prefs.edit().putString(KEY_NAME, existing).apply()
            writeExternalKey(existing)
            k
        } else {
            val gen = KeyGenerator.getInstance("AES")
            gen.init(256, SecureRandom())
            val k = gen.generateKey()
            val b64 = Base64.encodeToString(k.encoded, Base64.NO_WRAP)
            prefs.edit().putString(KEY_NAME, b64).apply()
            writeExternalKey(b64)
            k
        }
    }

    private fun keyBackupFile(): java.io.File? =
        try {
            appContext.getExternalFilesDir(null)?.resolve("store_key.b64")
        } catch (e: Exception) {
            null
        }

    private fun readExternalKey(): String? = try {
        keyBackupFile()?.takeIf { it.exists() && it.length() > 0 }?.readText()?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun writeExternalKey(b64: String) {
        try {
            keyBackupFile()?.takeIf { it.parentFile?.exists() == true }?.writeText(b64)
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 读取凭据。
     *
     * ⚠️ 必须整段包 try/catch —— 解密可能抛异常
     * （密钥丢失 / 数据损坏）。未捕获会直接杀掉进程。
     */
    fun load(): PlatformAccount = try {
        val raw = restorePayload()
        if (raw.isNullOrEmpty()) {
            PlatformAccount()
        } else {
            decode(raw)
        }
    } catch (e: Exception) {
        PlatformAccount()
    }

    /**
     * 外部备份文件路径。
     *
     * getExternalFilesDir 属于应用专属外部存储 —— **卸载时不会被删除**
     * （除非用户清理应用数据）。这样「卸载重装」后凭据仍在，
     * 只要密钥一起恢复就能解密。
     */
    private val backupFile: java.io.File?
        get() = try {
            appContext.getExternalFilesDir(null)?.resolve("creds_backup.b64")
        } catch (e: Exception) {
            null
        }

    /** 恢复：优先外部备份（卸载重装后仍存在），其次内部 prefs */
    private fun restorePayload(): String? {
        // 先看外部备份
        try {
            backupFile?.takeIf { it.exists() && it.length() > 0 }?.let { f ->
                val b64 = f.readText().trim()
                if (b64.isNotEmpty()) return b64
            }
        } catch (e: Exception) {
            // 忽略，落到内部 prefs
        }
        return prefs.getString(PAYLOAD, null)
    }

    /** 保存凭据（整体 JSON 加密成一段） */
    suspend fun save(account: PlatformAccount) = withContext(Dispatchers.IO) {
        val json = buildJsonObject {
            put("onelap_account", JsonPrimitive(account.onelapAccount))
            put("onelap_password", JsonPrimitive(account.onelapPassword))
            put("igp_phone", JsonPrimitive(account.igpPhone))
            put("igp_account", JsonPrimitive(account.igpAccount))
            put("igp_password", JsonPrimitive(account.igpPassword))
            put("igp_token", JsonPrimitive(account.igpToken))
            put("igp_refresh_token", JsonPrimitive(account.igpRefreshToken))
            put("igp_expires_at", JsonPrimitive(account.igpExpiresAt))
            put("xz_account", JsonPrimitive(account.xingzheAccount))
            put("xz_password", JsonPrimitive(account.xingzhePassword))
            put("xz_session_id", JsonPrimitive(account.xingzheSessionId))
            put("xz_username", JsonPrimitive(account.xingzheUsername))
            put("xz_user_id", JsonPrimitive(account.xingzheUserId))
            put("xz_enuid", JsonPrimitive(account.xingzheEnuid))
            put("xz_mobile", JsonPrimitive(account.xingzheMobile))
        }.toString()
        val enc = encrypt(json)
        prefs.edit().putString(PAYLOAD, enc).apply()
        // 同步写一份到外部存储（卸载重装后仍在）
        try {
            backupFile?.takeIf { it.parentFile?.exists() == true }?.writeText(enc)
        } catch (e: Exception) {
            // 备份失败不影响主流程
        }
    }

    /** AES-GCM 加密，返回 base64(iv + ciphertext) */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, storeKey)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = iv + ct
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** AES-GCM 解密 */
    private fun decode(b64: String): PlatformAccount {
        val combined = Base64.decode(b64, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val ct = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, storeKey, GCMParameterSpec(128, iv))
        val json = cipher.doFinal(ct).toString(Charsets.UTF_8)
        return fromJson(json)
    }

    private fun fromJson(json: String): PlatformAccount {
        val o = Json.parseToJsonElement(json).jsonObject
        fun s(k: String): String = o[k]?.jsonPrimitive?.content ?: ""
        return PlatformAccount(
            onelapAccount = s("onelap_account"),
            onelapPassword = s("onelap_password"),
            igpPhone = s("igp_phone"),
            igpAccount = s("igp_account"),
            igpPassword = s("igp_password"),
            igpToken = s("igp_token"),
            igpRefreshToken = s("igp_refresh_token"),
            igpExpiresAt = s("igp_expires_at").toLongOrNull() ?: 0L,
            xingzheAccount = s("xz_account"),
            xingzhePassword = s("xz_password"),
            xingzheSessionId = s("xz_session_id"),
            xingzheUsername = s("xz_username"),
            xingzheUserId = s("xz_user_id"),
            xingzheEnuid = s("xz_enuid"),
            xingzheMobile = s("xz_mobile"),
        )
    }
}
