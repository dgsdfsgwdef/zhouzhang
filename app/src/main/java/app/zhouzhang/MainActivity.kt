package app.zhouzhang

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zhouzhang.core.Bill
import app.zhouzhang.core.Direction
import app.zhouzhang.core.FlowType
import app.zhouzhang.core.Limits
import app.zhouzhang.core.Parser
import app.zhouzhang.core.Week
import app.zhouzhang.core.readCapped
import app.zhouzhang.data.BillStore
import app.zhouzhang.data.CategoryRule
import app.zhouzhang.data.CategorySum
import app.zhouzhang.data.DaySum
import app.zhouzhang.data.MerchantSum
import app.zhouzhang.data.MonthSummary
import app.zhouzhang.data.SecretBox
import app.zhouzhang.data.WeekSummary
import app.zhouzhang.mail.ImapClient
import app.zhouzhang.mail.MailAccount
import app.zhouzhang.mail.MailAttachment
import app.zhouzhang.mail.MailConfig
import app.zhouzhang.mail.MailPresets
import app.zhouzhang.ui.Ink
import app.zhouzhang.ui.ZType
import app.zhouzhang.ui.ZhouTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {
    private lateinit var store: BillStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BillStore(applicationContext)

        // 禁止截屏，同时让最近任务里的缩略图变成空白。
        // 这不是应用锁（应用锁已废弃），只是不让账单金额从系统层面漏出去：
        // 截图、录屏、任务切换缩略图都不再缓存本页内容。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }

        setContent {
            ZhouTheme { HomeScreen(store) }
        }
    }
}

enum class Mode(val label: String) { WEEK("周"), MONTH("月"), TREND("趋势") }

/** 导入结果的三态。成功和失败不该长得一样 */
enum class MsgKind { INFO, SUCCESS, PARTIAL, ERROR }

fun money(cents: Long): String = (if (cents < 0) "-" else "") + "¥" + amountDigits(cents)

/** 不带货币符号的金额。界面要把 ¥ 单独排版时用它，符号才能降一档 */
fun amountDigits(cents: Long): String {
    val abs = kotlin.math.abs(cents)
    return "%,d.%02d".format(abs / 100, abs % 100)
}

