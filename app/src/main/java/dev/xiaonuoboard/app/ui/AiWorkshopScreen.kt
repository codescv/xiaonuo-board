package dev.xiaonuoboard.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xiaonuoboard.app.AppContainer
import dev.xiaonuoboard.app.ai.AiPipeline
import dev.xiaonuoboard.app.ai.PromptBuilder
import dev.xiaonuoboard.app.data.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AiTab(val label: String) { COMBO("🧩 快捷组合"), FREE("⌨️ 自由输入"), PHOTO("📷 照片转线稿") }

/** AI 魔法画坊 (parent-locked): quick combos / alphabet letters / free text / photo → line art. */
@Composable
fun AiWorkshopScreen(container: AppContainer, onBack: () -> Unit, onColorNow: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(AiTab.COMBO) }
    var comboAlphabet by remember { mutableStateOf(false) }
    var character by remember { mutableIntStateOf(0) }
    var scene by remember { mutableIntStateOf(0) }
    var letter by remember { mutableIntStateOf(0) }
    var difficulty by remember { mutableStateOf(PromptBuilder.Difficulty.EASY) }
    var freeText by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val ioJobs = remember { JobTracker() }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val locked = busy || saving
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AiPipeline.Output?>(null) }
    var resultTitle by remember { mutableStateOf("") }

    // Free large bitmaps whenever a result/photo is replaced or the screen leaves — but only after
    // every background job that may still read them (generate / save) has finished.
    DisposableEffect(result) {
        val r = result
        onDispose {
            if (r != null) container.appScope.launch {
                ioJobs.joinAll()
                r.line.recycle()
                r.ref?.recycle()
            }
        }
    }
    DisposableEffect(photo) {
        val p = photo
        onDispose {
            if (p != null) container.appScope.launch { ioJobs.joinAll(); p.recycle() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { disposed.set(true) }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            photo = withContext(Dispatchers.IO) { BitmapIO.decodeUri(context, uri, maxDim = 1600) }
            if (photo == null) status = "无法读取这张照片"
        }
    }

    fun generate() {
        val apiKeyMissing = container.settings.current.apiKey.isBlank()
        if (apiKeyMissing) { status = "请先在 ⚙️ 家长设置 中填写 Gemini API Key"; return }
        val (prompt, title) = when (tab) {
            AiTab.COMBO -> if (comboAlphabet) {
                val ch = 'A' + letter
                PromptBuilder.alphabet(ch, PromptBuilder.ALPHABET_OBJECTS[letter], difficulty) to "AI · 字母 $ch${ch.lowercaseChar()}"
            } else {
                val c = PromptBuilder.CHARACTERS[character]
                val sc = PromptBuilder.SCENES[scene]
                PromptBuilder.combo(c, sc, difficulty) to "AI · ${c.label}${sc.label}"
            }
            AiTab.FREE -> {
                if (freeText.isBlank()) { status = "请先输入想画的内容"; return }
                PromptBuilder.freeText(freeText, difficulty) to "AI · ${freeText.take(10)}"
            }
            AiTab.PHOTO -> {
                if (photo == null) { status = "请先选择一张照片"; return }
                PromptBuilder.photoToLineArt(difficulty) to "AI · 照片线稿"
            }
        }
        busy = true; result = null; status = "准备中…"
        val input = if (tab == AiTab.PHOTO) photo else null
        // Runs on the app scope (tracked) so a Back press never recycles `input` mid-request.
        val job = container.appScope.async {
            val r = runCatching {
                container.aiPipeline.run(prompt, input) { _, msg -> scope.launch { status = msg } }
            }
            if (disposed.get()) r.getOrNull()?.let { it.line.recycle(); it.ref?.recycle() }
            r
        }
        ioJobs.track(job)
        scope.launch {
            val out = job.await()
            busy = false
            out.onSuccess {
                result = it; resultTitle = title
                status = (if (it.qcPassed) "✓ 已通过封闭连通域质检 · " else "⚠️ 质检提示：区域数量偏离预期 · ") + it.note
            }.onFailure { status = "生成失败：${it.message}" }
        }
    }

    fun save(colorNow: Boolean) {
        val r = result ?: return
        if (saving) return
        saving = true
        val title = resultTitle
        val job = container.appScope.async { runCatching { container.pages.saveCustomPage(title, r.line, r.ref, "ai", r.regions) } }
        ioJobs.track(job)
        scope.launch {
            val page = job.await()
            saving = false
            page.onSuccess {
                if (colorNow) onColorNow(it.id) else { status = "✓ 已保存到「AI 魔法」离线图库"; result = null }
            }.onFailure { status = "保存失败：${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EinkButton("← 返回") { onBack() }
            Column(Modifier.weight(1f)) {
                Text("✨ AI 魔法画坊（家长模式）", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("模型：${container.settings.current.model}", fontSize = 14.sp, color = Eink.Muted)
            }
        }
        EinkSegmented(AiTab.entries.map { it to it.label }, tab) { if (!locked) { tab = it; result = null } }

        when (tab) {
            AiTab.COMBO -> {
                EinkSegmented(listOf(false to "角色 × 场景", true to "🔤 字母 A–Z"), comboAlphabet) { comboAlphabet = it }
                if (!comboAlphabet) {
                    Text("1. 主角", fontWeight = FontWeight.ExtraBold)
                    ChipGrid(PromptBuilder.CHARACTERS.map { it.label }, character) { character = it }
                    Text("2. 场景", fontWeight = FontWeight.ExtraBold)
                    ChipGrid(PromptBuilder.SCENES.map { it.label }, scene) { scene = it }
                } else {
                    Text("选一个字母（含可爱主题物：${PromptBuilder.ALPHABET_OBJECTS[letter]}）", fontWeight = FontWeight.ExtraBold)
                    ChipGrid(('A'..'Z').map { "$it${it.lowercaseChar()}" }, letter, columns = 9, minHeight = 48) { letter = it }
                }
            }
            AiTab.FREE -> OutlinedTextField(
                value = freeText, onValueChange = { freeText = it },
                placeholder = { Text("例如：小兔子在月亮上荡秋千") },
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
            AiTab.PHOTO -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                EinkButton(if (photo == null) "📷 选择照片" else "📷 换一张", enabled = !locked) { pickPhoto.launch(arrayOf("image/*")) }
                photo?.let {
                    Image(it.asImageBitmap(), null, Modifier.height(96.dp).aspectRatio(it.width.toFloat() / it.height).border(Eink.Border, Eink.Ink))
                }
                Text("孩子 / 宠物 / 玩具照片都可以", fontSize = 14.sp, color = Eink.Muted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3. 线条难度：", fontWeight = FontWeight.ExtraBold)
            EinkSegmented(PromptBuilder.Difficulty.entries.map { it to it.label }, difficulty, Modifier.weight(1f)) { difficulty = it }
        }
        EinkButton(if (busy) "⏳ 生成中…" else "🪄 生成双图（线稿 + 参考图）", Modifier.fillMaxWidth(), selected = true, enabled = !locked) { generate() }
        status?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Bold) }

        val r = result
        if (r != null) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PreviewPane("涂色前 · 1-bit 线稿", r.line, Modifier.weight(1f))
                PreviewPane("涂色后 · 参考彩图", r.ref, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EinkButton(if (saving) "⏳ 保存中…" else "✓ 保存并立即开始涂色", Modifier.weight(2f), selected = true, enabled = !saving) { save(true) }
                EinkButton("仅保存", Modifier.weight(1f), enabled = !saving) { save(false) }
            }
        } else {
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreviewPane(title: String, bmp: Bitmap?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        Box(Modifier.fillMaxSize().border(Eink.Border, Eink.Ink, Eink.Shape).padding(4.dp), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text("（无参考图）", color = Eink.Muted)
            }
        }
    }
}

@Composable
fun ChipGrid(labels: List<String>, selected: Int, columns: Int = 3, minHeight: Int = 52, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.chunked(columns).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, label ->
                    val i = r * columns + c
                    EinkButton(label, Modifier.weight(1f), selected = i == selected, minHeight = minHeight.dp, fontSize = 15) { onSelect(i) }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
