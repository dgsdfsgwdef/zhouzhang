package app.zhouzhang

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.zhouzhang.core.Bill
import app.zhouzhang.core.Direction
import app.zhouzhang.core.FlowType
import app.zhouzhang.core.Parser
import app.zhouzhang.core.Week
import app.zhouzhang.data.BillStore
import app.zhouzhang.data.CategorySum
import app.zhouzhang.data.DaySum
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

/**
 * 应用锁。
 *
 * 退到后台立刻上锁，回到前台必须验证身份。允许用生物识别，也允许用系统 PIN/图案，
 * 这样没有录入指纹的用户不会把自己锁在门外。
 *
 * 设备既没生物识别也没设锁屏时，跳过验证——宁可少一层保护，也不能让 App 打不开。
 */
class MainActivity : FragmentActivity() {
    private lateinit var store: BillStore

    private var unlocked by mutableStateOf(false)
    private var needsPrompt = true
    private var canAuthenticate = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BillStore(applicationContext)

        // 禁止截屏，同时让最近任务里的缩略图变成空白
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }

        canAuthenticate = checkAuthenticator()
        if (!canAuthenticate) unlocked = true

        setContent {
            ZhouTheme {
                if (unlocked) {
                    HomeScreen(store)
                } else {
                    LockScreen(onUnlock = { prompt() })
                }
            }
        }
    }

    private fun checkAuthenticator(): Boolean {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return BiometricManager.from(this).canAuthenticate(allowed) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun prompt() {
        if (!canAuthenticate) {
            unlocked = true
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁周账")
            .setSubtitle("验证身份后查看账单")
            // 允许生物识别或系统 PIN/图案；两者同时允许时不能再设取消按钮
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            }
        ).authenticate(info)
    }

    override fun onStart() {
        super.onStart()
        if (needsPrompt && !unlocked) {
            needsPrompt = false
            prompt()
        }
    }

    override fun onStop() {
        super.onStop()
        // 转屏不算退到后台
        if (!isChangingConfigurations) {
            unlocked = false
            needsPrompt = true
        }
    }
}

enum class Mode(val label: String) { WEEK("周"), MONTH("月") }

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = Ink.Amber,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text("周账已锁定", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink.Text)
            Spacer(Modifier.height(6.dp))
            Text("验证身份后查看账单", fontSize = 13.sp, color = Ink.Muted)
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onUnlock,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink.Text,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("解锁", fontSize = 14.sp)
            }
        }
    }
}

fun money(cents: Long): String {
    val neg = cents < 0
    val abs = if (neg) -cents else cents
    return (if (neg) "-" else "") + "¥" + "%,d.%02d".format(abs / 100, abs % 100)
}

fun weekdayLabel(d: LocalDate): String = when (d.dayOfWeek.value) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
}

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

    fun refresh() {
        weekSum = store.summary(weekStart)
        bills = store.billsOfWeek(weekStart)
        monthSum = store.monthSummary(month)
        earliest = store.earliestDate()

        // 本周只过了几天，就只和上周同样天数比；过去的周则整周对比。
        val days = if (weekStart == thisWeek) {
            (ChronoUnit.DAYS.between(weekStart, today).toInt() + 1).coerceIn(1, 7)
        } else 7
        val prevStart = weekStart.minusWeeks(1)
        prevSame = store.sumConsumption(prevStart, prevStart.plusDays(days.toLong()))
    }

    LaunchedEffect(weekStart, month) { withContext(Dispatchers.IO) { refresh() } }

    fun startImport(uri: Uri, pwd: String?) {
        busy = true
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { readBytes(ctx, uri) }
                val result = withContext(Dispatchers.Default) {
                    Parser.parse(payload.first, payload.second, pwd)
                }
                when {
                    result.needsPassword -> {
                        pendingUri = uri
                        passwordError = result.error
                        askPassword = true
                    }

                    result.error != null -> message = result.error

                    else -> {
                        val counts = withContext(Dispatchers.IO) { store.insertAll(result.bills) }
                        message = buildString {
                            append("${result.platform?.label} · ${result.formatVersion} · ${result.encoding}\n\n")
                            append("解析 ${result.bills.size} 条\n")
                            append("新增 ${counts.first} 条，重复 ${counts.second} 条")
                            if (result.issues.isNotEmpty()) append("\n无法解析 ${result.issues.size} 条")
                        }
                        password = ""
                        passwordError = null
                        withContext(Dispatchers.IO) { refresh() }
                    }
                }
            } catch (e: Exception) {
                message = e.message ?: "导入失败"
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri, null)
    }

    Scaffold(
        containerColor = Ink.Bg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    picker.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                            "*/*",
                        )
                    )
                },
                containerColor = Ink.Text,
                contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(if (busy) "解析中…" else "导入账单") },
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 96.dp),
        ) {
            item {
                ModeSwitch(mode) { mode = it }
                Spacer(Modifier.height(22.dp))
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
                        askPassword = false
                        if (uri != null) startImport(uri, password)
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
}

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