fun weekdayLabel(d: LocalDate): String = when (d.dayOfWeek.value) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: BillStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val today = remember { LocalDate.now() }
    var startDay by remember { mutableStateOf(Week.DEFAULT_START_DAY) }
    val thisWeek = Week.startOf(today, startDay)
    val thisMonth = remember { YearMonth.from(today) }

    var mode by remember { mutableStateOf(Mode.WEEK) }
    var weekStart by remember { mutableStateOf(thisWeek) }
    var month by remember { mutableStateOf(thisMonth) }

    var weekSum by remember { mutableStateOf<WeekSummary?>(null) }
    var bills by remember { mutableStateOf<List<Bill>>(emptyList()) }
    var monthSum by remember { mutableStateOf<MonthSummary?>(null) }
    var prevSame by remember { mutableStateOf(0L) }
    var earliest by remember { mutableStateOf<LocalDate?>(null) }
    var lastWeek by remember { mutableStateOf<LocalDate?>(null) }

    var message by remember { mutableStateOf<String?>(null) }
    var msgKind by remember { mutableStateOf(MsgKind.INFO) }
    var msgUndo by remember { mutableStateOf(false) }
    var undoDismissed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var pendingQueue by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var monthBills by remember { mutableStateOf<List<Bill>>(emptyList()) }
    var trend by remember { mutableStateOf<List<Pair<LocalDate, Long>>>(emptyList()) }
    var uncategorized by remember { mutableStateOf(0) }
    var dayFilter by remember { mutableStateOf<LocalDate?>(null) }
    var drill by remember { mutableStateOf<String?>(null) }

    var hasData by remember { mutableStateOf(false) }
    var undoCount by remember { mutableStateOf(0) }
    var showRules by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var mailConfigured by remember { mutableStateOf(false) }
    var pendingMail by remember { mutableStateOf<MailAttachment?>(null) }

    fun refresh() {
        weekSum = store.summary(weekStart)
        bills = store.billsOfWeek(weekStart)
        monthSum = store.monthSummary(month)
        monthBills = store.billsOfRange(month.atDay(1), month.atDay(1).plusMonths(1))
        trend = store.weeklyTotals(thisWeek, 12)
        uncategorized = store.uncategorizedMerchants(500).size
        earliest = store.earliestDate()
        lastWeek = store.latestDataWeek()
        hasData = store.totalCount() > 0
        undoCount = store.pendingUndoCount()
        mailConfigured = MailConfig.load(ctx) != null
        startDay = store.weekStartDay()

        // 本周只过了几天，就只和上周同样天数比；过去的周则整周对比。
        val days = if (weekStart == thisWeek) {
            (ChronoUnit.DAYS.between(weekStart, today).toInt() + 1).coerceIn(1, 7)
        } else 7
        val prevStart = weekStart.minusWeeks(1)
        prevSame = store.sumConsumption(prevStart, prevStart.plusDays(days.toLong()))
    }

    LaunchedEffect(weekStart, month) {
        dayFilter = null
        withContext(Dispatchers.IO) { refresh() }
    }

    /**
     * 支持一次选多个文件（微信 + 支付宝一起导）。
     * 中途遇到加密包就停下问密码，剩下的排进队列，输完接着导。
     */
    fun importFiles(uris: List<Uri>, pwd: String?) {
        if (uris.isEmpty()) return
        busy = true
        scope.launch {
            var parsed = 0
            var added = 0
            var duplicated = 0
            var issues = 0
            val problems = ArrayList<String>()
            var needPassword: Uri? = null
            var rest: List<Uri> = emptyList()

            for ((index, uri) in uris.withIndex()) {
                try {
                    val payload = withContext(Dispatchers.IO) { readBytes(ctx, uri) }
                    val result = withContext(Dispatchers.Default) {
                        Parser.parse(payload.first, payload.second, pwd)
                    }
                    when {
                        result.needsPassword -> {
                            needPassword = uri
                            rest = uris.drop(index + 1)
                            break
                        }

                        result.error != null -> problems.add("${payload.second}：${result.error}")

                        else -> {
                            val counts = withContext(Dispatchers.IO) { store.insertAll(result.bills) }
                            parsed += result.bills.size
                            added += counts.first
                            duplicated += counts.second
                            issues += result.issues.size
                        }
                    }
                } catch (e: Exception) {
                    problems.add(e.message ?: "读取失败")
                }
            }

            if (needPassword != null) {
                pendingUri = needPassword
                pendingQueue = rest
                passwordError = "这个账单压缩包有密码，请输入解压密码"
                askPassword = true
                busy = false
                return@launch
            }

            password = ""
            passwordError = null
            pendingQueue = emptyList()

            val hasProblem = problems.isNotEmpty() || issues > 0
            msgKind = when {
                added == 0 && hasProblem -> MsgKind.ERROR
                hasProblem || duplicated > 0 -> MsgKind.PARTIAL
                else -> MsgKind.SUCCESS
            }
            msgUndo = added > 0
            undoDismissed = false
            message = buildString {
                if (uris.size > 1) append("共 ${uris.size} 个文件\n\n")
                append("解析 $parsed 条\n")
                append("新增 $added 条，重复 $duplicated 条")
                if (issues > 0) append("\n无法解析 $issues 条")
                if (problems.isNotEmpty()) {
                    append("\n\n")
                    problems.forEach { append("· $it\n") }
                }
            }

            if (added > 0) {
                // 导完直接跳到最新有数据的周，否则停在空的本周像是失败了
                withContext(Dispatchers.IO) { store.latestDataWeek() }?.let { latest ->
                    weekStart = latest
                    month = YearMonth.from(latest)
                }
            }
            withContext(Dispatchers.IO) { refresh() }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) importFiles(uris, null)
    }

    /**
     * 从邮箱收账单。附件直接解析入库，用户不用再选文件。
     * 加密包先用记住的解压码试，解不开就问一次。
     */
    fun syncMail() {
        val account = MailConfig.load(ctx)
        if (account == null) {
            message = "还没有配置邮箱"
            msgKind = MsgKind.ERROR
            msgUndo = false
            return
        }
        busy = true
        scope.launch {
            try {
                val attachments = withContext(Dispatchers.IO) { ImapClient.fetchBills(account) }
                if (attachments.isEmpty()) {
                    message = "最近 45 天没有找到账单邮件"
                    msgKind = MsgKind.INFO
                    msgUndo = false
                    return@launch
                }

                val stored = SecretBox.get(ctx, "zip_password")
                var parsed = 0
                var added = 0
                var duplicated = 0
                var skipped = 0
                var needPassword: MailAttachment? = null

                for (a in attachments) {
                    val result = withContext(Dispatchers.Default) {
                        Parser.parse(a.bytes, a.fileName, stored)
                    }
                    when {
                        result.needsPassword -> {
                            needPassword = a
                            break
                        }

                        result.error != null -> skipped++

                        else -> {
                            val counts = withContext(Dispatchers.IO) { store.insertAll(result.bills) }
                            parsed += result.bills.size
                            added += counts.first
                            duplicated += counts.second
                        }
                    }
                }

                if (needPassword != null) {
                    pendingMail = needPassword
                    passwordError = "邮件里的账单压缩包有密码"
                    askPassword = true
                    return@launch
                }

                msgUndo = added > 0
                undoDismissed = false
                msgKind = when {
                    added == 0 -> MsgKind.PARTIAL
                    skipped > 0 -> MsgKind.PARTIAL
                    else -> MsgKind.SUCCESS
                }
                message = buildString {
                    append("收到 ${attachments.size} 个附件\n\n")
                    append("解析 $parsed 条\n")
                    append("新增 $added 条，重复 $duplicated 条")
                    if (skipped > 0) append("\n跳过 $skipped 个无法解析的附件")
                }
                if (added > 0) {
                    withContext(Dispatchers.IO) { store.latestDataWeek() }?.let { latest ->
                        weekStart = latest
                        month = YearMonth.from(latest)
                    }
                }
                withContext(Dispatchers.IO) { refresh() }
            } catch (e: Exception) {
                message = "收信失败：${e.message ?: e.javaClass.simpleName}"
                msgKind = MsgKind.ERROR
                msgUndo = false
            } finally {
                busy = false
            }
        }
    }

    val mimeTypes = arrayOf(
        "text/csv",
        "text/comma-separated-values",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
        "*/*",
    )

    fun doUndo() {
        scope.launch {
            val n = withContext(Dispatchers.IO) { store.undoLastImport() }
            withContext(Dispatchers.IO) { refresh() }
            message = "已撤销 $n 条记录"
            msgKind = MsgKind.INFO
            msgUndo = false
            undoDismissed = false
        }
    }

    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
    val firstDate = earliest

    val periodLabel = when (mode) {
        Mode.WEEK -> Week.rangeLabel(weekStart)
        Mode.MONTH -> "${month.year} 年 ${month.monthValue} 月"
        Mode.TREND -> "最近 12 周"
    }
    val periodTotal = when (mode) {
        Mode.WEEK -> weekSum?.totalCents ?: 0
        Mode.MONTH -> monthSum?.totalCents ?: 0
        Mode.TREND -> 0
    }
    val periodCount = when (mode) {
        Mode.WEEK -> weekSum?.count ?: 0
        Mode.MONTH -> monthSum?.count ?: 0
        Mode.TREND -> 0
    }
    val canPrev = when (mode) {
        Mode.WEEK -> firstDate == null || weekStart.minusWeeks(1).plusDays(6) >= firstDate
        Mode.MONTH -> firstDate == null || !YearMonth.from(firstDate).isBefore(month.minusMonths(1))
        Mode.TREND -> false
    }
    val canNext = when (mode) {
        Mode.WEEK -> weekStart.isBefore(thisWeek)
        Mode.MONTH -> month.isBefore(thisMonth)
        Mode.TREND -> false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Bg)
            .systemBarsPadding(),
    ) {
        ZHeader(
            title = if (hasData) periodLabel else "周账",
            subtitle = if (collapsed && mode != Mode.TREND) "${money(periodTotal)} · $periodCount 笔" else null,
            showNav = hasData && mode != Mode.TREND,
            canPrev = canPrev,
            canNext = canNext,
            onPrev = { if (mode == Mode.WEEK) weekStart = weekStart.minusWeeks(1) else month = month.minusMonths(1) },
            onNext = { if (mode == Mode.WEEK) weekStart = weekStart.plusWeeks(1) else month = month.plusMonths(1) },
            busy = busy,
            showImport = hasData,
            onImport = { picker.launch(mimeTypes) },
            onSettings = { showSettings = true },
        )
        // 解析 1–3 万行要几秒，没有进度条会像「点了没反应」
        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Ink.AccentFill,
                trackColor = Ink.Sunk,
            )
        }

        if (!hasData) {
            Onboarding(
                modifier = Modifier.weight(1f),
                busy = busy,
                onImport = { picker.launch(mimeTypes) },
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 36.dp),
            ) {
                item {
                    if (undoCount > 0 && !undoDismissed) {
                        UndoStrip(
                            count = undoCount,
                            onUndo = { doUndo() },
                            onDismiss = { undoDismissed = true },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    ModeSwitch(mode) {
                        if (it != mode) {
                            mode = it
                            dayFilter = null
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }

                when (mode) {
                    Mode.WEEK -> {
                        val total = weekSum?.totalCents ?: 0
                        val count = weekSum?.count ?: 0
                        val delta = if (prevSame > 0 && total > 0) (total - prevSame) * 100.0 / prevSame else null
                        val cats = weekSum?.categories.orEmpty()
                        val days = weekSum?.days.orEmpty()
                        val filtered = if (dayFilter == null) bills else bills.filter { it.txnTime.toLocalDate() == dayFilter }
                        val grouped = filtered.groupBy { it.txnTime.toLocalDate() }
                            .toList().sortedByDescending { it.first }

                        item {
                            Text("本周支出", style = ZType.caption, color = Ink.Text3)
                            Spacer(Modifier.height(2.dp))
                            AmountBlock(
                                total = total,
                                meta = if (count > 0) "$count 笔消费" else "这周还没有记录",
                                deltaPct = delta?.let { kotlin.math.abs(it).toInt() },
                                deltaUp = (delta ?: 0.0) >= 0,
                            )
                            Spacer(Modifier.height(18.dp))
                        }

                        item {
                            if (total > 0) {
                                ChartCard(days, weekStart, dayFilter) { dayFilter = it }
                            } else {
                                EmptyWeekCard(lastWeek) { w -> weekStart = w }
                            }
                            Spacer(Modifier.height(20.dp))
                        }

                        item { SectionHeader("分类", uncategorized, onManage = { showRules = true }) }
                        items(cats) { c ->
                            CategoryRow(c.category, c.cents, total) { drill = c.category }
                        }

                        item { DetailHeader(dayFilter) { dayFilter = null } }

                        if (grouped.isEmpty()) {
                            item {
                                Text(
                                    if (dayFilter != null) "这天没有记录。" else "导入账单后，这里会按天列出每一笔。",
                                    style = ZType.secondary,
                                    color = Ink.Text3,
                                )
                            }
                        } else {
                            grouped.forEach { (date, list) ->
                                item { DayHeader(date, list) }
                                items(list) { b -> BillRow(b) }
                            }
                        }
                    }

                    Mode.MONTH -> {
                        val total = monthSum?.totalCents ?: 0
                        val cnt = monthSum?.count ?: 0
                        val dayCount = if (month == thisMonth) today.dayOfMonth else month.lengthOfMonth()
                        val prevMonth = monthSum?.prevTotalCents ?: 0
                        val delta = if (prevMonth > 0 && total > 0) (total - prevMonth) * 100.0 / prevMonth else null
                        val cats = monthSum?.categories.orEmpty()
                        val filtered = if (dayFilter == null) monthBills else monthBills.filter { it.txnTime.toLocalDate() == dayFilter }
                        val grouped = filtered.groupBy { it.txnTime.toLocalDate() }
                            .toList().sortedByDescending { it.first }

                        item {
                            Text("本月支出", style = ZType.caption, color = Ink.Text3)
                            Spacer(Modifier.height(2.dp))
                            AmountBlock(
                                total = total,
                                meta = if (cnt > 0) "$cnt 笔 · 日均 ${money(total / dayCount)}" else "本月还没有记录",
                                deltaPct = delta?.let { kotlin.math.abs(it).toInt() },
                                deltaUp = (delta ?: 0.0) >= 0,
                                deltaLabel = "比上月",
                            )
                            Spacer(Modifier.height(18.dp))
                        }

                        item { SectionHeader("分类占比", uncategorized, onManage = { showRules = true }) }
                        items(cats) { c ->
                            CategoryRow(c.category, c.cents, total) { drill = c.category }
                        }

                        val merchants = monthSum?.merchants.orEmpty()
                        if (merchants.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(18.dp))
                                SectionHeader("花得最多的商户", 0, onManage = {}, showManage = false)
                                merchants.forEachIndexed { i, m -> MerchantRow(i + 1, m) }
                            }
                        }

                        item { DetailHeader(dayFilter) { dayFilter = null } }

                        if (grouped.isEmpty()) {
                            item {
                                Text(
                                    if (dayFilter != null) "这天没有记录。" else "本月还没有可列的明细。",
                                    style = ZType.secondary,
                                    color = Ink.Text3,
                                )
                            }
                        } else {
                            grouped.forEach { (date, list) ->
                                item { DayHeader(date, list) }
                                items(list) { b -> BillRow(b) }
                            }
                        }
                    }

                    Mode.TREND -> {
                        item {
                            TrendCard(trend) { w ->
                                weekStart = w
                                mode = Mode.WEEK
                                scope.launch { listState.scrollToItem(0) }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        item { TrendStats(trend) }
                    }
                }
            }
        }
    }

    message?.let { body ->
        val icon = when (msgKind) {
            MsgKind.SUCCESS -> Icons.Rounded.CheckCircle
            MsgKind.PARTIAL -> Icons.Rounded.Warning
            MsgKind.ERROR -> Icons.Rounded.ErrorOutline
            MsgKind.INFO -> Icons.Rounded.Info
        }
        val tint = when (msgKind) {
            MsgKind.SUCCESS -> Ink.Down
            MsgKind.PARTIAL -> Ink.AccentFill
            MsgKind.ERROR -> Ink.Up
            MsgKind.INFO -> Ink.Text3
        }
        val title = when (msgKind) {
            MsgKind.SUCCESS -> "导入完成"
            MsgKind.PARTIAL -> "部分导入"
            MsgKind.ERROR -> "导入失败"
            MsgKind.INFO -> "提示"
        }
        // 撤销入口只在这里出现一次：刚看完结果，是唯一有撤销意愿的时刻
        val undoButton: (@Composable () -> Unit)? = if (msgUndo) {
            {
                TextButton(onClick = {
                    message = null
                    doUndo()
                }) {
                    Text("撤销本次导入", style = ZType.secondary, color = Ink.Up)
                }
            }
        } else null

        AlertDialog(
            onDismissRequest = { message = null },
            icon = { Icon(icon, contentDescription = null, tint = tint) },
            title = { Text(title, style = ZType.title) },
            text = { Text(body, style = ZType.bodySmall, lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("好") }
            },
            dismissButton = undoButton,
            containerColor = Ink.Surface,
        )
    }

    if (askPassword) {
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("需要解压密码", style = ZType.title) },
            text = {
                Column {
                    Text(
                        "这个账单压缩包是加密的。密码通常是身份证后 6 位或手机号后 6 位。",
                        style = ZType.secondary,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("解压密码") },
                    )
                    if (passwordError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(passwordError!!, style = ZType.caption, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingUri
                        val queue = pendingQueue
                        val mail = pendingMail
                        val pwd = password
                        askPassword = false
                        if (mail != null) {
                            // 记住解压码，然后重收一次；下次同类账单就不用再问
                            SecretBox.put(ctx, "zip_password", pwd)
                            pendingMail = null
                            syncMail()
                        } else if (uri != null) {
                            importFiles(listOf(uri) + queue, pwd)
                        }
                    }
                ) { Text("解压") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askPassword = false
                    pendingUri = null
                    password = ""
                    passwordError = null
                }) { Text("取消") }
            },
            containerColor = Ink.Surface,
        )
    }

    if (showRules) {
        ModalBottomSheet(
            onDismissRequest = { showRules = false },
            containerColor = Ink.Surface,
        ) {
            RulesSheet(store) { refresh() }
        }
    }

    drill?.let { cat ->
        val isWeek = mode == Mode.WEEK
        val cats = if (isWeek) weekSum?.categories else monthSum?.categories
        CategoryDrillSheet(
            category = cat,
            from = if (isWeek) weekStart else month.atDay(1),
            to = if (isWeek) weekStart.plusDays(7) else month.atDay(1).plusMonths(1),
            periodLabel = if (isWeek) "本周" else "本月",
            categoryTotal = cats?.firstOrNull { it.category == cat }?.cents ?: 0L,
            periodTotal = if (isWeek) weekSum?.totalCents ?: 0 else monthSum?.totalCents ?: 0,
            store = store,
            onDismiss = { drill = null },
            onManageRules = {
                drill = null
                showRules = true
            },
        )
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = Ink.Surface,
        ) {
            SettingsSheet(
                onClose = { showSettings = false },
                onSync = {
                    showSettings = false
                    syncMail()
                },
                onChanged = { scope.launch { withContext(Dispatchers.IO) { refresh() } } },
                onWeekStartChanged = { day ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.setWeekStartDay(day) }
                        weekStart = Week.startOf(today, day)
                        withContext(Dispatchers.IO) { refresh() }
                    }
                },
                currentWeekStart = startDay,
            )
        }
    }
}

