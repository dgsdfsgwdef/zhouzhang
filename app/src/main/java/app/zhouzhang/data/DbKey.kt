package app.zhouzhang.data

import android.content.Context

class DbKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 数据库口令的生成与保管。
 *
 * 首次启动随机生成 32 字节口令，用 KeystoreCipher 封装后存进 SharedPreferences。
 * 即使有人拿到 /data/data 目录，没有这台设备的 Keystore 也解不开口令。
 *
 * 解封失败时抛异常而不是重新生成——重新生成等于让旧数据库永久打不开。
 */
object DbKey {

    private const val PREFS = "zz_secure"
    private const val PREF_KEY = "db_passphrase"

    fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY, null)

        if (stored == null) {
            val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            prefs.edit().putString(PREF_KEY, KeystoreCipher.wrap(raw)).apply()
            return raw
        }

        return try {
            KeystoreCipher.unwrap(stored)
        } catch (e: Exception) {
            throw DbKeyException("数据库密钥无法解封，可能设备密钥已被清除", e)
        }
    }
}
