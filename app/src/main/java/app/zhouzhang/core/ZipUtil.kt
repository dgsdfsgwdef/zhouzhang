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
 *
 * 因为全程在内存里，"不落盘"也顺带堵死了 Zip Slip——条目名从不参与路径拼接。
 * 剩下的风险是压缩炸弹：条目名合法、体积却解压后几个 GB。这里按条目大小和数量限死。
 */
object ZipUtil {

    data class Entry(val name: String, val bytes: ByteArray)

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    fun extractFirst(bytes: ByteArray, password: String?): Entry {
        if (bytes.size > Limits.MAX_INPUT_BYTES) {
            throw InputTooLargeException("压缩包超过 ${Limits.MAX_INPUT_BYTES / 1024 / 1024} MB")
        }

        var entries = 0
        try {
            Zip4jInput(ByteArrayInputStream(bytes), password?.toCharArray()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (++entries > Limits.MAX_ZIP_ENTRIES) {
                        throw InputTooLargeException("压缩包条目过多，已中止")
                    }

                    val name = e.fileName
                    val wanted = !e.isDirectory &&
                            (name.endsWith(".csv", true) || name.endsWith(".xlsx", true))

                    if (wanted) {
                        // 头部声明了解压后大小就先看，省得白读一遍
                        if (e.uncompressedSize > Limits.MAX_ENTRY_BYTES) {
                            throw InputTooLargeException("压缩包内文件解压后超过上限，已中止")
                        }
                        return Entry(name, zis.readCapped(Limits.MAX_ENTRY_BYTES))
                    }
                    e = zis.nextEntry
                }
            }
        } catch (ex: InputTooLargeException) {
            // 体积超限不是密码问题，别伪装成"密码不对"让用户白试
            throw ex
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