/** 空状态引导：新用户打开就是一片空白，得先告诉他账单从哪来 */
@Composable
fun Onboarding(modifier: Modifier = Modifier, busy: Boolean, onImport: () -> Unit) {
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("周账", style = ZType.displaySmall, color = Ink.Text)
        Spacer(Modifier.height(10.dp))
        Text(
            "把微信和支付宝的官方账单，变成「每周花了多少、花在哪」。",
            style = ZType.body,
            color = Ink.Text2,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TrustChip("只存本机")
            TrustChip("数据库加密")
            TrustChip("导入可撤销")
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onImport,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink.Text,
                contentColor = Ink.Bg,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (busy) "解析中…" else "选择账单文件", style = ZType.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "CSV / XLSX / 加密 zip · 可一次选多个",
            style = ZType.micro,
            color = Ink.Text3,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("账单从哪来", style = ZType.bodySmall, color = Ink.Text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("两家路径不同", style = ZType.micro, color = Ink.Text3)
        }
        Spacer(Modifier.height(8.dp))

        StepCard(
            platform = "微信",
            steps = listOf(
                "我 → 服务 → 钱包 → 账单",
                "常见问题 → 下载账单 → 用于个人对账",
                "填邮箱和支付密码，收到加密压缩包",
            ),
            note = "解压码由「微信支付」公众号推送。",
            expanded = expanded == "微信",
            onToggle = { expanded = if (expanded == "微信") null else "微信" },
        )
        Spacer(Modifier.height(8.dp))
        StepCard(
            platform = "支付宝",
            steps = listOf(
                "我的 → 账单 → 右上角「…」",
                "开具交易流水证明 → 用于个人对账",
                "填邮箱，收到加密 zip",
            ),
            note = "密码通常是身份证后 6 位或手机号后 6 位。",
            expanded = expanded == "支付宝",
            onToggle = { expanded = if (expanded == "支付宝") null else "支付宝" },
        )
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
fun TrustChip(text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Ink.Sunk)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", style = ZType.micro, color = Ink.Accent)
        Spacer(Modifier.width(4.dp))
        Text(text, style = ZType.micro, color = Ink.Text2)
    }
}

