package dev.xiaonuoboard.app.ui.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import dev.xiaonuoboard.app.eink.DiagLog
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.eink.OnyxTouchHelperBackend
import dev.xiaonuoboard.app.eink.PenBurst
import dev.xiaonuoboard.app.eink.RawDrawingController
import dev.xiaonuoboard.app.eink.RawDrawingGeometry
import dev.xiaonuoboard.app.eink.RawStrokeSink
import dev.xiaonuoboard.app.eink.RefreshCoalescer
import dev.xiaonuoboard.app.eink.StrokeStats
import dev.xiaonuoboard.app.eink.TraceBounds
import dev.xiaonuoboard.app.eink.UiScheduler
import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.InputRouter
import dev.xiaonuoboard.app.engine.PointerKind
import dev.xiaonuoboard.app.engine.RawPressureNormalizer
import dev.xiaonuoboard.app.engine.StrokePressure
import dev.xiaonuoboard.app.engine.Tool
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Dedicated `SurfaceView` coloring canvas. Composites (bottom→top): white paper → optional 20%
 * reference ghost → [TiledPaintLayer] → 1-bit black line art directly onto the surface's software
 * buffer (`lockCanvas(dirtyRect)` + `unlockCanvasAndPost`), with zero GPU texture upload overhead
 * and zero Choreographer vsync wait.
 *
 * BOOX E-Ink refresh architecture (3 clean modes):
 *  1. **FULL mode (TouchHelper raw drawing)**:
 *     - Stylus MotionEvents are suppressed in [dispatchTouchEvent] while raw drawing is armed so
 *       there is no duplicate 240 Hz input race (while still feeding 3-miss self-healing).
 *     - Live strokes draw directly via BOOX EPD firmware (`hwPreview = true`) using solid
 *       constant-width `STROKE_STYLE_PENCIL` mapped onto BOOX built-in Notes' proven vivid Kaleido
 *       hardware palette ([RawDrawingGeometry.hardwarePreviewColor]; eraser white) so every crayon
 *       renders immediately in bold, zero-latency real color even at light child pressure.
 *     - On Kaleido color E-Ink and Android ≤ 11 (where `HAND_WRITING_REPAINT_MODE` is silently
 *       swallowed while `PEN_DRAWING` freezes SurfaceFlinger), `PEN_DRAWING` stays continuously
 *       held across active strokes (2.5 s idle in free coloring / 1.2 s with Stay-Inside-Lines /
 *       1.0 s eraser, or immediately at natural breaks like switching color/tool, undo, zoom, or
 *       exit) — matching BOOX Notes' smooth one-hold-per-session feel with zero mid-drawing
 *       flashes — before [flushPenBurst] cleanly commits exact pastel shades, Stay-Inside-Lines
 *       clipping, and 1-bit black line art via [OnyxEinkBridge.withForcedPost]. Monochrome Android
 *       12+ devices with a valid `HAND_WRITING_REPAINT_MODE` constant use 500 ms zero-pause repaint.
 *     - Structural updates (undo, reset, fill, clearGhosting, ghost toggle, zoom/pan, cancelled
 *       stroke) hide the pen layer, push the update via [OnyxEinkBridge.withForcedPost], and resume
 *       after [OnyxEinkBridge.penResumeDelayMs].
 *  2. **EPD_ONLY mode**:
 *     - Software strokes enter transient `ANIMATION_QUALITY` ([OnyxEinkBridge.enterFastStroke]),
 *       throttle partial surface blits to ≤30 Hz ([EPD_STROKE_FRAME_INTERVAL_MS] = 33 ms) to avoid
 *       waveform piling, and exit to a single crisp `NORMAL` (`GU`) repaint at pen-up.
 *  3. **OFF mode / non-BOOX**:
 *     - Plain `SurfaceView` software partial blits at up to 60 Hz with zero Onyx SDK calls.
 */
