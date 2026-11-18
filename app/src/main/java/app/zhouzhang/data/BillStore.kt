package app.zhouzhang.data

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import app.zhouzhang.core.Bill
import app.zhouzhang.core.Direction
import app.zhouzhang.core.FlowType
import app.zhouzhang.core.Platform
import app.zhouzhang.core.Week
import java.io.File
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

data class MerchantSum(val name: String, val cents: Long, val count: Int)
data class CategoryRule(val id: Long, val keyword: String, val category: String)

/**
 * 数据库全部落盘即加密（SQLCipher，AES-256）。
 *
 * 口令不写在代码里，由 DbKey 用 Android Keystore 保管。
 * 打开失败时抛异常而不是静默重建——重建等于抹掉用户的账。
 */
class BillStore(private val context: Context) {

    companion object {
        private const val DB_NAME = "zhouzhang.db"
        private const val SCHEMA_VERSION = 3

        private const val PREFS = "zz_import"
        private const val PREF_UNDO_MAX_ID = "undo_max_id"
        private const val PREF_UNDO_COUNT = "undo_count"

        /** 与建表顺序一致，迁移时按列拷贝 */
        private val COLUMNS = listOf(
            "id" to "INTEGER",
            "txn_time" to "INTEGER",
            "week_start" to "TEXT",
            "platform" to "TEXT",
            "direction" to "TEXT",
            "amount_cents" to "INTEGER",
            "flow_type" to "TEXT",
            "category" to "TEXT",
            "counterparty" to "TEXT",
            "item" to "TEXT",
            "method" to "TEXT",
            "status" to "TEXT",
            "order_no" to "TEXT",
            "merchant_no" to "TEXT",
            "note" to "TEXT",
            "source_file" to "TEXT",
        )

        private val DDL = """
            CREATE TABLE IF NOT EXISTS bill (
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

        /**
         * 用户自定义的分类规则。
         * 关键词命中商户名或商品名就把分类改掉，优先级高于内置关键词表——
         * 用户既然手动指了，就按用户的来。
         */
        private val RULE_DDL = """
            CREATE TABLE IF NOT EXISTS category_rule (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword TEXT NOT NULL,
                category TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent()
    }

    private var opened: SQLiteDatabase? = null

    @Synchronized
    private fun db(): SQLiteDatabase {
        opened?.let { if (it.isOpen) return it }

        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()

        val db = openOrMigrate(file)
        opened = db
        return db
    }

    // ---------------------------------------------------------------- 打开与迁移

    private fun openOrMigrate(file: File): SQLiteDatabase {
        val passphrase = DbKey.passphrase(context)

        // 旧版本用的是明文 SQLite，检测到就先把文件挪开，稍后搬数据
        val legacy = if (file.exists() && isPlaintext(file)) {
            File(file.parentFile, "legacy_plain.db").also {
                it.delete()
                if (!file.renameTo(it)) throw IllegalStateException("无法迁移旧数据库")
            }
        } else null

        if (legacy != null) {
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }

        val db = SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null)
        db.execSQL(DDL)
        db.execSQL(RULE_DDL)
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_week ON bill(week_start, flow_type, direction)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_time ON bill(txn_time)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_order ON bill(platform, order_no) " +
                    "WHERE order_no <> ''"
        )
        db.execSQL("PRAGMA user_version = $SCHEMA_VERSION")