/** 折叠的步骤卡：默认只占一行，点开才展开完整路径 */
@Composable
fun StepCard(
    platform: String,
    steps: List<String>,
    note: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.Surface)
            .border(1.dp, Ink.Border, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(platform, style = ZType.bodySmall, color = Ink.Text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起 ▴" else "展开步骤 ▾",
                style = ZType.micro,
                color = Ink.Text3,
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                HorizontalDivider(color = Ink.Hairline)
                steps.forEachIndexed { i, step ->
                    Row(Modifier.padding(top = 9.dp)) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Ink.Sunk),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = ZType.micro, color = Ink.Text2)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            step,
                            style = ZType.caption,
                            color = Ink.Text2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(note, style = ZType.micro, color = Ink.Text3)
            }
        }
    }
}

/** 分类整理：把未分类商户按金额列出来，点一下归到某类，并记住规则 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesSheet(store: BillStore, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var merchants by remember { mutableStateOf<List<MerchantSum>>(emptyList()) }
    var rules by remember { mutableStateOf<List<CategoryRule>>(emptyList()) }
    var picking by remember { mutableStateOf<MerchantSum?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                merchants = store.uncategorizedMerchants()
                rules = store.rules()
            }
        }
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { reload() } }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text("分类整理", style = ZType.title, fontWeight = FontWeight.Medium, color = Ink.Text)
        Spacer(Modifier.height(4.dp))
        Text(
            "给商户指定一次分类，之后同类交易自动套用。",
            style = ZType.caption,
            color = Ink.Text2,
        )

        if (note != null) {
            Spacer(Modifier.height(10.dp))
            Text(note!!, style = ZType.caption, color = Ink.Accent)
        }

        Spacer(Modifier.height(18.dp))
        Text("待归类", style = ZType.secondary, color = Ink.Text2)
        Spacer(Modifier.height(8.dp))

        if (merchants.isEmpty()) {
            Text("没有未分类的支出了", style = ZType.secondary, color = Ink.Text2)
        } else {
            merchants.forEach { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { picking = m }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = ZType.bodySmall, color = Ink.Text, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text("${m.count} 笔", style = ZType.micro, color = Ink.Text2)
                    }
                    Text(money(m.cents), style = ZType.bodySmall, color = Ink.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("归类 ›", style = ZType.caption, color = Ink.Accent)
                }
                HorizontalDivider(color = Ink.Hairline)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("已生效的规则", style = ZType.secondary, color = Ink.Text2)
        Spacer(Modifier.height(8.dp))

        if (rules.isEmpty()) {
            Text("还没有自定义规则", style = ZType.secondary, color = Ink.Text2)
        } else {
            rules.forEach { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        r.keyword,
                        style = ZType.secondary,
                        color = Ink.Text,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(r.category, style = ZType.secondary, color = Ink.Text2)
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.deleteRule(r.id) }
                                reload()
                                onChanged()
                                note = "已删除规则「${r.keyword}」"
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("删除", style = ZType.caption, color = Ink.Text2)
                    }
                }
                HorizontalDivider(color = Ink.Hairline)
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    picking?.let { m ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("「${m.name}」归到哪一类", style = ZType.body) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CATEGORIES.forEach { c ->
                        Text(
                            c,
                            style = ZType.body,
                            color = Ink.Text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val target = m
                                    picking = null
                                    scope.launch {
                                        val n = withContext(Dispatchers.IO) {
                                            store.addRule(target.name, c)
                                        }
                                        reload()
                                        onChanged()
                                        note = "「${target.name}」→ $c，更新了 $n 笔"
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            },
            containerColor = Ink.Surface,
        )
    }
}

val CATEGORIES = listOf("餐饮", "交通", "购物", "居家", "娱乐", "医疗", "教育", "通讯", "其他")

/** 索引 0 对应周一（LocalDate.dayOfWeek.value = 1） */
val WEEK_DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 设置：周起始日 + 邮箱收单 + 账单解压码 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onClose: () -> Unit,
    onSync: () -> Unit,
    onChanged: () -> Unit,
    onWeekStartChanged: (Int) -> Unit,
    currentWeekStart: Int,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("imap.qq.com") }
    var port by remember { mutableStateOf("993") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var zipPwd by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var configured by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            MailConfig.load(ctx)?.let {
                host = it.host
                port = it.port.toString()
                user = it.user
                password = it.password
                configured = true
            }
            zipPwd = SecretBox.get(ctx, "zip_password") ?: ""
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text("设置", style = ZType.title, fontWeight = FontWeight.Medium, color = Ink.Text)

        Spacer(Modifier.height(18.dp))
        Text("每周从哪天开始", style = ZType.secondary, color = Ink.Text2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEK_DAY_LABELS.forEachIndexed { index, label ->
                val day = index + 1
                val selected = day == currentWeekStart
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) Ink.Text else Ink.Sunk)
                        .clickable { onWeekStartChanged(day) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = ZType.caption,
                        color = if (selected) Ink.Bg else Ink.Text2,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("改完之后历史数据会重新分周。", style = ZType.micro, color = Ink.Text2)

        Spacer(Modifier.height(26.dp))
        Text("邮箱收单", style = ZType.body, fontWeight = FontWeight.Medium, color = Ink.Text)
        Spacer(Modifier.height(4.dp))
        Text(
            "绑定后，账单邮件到了点一下就能导入，不用再翻文件夹。填的是 IMAP 授权码，不是邮箱登录密码。",
            style = ZType.caption,
            lineHeight = 18.sp,
            color = Ink.Text2,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MailPresets.list.forEach { (name, h, p) ->
                AssistChip(
                    onClick = {
                        host = h
                        port = p.toString()
                    },
                    label = { Text(name, style = ZType.micro) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            singleLine = true,
            label = { Text("IMAP 服务器") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            singleLine = true,
            label = { Text("端口") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            singleLine = true,
            label = { Text("邮箱账号") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            label = { Text("授权码") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    working = true
                    status = "连接中…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                ImapClient.test(
                                    MailAccount(host.trim(), port.toIntOrNull() ?: 993,
                                        user.trim(), password)
                                )
                            }
                        }
                        status = result.fold(
                            { "连接成功" },
                            { "连接失败：${it.message ?: it.javaClass.simpleName}" }
                        )
                        working = false
                    }
                },
                enabled = !working && user.isNotBlank() && password.isNotBlank(),
            ) { Text("测试连接", style = ZType.secondary) }

            Button(
                onClick = {
                    MailConfig.save(
                        ctx,
                        MailAccount(host.trim(), port.toIntOrNull() ?: 993, user.trim(), password)
                    )
                    configured = true
                    status = "已保存"
                    onChanged()
                },
                enabled = user.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink.Text,
                    contentColor = Ink.Bg,
                ),
            ) { Text("保存", style = ZType.secondary) }
        }

        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(status!!, style = ZType.caption, color = Ink.Text2)
        }

        if (configured) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSync,
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink.Text,
                    contentColor = Ink.Bg,
                ),
                shape = RoundedCornerShape(12.dp),
            ) { Text("立即收信", style = ZType.titleSmall) }
        }

        Spacer(Modifier.height(24.dp))
        Text("账单解压码", style = ZType.secondary, color = Ink.Text2)
        Spacer(Modifier.height(6.dp))
        Text(
            "支付宝的交易明细 zip 是加密的，存一次就不用每次输入。",
            style = ZType.caption,
            color = Ink.Text2,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = zipPwd,
                onValueChange = { zipPwd = it },
                singleLine = true,
                label = { Text("解压码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = {
                    if (zipPwd.isBlank()) {
                        SecretBox.remove(ctx, "zip_password")
                        status = "已清除解压码"
                    } else {
                        SecretBox.put(ctx, "zip_password", zipPwd)
                        status = "已保存解压码"
                    }
                }
            ) { Text("保存", style = ZType.secondary, color = Ink.Accent) }
        }

        if (configured) {
            Spacer(Modifier.height(22.dp))
            TextButton(
                onClick = {
                    MailConfig.clear(ctx)
                    configured = false
                    password = ""
                    status = "已解除绑定"
                    onChanged()
                }
            ) { Text("解除邮箱绑定", style = ZType.secondary, color = Ink.Text2) }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ZHeader(
    title: String,
    subtitle: String?,
    showNav: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    busy: Boolean,
    showImport: Boolean,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNav) {
            IconButton(onClick = onPrev, enabled = canPrev, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.ChevronLeft,
                    contentDescription = "上一期",
                    tint = if (canPrev) Ink.Text else Ink.Hairline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ZType.titleSmall, color = Ink.Text, maxLines = 1)
            // 滚出首屏后，周期与金额挂在这里，不占额外高度
            if (subtitle != null) {
                Text(subtitle, style = ZType.caption, color = Ink.Text3, maxLines = 1)
            }
        }
        if (showNav) {
            IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = "下一期",
                    tint = if (canNext) Ink.Text else Ink.Hairline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showImport) {
            TextButton(
                onClick = onImport,
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    if (busy) "解析中" else "导入",
                    style = ZType.secondary,
                    color = if (busy) Ink.Text3 else Ink.Accent,
                )
            }
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = "设置",
                tint = Ink.Text2,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
fun ModeSwitch(mode: Mode, onChange: (Mode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.Sunk)
            .padding(3.dp)
    ) {
        Mode.entries.forEach { m ->
            val selected = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Ink.Text else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label,
                    style = ZType.secondary,
                    color = if (selected) Ink.Bg else Ink.Text2,
                )
            }
        }
    }
}

