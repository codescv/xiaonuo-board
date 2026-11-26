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
import dev.xiaonuoboard.app.data.DiagLogExporter
import dev.xiaonuoboard.app.eink.BooxSdkMode
import dev.xiaonuoboard.app.eink.DiagLog
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.i18n.AppLanguage
import dev.xiaonuoboard.app.i18n.LocalAppStrings
import dev.xiaonuoboard.app.update.InstallLaunchResult
import dev.xiaonuoboard.app.update.ReleaseParser
import dev.xiaonuoboard.app.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Parent Settings (parent-locked). */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onImported: (String) -> Unit) {
    val s = LocalAppStrings.current
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
        val currentStrings = s
        importStatus = currentStrings.settingsImportBusy
        scope.launch {
            val page = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = BitmapIO.decodeUri(context, uri) ?: error(currentStrings.settingsImportDecodeError)
                    val out = try { AiPipeline.importLocal(bmp, currentStrings) } finally { bmp.recycle() }
                    try {
                        val suffix = (System.currentTimeMillis() % 100000).toString()
                        container.pages.saveCustomPage(currentStrings.settingsImportTitle(suffix), out.line, out.ref, "imported", out.regions)
                    } finally {
                        out.line.recycle()
                        out.ref?.recycle()
                    }
                }
            }
            page.onSuccess { onImported(it.id) }.onFailure { importStatus = currentStrings.settingsImportFailed(it.message) }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EinkButton(s.backArrow) { persist(); onBack() }
            Text(s.settingsTitle, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(s.settingsVersionEdition(BuildConfig.VERSION_NAME), fontSize = 14.sp, color = Eink.Muted)
        }

        // Dedicated top card so non-Chinese parents find language switching immediately.
        EinkCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.settingsLanguageTitle, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                EinkSegmented(
                    options = listOf(
                        AppLanguage.SYSTEM to s.languageSystem,
                        AppLanguage.ZH to "中文",
                        AppLanguage.EN to "English",
                        AppLanguage.JA to "日本語",
                    ),
                    selected = settings.language,
                    fontSize = 15,
                ) { lang ->
                    container.settings.update { it.copy(language = lang) }
                }
                Text(s.settingsLanguageDesc, fontSize = 13.sp, color = Eink.Muted)
            }
        }

        EinkCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.settingsGeminiTitle, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text(s.settingsApiKeyLabel, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("AIza…") }, modifier = Modifier.weight(1f),
                    )
                    EinkButton(if (showKey) s.hide else s.show) { showKey = !showKey }
                }
                Text(s.settingsBaseUrlLabel, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                    placeholder = { Text(GeminiModels.DEFAULT_BASE_URL) }, modifier = Modifier.fillMaxWidth(),
                )
                Text(s.settingsModelLabel, fontWeight = FontWeight.Bold)
                GeminiModels.OPTIONS.forEach { opt ->
                    EinkButton(
                        "${opt.label(s)} · ${opt.id}", Modifier.fillMaxWidth(),
                        selected = settings.model == opt.id, fontSize = 15,
                    ) { customModel = ""; container.settings.update { it.copy(model = opt.id) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customModel, onValueChange = { customModel = it }, singleLine = true,
                        placeholder = { Text(s.settingsCustomModelPlaceholder) }, modifier = Modifier.weight(1f),
                    )
                    EinkButton(s.useLabel, selected = customModel.isNotBlank() && settings.model == customModel.trim()) {
                        if (customModel.isNotBlank()) container.settings.update { it.copy(model = customModel.trim()) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EinkButton(s.save, Modifier.weight(1f)) { persist(); testResult = s.settingsSaved }
                    EinkButton(s.settingsTestConnectivity, Modifier.weight(1f), selected = true) {
                        persist()
                        val currentStrings = s
                        testResult = currentStrings.settingsTesting
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                runCatching { container.gemini.testConnectivity(currentStrings) }.getOrElse { "✗ ${it.message}" }
                            }
                        }
                    }
                }
                testResult?.let { Text(it, fontWeight = FontWeight.Bold) }
            }
        }

        EinkCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.settingsBooxCardTitle, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                // Real runtime state: BOOX hardware? SDK initialized? TouchHelper raw pen armed?
                // Keying off settings.booxSdkMode & s.language ensures statusText recomposes immediately.
                val booxStatus = remember(settings.booxSdkMode, s.language) { OnyxEinkBridge.statusText(s) }
                Text(booxStatus, fontSize = 14.sp, color = Eink.Muted)
                OnyxEinkBridge.deviceDetail?.let { Text(s.settingsOnyxDevice(it), fontSize = 12.sp, color = Eink.Muted) }
                Text(s.settingsBooxSdkModeTitle, fontWeight = FontWeight.Bold)
                EinkSegmented(
                    listOf(
                        BooxSdkMode.OFF to s.settingsBooxSdkOff,
                        BooxSdkMode.EPD_ONLY to s.settingsBooxSdkEpdOnly,
                        BooxSdkMode.FULL to s.settingsBooxSdkFull,
                    ),
                    settings.booxSdkMode,
                    fontSize = 15,
                ) { mode ->
                    OnyxEinkBridge.setSdkMode(mode)
                    container.settings.update { it.copy(booxSdkMode = mode) }
                }
                Text(s.settingsBooxSdkDesc, fontSize = 12.sp, color = Eink.Muted)
                Text(s.settingsRefreshModeTitle, fontWeight = FontWeight.Bold)
                EinkSegmented(
                    listOf(EinkMode.REGAL to s.settingsRefreshRegalRec, EinkMode.NORMAL to s.einkModeNormal, EinkMode.FAST to s.einkModeFast),
                    settings.einkMode,
                    fontSize = 15,
                ) { m -> container.settings.update { it.copy(einkMode = m) } }
                Text(s.settingsSoundTitle, fontWeight = FontWeight.Bold)
                EinkSegmented(listOf(false to s.settingsSoundOff, true to s.settingsSoundOn), settings.soundEnabled) { on ->
                    container.settings.update { it.copy(soundEnabled = on) }
                }
                Text(s.settingsPalmRejectionTitle, fontWeight = FontWeight.Bold)
                EinkSegmented(listOf(false to s.settingsInputBoth, true to s.settingsInputStylusOnly), settings.stylusOnly) { on ->
                    container.settings.update { it.copy(stylusOnly = on) }
                }
                DiagnosticsSection(container)
            }
        }

        EinkCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.settingsImportTitle, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text(s.settingsImportDesc, fontSize = 14.sp, color = Eink.Muted)
                EinkButton(s.settingsImportButton, Modifier.fillMaxWidth()) { importDoc.launch(arrayOf("image/*")) }
                importStatus?.let { Text(it, fontWeight = FontWeight.Bold) }
            }
        }

        OtaUpdateCard(container)
    }
}