        if (legacy != null) {
            migrateFrom(legacy, db)
            legacy.delete()
        }
        return db
    }

    private fun isPlaintext(file: File): Boolean = try {
        val head = ByteArray(16)
        file.inputStream().use { it.read(head) }
        String(head, Charsets.US_ASCII) == "SQLite format 3\u0000"
    } catch (e: Exception) {
        false
    }

    /** 把明文库里的记录搬到加密库里，搬完再删旧文件 */
    private fun migrateFrom(legacy: File, target: SQLiteDatabase) {
        val old = android.database.sqlite.SQLiteDatabase.openDatabase(
            legacy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val names = COLUMNS.joinToString(", ") { it.first }
            target.beginTransaction()
            try {
                old.rawQuery("SELECT $names FROM bill", null).use { c ->
                    while (c.moveToNext()) {
                        val cv = ContentValues()
                        COLUMNS.forEachIndexed { i, (name, type) ->
                            if (type == "INTEGER") cv.put(name, c.getLong(i))
                            else cv.put(name, c.getString(i))
                        }
                        target.insertWithOnConflict(
                            "bill", null, cv, SQLiteDatabase.CONFLICT_IGNORE
                        )
                    }
                }
                target.setTransactionSuccessful()
            } finally {
                target.endTransaction()
            }
        } finally {
            old.close()
        }
    }

    // ---------------------------------------------------------------- 写入

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun maxId(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COALESCE(MAX(id),0) FROM bill", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /** 返回 (新增, 重复)。重复靠 (platform, order_no) 唯一索引挡掉。 */
    fun insertAll(bills: List<Bill>, startDay: Int = Week.DEFAULT_START_DAY): Pair<Int, Int> {
        if (bills.isEmpty()) return 0 to 0
        val db = db()

        // 用户规则优先于内置关键词
        val rules = rules(db)
        val prepared = if (rules.isEmpty()) bills else bills.map { b ->
            val hit = rules.firstOrNull {
                it.keyword.isNotBlank() &&
                        (b.counterparty.contains(it.keyword) || b.item.contains(it.keyword))
            }
            if (hit != null) b.copy(category = hit.category) else b
        }

        val before = maxId(db)
        var inserted = 0
        db.beginTransaction()
        try {
            for (b in prepared) {
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

        if (inserted > 0) {
            prefs.edit()
                .putLong(PREF_UNDO_MAX_ID, before)
                .putInt(PREF_UNDO_COUNT, inserted)
                .apply()
        }
        return inserted to (prepared.size - inserted)
    }

    // ---------------------------------------------------------------- 撤销导入

    fun pendingUndoCount(): Int = prefs.getInt(PREF_UNDO_COUNT, 0)

    /**
     * 撤销上一次导入。自增 id 单调递增，所以「删掉 id 大于导入前最大 id 的行」
     * 正好等于撤销这一批，不需要额外记录批次号。
     */
    fun undoLastImport(): Int {
        val before = prefs.getLong(PREF_UNDO_MAX_ID, -1)
        if (before < 0) return 0
        val deleted = db().delete("bill", "id > ?", arrayOf(before.toString()))
        prefs.edit().remove(PREF_UNDO_MAX_ID).remove(PREF_UNDO_COUNT).apply()
        return deleted
    }

    // ---------------------------------------------------------------- 分类规则

    fun rules(): List<CategoryRule> = rules(db())

    private fun rules(db: SQLiteDatabase): List<CategoryRule> {
        val out = ArrayList<CategoryRule>()
        db.rawQuery("SELECT id, keyword, category FROM category_rule ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                out.add(CategoryRule(c.getLong(0), c.getString(1), c.getString(2)))
            }
        }
        return out
    }

    /** 新增规则并立刻重算历史数据，返回被改写的行数 */
    fun addRule(keyword: String, category: String): Int {
        val db = db()
        db.execSQL(
            "INSERT INTO category_rule(keyword, category, created_at) VALUES(?,?,?)",
            arrayOf(keyword, category, System.currentTimeMillis())
        )
        return db.update(
            "bill",
            ContentValues().apply { put("category", category) },
            "counterparty LIKE ? OR item LIKE ?",
            arrayOf("%$keyword%", "%$keyword%")
        )
    }

    fun deleteRule(id: Long) {
        db().delete("category_rule", "id = ?", arrayOf(id.toString()))
    }

    /** 未分类的商户，按金额从大到小——先解决大头 */
    fun uncategorizedMerchants(limit: Int = 30): List<MerchantSum> {
        val out = ArrayList<MerchantSum>()
        db().rawQuery(
            "SELECT counterparty, SUM(amount_cents) s, COUNT(*) c FROM bill " +
                    "WHERE category='未分类' AND flow_type=? AND direction=? " +
                    "AND counterparty <> '' GROUP BY counterparty ORDER BY s DESC LIMIT $limit",
            arrayOf(FlowType.CONSUMPTION.name, Direction.EXPENSE.name)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(MerchantSum(c.getString(0), c.getLong(1), c.getInt(2)))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 查询

    fun summary(start: LocalDate): WeekSummary {
        val key = start.toString()
        val db = db()

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
        db().rawQuery(
            "SELECT txn_time, platform, direction, amount_cents, flow_type, category, " +
                    "counterparty, item, method, status, order_no, merchant_no, note, source_file " +
                    "FROM bill WHERE week_start=? ORDER BY txn_time DESC",
            arrayOf(start.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Bill(
                        txnTime = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(c.getLong(0)), ZoneId.systemDefault()
                        ),
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
        db().rawQuery(
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
        db().rawQuery(
            "SELECT COALESCE(SUM(amount_cents),0), COUNT(*) FROM bill " +
                    "WHERE txn_time >= ? AND txn_time < ? AND flow_type=? AND direction=?",
            args
        ).use { c -> if (c.moveToFirst()) { total = c.getLong(0); count = c.getInt(1) } }

        val categories = ArrayList<CategorySum>()
        db().rawQuery(
            "SELECT category, SUM(amount_cents) s FROM bill " +
                    "WHERE txn_time >= ? AND txn_time < ? AND flow_type=? AND direction=? " +
                    "GROUP BY category ORDER BY s DESC",
            args
        ).use { c -> while (c.moveToNext()) categories.add(CategorySum(c.getString(0), c.getLong(1))) }

        val merchants = ArrayList<CategorySum>()
        db().rawQuery(
            "SELECT counterparty, SUM(amount_cents) s FROM bill " +
                    "WHERE txn_time >= ? AND txn_time < ? AND flow_type=? AND direction=? " +
                    "AND counterparty <> '' GROUP BY counterparty ORDER BY s DESC LIMIT 5",
            args
        ).use { c -> while (c.moveToNext()) merchants.add(CategorySum(c.getString(0), c.getLong(1))) }

        return MonthSummary(month, total, count, sumConsumption(prevStart, start), categories, merchants)
    }

    /** 最早一笔的日期，用来判断还能不能继续往前翻 */
    fun earliestDate(): LocalDate? {
        db().rawQuery("SELECT MIN(txn_time) FROM bill", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                return Instant.ofEpochMilli(c.getLong(0))
                    .atZone(ZoneId.systemDefault()).toLocalDate()
            }
        }
        return null
    }

    /** 最新有数据的周，导入后自动跳过去——否则导完还停在空的本周，像是失败了 */
    fun latestDataWeek(): LocalDate? {
        db().rawQuery("SELECT MAX(week_start) FROM bill", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                return runCatching { LocalDate.parse(c.getString(0)) }.getOrNull()
            }
        }
        return null
    }

    fun totalCount(): Int {
        db().rawQuery("SELECT COUNT(*) FROM bill", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
