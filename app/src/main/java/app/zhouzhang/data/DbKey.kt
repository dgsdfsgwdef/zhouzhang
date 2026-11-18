package app.zhouzhang.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DbKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 数据库口令的生成与保管。
 *
 * 做法：首次启动随机生成 32 字节口令，用 Android Keystore 里的 AES-256 密钥
 * 加密后存进 SharedPreferences。Keystore 的私钥不出安全硬件（有 TEE/StrongBox 时），
 * 应用进程只能请求它加解密，拿不到密钥本身。
 *
 * 所以即使有人拿到 /data/data 目录，没有这台设备的 Keystore 也解不开口令，
 * 进而打不开数据库。root 且能注入进程的情况仍可绕过——这点在 README 里已写明。
 */
object DbKey {

    private const val KEY_ALIAS = "zhouzhang_db_key"
    private const val PREFS = "zz_secure"
    private const val PREF_KEY = "db_passphrase"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY, null)

        if (stored == null) return createAndStore(prefs)

        return try {
            unwrap(stored)
        } catch (e: Exception) {
            // 不能悄悄重新生成口令——那样旧数据库就永久打不开了。
            throw DbKeyException("数据库密钥无法解封，可能设备密钥已被清除", e)
        }
    }

    private fun createAndStore(prefs: android.content.SharedPreferences): ByteArray {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_KEY, wrap(raw)).apply()
        return raw
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(raw: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(raw)
        return b64(iv) + ":" + b64(body)
    }

    private fun unwrap(stored: String): ByteArray {
        val parts = stored.split(":")
        require(parts.size == 2) { "口令格式错误" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
