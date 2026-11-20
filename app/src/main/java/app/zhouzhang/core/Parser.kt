package app.zhouzhang.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/**
 * 账单解析。
 *
 * 不靠文件名、不靠用户选择平台：扫表头特征词判定，识别失败就明确报错，
 * 不猜——猜错的代价是几万条数据静默错位，比报错糟糕得多。
 */
object Parser {

    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val ALIASES: Map<String, List<String>> = mapOf(
        "txnTime" to listOf("交易时间", "交易创建时间", "付款时间"),
        "kind" to listOf("交易类型", "交易分类", "类型"),
        "counterparty" to listOf("交易对方"),
        "item" to listOf("商品", "商品说明", "商品名称"),
        "direction" to listOf("收/支"),
        "amount" to listOf("金额(元)", "金额"),
        "method" to listOf("支付方式", "收/付款方式"),
        "status" to listOf("当前状态", "交易状态"),
        "orderNo" to listOf("交易单号", "交易订单号", "交易号"),
        "merchantNo" to listOf("商户单号", "商家订单号"),
        "note" to listOf("备注"),
    )

    private fun norm(s: String): String =
        s.trim().replace("（", "(").replace("）", ")").replace("\u00A0", "").replace(" ", "")

    data class Header(val index: Int, val platform: Platform, val version: String, val cols: Map<String, Int>)

    fun locate(rows: List<List<String>>): Header? {
        for (i in 0 until minOf(rows.size, 40)) {
            val cells = rows[i].map { norm(it) }
            val hasTime = cells.any { it == "交易时间" || it == "交易创建时间" }
            val hasPeer = cells.any { it == "交易对方" }
            val hasAmount = cells.any { it == "金额(元)" || it == "金额" }
            if (!hasTime || !hasPeer || !hasAmount) continue

            val platform = when {
                cells.any { it == "交易类型" } -> Platform.WECHAT
                cells.any { it == "交易分类" } -> Platform.ALIPAY
                cells.any { it == "交易来源地" } -> Platform.ALIPAY
                else -> continue
            }
            val version = when {
                platform == Platform.WECHAT -> "微信 11 列"
                cells.any { it == "交易分类" } -> "支付宝新版 12 列"
                else -> "支付宝旧版 17 列"
            }
            val cols = HashMap<String, Int>()
            for ((key, aliases) in ALIASES) {
                val idx = cells.indexOfFirst { it in aliases }
                if (idx >= 0) cols[key] = idx
            }
            return Header(i, platform, version, cols)
        }
        return null
    }

    fun parse(bytes: ByteArray, sourceFile: String, password: String? = null): ParseResult {
        // 入口先挡一道：邮件附件和用户选的文件都可能远超一份账单该有的大小
        if (bytes.size > Limits.MAX_INPUT_BYTES) {
            return failed("文件超过 ${Limits.MAX_INPUT_BYTES / 1024 / 1024} MB，不是账单文件或已损坏")
        }

        val table: Table = try {
            loadTable(bytes, password)
        } catch (e: NeedPasswordException) {
            return ParseResult(
                bills = emptyList(), platform = null, formatVersion = "未知",
                encoding = "", totalRows = 0, issues = emptyList(),
                error = e.message, needsPassword = true,
            )
        } catch (e: InputTooLargeException) {
            // 压缩炸弹、超大表格都走这里。明确告诉用户是「太大」，不是「密码错」
            return failed(e.message ?: "文件内容异常")
        }

        val rows = table.rows
        val header = locate(rows)
            ?: return ParseResult(
                bills = emptyList(), platform = null, formatVersion = "未知",
                encoding = table.encoding, totalRows = rows.size, issues = emptyList(),
                error = "无法识别的账单格式。前 40 行里没找到「交易时间 + 交易对方 + 金额」表头，请确认是官方导出的个人对账账单。"
            )

        val bills = ArrayList<Bill>()
        val issues = ArrayList<ParseIssue>()
        var dataRows = 0

        for (r in header.index + 1 until rows.size) {
            val row = rows[r]
            if (row.all { it.isBlank() }) continue
            dataRows++

            val time = cell(row, header, "txnTime").let { normalizeTime(it) }
            if (time == null) {
                if (issues.size < 20) issues.add(ParseIssue(r + 1, row.joinToString(","), "时间无法解析"))
                continue
            }
            val amount = parseCents(cell(row, header, "amount"))
            if (amount == null) {
                if (issues.size < 20) issues.add(ParseIssue(r + 1, row.joinToString(","), "金额无法解析"))
                continue
            }

            val status = cell(row, header, "status")
            val kind = cell(row, header, "kind")
            val counterparty = cell(row, header, "counterparty")
            val item = cell(row, header, "item")
            val direction = when (cell(row, header, "direction")) {
                "支出" -> Direction.EXPENSE
                "收入" -> Direction.INCOME
                else -> Direction.NEUTRAL
            }
            val flow = classifyFlow(kind, item, counterparty, status)

            bills.add(
                Bill(
                    txnTime = time,
                    platform = header.platform,
                    direction = direction,
                    amountCents = amount,
                    flowType = flow,
                    category = categorize(flow, counterparty, item, kind),
                    counterparty = counterparty,
                    item = item,
                    method = cell(row, header, "method"),
                    status = status,
                    orderNo = cell(row, header, "orderNo"),
                    merchantNo = cell(row, header, "merchantNo"),
                    note = cell(row, header, "note"),
                    sourceFile = sourceFile,
                )
            )
        }

        return ParseResult(
            bills = bills,
            platform = header.platform,
            formatVersion = header.version,
            encoding = table.encoding,
            totalRows = dataRows,
            issues = issues,
        )
    }