/**
 * 金额。整屏唯一的视觉重心。
 *
 * ¥ 比数字小一档并共用基线——符号是单位，不该和数值抢注意力；
 * 数字走 ZType.display（内含 tnum），左右翻周时不会横向抖动。
 * 环比单独成 chip 而不是拼进字符串，涨跌才能上色。
 */
@Composable
fun AmountBlock(
    total: Long,
    meta: String,
    deltaPct: Int? = null,
    deltaUp: Boolean = true,
    deltaLabel: String = "比上周同期",
) {
    // 240ms 计数。翻周时让「数字变了」可感知，而不是整屏硬切
    val shown by animateIntAsState(
        targetValue = total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        animationSpec = tween(240),
        label = "amount",
    )
    Column {
        Row {
            Text(
                "¥",
                style = ZType.secondary,
                color = Ink.Text3,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                amountDigits(shown.toLong()),
                style = ZType.display,
                color = Ink.Text,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (deltaPct != null) {
                DeltaChip(deltaPct, deltaUp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (deltaPct != null) "$deltaLabel · $meta" else meta,
                style = ZType.caption,
                color = Ink.Text2,
            )
        }
    }
}

/** 涨红跌绿。中文语境的肌肉记忆，不需要额外解释 */
@Composable
fun DeltaChip(pct: Int, up: Boolean) {
    val fg = if (up) Ink.Up else Ink.Down
    val bg = if (up) Ink.UpBg else Ink.DownBg
    Row(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (up) "↑" else "↓", style = ZType.micro, color = fg)
        Spacer(Modifier.width(2.dp))
        Text("$pct%", style = ZType.micro, color = fg)
    }
}

/** 区块标题。「待归类」角标只在这里出现，清零即消失 */
@Composable
fun SectionHeader(
    title: String,
    pending: Int,
    onManage: () -> Unit,
    showManage: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = showManage, onClick = onManage)
            .padding(top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = ZType.bodySmall, color = Ink.Text, fontWeight = FontWeight.SemiBold)
        if (pending > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$pending 个待归类",
                style = ZType.micro,
                color = Ink.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Ink.Sunk)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (showManage) {
            Text("管理 ›", style = ZType.micro, color = Ink.Text3)
        }
    }
}

