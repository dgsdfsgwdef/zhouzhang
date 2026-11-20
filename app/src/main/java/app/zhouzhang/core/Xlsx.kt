package app.zhouzhang.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 极简 xlsx 读取器。
 *
 * 不引入 Apache POI——那个库十几 MB，为了读一张账单不值得。
 * xlsx 本质是个 zip，里面 sharedStrings.xml 存字符串池，sheet1.xml 存单元格。
 * 这里只用流式解析取这两份，内存占用跟行数无关。
 *
 * 注意微信导出的 xlsx 里，时间列是 Excel 序列号（46344.655…），
 * 金额是数值，不是字符串。转换放在 Parser 里做，这里只负责把值原样取出。
 *
 * 安全边界：xlsx 可能是邮件附件，等于陌生人可控的输入。所以每一处放大都被限死——
 * 条目数、单条目解压体积、行数、列号、单元格总数。列号尤其关键：
 * `r="ZZZZZ1"` 会让朴素的实现一次性补出 1200 万个空格子。
 */
object Xlsx {

    private const val SHARED = "xl/sharedStrings.xml"

    fun read(bytes: ByteArray): List<List<String>> {
        if (bytes.size > Limits.MAX_INPUT_BYTES) {
            throw InputTooLargeException("表格文件超过 ${Limits.MAX_INPUT_BYTES / 1024 / 1024} MB")
        }

        var sharedBytes: ByteArray? = null
        var sheetBytes: ByteArray? = null
        var entries = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (++entries > Limits.MAX_ZIP_ENTRIES) {
                    throw InputTooLargeException("压缩包条目过多，已中止")
                }
                when {
                    e.name == SHARED -> sharedBytes = zis.readCapped(Limits.MAX_ENTRY_BYTES)
                    sheetBytes == null &&
                            e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml") ->
                        sheetBytes = zis.readCapped(Limits.MAX_ENTRY_BYTES)
                }
                e = zis.nextEntry
            }
        }

        val sheet = sheetBytes ?: return emptyList()
        val shared = sharedBytes?.let { readShared(it) } ?: emptyList()
        return readSheet(sheet, shared)
    }

    private fun parser(input: InputStream): XmlPullParser {
        val f = XmlPullParserFactory.newInstance()
        f.isNamespaceAware = false
        val p = f.newPullParser()
        p.setInput(input, null)
        return p
    }

    /**
     * xlsx 规范里不会出现 DTD。出现就说明文件被构造过，直接拒绝——
     * 与其指望解析器把所有实体展开场景都堵住，不如在入口拦掉。
     */
    private fun rejectDoctype(bytes: ByteArray) {
        val n = minOf(bytes.size, 8 * 1024)
        val head = String(bytes, 0, n, Charsets.ISO_8859_1).uppercase()
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) {
            throw IllegalArgumentException("xlsx 中出现 DTD 声明，拒绝解析")
        }
    }

    /** <si><t>文本</t></si>，富文本会拆成多个 <r><t>，这里直接拼接 */
    private fun readShared(bytes: ByteArray): List<String> {
        rejectDoctype(bytes)
        val out = ArrayList<String>()
        val p = parser(ByteArrayInputStream(bytes))
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inSi && inT && sb.length < Limits.MAX_CELL_CHARS) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> { inSi = false; out.add(sb.toString()) }
                }
            }
            p.next()
        }
        return out
    }

    private fun readSheet(input: ByteArray, shared: List<String>): List<List<String>> {
        rejectDoctype(input)
        val rows = ArrayList<List<String>>()
        val p = parser(ByteArrayInputStream(input))

        var row: ArrayList<String>? = null
        var ref: String? = null
        var type: String? = null
        val sb = StringBuilder()
        var inV = false
        var inT = false
        var cells = 0

        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        if (rows.size >= Limits.MAX_ROWS) {
                            throw InputTooLargeException("表格行数超过 ${Limits.MAX_ROWS} 行")
                        }
                        row = ArrayList()
                    }
                    "c" -> {
                        ref = p.getAttributeValue(null, "r")
                        type = p.getAttributeValue(null, "t")
                        sb.setLength(0)
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }

                XmlPullParser.TEXT -> if ((inV || inT) && sb.length < Limits.MAX_CELL_CHARS) sb.append(p.text)

                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val r = row
                        val rf = ref
                        if (r != null && rf != null) {
                            val value = when (type) {
                                "s" -> sb.toString().trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                else -> sb.toString()
                            }
                            val idx = colIndex(rf)
                            // 列号必须落在真实范围内。否则一行就能撑爆内存。
                            if (idx >= Limits.MAX_COLUMNS) {
                                throw InputTooLargeException("表格列号 $rf 超出上限，文件已损坏或经过构造")
                            }
                            val pad = idx - r.size
                            if (pad > 0) {
                                cells += pad
                                if (cells > Limits.MAX_CELLS) {
                                    throw InputTooLargeException("表格单元格数量异常，已中止")
                                }
                                while (r.size < idx) r.add("")
                            }
                            if (r.size == idx) { r.add(value); cells++ } else r[idx] = value
                        }
                        ref = null
                        type = null
                    }
                    "row" -> {
                        row?.let { rows.add(it) }
                        row = null
                    }
                }
            }
            p.next()
        }
        return rows
    }

    /** "AB12" -> 27。只用前导字母，遇到数字停。 */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                n = n * 26 + (ch - 'A' + 1)
                // 早退：继续累乘只会溢出成负数，反而绕过上限检查
                if (n > Limits.MAX_COLUMNS * 26) return Limits.MAX_COLUMNS
            } else break
        }
        return (n - 1).coerceAtLeast(0)
    }
}
