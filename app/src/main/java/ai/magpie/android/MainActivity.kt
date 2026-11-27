package ai.magpie.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private var shouldRefresh by mutableStateOf(0)
    private var lastToggleAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.probeRootAsync()
        if (!Core.running()) MagpieService.fire(this, true)

        setContent {
            MiuixTheme {
                HomeScreen(
                    refreshTick = shouldRefresh,
                    onStart = { toggleDaemon(true) },
                    onStop = { toggleDaemon(false) },
                    onOpenUi = { openUi() },
                    onCheckUpdate = { checkUpdate() },
                    onUpdate = { doUpdate() },
                    onDiagnose = { diagnose() },
                    onCopy = { s -> copy(s) },
                    onCopyLog = { copy("---- magpie 日志 ----\n" + Core.tail(Core.logFile(this), 300)) },
                    onClearLog = {
                        Core.logFile(this).delete()
                        shouldRefresh++
                    },
                    onBattery = { batteryExempt() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shouldRefresh++
    }

    // ---------------------------------------------------------------- 动作

    /** 启停节流：连着点会让 start/stop 交错，1.5 秒内只认一次。 */
    private fun toggleDaemon(run: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleAt < 1500) {
            toast("正在切换，稍等一下")
            return
        }
        lastToggleAt = now
        if (run) {
            Core.prefs(this).edit().putBoolean("shouldRun", true).apply()
            MagpieService.fire(this, true)
            toast("正在启动…")
        } else {
            MagpieService.fire(this, false)
            toast("已停止")
        }
    }

    private fun openUi() {
        if (!Core.running()) {
            toast("magpie 还没起来，先点启动")
            return
        }
        startActivity(
            Intent(this, WebActivity::class.java).putExtra(
                "url",
                "http://127.0.0.1:${Core.WEB_PORT}/?k=${Core.webKey(this)}"
            )
        )
    }

    private fun checkUpdate() {
        toast("查最新版本…")
        Thread {
            val latest = Core.latestVersion()
            val mine = Core.versionName(this)
            val newer = Core.versionNum(latest) > Core.versionNum(mine)
            val msg = when {
                Core.versionNum(latest) < 0 -> latest
                newer -> "有新版 $latest（现在是 $mine）" +
                        if (Core.rootCached()) "，点「更新 magpie」" else "，无 root 需重装 APK"
                else -> "已是最新（$mine）"
            }
            runOnUiThread {
                toast(msg)
                Core.appendLog(this, "[update] $msg")
                shouldRefresh++
            }
        }.start()
    }

    private fun doUpdate() {
        if (!Core.rootCached()) {
            toast("更新需要 root；没有 root 就重装新 APK")
            return
        }
        toast("开始更新，可能要一两分钟…")
        Thread {
            val verdict = Core.runUpdate(this) ?: "更新失败（root 不可用）"
            runOnUiThread {
                toast(verdict)
                shouldRefresh++
            }
        }.start()
    }

    private fun diagnose() {
        toast("自检中…")
        Thread {
            val report = Diagnostics.run(this)
            runOnUiThread {
                copy(report)
                toast("自检结果已复制")
                shouldRefresh++
            }
        }.start()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("magpie", text))
    }

    private fun batteryExempt() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            toast("这台机器没有这个设置页")
        }
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}

// -------------------------------------------------------------------- 界面

