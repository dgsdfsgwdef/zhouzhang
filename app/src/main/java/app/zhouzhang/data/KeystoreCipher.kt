package app.zhouzhang.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-256 密钥封装敏感字符串。
 *
 * 密钥本身不出安全硬件（有 TEE/StrongBox 时），应用只能请求它加解密。
 * 数据库口令、邮箱授权码都走这里。
 *
 * 两套密文格式：
 *
 *  - 旧格式 `base64(iv):base64(ct)`，没有 AAD。**数据库口令仍在用**，故意不改：
 *    改动它意味着老用户升级后打不开自己的账本，而这个错误不可逆。
 *    它的威胁模型是「攻击者已拿到 /data/data 且能调用本应用进程」，到那一步
 *    已经是 root 级别，AAD 也挡不住。
 *
 *  - 新格式 `v2:base64(iv):base64(ct)`，把用途名绑进 GCM 认证标签。
 *    邮箱授权码、解压码这类**可以重新输入**的凭据走这里：密文在
 *    `secret_mail_password` 和 `secret_zip_password` 之间互换会被识别出来，
 *    而不是解出一段错位的明文。旧格式读得出来，读的时候顺手升级。
 */
object KeystoreCipher {

    private const val KEY_ALIAS = "zhouzhang_db_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val V2 = "v2:"

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

    // ---------------------------------------------------------------- 旧格式（数据库口令）

    fun wrap(raw: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(raw)
        return b64(cipher.iv) + ":" + b64(body)
    }

    fun unwrap(stored: String): ByteArray {
        val parts = stored.split(":")
        require(parts.size == 2) { "密文格式错误" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
    }

    // ---------------------------------------------------------------- 新格式（带 AAD）

    fun isCurrentFormat(stored: String): Boolean = stored.startsWith(V2)

    fun wrapText(value: String, purpose: String): String {
        require(purpose.isNotEmpty()) { "purpose 不能为空" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return V2 + b64(cipher.iv) + ":" + b64(body)
    }

    fun unwrapText(stored: String, purpose: String): String {
        require(purpose.isNotEmpty()) { "purpose 不能为空" }

        if (!isCurrentFormat(stored)) {
            // 旧格式没有 AAD 绑定，只能按原样解
            return String(unwrap(stored), Charsets.UTF_8)
        }

        val parts = stored.removePrefix(V2).split(":")
        require(parts.size == 2) { "密文格式错误" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
