package dev.xiaonuoboard.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xiaonuoboard.app.AppContainer
import dev.xiaonuoboard.app.BuildConfig
import dev.xiaonuoboard.app.ai.AiPipeline
import dev.xiaonuoboard.app.ai.GeminiModels
import dev.xiaonuoboard.app.data.BitmapIO
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.update.InstallLaunchResult
import dev.xiaonuoboard.app.update.ReleaseParser
import dev.xiaonuoboard.app.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 家长设置 (parent-locked). */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onImported: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var customModel by remember { mutableStateOf(if (GeminiModels.OPTIONS.none { it.id == settings.model }) settings.model else "") }
    var showKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }

    fun persist() = container.settings.update {
        it.copy(apiKey = apiKey.trim(), baseUrl = baseUrl.trim().ifBlank { GeminiModels.DEFAULT_BASE_URL })
    }

    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importStatus = "正在导入并转换为封闭线稿…"
        scope.launch {
            val page = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = BitmapIO.decodeUri(context, uri) ?: error("无法读取图片")
                    val out = try { AiPipeline.importLocal(bmp) } finally { bmp.recycle() }
                    try {
                        container.pages.saveCustomPage("导入 · ${System.currentTimeMillis() % 100000}", out.line, out.ref, "imported", out.regions)
                    } finally {
                        out.line.recycle()
                        out.ref?.recycle()
                    }
                }
            }
            page.onSuccess { onImported(it.id) }.onFailure { importStatus = "导入失败：${it.message}" }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EinkButton("← 返回") { persist(); onBack() }
            Text("⚙️ 家长设置", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME} · BOOX 优化版", fontSize = 14.sp, color = Eink.Muted)
        }

        EinkCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gemini AI 生图配置", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("API Key（仅保存在本机）", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("AIza…") }, modifier = Modifier.weight(1f),
                    )
                    EinkButton(if (showKey) "隐藏" else "显示") { showKey = !showKey }
                }
                Text("自定义 Base URL 代理（选填）", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                    placeholder = { Text(GeminiModels.DEFAULT_BASE_URL) }, modifier = Modifier.fillMaxWidth(),
                )
                Text("模型", fontWeight = FontWeight.Bold)
                GeminiModels.OPTIONS.forEach { opt ->
                    EinkButton(
                        "${opt.label} · ${opt.id}", Modifier.fillMaxWidth(),
                        selected = settings.model == opt.id, fontSize = 15,
                    ) { customModel = ""; container.settings.update { it.copy(model = opt.id) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customModel, onValueChange = { customModel = it }, singleLine = true,
                        placeholder = { Text("自定义模型名，如 gemini-xxx-image") }, modifier = Modifier.weight(1f),
                    )
                    EinkButton("使用", selected = customModel.isNotBlank() && settings.model == customModel.trim()) {
                        if (customModel.isNotBlank()) container.settings.update { it.copy(model = customModel.trim()) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EinkButton("保存", Modifier.weight(1f)) { persist(); testResult = "✓ 已保存" }
                    EinkButton("测试连通性", Modifier.weight(1f), selected = true) {
                        persist()
                        testResult = "测试中…"
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                runCatching { container.gemini.testConnectivity() }.getOrElse { "✗ ${it.message}" }
                            }
                        }
                    }
                }
                testResult?.let { Text(it, fontWeight = FontWeight.Bold) }
            }
        }

        EinkCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("BOOX 墨水屏与交互体验", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (OnyxEinkBridge.isAvailable) "已检测到 BOOX Onyx EPD 控制器 ✓"
                    else "未检测到 Onyx SDK（普通 Android 设备，自动降级为标准刷新）",
                    fontSize = 14.sp, color = Eink.Muted,
                )
                Text("默认刷新模式", fontWeight = FontWeight.Bold)
                EinkSegmented(
                    listOf(EinkMode.REGAL to "REGAL 清晰（推荐）", EinkMode.NORMAL to "标准", EinkMode.FAST to "快速"),
                    settings.einkMode,
                ) { m -> container.settings.update { it.copy(einkMode = m) } }
                Text("涂色音效与完成夸奖", fontWeight = FontWeight.Bold)
                EinkSegmented(listOf(false to "静音（默认）", true to "轻柔音效"), settings.soundEnabled) { on ->
                    container.settings.update { it.copy(soundEnabled = on) }
                }
                Text("防误触", fontWeight = FontWeight.Bold)
                EinkSegmented(listOf(false to "👆 手笔皆可", true to "✍️ 仅笔作画"), settings.stylusOnly) { on ->
                    container.settings.update { it.copy(stylusOnly = on) }
                }
            }
        }

        EinkCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本地导入", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("从相册 / 文件选择线稿图片，自动二值化、去噪并封口，加入「AI 魔法」图库。", fontSize = 14.sp, color = Eink.Muted)
                EinkButton("📂 从本地相册 / 文件导入", Modifier.fillMaxWidth()) { importDoc.launch(arrayOf("image/*")) }
                importStatus?.let { Text(it, fontWeight = FontWeight.Bold) }
            }
        }

        OtaUpdateCard(container)
    }
}

