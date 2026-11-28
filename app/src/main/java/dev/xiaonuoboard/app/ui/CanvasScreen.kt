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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect as UiRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.xiaonuoboard.app.AppContainer
import dev.xiaonuoboard.app.data.BitmapIO
import dev.xiaonuoboard.app.data.ColoringPage
import dev.xiaonuoboard.app.data.GalleryExporter
import dev.xiaonuoboard.app.eink.DiagLog
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.KaleidoPalette
import dev.xiaonuoboard.app.engine.PaletteExtractor
import dev.xiaonuoboard.app.engine.Tool
import dev.xiaonuoboard.app.i18n.LocalAppStrings
import dev.xiaonuoboard.app.ui.canvas.ColoringCanvasView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    val str = LocalAppStrings.current
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
            Text(if (loaded.first) str.canvasLoadError else str.canvasLoading, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (loaded.first) {
                Spacer(Modifier.height(16.dp))
                EinkButton(str.backArrow) { onBack() }
            }
        }
        return
    }

    val controller = s.controller
    val rootView = LocalView.current
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
    var showAllColors by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var ghostHint by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var celebrated by remember { mutableStateOf(false) }
    /** Bumped on reset so an in-flight save's celebration from before the reset is dropped. */
    var resetCount by remember { mutableIntStateOf(0) }
    val saveTicks = remember { MutableStateFlow(0) }
    /** An auto-save fired mid-stroke; it is re-queued as soon as that stroke ends. */
    val saveDeferred = remember { AtomicBoolean(false) }
    var canvasView by remember { mutableStateOf<ColoringCanvasView?>(null) }
    // Window bounds of overlays drawn ON TOP of the canvas: the BOOX pen layer must not draw there
    // (and pen taps on them must reach Compose), so they are excluded from TouchHelper raw drawing.
    var refButtonBounds by remember { mutableStateOf<UiRect?>(null) }
    var messageBounds by remember { mutableStateOf<UiRect?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val zoomLabel = if (zoom > 1.01f) "${String.format(Locale.US, "%.1f", zoom)}×" else null
    // Every piece of Compose chrome UI outside the SurfaceView canvas. While BOOX TouchHelper is
    // in PEN_DRAWING (2), SurfaceFlinger freezes standard Activity window updates; whenever any
    // toolbar / brush / palette / PiP / toast state changes, notify the canvas so it briefly
    // pauses PEN_DRAWING, refreshes rootView in NORMAL (GU), and resumes after settle.
    val chromeKey = listOf(
        tool,
        brush,
        color,
        stayInside,
        stylusOnly,
        ghost,
        pipExpanded,
        pipCollapsed,
        showAllColors,
        zoomLabel,
        confirmReset,
        message,
    )
    var chromeMounted by remember(s) { mutableStateOf(false) }
    LaunchedEffect(s, chromeKey) {
        if (!chromeMounted) {
            chromeMounted = true
            OnyxEinkBridge.setViewDefaultMode(rootView, EinkMode.NORMAL)
            OnyxEinkBridge.refreshHostUi(rootView, EinkMode.NORMAL)
            return@LaunchedEffect
        }
        val view = canvasView
        if (view != null && !view.isReleased) {
            view.notifyChromeUiChanged(rootView)
        } else {
            OnyxEinkBridge.refreshHostUi(rootView, EinkMode.NORMAL)
        }
    }

    // Push UI state into the engine.
    controller.tool = tool
    controller.brushSize = brush
    controller.color = color
    controller.stayInsideLines = stayInside
    controller.stylusOnly = stylusOnly

    /**
     * Auto-save. The UI thread only copies the paint IntArray (~2 ms); the progress scan, Bitmap
     * creation, compositing and PNG encoding run on [AppContainer.appScope] (IO). A save that comes
     * due while the child is mid-stroke is deferred to the end of that stroke so it never steals
     * UI-thread time from the pen.
     */
    fun saveNow(notifyNav: Boolean = true): Job? {
        val view = canvasView ?: return null
        if (view.isReleased) return null
        if (notifyNav && view.isStroking) {
            saveDeferred.set(true)
            DiagLog.log(DiagLog.SAVE, "autosave deferred (mid-stroke)")
            return null
        }
        saveDeferred.set(false)
        val snapT0 = System.nanoTime()
        val pixels = view.snapshotPixels()
        val snapshotNanos = System.nanoTime() - snapT0
        val existing = currentArtworkId
        val id = existing ?: container.artworks.create(s.page).id.also {
            currentArtworkId = it
            // Never touch navigation while the screen is being disposed (back already popped it).
            if (notifyNav) onArtworkCreated(it)
        }
        val mayCelebrate = notifyNav && !celebrated
        val resetsAtSnapshot = resetCount
        val celebrationText = str.canvasCelebration
        return ioJobs.track(container.appScope.launch {
            val bgT0 = System.nanoTime()
            val progress = controller.progress(pixels)
            if (mayCelebrate && progress >= 0.9f) {
                withContext(Dispatchers.Main) {
                    // The save outlives the screen: only celebrate if this canvas is still showing and
                    // the page was not cleared since the snapshot.
                    if (!celebrated && !view.isReleased && resetCount == resetsAtSnapshot) {
                        celebrated = true
                        message = celebrationText
                        container.sound.cheer()
                    }
                }
            }
            var snapshot: Bitmap? = null
            var composite: Bitmap? = null
            var thumb: Bitmap? = null
            try {
                snapshot = Bitmap.createBitmap(pixels, controller.width, controller.height, Bitmap.Config.ARGB_8888)
                composite = BitmapIO.composite(snapshot, s.lineOverlay)
                thumb = Bitmap.createScaledBitmap(composite, 300, 400, true)
                container.artworks.save(id, snapshot, thumb, progress)
                ThumbCache.invalidate("file:${container.artworks.thumbFile(id).absolutePath}")
                DiagLog.log(DiagLog.SAVE) {
                    "autosave snapshot(UI)=${DiagLog.ms(snapshotNanos)}ms bgSave=${DiagLog.ms(System.nanoTime() - bgT0)}ms " +
                        "progress=${"%.2f".format(Locale.US, progress)} final=${!notifyNav}"
                }
            } catch (e: Exception) {
                DiagLog.log(DiagLog.SAVE, "autosave FAILED: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                // Large 1200×1600 buffers: free eagerly (3 GB device).
                if (thumb !== composite) thumb?.recycle()
                composite?.recycle()
                snapshot?.recycle()
            }
        })
    }

    // The inline reset confirmation expires on its own (it also suspends BOOX raw drawing).
    LaunchedEffect(confirmReset) {
        if (confirmReset) {
            delay(CONFIRM_RESET_TIMEOUT_MS)
            confirmReset = false
        }
    }
    // Activity paused (screen off, another app, system dialog…) → BOOX pen layer off until resumed.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> canvasView?.setHostResumed(true)
                Lifecycle.Event.ON_PAUSE -> canvasView?.setHostResumed(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            message = str.canvasEyedropperBlackLine
        } else {
            color = c or (0xFF shl 24)
            message = str.canvasEyedropperPicked(PaletteExtractor.toHex(c))
            if (tool == Tool.ERASER) tool = Tool.FILL
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Top toolbar (68dp, big custom vector icons for kids — zero text, zero emoji) ──
        Row(
            Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val m = Modifier.weight(1f).fillMaxHeight()
            EinkIconButton(m, onClick = { canvasView?.prepareForExit(rootView); onBack() }) { tint -> KidIcons.BackArrow(tint) }
            EinkIconButton(m, selected = tool == Tool.CRAYON, onClick = { tool = Tool.CRAYON }) { tint -> KidIcons.Crayon(tint) }
            EinkIconButton(m, selected = tool == Tool.FILL, onClick = { tool = Tool.FILL }) { tint -> KidIcons.PaintBucket(tint) }
            EinkIconButton(m, selected = tool == Tool.ERASER, onClick = { tool = Tool.ERASER }) { tint -> KidIcons.Eraser(tint) }
            EinkIconButton(
                m,
                selected = stayInside,
                onClick = {
                    stayInside = !stayInside
                    container.settings.update { it.copy(stayInsideLines = stayInside) }
                },
            ) { tint -> KidIcons.ClipLock(stayInside, tint) }
            EinkIconButton(
                m,
                onClick = { if (controller.undo() == null) message = str.canvasNothingToUndo },
            ) { tint -> KidIcons.Undo(tint) }
            EinkIconButton(
                m,
                selected = ghostHint,
                onClick = { canvasView?.clearGhosting(rootView); ghostHint = false },
            ) { tint -> KidIcons.SparkleRefresh(tint) }
            EinkIconButton(
                m,
                onClick = {
                    val view = canvasView ?: return@EinkIconButton
                    if (view.isReleased) return@EinkIconButton
                    saveNow()
                    val pixels = view.snapshotPixels()
                    val currentStrings = str
                    val exportTitle = s.page.localizedTitle(currentStrings.language)
                    message = currentStrings.canvasExportingPng
                    ioJobs.track(container.appScope.launch {
                        val result = run {
                            var snapshot: Bitmap? = null
                            var out: Bitmap? = null
                            try {
                                snapshot = Bitmap.createBitmap(pixels, controller.width, controller.height, Bitmap.Config.ARGB_8888)
                                out = BitmapIO.composite(snapshot, s.lineOverlay)
                                val where = GalleryExporter.exportPng(context, out, "${exportTitle}_${System.currentTimeMillis()}", currentStrings)
                                currentArtworkId?.let { container.artworks.markExported(it) }
                                currentStrings.canvasExportSuccess(where)
                            } catch (e: Exception) {
                                currentStrings.exportFailed(e.message)
                            } finally {
                                out?.recycle()
                                snapshot?.recycle()
                            }
                        }
                        withContext(Dispatchers.Main) { message = result }
                    })
                },
            ) { tint -> KidIcons.SaveExport(tint) }
        }
        // ── Secondary row: proportional brush size dots (no text) / zoom reset / reset page ──
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tool != Tool.FILL) {
                val dotSizes = listOf(BrushSize.THIN to 11.dp, BrushSize.MEDIUM to 19.dp, BrushSize.THICK to 27.dp)
                dotSizes.forEach { (b, dp) ->
                    EinkIconButton(
                        Modifier.width(64.dp).fillMaxHeight(),
                        selected = brush == b,
                        minHeight = 48.dp,
                        onClick = { brush = b },
                    ) { tint -> KidIcons.BrushDot(dp, tint, b.label(str)) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (zoomLabel != null) {
                EinkButton(zoomLabel, Modifier.fillMaxHeight(), fontSize = 15, minHeight = 48.dp) {
                    canvasView?.resetZoom()
                }
            }
            if (confirmReset) {
                EinkButton(str.canvasConfirmReset, Modifier.fillMaxHeight(), selected = true, fontSize = 15, minHeight = 48.dp) {
                    controller.reset(); confirmReset = false; celebrated = false; resetCount++
                }
            } else {
                EinkIconButton(Modifier.width(64.dp).fillMaxHeight(), minHeight = 48.dp, onClick = { confirmReset = true }) { tint ->
                    KidIcons.ResetPage(tint)
                }
            }
        }
        // ── Canvas (left, never occluded) + side-docked reference PiP (right) ──
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidView(
                    // Before API 31 (BOOX Nova Air C runs Android 11 / API 30), SurfaceView
                    // punches its hole with an unbounded canvas.drawColor(0, PorterDuff.Mode.CLEAR).
                    // Compose's AndroidViewsHandler disables clipChildren, so without clipToBounds()
                    // that CLEAR erases everything drawn earlier in the window (the top toolbar and
                    // brush row). Mirrors androidx.media3.ui.compose.PlayerSurface.
                    modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
                    factory = { ctx ->
                        // Saved paint is already loaded into the controller's buffer.
                        ColoringCanvasView(ctx, controller, s.lineOverlay).also { v ->
                            v.refBitmap = s.ref
                            v.ghostVisible = ghost
                            v.onFilled = { container.sound.fill() }
                            v.onZoomChanged = { zoom = it }
                            v.onGhostHint = { ghostHint = it }
                            // Re-queue (debounced) an auto-save that was deferred because it came due mid-stroke.
                            v.onStrokeEnded = { if (saveDeferred.getAndSet(false)) saveTicks.value = saveTicks.value + 1 }
                            v.setHostResumed(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            canvasView = v
                        }
                    },
                    update = { v ->
                        if (v.ghostVisible != ghost) v.ghostVisible = ghost
                        // BOOX TouchHelper: armed for crayon / eraser only (never the bucket), and
                        // suspended while the enlarged reference or the reset confirmation is up.
                        v.syncToolState(tool, brush, color, suspendRaw = pipExpanded || confirmReset)
                        v.setRawExcludeRectsInWindow(
                            listOfNotNull(
                                refButtonBounds?.takeIf { s.refThumb != null && pipCollapsed },
                                messageBounds?.takeIf { message != null },
                            ).map { it.toAndroidRect() },
                        )
                    },
                )
                if (s.refThumb != null && !pipCollapsed && !pipExpanded) {
                    Column(
                        Modifier
                            .width(156.dp)
                            .padding(top = 4.dp)
                            .background(Eink.White, Eink.Shape)
                            .border(Eink.Border, Eink.Ink, Eink.Shape)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RefImage(s.refThumb, Modifier.fillMaxWidth().aspectRatio(0.75f)) { fx, fy -> pickFromRef(fx, fy) }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            EinkIconButton(Modifier.weight(1f), minHeight = 44.dp, onClick = { pipExpanded = true }) { tint ->
                                KidIcons.Expand(tint)
                            }
                            EinkIconButton(
                                Modifier.weight(1f),
                                selected = ghost,
                                minHeight = 44.dp,
                                onClick = {
                                    ghost = !ghost
                                    container.settings.update { it.copy(ghostUnderlay = ghost) }
                                },
                            ) { tint -> KidIcons.Eye(ghost, tint) }
                            EinkIconButton(Modifier.weight(1f), minHeight = 44.dp, onClick = { pipCollapsed = true }) { tint ->
                                KidIcons.Collapse(tint)
                            }
                        }
                    }
                }
            }
            if (s.refThumb != null && pipCollapsed) {
                EinkIconButton(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).onGloballyPositioned { refButtonBounds = it.boundsInWindow() },
                    onClick = { pipCollapsed = false },
                ) { tint ->
                    KidIcons.RefPicture(tint)
                }
            }
            if (s.refThumb != null && pipExpanded) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.86f)
                        .background(Eink.White, Eink.Shape).border(3.dp, Eink.Ink, Eink.Shape).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str.canvasRefExpandedTitle, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        EinkButton(str.close, fontSize = 15) { pipExpanded = false }
                    }
                    RefImage(s.ref ?: s.refThumb, Modifier.fillMaxWidth().aspectRatio(0.75f)) { fx, fy ->
                        pickFromRef(fx, fy); pipExpanded = false
                    }
                }
            }
            message?.let { msg ->
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .onGloballyPositioned { messageBounds = it.boundsInWindow() }
                        .background(Eink.Ink, Eink.Shape).einkClick { message = null }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(msg, color = Eink.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
        // ── Palette: Recommended 6 colors always visible; full 16-color tray collapsed by default ──
        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EinkIconButton(
                    Modifier.width(64.dp),
                    selected = showAllColors,
                    onClick = { showAllColors = !showAllColors },
                ) { tint -> KidIcons.MoreColors(showAllColors, tint) }
                val rec = s.recommended.take(6)
                rec.forEach { c -> ColorDot(c, selected = c == color, modifier = Modifier.weight(1f)) { color = c } }
                repeat(6 - rec.size) { Spacer(Modifier.weight(1f)) }
                EinkIconButton(
                    Modifier.width(64.dp),
                    selected = stylusOnly,
                    onClick = {
                        stylusOnly = !stylusOnly
                        container.settings.update { it.copy(stylusOnly = stylusOnly) }
                        message = if (stylusOnly) str.canvasStylusOnlyOn else str.canvasStylusOnlyOff
                    },
                ) { tint -> KidIcons.StylusMode(stylusOnly, tint) }
            }
            if (showAllColors) {
                KaleidoPalette.CRAYONS.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c -> ColorDot(c.argb, selected = c.argb == color, modifier = Modifier.weight(1f)) { color = c.argb } }
                    }
                }
            }
        }
    }
}

private const val CONFIRM_RESET_TIMEOUT_MS = 5_000L

private fun UiRect.toAndroidRect() =
    android.graphics.Rect(left.toInt(), top.toInt(), kotlin.math.ceil(right).toInt(), kotlin.math.ceil(bottom).toInt())

@Composable
private fun RefImage(bmp: Bitmap, modifier: Modifier, onPick: (Float, Float) -> Unit) {
    val s = LocalAppStrings.current
    Box(
        modifier.border(Eink.Border, Eink.Ink, RoundedCornerShape(6.dp)).pointerInput(bmp) {
            detectTapGestures { o -> onPick(o.x / size.width, o.y / size.height) }
        },
    ) {
        Image(bmp.asImageBitmap(), contentDescription = s.cdRefImage, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
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