@Composable
fun DetailHeader(dayFilter: LocalDate?, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("明细", style = ZType.bodySmall, color = Ink.Text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (dayFilter != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Ink.Sunk)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "只看 ${dayFilter.monthValue}/${dayFilter.dayOfMonth}",
                    style = ZType.micro,
                    color = Ink.Text2,
                )
                Spacer(Modifier.width(4.dp))
                Text("✕", style = ZType.micro, color = Ink.Text3)
            }
        }
    }
}

/** 日组头：这一天花了多少，让长列表可以跳读 */
@Composable
fun DayHeader(date: LocalDate, list: List<Bill>) {
    val total = list
        .filter { it.flowType == FlowType.CONSUMPTION && it.direction == Direction.EXPENSE }
        .sumOf { it.amountCents }
    val name = if (date == LocalDate.now()) "今天" else "周${weekdayLabel(date)}"
    Column {
        HorizontalDivider(color = Ink.Hairline, modifier = Modifier.padding(top = 12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$name ${date.monthValue}/${date.dayOfMonth}", style = ZType.caption, color = Ink.Text3)
            Spacer(Modifier.weight(1f))
            Text(money(total), style = ZType.caption, color = Ink.Text3)
        }
    }
}

@Composable
fun MiniBadge(text: String) {
    Text(
        text,
        style = ZType.micro,
        color = Ink.Text2,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Ink.Sunk)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun ChartCard(
    days: List<DaySum>,
    start: LocalDate,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    val peak = days.maxByOrNull { it.cents }
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("每日支出", style = ZType.caption, color = Ink.Text3)
                if (peak != null && peak.cents > 0) {
                    Text(
                        "周${weekdayLabel(peak.date)}最高 ${money(peak.cents)}",
                        style = ZType.micro,
                        color = Ink.Text3,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            DayBars(days, start, selected, onSelect)
        }
    }
}

/**
 * 七天柱状图。柱子可点——点某天筛下面明细，再点一次取消。
 * 未来日期画成淡柱且文字半透明，不再和「零消费」长得一样。
 */
@Composable
fun DayBars(
    days: List<DaySum>,
    start: LocalDate,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    val max = (days.maxOfOrNull { it.cents } ?: 0L).coerceAtLeast(1L)
    val today = LocalDate.now()
    Row(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (0..6).forEach { i ->
            val d = start.plusDays(i.toLong())
            val cents = days.firstOrNull { it.date == d }?.cents ?: 0L
            val future = d.isAfter(today)
            val ratio = (cents.toFloat() / max).coerceIn(0f, 1f)
            val h by animateFloatAsState(
                targetValue = if (cents == 0L || future) 3f else (ratio * 82f).coerceAtLeast(8f),
                animationSpec = tween(320),
                label = "dayBar",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(if (selected == d) null else d) },
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(h.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            when {
                                d == today -> Ink.AccentFill
                                future -> Ink.Hairline.copy(alpha = 0.45f)
                                cents == 0L -> Ink.BarIdle.copy(alpha = 0.55f)
                                selected == d -> Ink.Text
                                else -> Ink.BarIdle
                            }
                        )
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    weekdayLabel(d),
                    style = ZType.micro,
                    color = when {
                        d == today -> Ink.Accent
                        future -> Ink.Text3.copy(alpha = 0.5f)
                        else -> Ink.Text2
                    },
                )
            }
        }
    }
}

