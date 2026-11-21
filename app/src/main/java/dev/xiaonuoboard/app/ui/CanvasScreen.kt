package dev.xiaonuoboard.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.xiaonuoboard.app.AppContainer
import dev.xiaonuoboard.app.data.BitmapIO
import dev.xiaonuoboard.app.data.ColoringPage
import dev.xiaonuoboard.app.data.GalleryExporter
import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.KaleidoPalette
import dev.xiaonuoboard.app.engine.PaletteExtractor
import dev.xiaonuoboard.app.engine.Tool
import dev.xiaonuoboard.app.ui.canvas.ColoringCanvasView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything needed to color one artwork, loaded off the main thread. */
private class CanvasSession(
    val page: ColoringPage,
    val controller: ColoringCanvasController,
    val lineOverlay: Bitmap,
    val ref: Bitmap?,
    val refThumb: Bitmap?,
    val recommended: List<Int>,
) {
    fun recycle() {
        lineOverlay.recycle()
        refThumb?.takeIf { it !== ref }?.recycle()
        ref?.recycle()
    }
}

@OptIn(FlowPreview::class)
@Composable
fun CanvasScreen(
    container: AppContainer,
    pageId: String,
    artworkId: String?,
    onArtworkCreated: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsState()
    val initialArtworkId = remember(pageId) { artworkId }
    // first = finished loading, second = session (null → failed)
    val loaded by produceState<Pair<Boolean, CanvasSession?>>(false to null, pageId) {
        value = true to withContext(Dispatchers.Default) {
            runCatching { loadSession(container, pageId, initialArtworkId) }.getOrNull()
        }
    }
    val s = loaded.second
    if (s == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (loaded.first) "这张画打不开了 😢" else "正在准备画纸…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (loaded.first) {
                Spacer(Modifier.height(16.dp))
                EinkButton("← 返回") { onBack() }
            }
        }
        return
    }

    val controller = s.controller
    // Every background job that reads session bitmaps; they are recycled only after all have finished.
    val ioJobs = remember(pageId) { JobTracker() }
    var currentArtworkId by remember(pageId) { mutableStateOf(initialArtworkId) }
    var tool by remember { mutableStateOf(controller.tool) }
    var brush by remember { mutableStateOf(controller.brushSize) }
    var color by remember { mutableIntStateOf(s.recommended.firstOrNull() ?: controller.color) }
    var stayInside by remember { mutableStateOf(settings.stayInsideLines) }
    var stylusOnly by remember { mutableStateOf(settings.stylusOnly) }
    var ghost by remember { mutableStateOf(settings.ghostUnderlay) }
    var pipExpanded by remember { mutableStateOf(false) }
    var pipCollapsed by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var ghostHint by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var celebrated by remember { mutableStateOf(false) }
    val saveTicks = remember { MutableStateFlow(0) }
    var canvasView by remember { mutableStateOf<ColoringCanvasView?>(null) }

    // Push UI state into the engine.
    controller.tool = tool
    controller.brushSize = brush
    controller.color = color
    controller.stayInsideLines = stayInside
    controller.stylusOnly = stylusOnly

    fun saveNow(notifyNav: Boolean = true): Job? {
        val view = canvasView ?: return null
        if (view.paintBitmap.isRecycled) return null
        val snapshot = view.paintBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val progress = controller.progress()
        val existing = currentArtworkId
        val id = existing ?: container.artworks.create(s.page).id.also {
            currentArtworkId = it
            // Never touch navigation while the screen is being disposed (back already popped it).
            if (notifyNav) onArtworkCreated(it)
        }
        if (progress >= 0.9f && !celebrated) {
            celebrated = true
            message = "🎉 太棒啦！这幅画快涂完啦！"
            container.sound.cheer()
        }
        return ioJobs.track(container.appScope.launch {
            var composite: Bitmap? = null
            var thumb: Bitmap? = null
            try {
                composite = BitmapIO.composite(snapshot, s.lineOverlay)
                thumb = Bitmap.createScaledBitmap(composite, 300, 400, true)
                container.artworks.save(id, snapshot, thumb, progress)
                ThumbCache.invalidate("file:${container.artworks.thumbFile(id).absolutePath}")
            } finally {
                // Large 1200×1600 buffers: free eagerly (3 GB device).
                if (thumb !== composite) thumb?.recycle()
                composite?.recycle()
                snapshot.recycle()
            }
        })
    }

    LaunchedEffect(controller) {
        controller.onCommitted = { saveTicks.value = saveTicks.value + 1 }
        saveTicks.drop(1).debounce(600).collect { saveNow() }
    }
    DisposableEffect(controller) {
        onDispose {
            controller.onCommitted = null
            if (saveTicks.value > 0) saveNow(notifyNav = false)
            canvasView?.release()
            canvasView = null
            // Session bitmaps are freed only after every in-flight save / export (which composite
            // lineOverlay) has finished.
            container.appScope.launch {
                ioJobs.joinAll()
                s.recycle()
            }
        }
    }
    fun pickFromRef(fx: Float, fy: Float) {
        val ref = s.ref ?: return
        val x = (fx * ref.width).toInt().coerceIn(0, ref.width - 1)
        val y = (fy * ref.height).toInt().coerceIn(0, ref.height - 1)
        val c = ref.getPixel(x, y)
        val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        if (lum < 40) {
            message = "那是黑线哦，点一下色块吸色吧"
        } else {
            color = c or (0xFF shl 24)
            message = "已吸取参考色 ${PaletteExtractor.toHex(c)}"
            if (tool == Tool.ERASER) tool = Tool.FILL
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Top toolbar (56dp) ──
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val m = Modifier.weight(1f).fillMaxHeight()
            EinkButton("← 返回", m, fontSize = 15) { onBack() }
            EinkButton("🪣 填色", m, selected = tool == Tool.FILL, fontSize = 15) { tool = Tool.FILL }
            EinkButton("🖍️ 蜡笔", m, selected = tool == Tool.CRAYON, fontSize = 15) { tool = Tool.CRAYON }
            EinkButton("🧹 橡皮", m, selected = tool == Tool.ERASER, fontSize = 15) { tool = Tool.ERASER }
            EinkButton("🔒 不出线", m, selected = stayInside, fontSize = 15) {
                stayInside = !stayInside
                container.settings.update { it.copy(stayInsideLines = stayInside) }
            }
            EinkButton("↩️ 撤销", m, fontSize = 15) {
                if (controller.undo() == null) message = "没有可以撤销的步骤啦"
            }
            EinkButton(if (ghostHint) "✨ 清残影!" else "✨ 清残影", m, selected = ghostHint, fontSize = 15) {
                canvasView?.clearGhosting(); ghostHint = false
            }
            EinkButton("💾 导出", m, fontSize = 15) {
                val view = canvasView ?: return@EinkButton
                saveNow()
                val snapshot = view.paintBitmap.copy(Bitmap.Config.ARGB_8888, false)
                message = "正在导出高清 PNG…"
                ioJobs.track(container.appScope.launch {
                    val result = run {
                        var out: Bitmap? = null
                        try {
                            out = BitmapIO.composite(snapshot, s.lineOverlay)
                            val where = GalleryExporter.exportPng(context, out, "${s.page.title}_${System.currentTimeMillis()}")
                            currentArtworkId?.let { container.artworks.markExported(it) }
                            "✓ 已保存到相册 $where"
                        } catch (e: Exception) {
                            "导出失败：${e.message}"
                        } finally {
                            out?.recycle()
                            snapshot.recycle()
                        }
                    }
                    withContext(Dispatchers.Main) { message = result }
                })
            }
        }
        // ── Secondary row: brush size / zoom / reset ──
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (tool == Tool.FILL) "点一下色块就能填色" else "粗细：", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (tool != Tool.FILL) {
                BrushSize.entries.forEach { b ->
                    EinkButton(b.label, Modifier.width(64.dp).fillMaxHeight(), selected = brush == b, fontSize = 16) { brush = b }
                }
            }
            Spacer(Modifier.weight(1f))
            if (zoom > 1.01f) {
                EinkButton("🔍 ${"%.1f".format(zoom)}× 复位", Modifier.fillMaxHeight(), fontSize = 15) { canvasView?.resetZoom() }
            } else {
                Text("双指缩放 1–3×", fontSize = 14.sp, color = Eink.Muted)
            }
            EinkButton(if (confirmReset) "确认重置?" else "🔄 重置", Modifier.fillMaxHeight(), selected = confirmReset, fontSize = 15) {
                if (confirmReset) {
                    controller.reset(); confirmReset = false; celebrated = false
                } else {
                    confirmReset = true
                }
            }
        }
        // ── Canvas + floating reference PiP ──
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // Saved paint is already loaded into the controller's buffer.
                    ColoringCanvasView(ctx, controller, s.lineOverlay).also { v ->
                        v.refBitmap = s.ref
                        v.ghostVisible = ghost
                        v.onFilled = { container.sound.fill() }
                        v.onZoomChanged = { zoom = it }
                        v.onGhostHint = { ghostHint = it }
                        canvasView = v
                    }
                },
                update = { v -> if (v.ghostVisible != ghost) v.ghostVisible = ghost },
            )
            if (s.refThumb != null) {
                val pipModifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                when {
                    pipCollapsed -> EinkButton("🖼️ 看参考图", pipModifier, fontSize = 15) { pipCollapsed = false }
                    pipExpanded -> Column(
                        Modifier.align(Alignment.Center).fillMaxWidth(0.86f)
                            .background(Eink.White, Eink.Shape).border(3.dp, Eink.Ink, Eink.Shape).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("参考图 · 点任意位置吸色", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            EinkButton("关闭", fontSize = 15) { pipExpanded = false }
                        }
                        RefImage(s.ref ?: s.refThumb, Modifier.fillMaxWidth().aspectRatio(0.75f)) { fx, fy ->
                            pickFromRef(fx, fy); pipExpanded = false
                        }
                    }
                    else -> Column(
                        pipModifier.width(170.dp).background(Eink.White, Eink.Shape).border(Eink.Border, Eink.Ink, Eink.Shape).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("参考图(点吸色)", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        RefImage(s.refThumb, Modifier.fillMaxWidth().aspectRatio(0.75f)) { fx, fy -> pickFromRef(fx, fy) }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            EinkButton("⤢", Modifier.weight(1f), fontSize = 15, minHeight = 44.dp) { pipExpanded = true }
                            EinkButton("👁️", Modifier.weight(1f), selected = ghost, fontSize = 15, minHeight = 44.dp) {
                                ghost = !ghost
                                container.settings.update { it.copy(ghostUnderlay = ghost) }
                            }
                            EinkButton("收起", Modifier.weight(1f), fontSize = 13, minHeight = 44.dp) { pipCollapsed = true }
                        }
                    }
                }
            }
            message?.let { msg ->
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .background(Eink.Ink, Eink.Shape).einkClick { message = null }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(msg, color = Eink.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
        // ── Dual-row palette ──
        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("推荐色", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                val rec = s.recommended.take(6)
                rec.forEach { c -> ColorDot(c, selected = c == color, modifier = Modifier.weight(1f)) { color = c } }
                repeat(6 - rec.size) { Spacer(Modifier.weight(1f)) }
                EinkButton(if (stylusOnly) "✍️ 仅笔" else "👆 手笔", Modifier.width(104.dp), selected = stylusOnly, fontSize = 15) {
                    stylusOnly = !stylusOnly
                    container.settings.update { it.copy(stylusOnly = stylusOnly) }
                    message = if (stylusOnly) "仅笔作画(防误触)：手指只能缩放和拖动" else "手笔皆可涂色"
                }
            }
            KaleidoPalette.CRAYONS.chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c -> ColorDot(c.argb, selected = c.argb == color, modifier = Modifier.weight(1f)) { color = c.argb } }
                }
            }
        }
    }
}