@Composable
private fun OtaUpdateCard(container: AppContainer) {
    val context = LocalContext.current
    val state by container.updater.state.collectAsState()
    var installHint by remember { mutableStateOf<String?>(null) }

    fun triggerInstall() {
        when (val res = container.updater.launchInstaller(context)) {
            InstallLaunchResult.Launched -> installHint = "已拉起系统安装器"
            is InstallLaunchResult.NeedsUnknownSourcesPermission -> {
                installHint = "请允许「安装未知应用」权限，返回后将自动安装"
                runCatching { context.startActivity(res.settingsIntent) }
            }
            is InstallLaunchResult.Failed -> installHint = res.message
        }
    }

    fun openReleasePage(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    EinkCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("软件更新（GitHub OTA）", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("当前 v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Eink.Muted)
            }
            when (val s = state) {
                UpdateUiState.Idle -> {
                    Text("自动检测 GitHub Releases 新版本，支持 SHA-256 与签名校验后一键安装。", fontSize = 14.sp, color = Eink.Muted)
                    EinkButton("🔄 检查更新", Modifier.fillMaxWidth()) {
                        installHint = null
                        container.updater.check(force = true)
                    }
                }
                UpdateUiState.Checking -> {
                    Text("正在连接 GitHub 检查新版本…", fontWeight = FontWeight.Bold)
                    EinkButton("取消", Modifier.fillMaxWidth()) { container.updater.cancel() }
                }
                is UpdateUiState.UpToDate -> {
                    Text("✓ 已是最新版本（v${BuildConfig.VERSION_NAME}）", fontWeight = FontWeight.Bold)
                    EinkButton("🔄 重新检查", Modifier.fillMaxWidth()) {
                        installHint = null
                        container.updater.check(force = true)
                    }
                }
                is UpdateUiState.Available -> {
                    val sizeMb = if (s.asset.size > 0) " · %.1f MB".format(s.asset.size / (1024.0 * 1024.0)) else ""
                    Text("🎉 发现新版本：${s.release.tagName}$sizeMb", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    s.release.body?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
                        Text(notes.take(240), fontSize = 14.sp, color = Eink.Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EinkButton("⬇️ 下载并安装", Modifier.weight(1f), selected = true) {
                            installHint = null
                            container.updater.download { triggerInstall() }
                        }
                        EinkButton("🌐 发布页") {
                            openReleasePage(s.release.htmlUrl ?: ReleaseParser.DEFAULT_RELEASE_HTML_URL)
                        }
                    }
                }
                is UpdateUiState.Downloading -> {
                    val pct = if (s.totalBytes > 0) ((s.downloadedBytes * 100) / s.totalBytes).coerceIn(0, 100) else -1
                    val dlMb = "%.1f".format(s.downloadedBytes / (1024.0 * 1024.0))
                    val totMb = if (s.totalBytes > 0) "/ %.1f MB".format(s.totalBytes / (1024.0 * 1024.0)) else "MB"
                    val spd = if (s.speedBytesPerSec > 0) " · %.0f KB/s".format(s.speedBytesPerSec / 1024.0) else ""
                    Text(
                        if (pct >= 0) "正在下载 ${s.release.tagName}：$pct%（$dlMb $totMb$spd）"
                        else "正在下载 ${s.release.tagName}：$dlMb $totMb$spd",
                        fontWeight = FontWeight.Bold,
                    )
                    EinkButton("取消下载", Modifier.fillMaxWidth()) { container.updater.cancel() }
                }
                is UpdateUiState.Verifying -> {
                    Text("正在校验安装包完整性与签名…", fontWeight = FontWeight.Bold)
                }
                is UpdateUiState.Ready -> {
                    Text("✓ ${s.release.tagName} 已下载并通过签名校验", fontWeight = FontWeight.Bold)
                    EinkButton("📦 立即安装 ${s.release.tagName}", Modifier.fillMaxWidth(), selected = true) {
                        triggerInstall()
                    }
                }
                is UpdateUiState.Error -> {
                    Text("✗ ${s.message}", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EinkButton("🔄 重试", Modifier.weight(1f)) {
                            installHint = null
                            container.updater.check(force = true)
                        }
                        EinkButton("🌐 GitHub") { openReleasePage(s.releasePageUrl) }
                    }
                }
            }
            installHint?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