@Composable
fun CategoryRow(name: String, cents: Long, total: Long, onClick: () -> Unit) {
    val pct = if (total > 0) (cents * 100.0 / total).toInt() else 0
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = ZType.bodySmall, color = Ink.Text)
            Row {
                Text(money(cents), style = ZType.bodySmall, color = Ink.Text)
                Spacer(Modifier.width(10.dp))
                Text(
                    "$pct%",
                    style = ZType.caption,
                    color = Ink.Text3,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ink.Sunk)
        ) {
            Box(
                Modifier
                    .fillMaxWidth((pct.coerceIn(0, 100)) / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.AccentFill)
            )
        }
    }
}

@Composable
fun MerchantRow(rank: Int, m: CategorySum) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank", style = ZType.caption, color = Ink.Text3,
            modifier = Modifier.width(20.dp),
        )
        Text(
            m.category,
            style = ZType.bodySmall,
            color = Ink.Text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(money(m.cents), style = ZType.bodySmall, color = Ink.Text)
    }
}

@Composable
fun BillRow(b: Bill) {
    val isConsumption = b.flowType == FlowType.CONSUMPTION && b.direction == Direction.EXPENSE
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                b.counterparty.ifBlank { b.item.ifBlank { "—" } },
                style = ZType.bodySmall,
                color = if (isConsumption) Ink.Text else Ink.Text2,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 只有「非消费」才需要徽章；收入由「+」表达，不必再贴标签
                if (b.flowType != FlowType.CONSUMPTION) {
                    MiniBadge(b.flowType.label)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    buildString {
                        append("%02d:%02d".format(b.txnTime.hour, b.txnTime.minute))
                        if (isConsumption) append(" · ").append(b.category)
                        else if (b.direction == Direction.INCOME) append(" · 收入")
                    },
                    style = ZType.caption,
                    color = Ink.Text3,
                    maxLines = 1,
                )
            }
        }
        Text(
            (if (b.direction == Direction.INCOME) "+" else "") + money(b.amountCents),
            style = ZType.bodySmall,
            color = when {
                b.direction == Direction.INCOME -> Ink.Down
                isConsumption -> Ink.Text
                else -> Ink.Text3
            },
        )
    }
}

