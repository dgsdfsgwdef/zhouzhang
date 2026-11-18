package app.zhouzhang

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zhouzhang.core.Bill
import app.zhouzhang.core.Direction
import app.zhouzhang.core.FlowType
import app.zhouzhang.core.Parser
import app.zhouzhang.core.Week
import app.zhouzhang.data.BillStore
import app.zhouzhang.data.CategoryRule
import app.zhouzhang.data.CategorySum
import app.zhouzhang.data.DaySum
import app.zhouzhang.data.MerchantSum
import app.zhouzhang.data.MonthSummary
import app.zhouzhang.data.WeekSummary
import app.zhouzhang.ui.Ink
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
        setContent {
            ZhouTheme { HomeScreen(store) }
        }
    }
}

enum class Mode(val label: String) { WEEK("周"), MONTH("月") }

fun money(cents: Long): String {
    val neg = cents < 0
    val abs = if (neg) -cents else cents
    return (if (neg) "-" else "") + "¥" + "%,d.%02d".format(abs / 100, abs % 100)
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
    val thisWeek = remember { Week.startOf(today) }
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

    fun refresh() {
        weekSum = store.summary(weekStart)
        bills = store.billsOfWeek(weekStart)
        monthSum = store.monthSummary(month)
        earliest = store.earliestDate()
        hasData = store.totalCount() > 0
        undoCount = store.pendingUndoCount()

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
                    contentColor = Color.White,
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
                    TextButton(onClick = { showRules = true }) {
                        Text("分类整理", fontSize = 13.sp, color = Ink.Muted)
                    }
                }
                if (undoCount > 0) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("上次导入新增 $undoCount 条", fontSize = 12.sp, color = Ink.Muted)
                        TextButton(onClick = { doUndo() }) {
                            Text("撤销", fontSize = 13.sp, color = Ink.Amber)
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
                    AmountBlock(
                        total = weekSum?.totalCents ?: 0,
                        subtitle = buildString {
                            val c = weekSum?.count ?: 0
                            append(if (c > 0) "$c 笔消费" else "这周没有记录")
                            if (prevSame > 0 && (weekSum?.totalCents ?: 0) > 0) {
                                val diff = (weekSum!!.totalCents - prevSame) * 100.0 / prevSame
                                append(" · 比上周同期 ")
                                append(if (diff >= 0) "↑" else "↓")
                                append("%.0f%%".format(kotlin.math.abs(diff)))
                            }
                        },
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            WeekBars(weekSum?.days ?: emptyList(), weekStart)
                            Spacer(Modifier.height(18.dp))
                            HorizontalDivider(color = Ink.Divider)
                            Spacer(Modifier.height(14.dp))
                            val cats = weekSum?.categories.orEmpty()
                            if (cats.isEmpty()) {
                                Text("这周还没有消费记录", fontSize = 13.sp, color = Ink.Muted)
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
                    Text("明细", fontSize = 13.sp, color = Ink.Muted)
                    Spacer(Modifier.height(10.dp))
                }

                if (bills.isEmpty()) {
                    item {
                        Text(
                            "点右下角导入微信或支付宝导出的账单。\n" +
                                    "支持 CSV、XLSX，以及带密码的 zip。",
                            fontSize = 13.sp,
                            color = Ink.Muted,
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
                    )
                    Spacer(Modifier.height(10.dp))
                    val total = monthSum?.totalCents ?: 0
                    val cnt = monthSum?.count ?: 0
                    // 当月只过了几天就只除以几天，否则日均会被低估
                    val dayCount = if (month == thisMonth) today.dayOfMonth else month.lengthOfMonth()
                    AmountBlock(
                        total = total,
                        subtitle = buildString {
                            append(if (cnt > 0) "$cnt 笔消费" else "本月没有记录")
                            if (cnt > 0) append(" · 日均 ").append(money(total / dayCount))
                            val prev = monthSum?.prevTotalCents ?: 0
                            if (prev > 0 && total > 0) {
                                val diff = (total - prev) * 100.0 / prev
                                append(" · 比上月 ")
                                append(if (diff >= 0) "↑" else "↓")
                                append("%.0f%%".format(kotlin.math.abs(diff)))
                            }
                        },
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("分类占比", fontSize = 13.sp, color = Ink.Muted)
                            Spacer(Modifier.height(10.dp))
                            val cats = monthSum?.categories.orEmpty()
                            if (cats.isEmpty()) {
                                Text("本月还没有消费记录", fontSize = 13.sp, color = Ink.Muted)
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
                            shape = RoundedCornerShape(18.dp),
                            elevation = CardDefaults.cardElevation(0.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text("花得最多的商户", fontSize = 13.sp, color = Ink.Muted)
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
            title = { Text("导入结果", fontSize = 16.sp) },
            text = { Text(message!!, fontSize = 14.sp, lineHeight = 21.sp) },
            containerColor = Ink.Surface,
        )
    }

    if (askPassword) {
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("需要解压密码", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "支付宝的交易明细压缩包是加密的。密码通常是身份证后 6 位或手机号后 6 位。",
                        fontSize = 13.sp,
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
                        Text(passwordError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingUri
                        val queue = pendingQueue
                        askPassword = false
                        if (uri != null) importFiles(listOf(uri) + queue, password)
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
        Text("周账", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Ink.Text)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入微信和支付宝的官方账单，看每周花了多少、花在哪。数据只存在这台手机上。",
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = Ink.Muted,
        )

        Spacer(Modifier.height(34.dp))
        Text("账单从哪来", fontSize = 13.sp, color = Ink.Muted)
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
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(13.dp),
        ) {
            Text(if (busy) "解析中…" else "导入账单", fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "支持 CSV、XLSX 和加密 zip，可以一次选多个文件。",
            fontSize = 12.sp,
            color = Ink.Muted,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun StepCard(platform: String, path: String, note: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink.Surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(platform, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.Text)
            Spacer(Modifier.height(6.dp))
            Text(path, fontSize = 13.sp, lineHeight = 20.sp, color = Ink.Text)
            Spacer(Modifier.height(6.dp))
            Text(note, fontSize = 12.sp, lineHeight = 18.sp, color = Ink.Muted)
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
        Text("分类整理", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink.Text)
        Spacer(Modifier.height(4.dp))
        Text(
            "给商户指定一次分类，之后同类交易自动套用。",
            fontSize = 12.sp,
            color = Ink.Muted,
        )

        if (note != null) {
            Spacer(Modifier.height(10.dp))
            Text(note!!, fontSize = 12.sp, color = Ink.Amber)
        }

        Spacer(Modifier.height(18.dp))
        Text("待归类", fontSize = 13.sp, color = Ink.Muted)
        Spacer(Modifier.height(8.dp))

        if (merchants.isEmpty()) {
            Text("没有未分类的支出了", fontSize = 13.sp, color = Ink.Muted)
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
                        Text(m.name, fontSize = 14.sp, color = Ink.Text, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text("${m.count} 笔", fontSize = 11.sp, color = Ink.Muted)
                    }
                    Text(money(m.cents), fontSize = 14.sp, color = Ink.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("归类 ›", fontSize = 12.sp, color = Ink.Amber)
                }
                HorizontalDivider(color = Ink.Divider)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("已生效的规则", fontSize = 13.sp, color = Ink.Muted)
        Spacer(Modifier.height(8.dp))

        if (rules.isEmpty()) {
            Text("还没有自定义规则", fontSize = 13.sp, color = Ink.Muted)
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
                        fontSize = 13.sp,
                        color = Ink.Text,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(r.category, fontSize = 13.sp, color = Ink.Muted)
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
                        Text("删除", fontSize = 12.sp, color = Ink.Muted)
                    }
                }
                HorizontalDivider(color = Ink.Divider)
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    picking?.let { m ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("「${m.name}」归到哪一类", fontSize = 15.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CATEGORIES.forEach { c ->
                        Text(
                            c,
                            fontSize = 15.sp,
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

@Composable
fun ModeSwitch(mode: Mode, onChange: (Mode) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Ink.Surface)
            .padding(3.dp)
    ) {
        Mode.entries.forEach { m ->
            val selected = m == mode
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Ink.Text else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(horizontal = 20.dp, vertical = 7.dp)
            ) {
                Text(
                    m.label,
                    fontSize = 13.sp,
                    color = if (selected) Color.White else Ink.Muted,
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
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowButton(Icons.Rounded.ChevronLeft, canPrev, onPrev)
        Text(label, fontSize = 14.sp, color = Ink.Muted)
        ArrowButton(Icons.Rounded.ChevronRight, canNext, onNext)
    }
}

@Composable
fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) Ink.Text else Ink.Divider,
        )
    }
}

@Composable
fun AmountBlock(total: Long, subtitle: String) {
    Column {
        Text(
            money(total),
            fontSize = 42.sp,
            fontWeight = FontWeight.Medium,
            color = Ink.Text,
        )
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 13.sp, color = Ink.Muted)
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
            "$rank", fontSize = 12.sp, color = Ink.Muted,
            modifier = Modifier.width(20.dp),
        )
        Text(
            m.category,
            fontSize = 14.sp,
            color = Ink.Text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(money(m.cents), fontSize = 14.sp, color = Ink.Text)
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
            val h = if (cents == 0L) 3f else (ratio * 92f).coerceAtLeast(8f)
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
                                d == today -> Ink.Amber
                                cents == 0L -> Ink.Divider
                                else -> Ink.BarIdle
                            }
                        )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    weekdayLabel(d),
                    fontSize = 11.sp,
                    color = if (d == today) Ink.Amber else Ink.Muted,
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
            Text(name, fontSize = 14.sp, color = Ink.Text)
            Row {
                Text(money(cents), fontSize = 14.sp, color = Ink.Text)
                Spacer(Modifier.width(10.dp))
                Text(
                    "$pct%", fontSize = 13.sp, color = Ink.Muted,
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
                .background(Ink.Divider)
        ) {
            Box(
                Modifier
                    .fillMaxWidth((pct.coerceIn(0, 100)) / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.AmberSoft)
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
                fontSize = 14.sp,
                color = if (isConsumption) Ink.Text else Ink.Muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "%s %s · %s".format(
                    weekdayLabel(b.txnTime.toLocalDate()),
                    "%02d:%02d".format(b.txnTime.hour, b.txnTime.minute),
                    if (isConsumption) b.category else b.flowType.label,
                ),
                fontSize = 12.sp,
                color = Ink.Muted,
            )
        }
        Text(
            (if (b.direction == Direction.INCOME) "+" else "") + money(b.amountCents),
            fontSize = 14.sp,
            color = when {
                b.direction == Direction.INCOME -> Ink.Amber
                isConsumption -> Ink.Text
                else -> Ink.Muted
            },
        )
    }
    HorizontalDivider(color = Ink.Divider)
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
