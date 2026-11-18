package app.zhouzhang

import app.zhouzhang.core.Csv
import app.zhouzhang.core.Direction
import app.zhouzhang.core.FlowType
import app.zhouzhang.core.Parser
import app.zhouzhang.core.Platform
import app.zhouzhang.core.Week
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ParserTest {

    private fun sample(name: String): ByteArray {
        val candidates = listOf(File("../samples/$name"), File("samples/$name"))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到样本 $name，查找路径：${candidates.map { it.absolutePath }}")
        return f.readBytes()
    }

    @Test
    fun `微信账单能被识别并跳过说明行`() {
        val r = Parser.parse(sample("微信支付账单_2026-11.csv"), "wechat.csv")
        assertNull(r.error)
        assertEquals(Platform.WECHAT, r.platform)
        assertEquals("UTF-8", r.encoding)
        assertEquals(46, r.bills.size)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `微信转账与提现不计入消费`() {
        val r = Parser.parse(sample("微信支付账单_2026-11.csv"), "wechat.csv")
        val transfers = r.bills.filter { it.flowType == FlowType.TRANSFER }
        assertTrue("应识别出转账/红包/提现", transfers.size >= 5)
        assertTrue(transfers.all { it.direction == Direction.NEUTRAL })
    }

    @Test
    fun `微信全额退款被标记为退款`() {
        val r = Parser.parse(sample("微信支付账单_2026-11.csv"), "wechat.csv")
        val refunds = r.bills.filter { it.flowType == FlowType.REFUND }
        assertTrue("应识别出退款记录", refunds.isNotEmpty())
    }

    @Test
    fun `支付宝新版按 GB18030 解码`() {
        val r = Parser.parse(sample("支付宝交易明细_2026-11.csv"), "alipay.csv")
        assertNull(r.error)
        assertEquals(Platform.ALIPAY, r.platform)
        assertEquals("支付宝新版 12 列", r.formatVersion)
        assertEquals("GB18030", r.encoding)
        assertEquals(42, r.bills.size)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `支付宝新版的中性交易被识别`() {
        val r = Parser.parse(sample("支付宝交易明细_2026-11.csv"), "alipay.csv")
        assertTrue(r.bills.any { it.flowType == FlowType.FINANCE })
        assertTrue(r.bills.any { it.flowType == FlowType.REPAYMENT })
        assertTrue(r.bills.any { it.direction == Direction.INCOME })
    }

    @Test
    fun `支付宝旧版17列也能解析`() {
        val r = Parser.parse(sample("支付宝交易明细_旧版_2026-11.csv"), "alipay_old.csv")
        assertNull(r.error)
        assertEquals(Platform.ALIPAY, r.platform)
        assertEquals("支付宝旧版 17 列", r.formatVersion)
        assertEquals("GB18030", r.encoding)
        assertEquals(8, r.bills.size)
    }

    @Test
    fun `金额带人民币符号和千分位`() {
        assertEquals(128000L, Parser.parseCents("¥1,280.00"))
        assertEquals(3990L, Parser.parseCents("¥39.90"))
        assertEquals(100L, Parser.parseCents("1.00"))
        assertEquals(0L, Parser.parseCents("0"))
        assertNull(Parser.parseCents(""))
        assertNull(Parser.parseCents("abc"))
    }

    @Test
    fun `脏引号不会吞掉后面的行`() {
        val text = """
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-11-01 10:00:00,商户消费,某店,订单"未闭合,支出,¥12.00,零钱,支付成功,1,2,/
            2026-11-02 11:00:00,商户消费,另一家,正常商品,支出,¥13.00,零钱,支付成功,3,4,/
        """.trimIndent()
        val rows = Csv.parse(text)
        assertEquals(3, rows.size)
        assertEquals("正常商品", rows[2][3])
    }

    @Test
    fun `微信 xlsx 的 Excel 序列号时间能正确还原`() {
        val r = Parser.parse(sample("微信支付账单_xlsx样本.xlsx"), "wechat.xlsx")
        assertNull(r.error)
        assertEquals(Platform.WECHAT, r.platform)
        assertEquals("xlsx", r.encoding)
        assertEquals(30, r.bills.size)
        assertTrue("序列号应还原成 2026 年", r.bills.all { it.txnTime.year == 2026 })
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `xlsx 里的数值金额也按分处理`() {
        val r = Parser.parse(sample("微信支付账单_xlsx样本.xlsx"), "wechat.xlsx")
        assertTrue(r.bills.all { it.amountCents > 0 })
        // 1280 元 -> 128000 分，不能出现浮点误差
        assertTrue("应精确转成整数分", r.bills.any { it.amountCents == 128000L })
    }

    @Test
    fun `本机真实账单放 samples private 时能跑通`() {
        val dir = listOf(File("../samples/private"), File("samples/private"))
            .firstOrNull { it.isDirectory } ?: return
        val files = dir.listFiles()?.filter {
            it.extension.lowercase() in listOf("csv", "xlsx", "zip")
        }.orEmpty()
        if (files.isEmpty()) return
        for (f in files) {
            val r = Parser.parse(f.readBytes(), f.name, System.getProperty("zz.zip.password"))
            if (r.needsPassword) continue
            assertNull("${f.name} 解析失败：${r.error}", r.error)
            assertTrue("${f.name} 没解析出记录", r.bills.isNotEmpty())
        }
    }

    @Test
    fun `支付宝自带的交易分类作为兜底`() {
        val r = Parser.parse(sample("支付宝交易明细_2026-11.csv"), "alipay.csv")
        // 商户「无人零售」和商品「智能柜购物」都不含关键词，只能靠交易分类「日用百货」
        val b = r.bills.first { it.counterparty == "无人零售" }
        assertEquals("购物", b.category)
    }

    @Test
    fun `无法识别的文件给出明确错误`() {
        val r = Parser.parse("姓名,年龄\n张三,20\n".toByteArray(), "x.csv")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("无法识别"))
    }

    @Test
    fun `周起始日按周三计算`() {
        // 2026-11-18 是周三
        val wed = LocalDate.of(2026, 11, 18)
        assertEquals(wed, Week.startOf(wed))
        // 周二应归到前一个周三
        assertEquals(wed, Week.startOf(LocalDate.of(2026, 11, 24)))
        // 周四应归到本周三
        assertEquals(wed, Week.startOf(LocalDate.of(2026, 11, 19)))
        assertEquals("2026-11-18", Week.key(wed))
        assertEquals("11/18 – 11/24", Week.rangeLabel(wed))
    }

    @Test
    fun `起始日可切换到周一`() {
        val mon = LocalDate.of(2026, 11, 16)
        assertEquals(mon, Week.startOf(LocalDate.of(2026, 11, 18), 1))
        assertEquals(mon, Week.startOf(LocalDate.of(2026, 11, 22), 1))
    }
}
