package app.zhouzhang.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.zhouzhang.core.Bill
import app.zhouzhang.core.Direction
import app.zhouzhang.core.FlowType
import app.zhouzhang.core.Platform
import app.zhouzhang.core.Week
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

data class CategorySum(val category: String, val cents: Long)
data class DaySum(val date: LocalDate, val cents: Long)

data class MonthSummary(
    val month: YearMonth,
    val totalCents: Long,
    val count: Int,
    val prevTotalCents: Long,
    val categories: List<CategorySum>,
    val merchants: List<CategorySum>,
)

data class WeekSummary(
    val start: LocalDate,
    val totalCents: Long,
    val count: Int,
    val categories: List<CategorySum>,
    val days: List<DaySum>,
)

class BillStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {

    companion object {
        private const val DB_NAME = "zhouzhang.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bill (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                txn_time INTEGER NOT NULL,
                week_start TEXT NOT NULL,
                platform TEXT NOT NULL,
                direction TEXT NOT NULL,
                amount_cents INTEGER NOT NULL,
                flow_type TEXT NOT NULL,
                category TEXT NOT NULL,
                counterparty TEXT NOT NULL DEFAULT '',
                item TEXT NOT NULL DEFAULT '',
                method TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT '',
                order_no TEXT NOT NULL DEFAULT '',
                merchant_no TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                source_file TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX ix_week ON bill(week_start, flow_type, direction)")
        db.execSQL("CREATE INDEX ix_time ON bill(txn_time)")
        db.execSQL("CREATE UNIQUE INDEX ux_order ON bill(platform, order_no) WHERE order_no <> ''")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** 返回 (新增, 重复)。重复靠 (platform, order_no) 唯一索引挡掉。 */
    fun insertAll(bills: List<Bill>, startDay: Int = Week.DEFAULT_START_DAY): Pair<Int, Int> {
        if (bills.isEmpty()) return 0 to 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            for (b in bills) {
                val cv = ContentValues().apply {
                    put("txn_time", b.txnTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    put("week_start", Week.key(b.txnTime.toLocalDate(), startDay))
                    put("platform", b.platform.name)
                    put("direction", b.direction.name)
                    put("amount_cents", b.amountCents)
                    put("flow_type", b.flowType.name)
                    put("category", b.category)
                    put("counterparty", b.counterparty)
                    put("item", b.item)
                    put("method", b.method)
                    put("status", b.status)
                    put("order_no", b.orderNo)
                    put("merchant_no", b.merchantNo)
                    put("note", b.note)
                    put("source_file", b.sourceFile)
                }
                val id = db.insertWithOnConflict("bill", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (id != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted to (bills.size - inserted)
    }

    fun summary(start: LocalDate): WeekSummary {
        val key = start.toString()
        val db = readableDatabase

        var total = 0L
        var count = 0
        db.rawQuery(
            "SELECT COALESCE(SUM(amount_cents),0), COUNT(*) FROM bill " +
                    "WHERE week_start=? AND flow_type=? AND direction=?",
            arrayOf(key, FlowType.CONSUMPTION.name, Direction.EXPENSE.name)
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getLong(0); count = c.getInt(1)
            }
        }

        val categories = ArrayList<CategorySum>()
        db.rawQuery(
            "SELECT category, SUM(amount_cents) s FROM bill " +
                    "WHERE week_start=? AND flow_type=? AND direction=? " +
                    "GROUP BY category ORDER BY s DESC",
            arrayOf(key, FlowType.CONSUMPTION.name, Direction.EXPENSE.name)
        ).use { c ->
            while (c.moveToNext()) categories.add(CategorySum(c.getString(0), c.getLong(1)))
        }

        val byDay = HashMap<LocalDate, Long>()
        db.rawQuery(
            "SELECT txn_time, amount_cents FROM bill " +
                    "WHERE week_start=? AND flow_type=? AND direction=?",
            arrayOf(key, FlowType.CONSUMPTION.name, Direction.EXPENSE.name)
        ).use { c ->
            while (c.moveToNext()) {
                val d = Instant.ofEpochMilli(c.getLong(0))
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                byDay[d] = (byDay[d] ?: 0L) + c.getLong(1)
            }
        }
        val days = (0..6).map { start.plusDays(it.toLong()) }
            .map { DaySum(it, byDay[it] ?: 0L) }

        return WeekSummary(start, total, count, categories, days)
    }

    fun billsOfWeek(start: LocalDate): List<Bill> {
        val out = ArrayList<Bill>()
        readableDatabase.rawQuery(
            "SELECT txn_time, platform, direction, amount_cents, flow_type, category, " +
                    "counterparty, item, method, status, order_no, merchant_no, note, source_file " +
                    "FROM bill WHERE week_start=? ORDER BY txn_time DESC",
            arrayOf(start.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Bill(
                        txnTime = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(c.getLong(0)), ZoneId.systemDefault()),
                        platform = Platform.valueOf(c.getString(1)),
                        direction = Direction.valueOf(c.getString(2)),
                        amountCents = c.getLong(3),
                        flowType = FlowType.valueOf(c.getString(4)),
                        category = c.getString(5),
                        counterparty = c.getString(6),
                        item = c.getString(7),
                        method = c.getString(8),
                        status = c.getString(9),
                        orderNo = c.getString(10),
                        merchantNo = c.getString(11),
                        note = c.getString(12),
                        sourceFile = c.getString(13),
                    )
                )
            }
        }
        return out
    }

    private fun bounds(from: LocalDate, toExclusive: LocalDate): Array<String> {
        val zone = ZoneId.systemDefault()
        return arrayOf(
            from.atStartOfDay(zone).toInstant().toEpochMilli().toString(),
            toExclusive.atStartOfDay(zone).toInstant().toEpochMilli().toString(),
        )
    }

    /** [from, toExclusive) 的消费合计，用于「上周同期」对比 */
    fun sumConsumption(from: LocalDate, toExclusive: LocalDate): Long {
        val b = bounds(from, toExclusive)
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount_cents),0) FROM bill " +
                    "WHERE txn_time >= ? AND txn_time < ? AND flow_type=? AND direction=?",
            arrayOf(b[0], b[1], FlowType.CONSUMPTION.name, Direction.EXPENSE.name)
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /** 月总结按自然月统计，不按周拼接——跨月的周会重复计。 */
    fun monthSummary(month: YearMonth): MonthSummary {
        val start = month.atDay(1)
        val end = start.plusMonths(1)
        val prevStart = start.minusMonths(1)
        val b = bounds(start, end)
        val args = arrayOf(b[0], b[1], FlowType.CONSUMPTION.name, Direction.EXPENSE.name)

        var total = 0L
        var count = 0
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount_cents),0), COUNT(*) FROM bill " +
                    "WHERE txn_time >= ? AND txn_time < ? AND flow_type=? AND direction=?",
            args
        ).use { c -> if (c.moveToFirst()) { total = c.getLong(0); count = c.getInt(1) } }

        val categories = ArrayList<CategorySum>()
        readableDatabase.rawQuery(
            "SELECT category, SUM(amount_cents) s FROM bill " +
                    "WHERE txn_time >= ? AND txn_time < ? AND flow_type=? AND direction=? " +
                    "GROUP BY category ORDER BY s DESC",
            args
        ).use { c -> while (c.moveToNext()) categories.add(CategorySum(c.getString(0), c.getLong(1))) }

        val merchants = ArrayList<CategorySum>()
        readableDatabase.rawQuery(
            "SELECT counterparty, SUM(amount_cents) s FROM bill " +
                    "WHERE txn_time >= ? AND txn_time < ? AND flow_type=? AND direction=? " +
                    "AND counterparty <> '' GROUP BY counterparty ORDER BY s DESC LIMIT 5",
            args
        ).use { c -> while (c.moveToNext()) merchants.add(CategorySum(c.getString(0), c.getLong(1))) }

        return MonthSummary(month, total, count, sumConsumption(prevStart, start), categories, merchants)
    }

    /** 最早一笔的日期，用来判断还能不能继续往前翻 */
    fun earliestDate(): LocalDate? {
        readableDatabase.rawQuery("SELECT MIN(txn_time) FROM bill", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                return Instant.ofEpochMilli(c.getLong(0))
                    .atZone(ZoneId.systemDefault()).toLocalDate()
            }
        }
        return null
    }

    fun totalCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM bill", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
