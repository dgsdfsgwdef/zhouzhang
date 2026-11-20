package app.zhouzhang.data

import android.content.Context

/** 邮箱授权码、账单解压码这类敏感串，统一走 Keystore 加密后存本地 */
object SecretBox {

    private const val PREFS = "zz_secure"

    fun put(context: Context, name: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("secret_$name", KeystoreCipher.wrapText(value))
            .apply()
    }

    fun get(context: Context, name: String): String? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("secret_$name", null) ?: return null
        return runCatching { KeystoreCipher.unwrapText(stored) }.getOrNull()
    }

    fun remove(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("secret_$name")
            .apply()
    }
}