@SuppressLint("ViewConstructor")
class ColoringCanvasView(
    context: Context,
    val controller: ColoringCanvasController,
    private val lineOverlay: Bitmap,
) : SurfaceView(context), SurfaceHolder.Callback {

    private val cw = controller.width
    private val ch = controller.height
    private val paintLayer = TiledPaintLayer(controller.paint, cw, ch)

    var refBitmap: Bitmap? = null
        set(value) {
            field = value
            if (ghostVisible) refreshWholeView(EinkMode.NORMAL)
        }
    var ghostVisible: Boolean = false
        set(value) {
            field = value
            refreshWholeView(EinkMode.NORMAL)
        }

    var onFilled: (() -> Unit)? = null
    var onStrokeFinished: (() -> Unit)? = null
    /** Fired after EVERY stroke end, committed or cancelled (e.g. to run an auto-save deferred mid-stroke). */
    var onStrokeEnded: (() -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    /** true once enough partial refreshes accumulated that the ✨ 清残影 button should be highlighted. */
    var onGhostHint: ((Boolean) -> Unit)? = null

    private val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint() // no filtering: keep 1-bit lines crisp
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 51 } // ~20%
    private val borderPaint = Paint().apply { color = 0xFF111111.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val canvasRect = Rect(0, 0, cw, ch)
    private val srcSubRect = Rect()

    private val baseMatrix = Matrix()
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private val matrixValues = FloatArray(9)
    private var viewScale = 1f
    private var surfaceW = 0
    private var surfaceH = 0
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f

    private var surfaceValid = false
    private var surfaceRenderedOnce = false
    private var lastRenderedW = 0
    private var lastRenderedH = 0
    private var strokesSinceFull = 0
    /** The BOOX hardware pen layer is drawing the current stroke: no surface blit until pen-up burst flush. */
    private var hwPreview = false
    private var stroking = false
    private var activeStrokeIsPen = false
    /** Union of everything the current stroke changed (incl. a cancel's rollback) → one clean refresh at the end. */
    private var strokeDirty = DirtyRect.EMPTY
    private val tmpRect = RectF()
    private val refreshes = RefreshCoalescer()
    private var flushScheduled = false
    private var pendingTransformRedraw = false
    private var lastFastBlitUptimeMs = 0L
    private var pendingArmRawAfterInteraction = false
    private var pendingChromeRefreshHost: View? = null
    private var pendingChromeRefreshMode: EinkMode = EinkMode.NORMAL
    private val flushRefresh = Runnable { flushPendingRefresh() }
    private val armRawSurface = Runnable { armRawSurfaceNow() }

    // Hardware pen trace bookkeeping (view px): the trace is NOT region-clipped and may extend past
    // the painted rect (不涂出线) or the canvas image, so its own bounds are refreshed with the burst.
    private val trace = TraceBounds()
    /** Committed pen strokes + hardware traces awaiting the debounced end-of-burst repaint. */
    private val penBurst = PenBurst()
    /** The current finger gesture changed what is shown (pan / zoom / finger paint) → pen layer held. */
    private var fingerVisual = false
    private var fingerTransformed = false
    private var lastRawStrokeEndUptime = Long.MIN_VALUE

    /** Raw BOOX pen pressure → [0, 1] (self-corrects a wrong reported maximum). */
    private val rawPressure = RawPressureNormalizer(OnyxEinkBridge.maxTouchPressure)
    // Diagnostics: per-stroke accumulator + the input currently being fed into the router.
    private val stats = StrokeStats()
    private var feedingRaw = false
    private var feedRawPressure = Float.NaN
    private var feedEventTime = 0L
    private var fillDone = false
    private var feeding = false
    private var endPending = false
    private var endDirty = DirtyRect.EMPTY
    private var endCancelled = false
    /** Last refresh issued, reported with the next logged frame. */
    private var lastFlushInfo: String? = null

    private var currentTool: Tool = controller.tool
    private var currentColor: Int = controller.color
    private var excludeWindowRects: List<Rect> = emptyList()
    private val screenLoc = IntArray(2)
    private val windowLoc = IntArray(2)
    private val visibleRect = Rect()
    private var initDone = false

    /** True while a crayon / eraser stroke is in progress (callers defer heavy work until it ends). */
    val isStroking: Boolean get() = stroking

    private val router: InputRouter = InputRouter(
        controller = controller,
        listener = object : InputRouter.Listener {
            override fun onTransform(scale: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {
                applyTransform(scale, focusX, focusY, dx, dy)
            }

            override fun onStrokeStart(kind: PointerKind, x: Float, y: Float, pressure: Float, time: Long) {
                stroking = true
                activeStrokeIsPen = isPen(kind)
                if (endPending) logStrokeEnd() // the stroke this one pre-empted (pen priority)
                if (DiagLog.enabled) {
                    stats.begin(
                        if (feedingRaw) StrokeStats.Source.RAW else StrokeStats.Source.MOTION, kind,
                        renderEnabledAtBegin = if (feedingRaw) raw.lastBeginRenderWasEnabled else raw.isRenderEnabled,
                        nowMs = SystemClock.uptimeMillis(), eventTimeMs = feedEventTime,
                    )
                    stats.point(pressure, feedRawPressure)
                }
                if (activeStrokeIsPen) {
                    // Pen priority supersedes any resting finger hold / fast transform state.
                    // If a zoom/pan changed viewMatrix without a final settle blit, push it now so
                    // surface pixels match viewMatrix before the new stroke begins.
                    if (fingerVisual) {
                        val needsTransformFlush = fingerTransformed || pendingTransformRedraw
                        fingerVisual = false
                        fingerTransformed = false
                        pendingTransformRedraw = false
                        OnyxEinkBridge.exitFastStroke(refresh = false)
                        if (needsTransformFlush && surfaceValid && !paintLayer.isReleased) {
                            OnyxEinkBridge.withForcedPost(
                                this@ColoringCanvasView,
                                EinkMode.NORMAL,
                                forceUnfreeze = raw.isOpen,
                            ) { renderToSurface(null) }
                        }
                    }
                    startPenLayerStroke(x, y)
                } else {
                    beginFingerVisual()
                }
            }

            override fun onStrokePoint(x: Float, y: Float, pressure: Float, time: Long) {
                if (hwPreview) trace.add(x, y)
                stats.point(pressure, feedRawPressure)
            }

            override fun onStrokeEnd(rect: DirtyRect, x: Float, y: Float, pressure: Float, time: Long, cancelled: Boolean) {
                stroking = false
                val wasPen = activeStrokeIsPen
                activeStrokeIsPen = false
                // Union of everything synced during the stroke, so a cancelled stroke's rollback
                // (reported with an empty rect) is also repainted cleanly.
                val all = strokeDirty.union(rect.clamp(cw, ch))
                strokeDirty = DirtyRect.EMPTY
                if (stats.active) {
                    endDirty = all
                    endCancelled = cancelled
                    endPending = true
                    // Inside a feed the summary waits for the feed's CPU time (strokeEnd's undo
                    // commit included); a cancel from outside any feed (release, detach) logs now.
                    if (!feeding) logStrokeEnd()
                }
                if (hwPreview) {
                    if (!cancelled) trace.add(x, y)
                    endPenLayerStroke(all, cancelled)
                } else {
                    if (wasPen && raw.isEnabled) raw.onPenStrokeEnd(cancelled)
                    // Exit transient ANIMATION_QUALITY before final clean color repaint.
                    OnyxEinkBridge.exitFastStroke(refresh = false)
                    removeCallbacks(flushRefresh)
                    flushScheduled = false
                    val pendingRect = refreshes.drain()?.rect ?: DirtyRect.EMPTY
                    val finalDirty = all.union(pendingRect)
                    if (!finalDirty.isEmpty) {
                        val viewRect = canvasToViewRect(finalDirty)
                        if (viewRect.intersect(0, 0, effectiveWidth(), effectiveHeight())) {
                            val t0 = System.nanoTime()
                            val info = "refresh=NORMAL rect=${viewRect.toShortString()}"
                            if (DiagLog.enabled && !stats.active) lastFlushInfo = info
                            OnyxEinkBridge.withForcedPost(
                                this@ColoringCanvasView,
                                EinkMode.NORMAL,
                                forceUnfreeze = raw.isOpen,
                            ) { renderToSurface(viewRect) }
                            if (DiagLog.enabled && !stats.active && lastFlushInfo != null) {
                                val logged = lastFlushInfo
                                lastFlushInfo = null
                                DiagLog.log(DiagLog.DRAW) {
                                    "strokeSettle $logged total=${DiagLog.ms(System.nanoTime() - t0)}ms"
                                }
                            }
                        }
                    }
                    // If this stroke ran in software mode during a pen-layer pause window, resume
                    // the hardware pen layer after the stroke's NORMAL repaint settles.
                    if (raw.isEnabled && !raw.isRenderEnabled && !fingerVisual) {
                        raw.schedulePenLayerResume()
                    }
                }
                if (!cancelled) {
                    strokesSinceFull++
                    onGhostHint?.invoke(strokesSinceFull >= GHOST_HINT_EVERY)
                    onStrokeFinished?.invoke()
                }
                onStrokeEnded?.invoke()
                flushPendingChromeRefresh()
                maybeReschedulePendingRawArm()
            }

            override fun onFilled(rect: DirtyRect) {
                fillDone = true
                strokesSinceFull++
                onGhostHint?.invoke(strokesSinceFull >= GHOST_HINT_EVERY)
                onFilled?.invoke()
            }
        },
        toCanvas = { p -> inverse.mapPoints(p) },
        tapSlop = TAP_SLOP_PX,
        palmTouchMajor = PALM_TOUCH_MAJOR_PX,
    )

    /** TouchHelper raw strokes → the router, as pointer [RAW_POINTER_ID] (view-local px). */
    private val rawSink: RawStrokeSink = object : RawStrokeSink {
        override fun rawStrokeBegin(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean) {
            val kind = if (erasing) PointerKind.ERASER_TIP else PointerKind.STYLUS
            val t0 = System.nanoTime()
            feedRaw(pressure, time)
            router.down(RAW_POINTER_ID, kind, x, y, 0f, rawPressure.normalize(pressure), SystemClock.uptimeMillis())
            endFeed(t0)
        }

        override fun rawStrokeMove(x: Float, y: Float, pressure: Float, time: Long) {
            val t0 = System.nanoTime()
            feedRaw(pressure, time)
            router.move(RAW_POINTER_ID, x, y, rawPressure.normalize(pressure), SystemClock.uptimeMillis())
            endFeed(t0)
        }

        override fun rawStrokeEnd(x: Float, y: Float, pressure: Float, time: Long) {
            val t0 = System.nanoTime()
            feedRaw(Float.NaN, time) // the lift sample's pressure is not a drawing sample
            router.up(RAW_POINTER_ID, x, y, rawPressure.normalize(pressure), SystemClock.uptimeMillis())
            endFeed(t0)
            lastRawStrokeEndUptime = SystemClock.uptimeMillis()
        }

        override fun rawStrokeAbort() {
            router.cancelPointer(RAW_POINTER_ID)
            lastRawStrokeEndUptime = SystemClock.uptimeMillis()
        }
    }

    private val raw: RawDrawingController = RawDrawingController(
        available = { OnyxEinkBridge.isTouchHelperAvailable },
        backendFactory = { listener -> OnyxTouchHelperBackend(this, listener) },
        sink = rawSink,
        scheduler = object : UiScheduler {
            override fun postDelayed(task: Runnable, delayMs: Long) { this@ColoringCanvasView.postDelayed(task, delayMs) }
            override fun cancel(task: Runnable) { removeCallbacks(task) }
        },
        onFailure = { reason -> OnyxEinkBridge.reportRawDrawingFailure(reason) },
        // End of a pen burst (500 ms idle, tool/color/brush change, raw off, finger gesture, failure):
        // commits the real Kaleido color & crisp outlines onto the SurfaceView.
        onPenUpFlush = { flushPenBurst() },
        resumeDelayMs = { OnyxEinkBridge.penResumeDelayMs },
        requireSurface = true,
    )

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { syncRawBounds() }

    init {
        // Default SurfaceView Z-order (setZOrderOnTop(false)) keeps Compose overlays on top.
        holder.addCallback(this)
        controller.onDirty = { r -> syncRect(r) }
        isFocusable = true
        initDone = true
    }

    /** Whether TouchHelper raw drawing is currently armed (diagnostics). */
    val isRawDrawingActive: Boolean get() = raw.isEnabled

    /**
     * Pushes tool / brush / color and overlay state (call on every UI state change). Raw drawing is
     * only armed for freehand tools — never the paint bucket — and is suspended while an overlay or
     * confirmation covers the canvas so the pen layer never draws over it.
     */
    fun syncToolState(tool: Tool, brush: BrushSize, color: Int, suspendRaw: Boolean) {
        val colorChanged = color != currentColor
        currentTool = tool
        currentColor = color
        if (colorChanged && !penBurst.isEmpty) {
            // Even when two shades share the same BOOX hardware preview hue, flush any pending
            // burst immediately when the user switches logical crayon color (natural break).
            raw.flushPenUpNow()
        }
        raw.setStroke(
            rawStrokeWidthPx(tool, brush),
            RawDrawingGeometry.hardwarePreviewColor(color, eraser = tool == Tool.ERASER),
            pressureSensitive = false,
        )
        raw.setSuspended(suspendRaw)
        raw.setToolAllowsRaw(tool == Tool.CRAYON || tool == Tool.ERASER)
    }

    /** Host (activity) resumed / paused. */
    fun setHostResumed(resumed: Boolean) {
        if (!resumed) OnyxEinkBridge.exitFastStroke(refresh = false)
        raw.setHostResumed(resumed)
    }

    /** Window-coordinate bounds of overlays drawn on top of the canvas (excluded from raw drawing). */
    fun setRawExcludeRectsInWindow(rects: List<Rect>) {
        if (rects == excludeWindowRects) return
        excludeWindowRects = rects
        syncRawBounds()
    }

    fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        updateMatrix()
        onZoomChanged?.invoke(zoom)
        refreshWholeView(EinkMode.NORMAL)
    }

    /**
     * Briefly unfreezes BOOX `PEN_DRAWING` (`hidePenLayer` → `PEN_PAUSE`) so Compose chrome changes
     * on [hostView] (top toolbar, brush row, PiP, bottom palette, toasts) post cleanly to the EPD,
     * then resumes hardware raw drawing after [OnyxEinkBridge.penResumeDelayMs].
     *
     * Deferred while a stroke or pinch/pan gesture is in flight so live strokes and zoom never stutter.
     * Never called during continuous canvas coloring — zero impact on handwriting speed.
     */
    fun notifyChromeUiChanged(hostView: View, mode: EinkMode = EinkMode.NORMAL) {
        if (!initDone || !OnyxEinkBridge.isAvailable) return
        if (hwPreview || stroking || fingerVisual) {
            pendingChromeRefreshMode = if (pendingChromeRefreshHost == null) {
                mode
            } else if (mode == EinkMode.NORMAL) {
                EinkMode.NORMAL
            } else {
                pendingChromeRefreshMode
            }
            pendingChromeRefreshHost = hostView
            return
        }
        pendingChromeRefreshHost = null
        pendingChromeRefreshMode = EinkMode.FAST
        val hadBurst = !penBurst.isEmpty
        val wasLive = raw.isOpen && raw.isEnabled && raw.isRenderEnabled
        if (wasLive) {
            raw.hidePenLayer()
        }
        if (hadBurst && surfaceValid && !paintLayer.isReleased) {
            flushPenBurst()
        }
        OnyxEinkBridge.refreshHostUi(hostView, mode)
        OnyxEinkBridge.enablePost(this, 1)
        DiagLog.log(DiagLog.DRAW) { "chromeRefresh mode=${mode.name} wasLive=$wasLive hadBurst=$hadBurst rawOpen=${raw.isOpen}" }
        if (raw.isOpen && raw.isEnabled && !raw.isRenderEnabled) {
            raw.schedulePenLayerResume()
        } else if (!raw.isOpen && surfaceValid && OnyxEinkBridge.isTouchHelperAvailable) {
            scheduleInitialRawArm()
        }
    }

    /** Leaves BOOX scribble freeze immediately before popping the canvas screen (Back button). */
    fun prepareForExit(hostView: View) {
        if (!initDone || !OnyxEinkBridge.isAvailable) return
        removeCallbacks(armRawSurface)
        pendingArmRawAfterInteraction = false
        pendingChromeRefreshHost = null
        OnyxEinkBridge.exitFastStroke(refresh = false)
        raw.hidePenLayer()
        OnyxEinkBridge.enablePostGlobal(1)
        OnyxEinkBridge.enablePost(hostView, 1)
    }

    private fun flushPendingChromeRefresh() {
        val host = pendingChromeRefreshHost ?: return
        if (hwPreview || stroking || fingerVisual) return
        notifyChromeUiChanged(host, pendingChromeRefreshMode)
    }

    fun clearGhosting(hostView: View? = null) {
        strokesSinceFull = 0
        onGhostHint?.invoke(false)
        if (!initDone) return
        pendingChromeRefreshHost = null
        penBurst.clear()
        refreshes.clear()
        removeCallbacks(flushRefresh)
        flushScheduled = false
        pendingTransformRedraw = false
        OnyxEinkBridge.exitFastStroke(refresh = false)
        raw.hidePenLayer()
        DiagLog.log(DiagLog.DRAW, "clearGhosting (GC)")
        if (OnyxEinkBridge.isAvailable) {
            if (hostView != null) {
                OnyxEinkBridge.enablePostGlobal(1)
                OnyxEinkBridge.enablePost(hostView, 1)
                hostView.invalidate()
            }
            // Push latest pixels without tagging a duplicate GC flash, then issue one global GC flash.
            OnyxEinkBridge.withForcedPost(this, mode = null, forceUnfreeze = raw.isOpen) { renderToSurface(null) }
            if (!OnyxEinkBridge.repaintEverything(EinkMode.FULL)) {
                OnyxEinkBridge.withForcedPost(this, mode = EinkMode.FULL, forceUnfreeze = raw.isOpen) { renderToSurface(null) }
            }
        } else {
            renderToSurface(null)
        }
        raw.schedulePenLayerResume()
    }

    /** Structural full-surface refresh with the BOOX pen layer hidden around it. */
    private fun refreshWholeView(mode: EinkMode) {
        if (!initDone || !surfaceValid) return
        if (hwPreview) return // mid-stroke: shown by the pen-up burst repaint
        penBurst.clear()
        refreshes.clear()
        removeCallbacks(flushRefresh)
        flushScheduled = false
        pendingTransformRedraw = false
        raw.hidePenLayer()
        OnyxEinkBridge.withForcedPost(this, mode, forceUnfreeze = raw.isOpen) { renderToSurface(null) }
        raw.schedulePenLayerResume()
    }

    val isReleased: Boolean get() = paintLayer.isReleased

    /**
     * Copy of the current paint pixels (unpremultiplied ARGB, row-major `width × height`) for
     * auto-save / export. A plain array copy (~2 ms) is the only UI-thread cost; turning it into a
     * Bitmap, compositing and PNG encoding all happen on a background thread.
     */
    fun snapshotPixels(): IntArray = controller.paint.copyOf()

    /** Releases the display tiles and TouchHelper; call once the view has left the window. */
    fun release() {
        holder.removeCallback(this)
        removeCallbacks(armRawSurface)
        pendingArmRawAfterInteraction = false
        pendingChromeRefreshHost = null
        surfaceValid = false
        surfaceRenderedOnce = false
        lastRenderedW = 0
        lastRenderedH = 0
        raw.release()
        OnyxEinkBridge.exitFastStroke(refresh = false)
        abortActiveStroke()
        controller.onDirty = null
        clearPendingRefresh()
        paintLayer.release()
    }

    // ---------------- SurfaceView hole-punch clipping (API ≤ 30) ----------------

    /**
     * Defense-in-depth alongside `Modifier.clipToBounds()` on the Compose `AndroidView`: on API ≤ 30
     * (Android 11 / BOOX Nova Air C), `SurfaceView.clearSurfaceViewPort` punches its hole with an
     * unbounded `canvas.drawColor(0, PorterDuff.Mode.CLEAR)`. Clipping to our own bounds guarantees
     * the CLEAR punch-hole can never erase sibling Compose UI (top toolbar / brush row) drawn
     * earlier in the same window frame.
     */
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        try {
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        try {
            super.dispatchDraw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    // ---------------- SurfaceHolder.Callback ----------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Lightweight: Android always delivers surfaceChanged immediately with final dimensions.
        surfaceValid = true
        OnyxEinkBridge.enablePost(this, 1)
        DiagLog.log(DiagLog.CANVAS) { "surfaceCreated ${width}x$height touchHelper=${OnyxEinkBridge.isTouchHelperAvailable}" }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceValid = true
        val resized = width != lastRenderedW || height != lastRenderedH
        updateViewSize(width, height)
        OnyxEinkBridge.enablePost(this, 1)
        if (raw.isOpen) {
            raw.setSurfaceValid(true)
            syncRawBounds()
        }
        if (!surfaceRenderedOnce || resized) {
            // Clear stale burst / partial refresh work superseded by the full-surface redraw.
            penBurst.clear()
            refreshes.clear()
            removeCallbacks(flushRefresh)
            flushScheduled = false
            pendingTransformRedraw = false
            if (raw.isOpen) {
                // Keep PEN_DRAWING paused while the Compose palette/PiP resize finishes its
                // NORMAL refresh on the EPD, then resume after penResumeDelayMs.
                raw.hidePenLayer()
            }
            var rendered = false
            OnyxEinkBridge.withForcedPost(this, EinkMode.NORMAL, forceUnfreeze = raw.isOpen) {
                rendered = renderToSurface(null)
            }
            if (rendered) {
                surfaceRenderedOnce = true
                lastRenderedW = width
                lastRenderedH = height
            }
            if (raw.isOpen) {
                raw.schedulePenLayerResume()
            } else {
                // Let the initial Compose top toolbar / palette frame and NORMAL EPD refresh
                // settle before opening TouchHelper (which enters PEN_DRAWING and freezes window
                // updates). Any early stroke during this window draws via ANIMATION_QUALITY.
                scheduleInitialRawArm()
            }
        } else if (!raw.isOpen) {
            scheduleInitialRawArm()
        }
        DiagLog.log(DiagLog.CANVAS) {
            "surfaceChanged ${width}x$height resized=$resized rendered=$surfaceRenderedOnce rawOpen=${raw.isOpen}"
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        DiagLog.log(DiagLog.CANVAS, "surfaceDestroyed")
        removeCallbacks(armRawSurface)
        pendingArmRawAfterInteraction = false
        pendingChromeRefreshHost = null
        surfaceValid = false
        surfaceRenderedOnce = false
        lastRenderedW = 0
        lastRenderedH = 0
        raw.setSurfaceValid(false)
        OnyxEinkBridge.exitFastStroke(refresh = false)
        abortActiveStroke()
        clearPendingRefresh()
    }

    private fun scheduleInitialRawArm() {
        removeCallbacks(armRawSurface)
        pendingArmRawAfterInteraction = false
        if (!OnyxEinkBridge.isTouchHelperAvailable) {
            syncRawBounds()
            raw.setSurfaceValid(true)
            return
        }
        postDelayed(armRawSurface, OnyxEinkBridge.penResumeDelayMs)
    }

    private fun armRawSurfaceNow() {
        if (!surfaceValid || paintLayer.isReleased) return
        if (stroking || fingerVisual) {
            pendingArmRawAfterInteraction = true
            return
        }
        pendingArmRawAfterInteraction = false
        syncRawBounds()
        raw.setSurfaceValid(true)
    }

    private fun maybeReschedulePendingRawArm() {
        if (!pendingArmRawAfterInteraction || stroking || fingerVisual) return
        pendingArmRawAfterInteraction = false
        if (surfaceValid && !paintLayer.isReleased && !raw.isOpen) {
            removeCallbacks(armRawSurface)
            postDelayed(armRawSurface, OnyxEinkBridge.penResumeDelayMs)
        }
    }

    // ---------------- View lifecycle ----------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DiagLog.log(DiagLog.CANVAS) { "attached canvas=${cw}x$ch surfaceView=true touchHelper=${OnyxEinkBridge.isTouchHelperAvailable}" }
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        raw.setWindowFocused(hasWindowFocus())
        raw.setVisible(isEffectivelyVisible())
        raw.setAttached(true)
        syncRawBounds()
    }

    override fun onDetachedFromWindow() {
        DiagLog.log(DiagLog.CANVAS, "detached")
        removeCallbacks(armRawSurface)
        pendingArmRawAfterInteraction = false
        pendingChromeRefreshHost = null
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        raw.setAttached(false)
        OnyxEinkBridge.exitFastStroke(refresh = false)
        abortActiveStroke()
        clearPendingRefresh()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Dialogs, the notification shade, system install prompts… take focus: pen layer & fast mode off.
        if (!hasWindowFocus) OnyxEinkBridge.exitFastStroke(refresh = false)
        raw.setWindowFocused(hasWindowFocus)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (initDone) raw.setVisible(isEffectivelyVisible())
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (initDone) raw.setVisible(isEffectivelyVisible())
    }

    private fun isEffectivelyVisible(): Boolean = isShown && windowVisibility == VISIBLE

    /** Limit rect = the visible view bounds (view-local); excludes = overlays; plus screen position. */
    private fun syncRawBounds() {
        if (!initDone || !isAttachedToWindow) return
        if (width <= 0 || height <= 0 || !getLocalVisibleRect(visibleRect)) {
            raw.setBounds(DirtyRect.EMPTY, emptyList(), 0, 0)
            return
        }
        val limit = DirtyRect(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom)
        getLocationInWindow(windowLoc)
        val exclude = RawDrawingGeometry.windowToLocalExcludes(
            excludeWindowRects.map { DirtyRect(it.left, it.top, it.right, it.bottom) }, windowLoc[0], windowLoc[1], limit,
        )
        getLocationOnScreen(screenLoc)
        raw.setBounds(limit, exclude, screenLoc[0], screenLoc[1])
    }

    private fun abortActiveStroke() {
        OnyxEinkBridge.exitFastStroke(refresh = false)
        if (stroking || hwPreview) router.cancel()
        hwPreview = false
        stroking = false
        activeStrokeIsPen = false
        strokeDirty = DirtyRect.EMPTY
        trace.reset()
    }

    private fun clearPendingRefresh() {
        removeCallbacks(flushRefresh)
        flushScheduled = false
        pendingTransformRedraw = false
        refreshes.clear()
        penBurst.clear()
        fingerVisual = false
        fingerTransformed = false
    }

    // ---------------- diagnostics ----------------

    private fun feedRaw(rawPressureValue: Float, eventTime: Long) {
        feeding = true
        feedingRaw = true
        feedRawPressure = rawPressureValue
        feedEventTime = eventTime
    }

    private fun feedMotion(rawPressureValue: Float, eventTime: Long) {
        feeding = true
        feedingRaw = false
        feedRawPressure = rawPressureValue
        feedEventTime = eventTime
    }

    /** Ends one router call: charges its CPU time to the stroke and emits a pending summary. */
    private fun endFeed(startNanos: Long) {
        val spent = System.nanoTime() - startNanos
        if (stats.active) stats.addCpu(spent)
        feeding = false
        if (endPending) logStrokeEnd()
        if (fillDone) {
            fillDone = false
            DiagLog.log(DiagLog.FILL) {
                "fill+undo ${DiagLog.ms(spent)}ms undoTiles=${controller.undoStack.lastStepTileCount} undoSteps=${controller.undoStack.size}"
            }
        }
    }

    private fun logStrokeEnd() {
        endPending = false
        stats.end(SystemClock.uptimeMillis(), endDirty, endCancelled, controller.currentBrushRadius)?.let {
            DiagLog.log(DiagLog.STROKE, if (rawPressure.observedMax > 0f) "$it rawScale=${rawPressure.scaleMax}" else it)
        }
        endDirty = DirtyRect.EMPTY
    }

    // ---------------- BOOX pen layer (TouchHelper) ----------------

    private fun startPenLayerStroke(x: Float, y: Float) {
        if (!raw.isEnabled) {
            // EPD_ONLY / OFF / fallback: enter transient fast waveform for smooth software drawing.
            OnyxEinkBridge.enterFastStroke()
            return
        }
        // Only suppress software preview when TouchHelper raw input owns this stroke AND the
        // firmware pen layer was already live at pen-down (otherwise firmware missed pen-down).
        val liveAtBegin = feedingRaw && raw.lastBeginRenderWasEnabled
        raw.onPenStrokeStart()
        if (!raw.isEnabled) {
            OnyxEinkBridge.enterFastStroke()
            return
        }
        if (!liveAtBegin) {
            // Pen layer was paused at pen-down (e.g. undo/fill/resize settle): RawDrawingController
            // kept render off for this stroke, so run smooth software preview in ANIMATION_QUALITY
            // and resume the pen layer cleanly after pen-up.
            if (!raw.isRenderEnabled) OnyxEinkBridge.enterFastStroke()
            return
        }
        hwPreview = true
        removeCallbacks(flushRefresh)
        flushScheduled = false
        refreshes.drain()?.let { strokeDirty = strokeDirty.union(it.rect) }
        trace.reset()
        trace.add(x, y)
    }

    /** Hardware pen stroke ended: commit its areas to the burst and debounce the zero-pause repaint. */
    private fun endPenLayerStroke(all: DirtyRect, cancelled: Boolean) {
        hwPreview = false
        raw.onPenStrokeEnd(cancelled)
        penBurst.addStroke(all)
        trace.refreshRect(rawStrokeWidthPx(currentTool, controller.brushSize), TRACE_PAD_PX)?.let { penBurst.addTrace(it) }
        trace.reset()
        if (cancelled) {
            // Rolled back (aborted raw stroke, pen priority…): hide pen layer to clear trace, repaint now.
            raw.hidePenLayer()
            flushPenBurst()
            raw.schedulePenLayerResume()
        } else {
            // Keep the pen layer continuously live across strokes (like BOOX Notes); commit the
            // exact pastel shade, Stay-Inside-Lines clip, and 1-bit line art on idle or at breaks.
            val delayMs = RawDrawingController.penUpFlushDelayMs(
                zeroPauseRepaint = OnyxEinkBridge.shouldUseZeroPauseHandwritingRepaint,
                eraser = currentTool == Tool.ERASER,
                stayInsideLines = controller.stayInsideLines,
            )
            raw.schedulePenUpFlush(delayMs)
        }
    }

    /**
     * Pen-up burst commit onto the `SurfaceView`.
     *
     * On Kaleido color E-Ink and Android ≤ 11 (e.g. BOOX Nova Air C, where `HAND_WRITING_REPAINT_MODE`
     * is silently swallowed while `PEN_DRAWING` freezes SurfaceFlinger), cleanly hides the temporary
     * hardware trace (`hidePenLayer()`), posts a crisp partial `NORMAL` (`GU`) color repaint via
     * [OnyxEinkBridge.withForcedPost], and resumes `PEN_DRAWING` after [OnyxEinkBridge.penResumeDelayMs].
     * On monochrome Android 12+ devices with a valid `HAND_WRITING_REPAINT_MODE` constant, keeps the
     * zero-pause repaint path.
     */
    private fun flushPenBurst() {
        if (hwPreview) return
        raw.cancelPenUpFlush()
        removeCallbacks(flushRefresh)
        flushScheduled = false
        val flush = penBurst.take(refreshes.drain()) ?: return
        if (paintLayer.isReleased || !surfaceValid) return
        val rect = flushToViewRect(flush) ?: return
        val sw = effectiveWidth()
        val sh = effectiveHeight()
        // Mirror OnyxPenDemo's PARTIAL_REFRESH_MAX_AREA_RATIO (0.6): when the burst spans >=60% of
        // the surface, lock the full surface (null) to avoid partial-buffer copy-back overhead.
        val targetRect: Rect? = if (
            sw > 0 && sh > 0 &&
            rect.width().toLong() * rect.height().toLong() * 100L >= sw.toLong() * sh.toLong() * PARTIAL_REFRESH_MAX_AREA_PERCENT
        ) null else rect
        val t0 = System.nanoTime()
        val wasLive = raw.isEnabled && raw.isRenderEnabled
        val zeroPause = wasLive && OnyxEinkBridge.shouldUseZeroPauseHandwritingRepaint
        if (wasLive && !zeroPause) {
            raw.hidePenLayer()
        }
        val label = if (zeroPause) "HAND_WRITING_REPAINT" else flush.mode.name
        if (DiagLog.enabled && !stats.active) {
            lastFlushInfo = "refresh=$label rect=${targetRect?.toShortString() ?: "FULL"}" + if (flush.viewRect.isEmpty) "" else " +trace"
        }
        if (zeroPause) {
            OnyxEinkBridge.withHandwritingRepaint(this) { renderToSurface(targetRect) }
        } else {
            OnyxEinkBridge.withForcedPost(this, flush.mode, forceUnfreeze = raw.isOpen) { renderToSurface(targetRect) }
        }
        if (wasLive && !zeroPause) {
            raw.schedulePenLayerResume()
        }
        if (DiagLog.enabled && !stats.active && lastFlushInfo != null) {
            // No paint tiles needed upload (e.g. trace-only burst clipped by 不涂出线): still log it.
            val info = lastFlushInfo
            lastFlushInfo = null
            DiagLog.log(DiagLog.DRAW) { "burstFlush $info total=${DiagLog.ms(System.nanoTime() - t0)}ms" }
        }
    }

    /** The finger gesture changes what is shown: hold the pen layer hidden (flushes a pen burst). */
    private fun beginFingerVisual() {
        if (fingerVisual) return
        fingerVisual = true
        raw.setFingerGesture(true)
        OnyxEinkBridge.enterFastStroke()
    }

    private fun endFingerVisual() {
        if (!fingerVisual) return
        fingerVisual = false
        val wasTransformed = fingerTransformed
        fingerTransformed = false
        pendingTransformRedraw = false
        OnyxEinkBridge.exitFastStroke(refresh = false)
        if (wasTransformed && surfaceValid && !paintLayer.isReleased) {
            OnyxEinkBridge.withForcedPost(this, EinkMode.NORMAL, forceUnfreeze = raw.isOpen) { renderToSurface(null) }
        }
        flushPendingChromeRefresh()
        raw.setFingerGesture(false)
        maybeReschedulePendingRawArm()
    }

    /**
     * Hardware trace width in screen px: constant brush (eraser ×1.5) diameter at the current zoom.
     * Uses constant-width `STROKE_STYLE_PENCIL` so light toddler strokes stay bold and visible
     * (the committed crayon stroke in [controller] still applies full Wacom EMR pressure).
     */
    private fun rawStrokeWidthPx(tool: Tool, brush: BrushSize): Float =
        RawDrawingGeometry.traceWidthPx(brush.radius, tool == Tool.ERASER, viewScale, pressureSensitive = false)

    private fun syncRawStrokeWidth() {
        raw.setStroke(
            rawStrokeWidthPx(currentTool, controller.brushSize),
            RawDrawingGeometry.hardwarePreviewColor(controller.color, eraser = currentTool == Tool.ERASER),
            pressureSensitive = false,
        )
    }

    // ---------------- refresh & SurfaceView rendering ----------------

    /**
     * Marks changed paint pixels for upload and queues a coalesced surface blit, unless the BOOX
     * hardware pen layer owns the current stroke ([hwPreview]).
     */
    private fun syncRect(r: DirtyRect) {
        if (paintLayer.isReleased) return
        val c = r.clamp(cw, ch)
        if (c.isEmpty) return
        paintLayer.markDirty(c)
        if (stroking) strokeDirty = strokeDirty.union(c)
        if (hwPreview) return // hardware pen layer is showing the trace; zero-pause repaint on burst end
        requestRefresh(c, if (stroking) EinkMode.FAST else EinkMode.NORMAL)
    }

    /** Queues [c] (canvas coords) for a coalesced surface blit (throttled for FAST strokes on EPD). */
    private fun requestRefresh(c: DirtyRect, mode: EinkMode) {
        refreshes.request(c, mode)
        scheduleFlush(fast = mode == EinkMode.FAST)
    }

    private fun scheduleFlush(fast: Boolean) {
        if (!fast) {
            // Non-FAST updates (undo, fill, reset) must not wait behind an in-flight FAST throttle timer.
            removeCallbacks(flushRefresh)
            flushScheduled = true
            post(flushRefresh)
            return
        }
        if (flushScheduled) return
        flushScheduled = true
        val interval = if (OnyxEinkBridge.isAvailable) EPD_STROKE_FRAME_INTERVAL_MS else NON_BOOX_FRAME_INTERVAL_MS
        val elapsed = SystemClock.uptimeMillis() - lastFastBlitUptimeMs
        val delay = (interval - elapsed).coerceAtLeast(0L)
        if (delay > 0L) {
            postDelayed(flushRefresh, delay)
        } else {
            post(flushRefresh)
        }
    }

    private fun flushPendingRefresh() {
        flushScheduled = false
        if (hwPreview || paintLayer.isReleased || !surfaceValid) return

        if (pendingTransformRedraw) {
            pendingTransformRedraw = false
            refreshes.clear()
            penBurst.clear()
            renderToSurface(null)
            lastFastBlitUptimeMs = SystemClock.uptimeMillis()
            return
        }

        val batch = refreshes.drain()
        // For FAST mid-stroke updates, keep penBurst untouched (it belongs to hardware strokes).
        if (batch != null && batch.mode == EinkMode.FAST && penBurst.isEmpty) {
            val rect = canvasToViewRect(batch.rect)
            if (rect.intersect(0, 0, effectiveWidth(), effectiveHeight())) {
                // Already in transient ANIMATION_QUALITY (or non-BOOX): direct fast surface blit.
                renderToSurface(rect)
                lastFastBlitUptimeMs = SystemClock.uptimeMillis()
            }
            return
        }

        // Structural update (fill, undo, reset) or non-FAST batch: folds any pending pen burst in.
        raw.cancelPenUpFlush()
        val flush = penBurst.take(batch) ?: return
        val rect = flushToViewRect(flush) ?: return

        val needsPenLayerHide = raw.isEnabled && raw.isRenderEnabled
        if (needsPenLayerHide) raw.hidePenLayer()
        val t0 = System.nanoTime()
        if (DiagLog.enabled && !stats.active) {
            lastFlushInfo = "refresh=${flush.mode.name} rect=${rect.toShortString()}" +
                if (flush.viewRect.isEmpty) "" else " +trace"
        }
        OnyxEinkBridge.withForcedPost(this, flush.mode, forceUnfreeze = raw.isOpen) { renderToSurface(rect) }
        if (DiagLog.enabled && !stats.active && lastFlushInfo != null) {
            val info = lastFlushInfo
            lastFlushInfo = null
            DiagLog.log(DiagLog.DRAW) { "structuralFlush $info total=${DiagLog.ms(System.nanoTime() - t0)}ms" }
        }
        // Resume the pen layer after the proper EPD waveform settle time (500ms color / 150ms mono).
        raw.schedulePenLayerResume()
    }

    private fun effectiveWidth(): Int = if (surfaceW > 0) surfaceW else width
    private fun effectiveHeight(): Int = if (surfaceH > 0) surfaceH else height

    private fun flushToViewRect(flush: PenBurst.Flush): Rect? {
        val rect = if (flush.canvasRect.isEmpty) Rect() else canvasToViewRect(flush.canvasRect)
        if (!flush.viewRect.isEmpty) {
            rect.union(flush.viewRect.left, flush.viewRect.top, flush.viewRect.right, flush.viewRect.bottom)
        }
        return if (rect.intersect(0, 0, effectiveWidth(), effectiveHeight())) rect else null
    }

    private fun canvasToViewRect(c: DirtyRect): Rect {
        tmpRect.set(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat())
        viewMatrix.mapRect(tmpRect)
        return Rect(floor(tmpRect.left).toInt() - 2, floor(tmpRect.top).toInt() - 2, ceil(tmpRect.right).toInt() + 2, ceil(tmpRect.bottom).toInt() + 2)
    }

    private fun viewToCanvasRect(v: Rect): DirtyRect {
        tmpRect.set(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
        inverse.mapRect(tmpRect)
        return DirtyRect(
            floor(tmpRect.left).toInt() - 2,
            floor(tmpRect.top).toInt() - 2,
            ceil(tmpRect.right).toInt() + 2,
            ceil(tmpRect.bottom).toInt() + 2,
        ).clamp(cw, ch)
    }

    /**
     * Fast software partial rendering onto the `SurfaceView` buffer via `lockCanvas(inOutDirty)`.
     * Only flushes dirty paint tiles and only draws tiles / line-art sub-rects intersecting the
     * actual locked rect (which Android may expand on a fresh buffer).
     */
    private fun renderToSurface(dirtyRect: Rect?): Boolean {
        if (!surfaceValid || paintLayer.isReleased || lineOverlay.isRecycled) return false
        val w = effectiveWidth()
        val h = effectiveHeight()
        if (w <= 0 || h <= 0) return false
        val surface = holder.surface
        if (surface == null || !surface.isValid) return false

        val inOutDirty: Rect? = dirtyRect?.let {
            Rect(
                it.left.coerceIn(0, w),
                it.top.coerceIn(0, h),
                it.right.coerceIn(0, w),
                it.bottom.coerceIn(0, h),
            ).takeUnless { r -> r.isEmpty } ?: return false
        }

        val t0 = System.nanoTime()
        val tilesCopied = paintLayer.flush()
        val t1 = System.nanoTime()

        val canvas = try {
            holder.lockCanvas(inOutDirty)
        } catch (e: Throwable) {
            DiagLog.log(DiagLog.DRAW, "lockCanvas failed: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } ?: return false

        try {
            val actualDirty = inOutDirty ?: Rect(0, 0, canvas.width, canvas.height)
            canvas.save()
            canvas.clipRect(actualDirty)
            canvas.drawRect(
                actualDirty.left.toFloat(),
                actualDirty.top.toFloat(),
                actualDirty.right.toFloat(),
                actualDirty.bottom.toFloat(),
                bgPaint,
            )
            canvas.concat(viewMatrix)

            val dirtyCanvas = viewToCanvasRect(actualDirty)
            if (!dirtyCanvas.isEmpty) {
                if (ghostVisible) {
                    refBitmap?.takeIf { !it.isRecycled }?.let { ref ->
                        if (ref.width == cw && ref.height == ch) {
                            srcSubRect.set(dirtyCanvas.left, dirtyCanvas.top, dirtyCanvas.right, dirtyCanvas.bottom)
                            canvas.drawBitmap(ref, srcSubRect, srcSubRect, ghostPaint)
                        } else {
                            canvas.drawBitmap(ref, null, canvasRect, ghostPaint)
                        }
                    }
                }
                paintLayer.drawRegion(canvas, bitmapPaint, dirtyCanvas)
                srcSubRect.set(dirtyCanvas.left, dirtyCanvas.top, dirtyCanvas.right, dirtyCanvas.bottom)
                canvas.drawBitmap(lineOverlay, srcSubRect, srcSubRect, linePaint)
            }
            canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), borderPaint)
            canvas.restore()
        } finally {
            try {
                // Mirror OnyxPenDemo BaseRenderer.beforeUnlockCanvas so SurfaceView posting is
                // explicitly unblocked at unlock time when a TouchHelper session is open.
                if (raw.isOpen) OnyxEinkBridge.enablePost(this, 1)
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Throwable) {
                DiagLog.log(DiagLog.DRAW, "unlockCanvasAndPost failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val drawNanos = System.nanoTime() - t1
        if (tilesCopied > 0) logFrame(tilesCopied, t1 - t0, drawNanos)
        return true
    }

    // ---------------- layout / transform ----------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewSize(w, h)
        syncRawBounds()
    }

    private fun updateViewSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == surfaceW && h == surfaceH) return
        surfaceW = w
        surfaceH = h
        val scale = minOf(w.toFloat() / cw, h.toFloat() / ch)
        val dx = (w - cw * scale) / 2f
        val dy = (h - ch * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        clampPan()
        updateMatrix()
        DiagLog.setState("canvas", "bitmap=${cw}x$ch view=${w}x$h fitScale=${"%.3f".format(java.util.Locale.US, scale)}")
        DiagLog.log(DiagLog.CANVAS) { "size view=${w}x$h canvas=${cw}x$ch scale=$scale" }
    }

    private fun updateMatrix() {
        viewMatrix.set(baseMatrix)
        viewMatrix.postScale(zoom, zoom)
        viewMatrix.postTranslate(panX, panY)
        viewMatrix.invert(inverse)
        viewMatrix.getValues(matrixValues)
        viewScale = matrixValues[Matrix.MSCALE_X]
        if (initDone) syncRawStrokeWidth()
    }

    private fun applyTransform(scale: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {
        val oldZoom = zoom
        val oldPanX = panX
        val oldPanY = panY
        val newZoom = (zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newZoom / zoom
        panX = focusX - (focusX - panX) * f + dx
        panY = focusY - (focusY - panY) * f + dy
        zoom = newZoom
        clampPan()
        // A resting finger / pan at 1× (clamped) changes nothing: keep the pen layer and skip the frame.
        if (zoom == oldZoom && panX == oldPanX && panY == oldPanY) return
        fingerTransformed = true
        beginFingerVisual()
        updateMatrix()
        if (zoom != oldZoom) onZoomChanged?.invoke(zoom)
        pendingTransformRedraw = true
        scheduleFlush(fast = true)
    }

    private fun clampPan() {
        if (zoom <= 1f) { panX = 0f; panY = 0f; return }
        val w = effectiveWidth().toFloat()
        val h = effectiveHeight().toFloat()
        panX = panX.coerceIn(w - w * zoom, 0f)
        panY = panY.coerceIn(h - h * zoom, 0f)
    }

    /**
     * Frame diagnostics. During a stroke frames are folded into the stroke summary (no per-frame
     * lines); otherwise one line per frame that uploaded paint tiles, with the refresh that caused it.
     */
    private fun logFrame(tiles: Int, uploadNanos: Long, drawNanos: Long) {
        if (!DiagLog.enabled) return
        if (stats.active) {
            stats.frame(tiles, uploadNanos, drawNanos)
            return
        }
        val flush = lastFlushInfo
        lastFlushInfo = null
        DiagLog.log(DiagLog.DRAW) {
            "frame dirtyTiles=$tiles upload=${DiagLog.ms(uploadNanos)}ms draw=${DiagLog.ms(drawNanos)}ms ${flush ?: "refresh=-"}"
        }
    }

    // ---------------- MotionEvents ----------------

    private fun isStylusPointer(e: MotionEvent, index: Int): Boolean = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        else -> e.isFromSource(InputDevice.SOURCE_STYLUS)
    }

    private fun hasStylusPointer(e: MotionEvent): Boolean {
        for (i in 0 until e.pointerCount) {
            if (isStylusPointer(e, i)) return true
        }
        return false
    }

    private fun kindOf(e: MotionEvent, index: Int): PointerKind = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
        MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        // Some digitizer firmwares report the EMR pen without a tool type: never treat it as a finger.
        else -> if (e.isFromSource(InputDevice.SOURCE_STYLUS)) PointerKind.STYLUS else PointerKind.UNKNOWN
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // While BOOX TouchHelper raw drawing is armed, suppress stylus MotionEvents before they
        // reach InputRouter or requestUnbufferedDispatch (eliminating the duplicate 240Hz race).
        // Also clean up any palm/finger pointers that started before the stylus joined so
        // InputRouter never leaks pointers or leaves isFingerGestureActive / ANIMATION_QUALITY stuck.
        if (raw.isEnabled && hasStylusPointer(e)) {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isStylusPointer(e, e.actionIndex)) raw.onMotionPenObserved(down = true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    router.cancelAllExcept(RAW_POINTER_ID)
                    endFingerVisual()
                    if (isStylusPointer(e, e.actionIndex)) raw.onMotionPenObserved(down = true)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (isStylusPointer(e, e.actionIndex)) {
                        raw.onMotionPenObserved(down = false, cancelled = false)
                    } else {
                        router.cancelPointer(e.getPointerId(e.actionIndex))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isStylusPointer(e, e.actionIndex)) raw.onMotionPenObserved(down = false, cancelled = false)
                    router.cancelAllExcept(RAW_POINTER_ID)
                    endFingerVisual()
                }
                MotionEvent.ACTION_CANCEL -> {
                    raw.onMotionPenObserved(down = false, cancelled = true)
                    router.cancelAllExcept(RAW_POINTER_ID)
                    endFingerVisual()
                }
            }
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val kind = kindOf(e, i)
                // Defense-in-depth: drop a late MotionEvent pen-down from a stroke TouchHelper just finished.
                if (isPen(kind) && raw.isOpen && e.eventTime <= lastRawStrokeEndUptime) return true
                val t0 = System.nanoTime()
                feedMotion(e.getPressure(i), e.eventTime)
                router.down(e.getPointerId(i), kind, e.getX(i), e.getY(i), e.getTouchMajor(i), StrokePressure.normalize(e.getPressure(i), 1f), e.eventTime)
                endFeed(t0)
                if (!raw.isEnabled && router.mode == InputRouter.Mode.STROKE && router.activeId == e.getPointerId(i)) {
                    requestUnbufferedDispatch(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val t0 = System.nanoTime()
                for (h in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount) {
                        val p = e.getHistoricalPressure(i, h)
                        feedMotion(p, e.eventTime)
                        router.move(e.getPointerId(i), e.getHistoricalX(i, h), e.getHistoricalY(i, h), StrokePressure.normalize(p, 1f), e.getHistoricalEventTime(h))
                    }
                }
                for (i in 0 until e.pointerCount) {
                    val p = e.getPressure(i)
                    feedMotion(p, e.eventTime)
                    router.move(e.getPointerId(i), e.getX(i), e.getY(i), StrokePressure.normalize(p, 1f), e.eventTime)
                }
                feedMotion(Float.NaN, e.eventTime)
                endFeed(t0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val t0 = System.nanoTime()
                feedMotion(Float.NaN, e.eventTime)
                router.up(e.getPointerId(i), e.getX(i), e.getY(i), StrokePressure.normalize(e.getPressure(i), 1f), e.eventTime)
                endFeed(t0)
                if (e.actionMasked == MotionEvent.ACTION_UP) endFingerVisual()
            }
            // Cancels the MotionEvent stream only; a raw pen stroke (separate source) carries on.
            MotionEvent.ACTION_CANCEL -> {
                val t0 = System.nanoTime()
                feedMotion(Float.NaN, e.eventTime)
                router.cancelAllExcept(RAW_POINTER_ID)
                endFeed(t0)
                endFingerVisual()
            }
            else -> return super.onTouchEvent(e)
        }
        return true
    }

    private fun isPen(kind: PointerKind) = kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
        const val TAP_SLOP_PX = 24f
        const val PALM_TOUCH_MAJOR_PX = 120f
        /** Highlight the ghost-clearing full refresh button after this many partial updates. */
        const val GHOST_HINT_EVERY = 15
        /** Router pointer id for the TouchHelper raw pen stream (never a real MotionEvent id). */
        const val RAW_POINTER_ID = 0x7EAD_0001
        /** Extra margin around the hardware trace bounds for the pen-up refresh. */
        private const val TRACE_PAD_PX = 10f
        /** Throttle mid-stroke surface blits to ≤30 Hz on BOOX EPD so waveforms never pile up. */
        const val EPD_STROKE_FRAME_INTERVAL_MS = 33L
        /** Standard 60 Hz frame pacing off BOOX / in OFF mode. */
        const val NON_BOOX_FRAME_INTERVAL_MS = 16L
        /** Above 60% surface area, lock full surface instead of partial copy-back (OnyxPenDemo ratio). */
        private const val PARTIAL_REFRESH_MAX_AREA_PERCENT = 60L
    }
}