/** 12 周趋势。点柱子直接跳到那一周 */
@Composable
fun TrendCard(trend: List<Pair<LocalDate, Long>>, onPick: (LocalDate) -> Unit) {
    val max = (trend.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val mean = if (trend.isEmpty()) 0L else trend.sumOf { it.second } / trend.size
    val currentWeek = trend.lastOrNull()?.first
    val lineColor = Ink.Hairline
    val barColor = Ink.BarIdle
    val accent = Ink.AccentFill
    val labelColor = Ink.Text3

    Card(
        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("每周支出", style = ZType.caption, color = labelColor)
                Text("周均 ${money(mean)}", style = ZType.micro, color = labelColor)
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    trend.forEach { (w, cents) ->
                        val ratio = (cents.toFloat() / max).coerceIn(0f, 1f)
                        val barH by animateFloatAsState(
                            targetValue = (ratio * 128f).coerceAtLeast(2f),
                            animationSpec = tween(320),
                            label = "trendBar",
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onPick(w) },
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.72f)
                                    .height(barH.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (w == currentWeek) accent else barColor)
                            )
                        }
                    }
                }
                if (mean > 0) {
                    Canvas(Modifier.matchParentSize()) {
                        val y = size.height * (1f - mean.toFloat() / max)
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    trend.firstOrNull()?.first?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "",
                    style = ZType.micro,
                    color = labelColor,
                )
                Text("最近 12 周", style = ZType.micro, color = labelColor)
                Text("本周", style = ZType.micro, color = accent)
            }
        }
    }
}

@Composable
fun TrendStats(trend: List<Pair<LocalDate, Long>>) {
    if (trend.isEmpty()) return
    val maxEntry = trend.maxByOrNull { it.second } ?: return
    val minEntry = trend.filter { it.second > 0 }.minByOrNull { it.second }
    val mean = trend.sumOf { it.second } / trend.size
    val above = trend.count { it.second > mean }

    Card(
        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell("最高一周", money(maxEntry.second), weekRange(maxEntry.first), Modifier.weight(1f))
            VerticalDivider(color = Ink.Hairline, modifier = Modifier.height(34.dp))
            StatCell(
                "最低一周",
                minEntry?.let { money(it.second) } ?: "—",
                minEntry?.let { weekRange(it.first) } ?: "",
                Modifier.weight(1f),
            )
            VerticalDivider(color = Ink.Hairline, modifier = Modifier.height(34.dp))
            StatCell("高于均值", "$above 周", "共 ${trend.size} 周", Modifier.weight(1f))
        }
    }
}

@Composable
fun StatCell(key: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 14.dp)) {
        Text(key, style = ZType.micro, color = Ink.Text3)
        Spacer(Modifier.height(2.dp))
        Text(value, style = ZType.bodySmall, color = Ink.Text, fontWeight = FontWeight.SemiBold)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(1.dp))
            Text(sub, style = ZType.micro, color = Ink.Text3)
        }
    }
}

fun weekRange(start: LocalDate): String {
    val end = start.plusDays(6)
    return "${start.monthValue}/${start.dayOfMonth} – ${end.monthValue}/${end.dayOfMonth}"
}

/** 分类下钻：这个分类这周/这月的每一笔都在这里，包括退款 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDrillSheet(
    category: String,
    from: LocalDate,
    to: LocalDate,
    periodLabel: String,
    categoryTotal: Long,
    periodTotal: Long,
    store: BillStore,
    onDismiss: () -> Unit,
    onManageRules: () -> Unit,
) {
    var rows by remember { mutableStateOf<List<Bill>>(emptyList()) }
    LaunchedEffect(category, from, to) {
        rows = withContext(Dispatchers.IO) {
            store.billsOfRange(from, to).filter { it.category == category }
        }
    }
    val pct = if (periodTotal > 0) (categoryTotal * 100.0 / periodTotal).toInt() else 0

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(category, style = ZType.title, color = Ink.Text)
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    "¥",
                    style = ZType.secondary,
                    color = Ink.Text3,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    amountDigits(categoryTotal),
                    style = ZType.displaySmall,
                    color = Ink.Text,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "$periodLabel · ${rows.size} 笔 · 占 $pct%",
                style = ZType.caption,
                color = Ink.Text2,
            )
            Spacer(Modifier.height(10.dp))

            rows.forEach { b ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            b.counterparty.ifBlank { b.item.ifBlank { "—" } },
                            style = ZType.bodySmall,
                            color = Ink.Text,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${b.txnTime.monthValue}/${b.txnTime.dayOfMonth} " +
                                    "%02d:%02d".format(b.txnTime.hour, b.txnTime.minute) +
                                    " · " + b.flowType.label,
                            style = ZType.caption,
                            color = Ink.Text3,
                            maxLines = 1,
                        )
                    }
                    Text(
                        money(b.amountCents),
                        style = ZType.bodySmall,
                        color = if (b.flowType == FlowType.CONSUMPTION) Ink.Text else Ink.Text3,
                    )
                }
                HorizontalDivider(color = Ink.Hairline)
            }

            if (rows.isEmpty()) {
                Text("这个分类没有可列的明细。", style = ZType.secondary, color = Ink.Text3)
            }

            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onManageRules, contentPadding = PaddingValues(0.dp)) {
                Text("管理分类规则 ›", style = ZType.secondary, color = Ink.Accent)
            }
        }
    }
}

/** 一次性提示：刚导完才出现，可撤销、也可直接关掉，不常驻首屏 */
@Composable
fun UndoStrip(count: Int, onUndo: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Ink.Sunk)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("上次导入新增 $count 条", style = ZType.caption, color = Ink.Text2)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("撤销", style = ZType.secondary, color = Ink.Accent)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "关闭提示",
                tint = Ink.Text3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 空周不该只画一张全零的图。给出去处，比给一个「没有数据」有用 */
@Composable
fun EmptyWeekCard(lastWeek: LocalDate?, onJump: (LocalDate) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Ink.Sunk),
                contentAlignment = Alignment.Center,
            ) {
                Text("↓", style = ZType.body, color = Ink.Text3)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "导入这周的账单后，\n这里会按天显示支出和明细。",
                style = ZType.secondary,
                color = Ink.Text2,
                textAlign = TextAlign.Center,
            )
            if (lastWeek != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "上次有记录：${Week.rangeLabel(lastWeek)} ›",
                    style = ZType.caption,
                    color = Ink.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onJump(lastWeek) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 只负责把文件原样读出来。解压、解码、格式识别全部交给 Parser。 */
private fun readBytes(ctx: Context, uri: Uri): Pair<ByteArray, String> {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    } ?: "账单"

    val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IOException("读不到这个文件")

    return raw to name
}
