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
import dev.xiaonuoboard.app.i18n.AppStrings
import dev.xiaonuoboard.app.i18n.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AiTab {
    COMBO, FREE, PHOTO;

    fun label(s: AppStrings): String = when (this) {
        COMBO -> s.aiTabCombo
        FREE -> s.aiTabFree
        PHOTO -> s.aiTabPhoto
    }
}

/** AI Magic Studio (parent-locked): quick combos / alphabet letters / free text / photo → line art. */
@Composable
fun AiWorkshopScreen(container: AppContainer, onBack: () -> Unit, onColorNow: (String) -> Unit) {
    val s = LocalAppStrings.current
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
        if (uri != null) {
            val currentStrings = s
            scope.launch {
                photo = withContext(Dispatchers.IO) { BitmapIO.decodeUri(context, uri, maxDim = 1600) }
                if (photo == null) status = currentStrings.aiPhotoReadError
            }
        }
    }

    fun generate() {
        val currentStrings = s
        val apiKeyMissing = container.settings.current.apiKey.isBlank()
        if (apiKeyMissing) { status = currentStrings.aiNeedApiKey; return }
        val (prompt, title) = when (tab) {
            AiTab.COMBO -> if (comboAlphabet) {
                val ch = 'A' + letter
                // Model prompt MUST stay English (PromptBuilder.ALPHABET_OBJECTS); title is localized.
                PromptBuilder.alphabet(ch, PromptBuilder.ALPHABET_OBJECTS[letter], difficulty) to
                    currentStrings.aiTitleAlphabet(ch)
            } else {
                val c = PromptBuilder.CHARACTERS[character]
                val sc = PromptBuilder.SCENES[scene]
                PromptBuilder.combo(c, sc, difficulty) to
                    currentStrings.aiTitleCombo(c.label(currentStrings), sc.label(currentStrings))
            }
            AiTab.FREE -> {
                if (freeText.isBlank()) { status = currentStrings.aiNeedFreeText; return }
                PromptBuilder.freeText(freeText, difficulty) to currentStrings.aiTitleFree(freeText.take(10))
            }
            AiTab.PHOTO -> {
                if (photo == null) { status = currentStrings.aiNeedPhoto; return }
                PromptBuilder.photoToLineArt(difficulty) to currentStrings.aiTitlePhoto
            }
        }
        busy = true; result = null; status = currentStrings.aiPreparing
        val input = if (tab == AiTab.PHOTO) photo else null
        // Runs on the app scope (tracked) so a Back press never recycles `input` mid-request.
        val job = container.appScope.async {
            val r = runCatching {
                container.aiPipeline.run(prompt, input, strings = currentStrings) { _, msg -> scope.launch { status = msg } }
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
                status = (if (it.qcPassed) currentStrings.aiQcPassedPrefix else currentStrings.aiQcWarningPrefix) + it.note
            }.onFailure { status = currentStrings.aiGenerateFailed(it.message) }
        }
    }

    fun save(colorNow: Boolean) {
        val r = result ?: return
        if (saving) return
        saving = true
        val currentStrings = s
        val title = resultTitle
        val job = container.appScope.async { runCatching { container.pages.saveCustomPage(title, r.line, r.ref, "ai", r.regions) } }
        ioJobs.track(job)
        scope.launch {
            val page = job.await()
            saving = false
            page.onSuccess {
                if (colorNow) onColorNow(it.id) else { status = currentStrings.aiSavedToLibrary; result = null }
            }.onFailure { status = currentStrings.aiSaveFailed(it.message) }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EinkButton(s.backArrow) { onBack() }
            Column(Modifier.weight(1f)) {
                Text(s.aiHeaderTitle, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(s.aiModelLabel(container.settings.current.model), fontSize = 14.sp, color = Eink.Muted)
            }
        }
        EinkSegmented(AiTab.entries.map { it to it.label(s) }, tab, fontSize = 15) { if (!locked) { tab = it; result = null } }

        when (tab) {
            AiTab.COMBO -> {
                EinkSegmented(listOf(false to s.aiComboCharacterScene, true to s.aiComboAlphabet), comboAlphabet) { comboAlphabet = it }
                if (!comboAlphabet) {
                    Text(s.aiStepCharacter, fontWeight = FontWeight.ExtraBold)
                    ChipGrid(PromptBuilder.CHARACTERS.map { it.label(s) }, character) { character = it }
                    Text(s.aiStepScene, fontWeight = FontWeight.ExtraBold)
                    ChipGrid(PromptBuilder.SCENES.map { it.label(s) }, scene) { scene = it }
                } else {
                    Text(s.aiPickLetterPrompt(s.alphabetObject(letter)), fontWeight = FontWeight.ExtraBold)
                    ChipGrid(('A'..'Z').map { "$it${it.lowercaseChar()}" }, letter, columns = 9, minHeight = 48) { letter = it }
                }
            }
            AiTab.FREE -> OutlinedTextField(
                value = freeText, onValueChange = { freeText = it },
                placeholder = { Text(s.aiFreePlaceholder) },
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
            AiTab.PHOTO -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                EinkButton(if (photo == null) s.aiPickPhoto else s.aiChangePhoto, enabled = !locked) { pickPhoto.launch(arrayOf("image/*")) }
                photo?.let {
                    Image(it.asImageBitmap(), null, Modifier.height(96.dp).aspectRatio(it.width.toFloat() / it.height).border(Eink.Border, Eink.Ink))
                }
                Text(s.aiPhotoHint, fontSize = 14.sp, color = Eink.Muted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.aiStepDifficulty, fontWeight = FontWeight.ExtraBold)
            EinkSegmented(PromptBuilder.Difficulty.entries.map { it to it.label(s) }, difficulty, Modifier.weight(1f)) { difficulty = it }
        }
        EinkButton(if (busy) s.aiGenerating else s.aiGenerateButton, Modifier.fillMaxWidth(), selected = true, enabled = !locked) { generate() }
        status?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Bold) }

        val r = result
        if (r != null) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PreviewPane(s.aiPreviewLineArt, r.line, Modifier.weight(1f))
                PreviewPane(s.aiPreviewRef, r.ref, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EinkButton(if (saving) s.aiSaving else s.aiSaveAndColor, Modifier.weight(2f), selected = true, enabled = !saving) { save(true) }
                EinkButton(s.aiSaveOnly, Modifier.weight(1f), enabled = !saving) { save(false) }
            }
        } else {
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreviewPane(title: String, bmp: Bitmap?, modifier: Modifier) {
    val s = LocalAppStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        Box(Modifier.fillMaxSize().border(Eink.Border, Eink.Ink, Eink.Shape).padding(4.dp), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text(s.aiNoRef, color = Eink.Muted)
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
