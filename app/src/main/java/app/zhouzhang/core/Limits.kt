package app.zhouzhang.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 解析链路的资源上限。
 *
 * 账单有两个来源：用户自己选的文件，和邮箱里的附件。后者是**任何人都能投递**的
 * 不可信输入——只要知道收件地址，就能发一封主题里带「账单」、附件是高压缩比 zip
 * 的邮件，等用户点一次「收信」。
 *
 * 没有上限的话，几百 KB 的 zip 解压后可以撑到几个 GB，进程直接被 OOM 掉；
 * xlsx 里一个 `r="ZZZZZ1"` 的单元格引用也能让解析器一次性分配上千万个格子。
 * 这里把每一层都钉死，超限就明确报错，不静默截断。
 */
object Limits {

    /** 单个账单文件（zip / csv / xlsx）的原始大小 */
    const val MAX_INPUT_BYTES = 32L * 1024 * 1024

    /** 压缩包内单个条目解压后的大小 */
    const val MAX_ENTRY_BYTES = 24L * 1024 * 1024

    /** 扫描的 zip 条目数，防止几十万个空条目拖死循环 */
    const val MAX_ZIP_ENTRIES = 4096

    /** 行数。个人账单一年也就几千行，20 万足够宽松 */
    const val MAX_ROWS = 200_000

    /** 列数。真实账单最多 17 列，512 已有 30 倍余量 */
    const val MAX_COLUMNS = 512

    /** 单元格总数。挡住「每行一个超大列引用」造成的放大 */
    const val MAX_CELLS = 1_000_000

    /** 单元格字符数，防止单格塞进几 MB 文本 */
    const val MAX_CELL_CHARS = 8_192
}

/** 输入超出上限。与「密码错误」「格式不认」区分开，避免被当成别的错误吞掉。 */
class InputTooLargeException(message: String) : Exception(message)

/** 读满 max 字节就抛异常，而不是继续吃到 OOM */
fun InputStream.readCapped(max: Long): ByteArray {
    val out = ByteArrayOutputStream(minOf(max, 64L * 1024).toInt())
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw InputTooLargeException("解压后超过 ${max / 1024 / 1024} MB，已中止")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