    private class Table(val rows: List<List<String>>, val encoding: String)

    private fun failed(message: String) = ParseResult(
        bills = emptyList(), platform = null, formatVersion = "未知",
        encoding = "", totalRows = 0, issues = emptyList(), error = message,
    )

    /**
     * 三种输入形态：
     *   1. 纯 CSV（支付宝网页端导出、手动解压后的文件）
     *   2. XLSX（微信新版导出，时间列是 Excel 序列号）
     *   3. 加密 zip（两家都会给，里面可能是 csv 也可能是 xlsx）
     */
    private fun loadTable(bytes: ByteArray, password: String?): Table {
        if (!ZipUtil.isZip(bytes)) {
            val d = Csv.decode(bytes)
            return Table(Csv.parse(d.text), d.encoding)
        }

        // 先按 xlsx 试。xlsx 本身就是 zip，靠能否读出工作表区分。
        // 注意不能把「太大」也当成「不是 xlsx」吞掉，否则炸弹会继续往下走。
        val asXlsx = try {
            Xlsx.read(bytes)
        } catch (e: InputTooLargeException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (asXlsx.isNotEmpty()) return Table(asXlsx, "xlsx")

        val entry = ZipUtil.extractFirst(bytes, password)
        return if (entry.name.endsWith(".xlsx", true)) {
            Table(Xlsx.read(entry.bytes), "xlsx")
        } else {
            val d = Csv.decode(entry.bytes)
            Table(Csv.parse(d.text), d.encoding)
        }
    }

    private fun cell(row: List<String>, h: Header, key: String): String {
        val i = h.cols[key] ?: return ""
        return if (i < row.size) row[i].trim() else ""
    }

    private fun normalizeTime(raw: String): LocalDateTime? {
        if (raw.isBlank()) return null
        val t = raw.trim()

        // 微信 xlsx 的时间列是 Excel 序列号：46344.65520833333
        t.toDoubleOrNull()?.let { d -> excelSerial(d)?.let { return it } }

        val s = t.replace("/", "-").replace(".", "-")
            .replace(Regex("\\s+"), " ")
        return try {
            LocalDateTime.parse(s, TIME_FMT)
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(s.substring(0, 16) + ":00", TIME_FMT)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Excel 序列日期，纪元取 1899-12-30（抵消 1900 年的闰年 bug） */
    private fun excelSerial(d: Double): LocalDateTime? {
        if (d < 20000 || d > 80000) return null
        val whole = floor(d).toLong()
        var secs = Math.round((d - whole) * 86400.0)
        var date = LocalDate.of(1899, 12, 30).plusDays(whole)
        if (secs >= 86400) {
            date = date.plusDays(1)
            secs -= 86400
        }
        return date.atTime(LocalTime.ofSecondOfDay(secs))
    }

    /** 金额统一转整数分。用 BigDecimal，绝不用 Double。 */
    fun parseCents(raw: String): Long? {
        if (raw.isBlank()) return null
        val cleaned = raw.replace("¥", "").replace("￥", "").replace(",", "")
            .replace("元", "").trim()
        if (cleaned.isEmpty()) return null
        return try {
            BigDecimal(cleaned).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun classifyFlow(kind: String, item: String, peer: String, status: String): FlowType {
        val text = "$kind $item $peer"
        return when {
            status.contains("退款") || text.contains("退款") -> FlowType.REFUND
            contains(text, "还款", "花呗", "借呗") -> FlowType.REPAYMENT
            contains(text, "余额宝", "零钱通", "基金", "理财", "定投", "黄金", "债券") -> FlowType.FINANCE
            contains(text, "转账", "红包", "提现", "群收款", "亲属卡", "亲情卡") -> FlowType.TRANSFER
            else -> FlowType.CONSUMPTION
        }
    }

    private fun contains(text: String, vararg keys: String) = keys.any { text.contains(it) }

    private val CATEGORY_RULES: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("美团", "饿了么", "肯德基", "麦当劳", "星巴克", "瑞幸", "喜茶", "奈雪",
            "蜜雪", "海底捞", "餐饮", "餐厅", "饭店", "咖啡", "奶茶", "烧烤", "火锅", "面馆",
            "食堂", "便利店", "烘焙", "面包", "小吃"),
        "交通" to listOf("滴滴", "高德", "地铁", "公交", "铁路", "12306", "航空", "机票", "加油",
            "中石化", "中石油", "停车", "哈啰", "青桔", "单车", "打车", "出租车", "高速", "充电桩"),
        "购物" to listOf("淘宝", "天猫", "京东", "拼多多", "唯品会", "苏宁", "超市", "盒马",
            "沃尔玛", "永辉", "大润发", "商场", "优衣库", "无印良品", "小米", "Apple", "苹果",
            "华为", "商城", "旗舰店", "专营店"),
        "居家" to listOf("水费", "电费", "燃气", "物业", "房租", "家政", "宜家", "家具", "家居",
            "快递", "菜市场", "生鲜"),
        "娱乐" to listOf("电影", "影院", "游戏", "腾讯视频", "爱奇艺", "优酷", "网易云", "QQ音乐",
            "Steam", "健身", "KTV", "旅游", "酒店", "携程", "演出", "门票"),
        "医疗" to listOf("医院", "药房", "药店", "诊所", "体检", "挂号", "医疗"),
        "教育" to listOf("学费", "培训", "书店", "图书", "当当", "网课", "教育"),
        "通讯" to listOf("话费", "流量", "中国移动", "中国联通", "中国电信", "宽带"),
    )

    /**
     * 支付宝自己给了「交易分类」（日用百货/餐饮美食/交通出行…），
     * 这比从商户名猜关键词可靠得多。关键词规则没命中时用它兜底，
     * 不要浪费这个信号。
     */
    private val ALIPAY_KIND_MAP = mapOf(
        "餐饮美食" to "餐饮",
        "交通出行" to "交通",
        "日用百货" to "购物",
        "服饰装扮" to "购物",
        "数码电器" to "购物",
        "美容美发" to "购物",
        "母婴亲子" to "购物",
        "生活服务" to "居家",
        "住房物业" to "居家",
        "爱车养车" to "交通",
        "文化休闲" to "娱乐",
        "运动户外" to "娱乐",
        "医疗健康" to "医疗",
        "教育培训" to "教育",
        "通讯" to "通讯",
        "充值缴费" to "通讯",
    )

    private fun categorize(flow: FlowType, peer: String, item: String, kind: String): String {
        if (flow != FlowType.CONSUMPTION) return flow.label
        val text = "$peer $item"
        for ((category, keys) in CATEGORY_RULES) {
            if (keys.any { text.contains(it, ignoreCase = true) }) return category
        }
        ALIPAY_KIND_MAP[kind]?.let { return it }
        return "未分类"
    }
}