@Composable
private fun RefImage(bmp: Bitmap, modifier: Modifier, onPick: (Float, Float) -> Unit) {
    Box(
        modifier.border(Eink.Border, Eink.Ink, RoundedCornerShape(6.dp)).pointerInput(bmp) {
            detectTapGestures { o -> onPick(o.x / size.width, o.y / size.height) }
        },
    ) {
        Image(bmp.asImageBitmap(), contentDescription = "参考涂色图", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
    }
}

/** Crayon dot: selected state = thick ink ring + raised (offset up), readable on E-Ink at a glance. */
@Composable
private fun ColorDot(argb: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(52.dp).einkClick(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .offset(y = if (selected) (-4).dp else 0.dp)
                .size(if (selected) 44.dp else 38.dp)
                .background(Color(argb), CircleShape)
                .border(if (selected) 5.dp else 2.dp, Eink.Ink, CircleShape),
        )
    }
}

private fun loadSession(container: AppContainer, pageId: String, artworkId: String?): CanvasSession? {
    val ctx = container.appContext
    val page = container.pages.findPage(pageId) ?: return null
    val lineRaw = BitmapIO.loadPageBitmap(ctx, page, ref = false) ?: return null
    val line = BitmapIO.fitToCanvas(lineRaw)
    if (line !== lineRaw) lineRaw.recycle()
    val w = line.width
    val h = line.height
    val ink = BitmapIO.inkMask(line)
    line.recycle()
    val overlay = BitmapIO.inkOverlay(ink, w, h)
    val ref = BitmapIO.loadPageBitmap(ctx, page, ref = true)?.let { raw ->
        BitmapIO.fitToCanvas(raw, w, h).also { if (it !== raw) raw.recycle() }
    }
    val refThumb = ref?.let { Bitmap.createScaledBitmap(it, 300, 400, true) }
    val paintBmp = artworkId?.let { container.artworks.loadPaint(it) }
    val paintPixels = paintBmp?.takeIf { it.width == w && it.height == h }?.let { BitmapIO.pixels(it) }
    paintBmp?.recycle()
    val controller = ColoringCanvasController(w, h, ink, paintPixels)
    val extracted = ref?.let { PaletteExtractor.extract(BitmapIO.pixels(it), count = 6, stride = 5) }.orEmpty()
    val recommended = extracted.ifEmpty { page.palette }.ifEmpty { KaleidoPalette.CRAYONS.take(6).map { it.argb } }
    return CanvasSession(page, controller, overlay, ref, refThumb, recommended)
}
