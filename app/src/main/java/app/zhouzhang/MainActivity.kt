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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

enum class Mode(val label: String) { WEEK("周"), MONTH("月") }

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

    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var pendingQueue by remember { mutableStateOf<List<Uri>>(emptyList()) }

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
        earliest = store.earliestDate()
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

    LaunchedEffect(weekStart, month) { withContext(Dispatchers.IO) { refresh() } }

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
            return
        }
        busy = true
        scope.launch {
            try {
                val attachments = withContext(Dispatchers.IO) { ImapClient.fetchBills(account) }
                if (attachments.isEmpty()) {
                    message = "最近 45 天没有找到账单邮件"
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
        }
    }

    Scaffold(
        containerColor = Ink.Bg,
        floatingActionButton = {
            if (hasData) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(mimeTypes) },
                    containerColor = Ink.Text,
                    contentColor = Ink.Bg,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(if (busy) "解析中…" else "导入账单") },
                )
            }
        }
    ) { pad ->
        if (!hasData) {
            Onboarding(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                busy = busy,
                onImport = { picker.launch(mimeTypes) },
            )
        } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 96.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeSwitch(mode) { mode = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showRules = true }) {
                            Text("分类整理", style = ZType.secondary, color = Ink.Text2)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, "设置", tint = Ink.Text2)
                        }
                    }
                }
                if (undoCount > 0) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("上次导入新增 $undoCount 条", style = ZType.caption, color = Ink.Text2)
                        TextButton(onClick = { doUndo() }) {
                            Text("撤销", style = ZType.secondary, color = Ink.Accent)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            if (mode == Mode.WEEK) {
                item {
                    PeriodBar(
                        label = Week.rangeLabel(weekStart),
                        canPrev = earliest == null || weekStart.minusWeeks(1).plusDays(6) >= earliest,
                        canNext = weekStart.isBefore(thisWeek),
                        onPrev = { weekStart = weekStart.minusWeeks(1) },
                        onNext = { weekStart = weekStart.plusWeeks(1) },
                    )
                    Spacer(Modifier.height(10.dp))
                    val weekTotal = weekSum?.totalCents ?: 0
                    val weekCount = weekSum?.count ?: 0
                    val weekDelta = if (prevSame > 0 && weekTotal > 0) {
                        (weekTotal - prevSame) * 100.0 / prevSame
                    } else {
                        null
                    }
                    AmountBlock(
                        total = weekTotal,
                        meta = if (weekCount > 0) "$weekCount 笔消费" else "这周没有记录",
                        deltaPct = weekDelta?.let { kotlin.math.abs(it).toInt() },
                        deltaUp = (weekDelta ?: 0.0) >= 0,
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Ink.Border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            WeekBars(weekSum?.days ?: emptyList(), weekStart)
                            Spacer(Modifier.height(18.dp))
                            HorizontalDivider(color = Ink.Hairline)
                            Spacer(Modifier.height(14.dp))
                            val cats = weekSum?.categories.orEmpty()
                            if (cats.isEmpty()) {
                                Text("这周还没有消费记录", style = ZType.secondary, color = Ink.Text2)
                            } else {
                                cats.take(6).forEach { c ->
                                    CategoryRow(c.category, c.cents, weekSum!!.totalCents)
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(26.dp)) }

                item {
                    Text("明细", style = ZType.secondary, color = Ink.Text2)
                    Spacer(Modifier.height(10.dp))
                }

                if (bills.isEmpty()) {
                    item {
                        Text(
                            "点右下角导入微信或支付宝导出的账单。\n" +
                                    "支持 CSV、XLSX，以及带密码的 zip。",
                            style = ZType.secondary,
                            color = Ink.Text2,
                            lineHeight = 20.sp,
                        )
                    }
                } else {
                    items(bills) { b -> BillRow(b) }
                }
            } else {
                item {
                    PeriodBar(
                        label = "${month.year} 年 ${month.monthValue} 月",
                        canPrev = earliest == null ||
                                !YearMonth.from(earliest).isBefore(month.minusMonths(1)),
                        canNext = month.isBefore(thisMonth),
                        onPrev = { month = month.minusMonths(1) },
                        onNext = { month = month.plusMonths(1) },
                        prevLabel = "上个月",
                        nextLabel = "下个月",
                    )
                    Spacer(Modifier.height(10.dp))
                    val total = monthSum?.totalCents ?: 0
                    val cnt = monthSum?.count ?: 0
                    // 当月只过了几天就只除以几天，否则日均会被低估
                    val dayCount = if (month == thisMonth) today.dayOfMonth else month.lengthOfMonth()
                    val prevMonth = monthSum?.prevTotalCents ?: 0
                    val monthDelta = if (prevMonth > 0 && total > 0) {
                        (total - prevMonth) * 100.0 / prevMonth
                    } else {
                        null
                    }
                    AmountBlock(
                        total = total,
                        meta = if (cnt > 0) "$cnt 笔 · 日均 ${money(total / dayCount)}" else "本月没有记录",
                        deltaPct = monthDelta?.let { kotlin.math.abs(it).toInt() },
                        deltaUp = (monthDelta ?: 0.0) >= 0,
                        deltaLabel = "比上月",
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Ink.Border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("分类占比", style = ZType.secondary, color = Ink.Text2)
                            Spacer(Modifier.height(10.dp))
                            val cats = monthSum?.categories.orEmpty()
                            if (cats.isEmpty()) {
                                Text("本月还没有消费记录", style = ZType.secondary, color = Ink.Text2)
                            } else {
                                cats.forEach { c ->
                                    CategoryRow(c.category, c.cents, monthSum!!.totalCents)
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }

                item {
                    val merchants = monthSum?.merchants.orEmpty()
                    if (merchants.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Ink.Surface),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Ink.Border),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text("花得最多的商户", style = ZType.secondary, color = Ink.Text2)
                                Spacer(Modifier.height(6.dp))
                                merchants.forEachIndexed { i, m ->
                                    MerchantRow(i + 1, m)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
            title = { Text("导入结果", style = ZType.title) },
            text = { Text(message!!, style = ZType.bodySmall, lineHeight = 21.sp) },
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
                        "支付宝的交易明细压缩包是加密的。密码通常是身份证后 6 位或手机号后 6 位。",
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
    Column(
        modifier
            .padding(horizontal = 28.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("周账", style = ZType.displaySmall, color = Ink.Text)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入微信和支付宝的官方账单，看每周花了多少、花在哪。数据只存在这台手机上。",
            style = ZType.bodySmall,
            lineHeight = 22.sp,
            color = Ink.Text2,
        )

        Spacer(Modifier.height(34.dp))
        Text("账单从哪来", style = ZType.secondary, color = Ink.Text2)
        Spacer(Modifier.height(12.dp))

        StepCard(
            "微信",
            "我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账，填邮箱和支付密码。",
            "邮件里是加密压缩包，解压码由「微信支付」公众号推送。",
        )
        Spacer(Modifier.height(12.dp))
        StepCard(
            "支付宝",
            "我的 → 账单 → 右上角「…」→ 开具交易流水证明 → 用于个人对账，发到邮箱。",
            "邮件里是加密 zip，密码通常是身份证后 6 位或手机号后 6 位。",
        )

        Spacer(Modifier.height(30.dp))
        Button(
            onClick = onImport,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink.Text,
                contentColor = Ink.Bg,
            ),
            shape = RoundedCornerShape(13.dp),
        ) {
            Text(if (busy) "解析中…" else "导入账单", style = ZType.body)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "支持 CSV、XLSX 和加密 zip，可以一次选多个文件。",
            style = ZType.caption,
            color = Ink.Text2,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun StepCard(platform: String, path: String, note: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(platform, style = ZType.bodySmall, fontWeight = FontWeight.Medium, color = Ink.Text)
            Spacer(Modifier.height(6.dp))
            Text(path, style = ZType.secondary, lineHeight = 20.sp, color = Ink.Text)
            Spacer(Modifier.height(6.dp))
            Text(note, style = ZType.caption, lineHeight = 18.sp, color = Ink.Text2)
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
fun ModeSwitch(mode: Mode, onChange: (Mode) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Ink.Sunk)
            .padding(3.dp)
    ) {
        Mode.entries.forEach { m ->
            val selected = m == mode
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Ink.Text else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
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

@Composable
fun PeriodBar(
    label: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    prevLabel: String = "上一周",
    nextLabel: String = "下一周",
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowButton(Icons.Rounded.ChevronLeft, canPrev, onPrev, prevLabel)
        Text(label, style = ZType.bodySmall, color = Ink.Text2)
        ArrowButton(Icons.Rounded.ChevronRight, canNext, onNext, nextLabel)
    }
}

@Composable
fun ArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Ink.Text else Ink.Hairline,
        )
    }
}

/**
 * 本周金额。整屏唯一的视觉重心。
 *
 * ¥ 比数字小一档并基线对齐——符号是单位，不该和数值抢注意力；
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
    Column {
        Row {
            // 符号与数字共用一条基线，比手工调 padding 稳
            Text(
                "¥",
                style = ZType.secondary,
                color = Ink.Text3,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                amountDigits(total),
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

@Composable
fun MerchantRow(rank: Int, m: CategorySum) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank", style = ZType.caption, color = Ink.Text2,
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
fun WeekBars(days: List<DaySum>, start: LocalDate) {
    val max = (days.maxOfOrNull { it.cents } ?: 0L).coerceAtLeast(1L)
    val today = LocalDate.now()
    Row(
        Modifier
            .fillMaxWidth()
            .height(118.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0..6).forEach { i ->
            val d = start.plusDays(i.toLong())
            val cents = days.firstOrNull { it.date == d }?.cents ?: 0L
            val ratio = (cents.toFloat() / max).coerceIn(0f, 1f)
            val h = if (cents == 0L) 3f else (ratio * 84f).coerceAtLeast(8f)
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .height(h.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                d == today -> Ink.AccentFill
                                cents == 0L -> Ink.Hairline
                                else -> Ink.BarIdle
                            }
                        )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    weekdayLabel(d),
                    style = ZType.micro,
                    color = if (d == today) Ink.Accent else Ink.Text2,
                )
            }
        }
    }
}

@Composable
fun CategoryRow(name: String, cents: Long, total: Long) {
    val pct = if (total > 0) (cents * 100.0 / total).toInt() else 0
    Column(Modifier.padding(vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = ZType.bodySmall, color = Ink.Text)
            Row {
                Text(money(cents), style = ZType.bodySmall, color = Ink.Text)
                Spacer(Modifier.width(10.dp))
                Text(
                    "$pct%", style = ZType.secondary, color = Ink.Text2,
                    modifier = Modifier.width(38.dp), textAlign = TextAlign.End
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ink.Hairline)
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
fun BillRow(b: Bill) {
    val isConsumption = b.flowType == FlowType.CONSUMPTION && b.direction == Direction.EXPENSE
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
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
            Text(
                "%s %s · %s".format(
                    weekdayLabel(b.txnTime.toLocalDate()),
                    "%02d:%02d".format(b.txnTime.hour, b.txnTime.minute),
                    if (isConsumption) b.category else b.flowType.label,
                ),
                style = ZType.caption,
                color = Ink.Text2,
            )
        }
        Text(
            (if (b.direction == Direction.INCOME) "+" else "") + money(b.amountCents),
            style = ZType.bodySmall,
            color = when {
                b.direction == Direction.INCOME -> Ink.Accent
                isConsumption -> Ink.Text
                else -> Ink.Text2
            },
        )
    }
    HorizontalDivider(color = Ink.Hairline)
}

/**
 * 只负责把文件原样读出来。解压、解码、格式识别全部交给 Parser。
 *
 * 选择器不限制 MIME 类型，用户完全可能误选一个几百 MB 的视频。整份读进内存会
 * 直接 OOM，所以按字节数硬限，超了就报错而不是崩掉。
 */
private fun readBytes(ctx: Context, uri: Uri): Pair<ByteArray, String> {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    } ?: "账单"

    val raw = ctx.contentResolver.openInputStream(uri)?.use {
        it.readCapped(Limits.MAX_INPUT_BYTES)
    } ?: throw IOException("读不到这个文件")

    return raw to name
}
