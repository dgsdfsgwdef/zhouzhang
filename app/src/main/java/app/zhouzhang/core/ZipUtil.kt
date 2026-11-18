package app.zhouzhang.core

import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jInput
import java.io.ByteArrayInputStream

class NeedPasswordException(message: String) : Exception(message)

/**
 * 加密压缩包处理。
 *
 * Java 标准库的 ZipInputStream 解不了加密条目，而微信和支付宝导出的都是加密包，
 * 所以必须引入 zip4j。这是整个项目唯一一个"不得不加"的第三方依赖。
 *
 * 用 zip4j 的流式 ZipInputStream 而不是 ZipFile：后者只接受 File，
 * 意味着得先把账单落盘成临时文件。流式读取全程在内存里，符合"文件不落盘"的承诺。
 */
object ZipUtil {

    data class Entry(val name: String, val bytes: ByteArray)

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    fun extractFirst(bytes: ByteArray, password: String?): Entry {
        try {
            Zip4jInput(ByteArrayInputStream(bytes), password?.toCharArray()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.fileName
                    if (!e.isDirectory &&
                        (name.endsWith(".csv", true) || name.endsWith(".xlsx", true))
                    ) {
                        return Entry(name, zis.readBytes())
                    }
                    e = zis.nextEntry
                }
            }
        } catch (ex: Exception) {
            throw if (password.isNullOrEmpty()) {
                NeedPasswordException("这个账单压缩包有密码，请输入解压密码")
            } else {
                NeedPasswordException("密码不对，再试一次")
            }
        }
        throw IllegalStateException("压缩包里没找到 CSV 或 XLSX 文件")
    }
}
