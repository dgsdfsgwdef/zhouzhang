package app.zhouzhang.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 容错 CSV 读取器。
 *
 * 微信账单里经常出现未转义的引号（商家名、备注），Go 的 encoding/csv 会直接
 * 报 "extraneous or missing quote"，yann0917/awm 为此修了 14 个月。
 * 这里的原则是：能救就救，绝不因为一个字符丢掉整份账单。
 *
 * 判定规则：
 *  - 只有出现在字段开头（缓冲区为空）的 " 才可能是引号开始
 *  - 引号内遇到 " 时，只有后面紧跟 , 换行 或 文件结尾，才算闭合；否则当普通字符
 *
 * 安全边界：CSV 可能是邮件附件，行数与单元格数都要限死，否则一份全是逗号的
 * 大文件能瞬间造出几千万个空字符串。
 */
object Csv {

    fun parse(text: String): List<List<String>> =
        parse(text, Limits.MAX_ROWS, Limits.MAX_CELLS, Limits.MAX_CELL_CHARS)

    fun parse(text: String, maxRows: Int, maxCells: Int, maxCellChars: Int): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        var cells = 0
        val n = text.length

        fun endCell() {
            row.add(sb.toString())
            sb.clear()
            if (++cells > maxCells) throw InputTooLargeException("单元格数量超过 $maxCells，已中止")
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> {
                        if (sb.length < maxCellChars) sb.append('"')
                        i++
                    }
                    c == '"' && (i + 1 >= n || text[i + 1] == ',' ||
                            text[i + 1] == '\n' || text[i + 1] == '\r') -> {
                        inQuotes = false
                    }
                    else -> if (sb.length < maxCellChars) sb.append(c)
                }
            } else {
                when {
                    c == '"' && sb.isEmpty() -> inQuotes = true
                    c == ',' -> endCell()
                    c == '\r' -> { /* 忽略，交给 \n 处理 */ }
                    c == '\n' -> {
                        endCell()
                        rows.add(row)
                        if (rows.size > maxRows) throw InputTooLargeException("行数超过 $maxRows，已中止")
                        row = ArrayList()
                    }
                    else -> if (sb.length < maxCellChars) sb.append(c)
                }
            }
            i++
        }

        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString())
            rows.add(row)
            if (rows.size > maxRows) throw InputTooLargeException("行数超过 $maxRows，已中止")
        }
        return rows
    }

    data class Decoded(val text: String, val encoding: String)

    /**
     * UTF-8 严格解码，失败则按 GB18030（GBK 超集，兼容支付宝旧账单）。
     * 顺序不能反：GB18030 几乎不会解码失败，先试它会把 UTF-8 也当成中文乱码。
     */
    fun decode(bytes: ByteArray): Decoded {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            Decoded(text.removePrefix("\uFEFF"), "UTF-8")
        } catch (e: CharacterCodingException) {
            Decoded(String(bytes, Charset.forName("GB18030")), "GB18030")
        }
    }
}