/** Diagnostics: export / share the [DiagLog] ring buffer (pen latency, refreshes, TouchHelper state). */
@Composable
private fun DiagnosticsSection(container: AppContainer) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(s.settingsDiagTitle, fontWeight = FontWeight.Bold)
    Text(
        s.settingsDiagDesc(DiagLog.capacity, DiagLog.count),
        fontSize = 14.sp, color = Eink.Muted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EinkButton(s.settingsDiagExport, Modifier.weight(1f), enabled = !busy) {
            busy = true
            val currentStrings = s
            status = currentStrings.settingsDiagExporting
            val snapshot = container.settings.current
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching { DiagLogExporter.saveToDownloads(context, snapshot, currentStrings) }
                        .fold(
                            { currentStrings.settingsDiagExportOk(it.entries, it.location) },
                            { currentStrings.settingsDiagExportFailed(it.message) },
                        )
                }
                busy = false
            }
        }
        EinkButton(s.settingsDiagShare, Modifier.weight(1f), enabled = !busy) {
            busy = true
            val currentStrings = s
            val snapshot = container.settings.current
            scope.launch {
                val shared = withContext(Dispatchers.IO) { runCatching { DiagLogExporter.shareIntent(context, snapshot, currentStrings) } }
                status = shared.fold(
                    { (intent, res) ->
                        runCatching { context.startActivity(intent) }
                            .fold(
                                { currentStrings.settingsDiagShareOk(res.entries, res.location) },
                                { currentStrings.settingsDiagShareLaunchFailed(it.message) },
                            )
                    },
                    { currentStrings.settingsDiagShareFailed(it.message) },
                )
                busy = false
            }
        }
    }
    status?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun OtaUpdateCard(container: AppContainer) {
    val str = LocalAppStrings.current
    val context = LocalContext.current
    val state by container.updater.state.collectAsState()
    var installHint by remember { mutableStateOf<String?>(null) }

    fun triggerInstall() {
        when (val res = container.updater.launchInstaller(context)) {
            InstallLaunchResult.Launched -> installHint = str.otaInstallerLaunched
            is InstallLaunchResult.NeedsUnknownSourcesPermission -> {
                installHint = str.otaUnknownSourcesHint
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
                Text(str.otaTitle, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text(str.otaCurrentVersion(BuildConfig.VERSION_NAME), fontSize = 14.sp, color = Eink.Muted)
            }
            when (val s = state) {
                UpdateUiState.Idle -> {
                    Text(str.otaIdleDesc, fontSize = 14.sp, color = Eink.Muted)
                    EinkButton(str.otaCheckButton, Modifier.fillMaxWidth()) {
                        installHint = null
                        container.updater.check(force = true)
                    }
                }
                UpdateUiState.Checking -> {
                    Text(str.otaChecking, fontWeight = FontWeight.Bold)
                    EinkButton(str.cancel, Modifier.fillMaxWidth()) { container.updater.cancel() }
                }
                is UpdateUiState.UpToDate -> {
                    Text(str.otaUpToDate(BuildConfig.VERSION_NAME), fontWeight = FontWeight.Bold)
                    EinkButton(str.otaRecheckButton, Modifier.fillMaxWidth()) {
                        installHint = null
                        container.updater.check(force = true)
                    }
                }
                is UpdateUiState.Available -> {
                    val sizeMb = if (s.asset.size > 0) String.format(Locale.US, " · %.1f MB", s.asset.size / (1024.0 * 1024.0)) else ""
                    Text(str.otaAvailable(s.release.tagName, sizeMb), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    s.release.body?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
                        Text(notes.take(240), fontSize = 14.sp, color = Eink.Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EinkButton(str.otaDownloadInstallButton, Modifier.weight(1f), selected = true) {
                            installHint = null
                            container.updater.download { triggerInstall() }
                        }
                        EinkButton(str.otaReleasePageButton) {
                            openReleasePage(s.release.htmlUrl ?: ReleaseParser.DEFAULT_RELEASE_HTML_URL)
                        }
                    }
                }
                is UpdateUiState.Downloading -> {
                    val pct = if (s.totalBytes > 0) ((s.downloadedBytes * 100) / s.totalBytes).coerceIn(0, 100).toInt() else -1
                    val dlMb = String.format(Locale.US, "%.1f", s.downloadedBytes / (1024.0 * 1024.0))
                    val totMb = if (s.totalBytes > 0) String.format(Locale.US, "/ %.1f MB", s.totalBytes / (1024.0 * 1024.0)) else "MB"
                    val spd = if (s.speedBytesPerSec > 0) String.format(Locale.US, " · %.0f KB/s", s.speedBytesPerSec / 1024.0) else ""
                    Text(
                        if (pct >= 0) str.otaDownloadingPct(s.release.tagName, pct, dlMb, totMb, spd)
                        else str.otaDownloadingNoPct(s.release.tagName, dlMb, totMb, spd),
                        fontWeight = FontWeight.Bold,
                    )
                    EinkButton(str.otaCancelDownloadButton, Modifier.fillMaxWidth()) { container.updater.cancel() }
                }
                is UpdateUiState.Verifying -> {
                    Text(str.otaVerifying, fontWeight = FontWeight.Bold)
                }
                is UpdateUiState.Ready -> {
                    Text(str.otaReady(s.release.tagName), fontWeight = FontWeight.Bold)
                    EinkButton(str.otaInstallNow(s.release.tagName), Modifier.fillMaxWidth(), selected = true) {
                        triggerInstall()
                    }
                }
                is UpdateUiState.Error -> {
                    Text("✗ ${s.message}", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EinkButton(str.otaRetryButton, Modifier.weight(1f)) {
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
