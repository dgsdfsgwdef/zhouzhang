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
 * 密文格式：base64(iv):base64(ciphertext)，GCM 自带完整性校验。
 */
object KeystoreCipher {

    private const val KEY_ALIAS = "zhouzhang_db_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

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

    fun wrapText(value: String): String = wrap(value.toByteArray(Charsets.UTF_8))

    fun unwrapText(stored: String): String = String(unwrap(stored), Charsets.UTF_8)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
