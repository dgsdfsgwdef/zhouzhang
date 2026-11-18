package app.zhouzhang.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.WeekFields

enum class Platform(val label: String) {
    WECHAT("微信"),
    ALIPAY("支付宝"),
}

enum class Direction(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    NEUTRAL("不计收支"),
}

/** 资金流向：只有 CONSUMPTION 计入消费统计 */
enum class FlowType(val label: String) {
    CONSUMPTION("消费"),
    TRANSFER("转账"),
    REPAYMENT("还款"),
    FINANCE("理财"),
    REFUND("退款"),
}

data class Bill(
    val txnTime: LocalDateTime,
    val platform: Platform,
    val direction: Direction,
    val amountCents: Long,
    val flowType: FlowType,
    val category: String,
    val counterparty: String,
    val item: String,
    val method: String,
    val status: String,
    val orderNo: String,
    val merchantNo: String,
    val note: String,
    val sourceFile: String,
)

data class ParseIssue(val line: Int, val raw: String, val reason: String)

data class ParseResult(
    val bills: List<Bill>,
    val platform: Platform?,
    val formatVersion: String,
    val encoding: String,
    val totalRows: Int,
    val issues: List<ParseIssue>,
    val error: String? = null,
    val needsPassword: Boolean = false,
)

/**
 * 周起始日可配置（1=周一 … 7=周日），默认 3（周三）。
 *
 * 不用 ISO 周编号做键：ISO 8601 假定周一开始，起始日改成周三后
 * 「2026-W47」会横跨两个自然周，指代不明。用起始日期做键，排序即时间序。
 */
object Week {
    const val DEFAULT_START_DAY = 3

    fun startOf(date: LocalDate, startDay: Int = DEFAULT_START_DAY): LocalDate {
        val diff = (date.dayOfWeek.value - startDay + 7) % 7
        return date.minusDays(diff.toLong())
    }

    fun startOf(time: LocalDateTime, startDay: Int = DEFAULT_START_DAY): LocalDate =
        startOf(time.toLocalDate(), startDay)

    fun key(date: LocalDate, startDay: Int = DEFAULT_START_DAY): String =
        startOf(date, startDay).toString()

    /** 用于界面显示的「11/18 – 11/24」 */
    fun rangeLabel(start: LocalDate): String {
        val end = start.plusDays(6)
        return "%d/%d – %d/%d".format(start.monthValue, start.dayOfMonth, end.monthValue, end.dayOfMonth)
    }
}