@Composable
private fun HomeScreen(
    refreshTick: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenUi: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdate: () -> Unit,
    onDiagnose: () -> Unit,
    onCopy: (String) -> Unit,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
    onBattery: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var running by remember { mutableStateOf(Core.running()) }
    var rootKnown by remember { mutableStateOf(Core.rootKnown()) }
    var progress by remember { mutableStateOf(Core.progress) }
    var mode by remember { mutableStateOf(Core.prefs(ctx).getString("mode", "auto") ?: "auto") }
    var lan by remember { mutableStateOf(Core.lan(ctx)) }
    var autostart by remember { mutableStateOf(Core.prefs(ctx).getBoolean("autostart", false)) }
    var telemetry by remember { mutableStateOf(Core.prefs(ctx).getBoolean("telemetry", true)) }
    var proxy by remember { mutableStateOf(Core.proxy(ctx)) }
    var log by remember { mutableStateOf("") }

    LaunchedEffect(refreshTick) {
        while (true) {
            running = Core.running()
            rootKnown = Core.rootKnown()
            progress = Core.progress
            // 读日志是文件 IO，别占主线程
            log = withContext(Dispatchers.IO) { Core.tail(Core.logFile(ctx), 120) }
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "magpie",
                subtitle = "v${Core.versionName(ctx)} · 手机上的计划池代理",
                largeTitle = "magpie",
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // ---------------------------------------------------- 状态
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    when {
                                        running -> Color(0xFF00B34A)
                                        progress.isNotEmpty() -> Color(0xFFF5A623)
                                        else -> Color(0xFFFF3B30)
                                    },
                                    CircleShape,
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    running -> "运行中"
                                    progress.isNotEmpty() -> progress
                                    else -> "启动失败"
                                },
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = buildString {
                                    append(if (rootKnown == true) "root 守护" else "普通前台服务")
                                    append(" · 网关 ")
                                    append(Core.gatewayUrl(ctx))
                                },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = if (running) "停止" else "启动",
                            onClick = { if (running) onStop() else onStart() },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(text = "打开界面", onClick = onOpenUi, modifier = Modifier.weight(1f))
                        TextButton(
                            text = "复制界面链接",
                            onClick = { onCopy(Core.webUrl(ctx)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---------------------------------------------------- 更新
            item { SmallTitle(text = "更新") }
            item {
                Card {
                    ArrowPreference(
                        title = "检查更新",
                        summary = "对比官方最新版本",
                        onClick = onCheckUpdate,
                    )
                    ArrowPreference(
                        title = "更新 magpie",
                        summary = if (rootKnown == true) "root 模式下直接换二进制" else "需要 root；否则重装 APK",
                        onClick = onUpdate,
                        enabled = rootKnown == true,
                    )
                }
            }

            // ---------------------------------------------------- 运行
            item { SmallTitle(text = "运行") }
            item {
                Card {
                    SwitchPreference(
                        title = "开机自启",
                        summary = if (rootKnown == true) "写入 /data/adb/service.d" else "走系统开机广播",
                        checked = autostart,
                        onCheckedChange = { on ->
                            autostart = on
                            Core.prefs(ctx).edit().putBoolean("autostart", on).apply()
                            Thread {
                                if (Core.useRoot(ctx)) Core.setRootAutostart(ctx, on)
                            }.start()
                        },
                    )
                    SwitchPreference(
                        title = "root 守护模式",
                        summary = when (rootKnown) {
                            null -> "检测中…"
                            true -> "已授权 · 强杀 App 也不影响"
                            false -> "没有 root，会退回普通模式"
                        },
                        checked = mode != "app",
                        onCheckedChange = { on ->
                            mode = if (on) "root" else "app"
                            Core.prefs(ctx).edit().putString("mode", mode).apply()
                            if (on) Core.probeRootAsync()
                            if (Core.running()) MagpieService.fire(ctx, true)
                        },
                    )
                    SwitchPreference(
                        title = "局域网可访问",
                        summary = "其它设备可用这台手机的网关",
                        checked = lan,
                        onCheckedChange = { on ->
                            lan = on
                            Core.prefs(ctx).edit().putBoolean("lan", on).apply()
                            if (Core.running()) MagpieService.fire(ctx, true)
                        },
                    )
                    SwitchPreference(
                        title = "匿名使用统计",
                        summary = "magpie 官方的计数，可关",
                        checked = telemetry,
                        onCheckedChange = { on ->
                            telemetry = on
                            Core.prefs(ctx).edit().putBoolean("telemetry", on).apply()
                        },
                    )
                }
            }

            // ---------------------------------------------------- 代理
            item { SmallTitle(text = "网络代理") }
            item {
                Card {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("出站与更新走这个代理（可留空）", fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            BasicTextField(
                                value = proxy,
                                onValueChange = {
                                    proxy = it
                                    Core.prefs(ctx).edit().putString("proxy", it.trim()).apply()
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                                decorationBox = { inner ->
                                    if (proxy.isEmpty()) {
                                        Text("http://127.0.0.1:7890", fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                                    }
                                    inner()
                                },
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------- 工具
            item { SmallTitle(text = "工具") }
            item {
                Card {
                    ArrowPreference(title = "自检并复制", summary = "跑一遍二进制，结果进剪贴板", onClick = onDiagnose)
                    ArrowPreference(title = "复制日志", summary = "最近 300 行", onClick = onCopyLog)
                    ArrowPreference(title = "清空日志", onClick = onClearLog)
                    ArrowPreference(title = "电池不限制", summary = "防止系统省电策略杀后台", onClick = onBattery)
                }
            }

            // ---------------------------------------------------- 说明
            item { SmallTitle(text = "插件与更新") }
            item {
                Card {
                    Text(
                        text = "· 插件和 MCP 在「界面 → 资源库 / 插件」里装，手机上不用装额外运行时；" +
                                "第一次用到时 magpie 会自己下载安卓版 Bun（约 90MB）。\n" +
                                "· 有 root 时「更新 magpie」直接换守护二进制；没有 root 就重装新 APK。\n" +
                                "· 别的设备要连：打开「局域网可访问」，把 ANTHROPIC_BASE_URL 指向 http://手机IP:${Core.GW_PORT}。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ---------------------------------------------------- 日志
            item { SmallTitle(text = "日志") }
            item {
                Card {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = log.ifEmpty { "（还没有日志）" },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}
