package app.zhouzhang

import app.zhouzhang.core.Csv
import app.zhouzhang.core.InputTooLargeException
import app.zhouzhang.core.Limits
import app.zhouzhang.core.Parser
import app.zhouzhang.core.Xlsx
import app.zhouzhang.core.ZipUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 恶意账单的回归测试。
 *
 * 这些输入都能从「邮箱附件」进来，也就是陌生人可控。每一条都对应一个真实的
 * 资源耗尽路径：压缩炸弹、超大列引用、海量行、DTD 实体。
 *
 * 断言的重点不是「报错」本身，而是**报错而不是 OOM**——所以每个用例都必须
 * 在有限内存里跑完。
 */
class SecurityLimitsTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `压缩炸弹按解压体积被拒绝，而不是 OOM`() {
        // 30 MB 的零字节，deflate 之后只有几十 KB
        val bomb = zipOf("账单.csv" to ByteArray(30 * 1024 * 1024))
        assertTrue("样本应当压缩得很小，否则测不到这条路径", bomb.size < 1_000_000)

        val e = assertThrows(InputTooLargeException::class.java) {
            ZipUtil.extractFirst(bomb, null)
        }
        assertTrue(e.message!!.contains("MB"))
    }

    @Test
    fun `压缩炸弹不会被伪装成需要密码`() {
        val bomb = zipOf("账单.csv" to ByteArray(30 * 1024 * 1024))
        val r = Parser.parse(bomb, "bomb.zip")

        assertNotNull("应当给出错误", r.error)
        assertFalse("体积超限不是密码问题，不能弹密码框", r.needsPassword)
        assertTrue(r.bills.isEmpty())
    }

    @Test
    fun `xlsx 里超大的列引用不会撑爆内存`() {
        // 朴素实现会为 r="ZZZZZ1" 补出约 1200 万个空格子
        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet><sheetData><row r="1"><c r="ZZZZZ1"><v>1</v></c></row></sheetData></worksheet>
        """.trimIndent().toByteArray()

        val xlsx = zipOf("xl/worksheets/sheet1.xml" to sheet)
        assertThrows(InputTooLargeException::class.java) { Xlsx.read(xlsx) }
    }

    @Test
    fun `带 DTD 的 xlsx 被直接拒绝`() {
        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE t [<!ENTITY e "boom">]>
            <worksheet><sheetData/></worksheet>
        """.trimIndent().toByteArray()

        val xlsx = zipOf("xl/worksheets/sheet1.xml" to sheet)
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(xlsx) }
    }

    @Test
    fun `超长 CSV 按行数被拒绝`() {
        val sb = StringBuilder("时间,对方,金额\n")
        repeat(Limits.MAX_ROWS + 5) { sb.append("2026-11-18 10:00:00,商户,1.00\n") }
        assertThrows(InputTooLargeException::class.java) { Csv.parse(sb.toString()) }
    }

    @Test
    fun `正常 xlsx 不受列上限影响`() {
        val shared = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst><si><t>交易时间</t></si></sst>
        """.trimIndent().toByteArray()

        // 第 11 列（K）是微信账单的真实宽度，必须照常读出来
        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet><sheetData>
              <row r="1">
                <c r="A1" t="s"><v>0</v></c>
                <c r="K1"><v>42</v></c>
              </row>
            </sheetData></worksheet>
        """.trimIndent().toByteArray()

        val rows = Xlsx.read(zipOf("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to sheet))
        assertEquals(1, rows.size)
        assertEquals(11, rows[0].size)
        assertEquals("交易时间", rows[0][0])
        assertEquals("42", rows[0][10])
    }
}
