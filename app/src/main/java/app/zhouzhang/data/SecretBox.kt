package app.zhouzhang.data

import android.content.Context

/**
 * 邮箱授权码、账单解压码这类敏感串，统一走 Keystore 加密后存本地。
 *
 * 每个名字作为 AAD 绑定进密文：`secret_mail_password` 的密文挪到
 * `secret_zip_password` 下面会解不开，而不是静默解出一段错位的明文。
 */
object SecretBox {

    private const val PREFS = "zz_secure"

    private fun key(name: String) = "secret_$name"

    /** AAD 用途串，与存储键分开：改键名不改变已加密数据的语义 */
    private fun purpose(name: String) = "zhouzhang/secret/$name"

    fun put(context: Context, name: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(name), KeystoreCipher.wrapText(value, purpose(name)))
            .apply()
    }

    fun get(context: Context, name: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(key(name), null) ?: return null

        val value = runCatching {
            KeystoreCipher.unwrapText(stored, purpose(name))
        }.getOrNull() ?: return null

        // 旧格式没有 AAD，读到就顺手升级成 v2。失败不影响本次结果。
        if (!KeystoreCipher.isCurrentFormat(stored)) {
            runCatching {
                prefs.edit()
                    .putString(key(name), KeystoreCipher.wrapText(value, purpose(name)))
                    .apply()
            }
        }
        return value
    }

    fun remove(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(name))
            .apply()
    }
}
