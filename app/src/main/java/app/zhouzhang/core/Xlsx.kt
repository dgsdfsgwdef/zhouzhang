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
 */
object Xlsx {

    fun read(bytes: ByteArray): List<List<String>> {
        var shared: List<String> = emptyList()
        var sheet: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                when {
                    e.name == "xl/sharedStrings.xml" -> shared = readShared(zis)
                    sheet == null && e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml") ->
                        sheet = zis.readBytes()
                }
                e = zis.nextEntry
            }
        }

        val sheetBytes = sheet ?: return emptyList()
        return readSheet(sheetBytes, shared)
    }

    private fun parser(input: InputStream): XmlPullParser {
        val f = XmlPullParserFactory.newInstance()
        f.isNamespaceAware = false
        val p = f.newPullParser()
        p.setInput(input, null)
        return p
    }

    /** <si><t>文本</t></si>，富文本会拆成多个 <r><t>，这里直接拼接 */
    private fun readShared(input: InputStream): List<String> {
        val out = ArrayList<String>()
        val p = parser(input)
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inSi && inT) sb.append(p.text)
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
        val rows = ArrayList<List<String>>()
        val p = parser(ByteArrayInputStream(input))

        var row: ArrayList<String>? = null
        var ref: String? = null
        var type: String? = null
        val sb = StringBuilder()
        var inV = false
        var inT = false

        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        ref = p.getAttributeValue(null, "r")
                        type = p.getAttributeValue(null, "t")
                        sb.setLength(0)
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }

                XmlPullParser.TEXT -> if (inV || inT) sb.append(p.text)

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
                            while (r.size < idx) r.add("")
                            if (r.size == idx) r.add(value) else r[idx] = value
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

    /** "AB12" -> 27 */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return (n - 1).coerceAtLeast(0)
    }
}
