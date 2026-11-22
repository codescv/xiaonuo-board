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

/**
 * Native coloring surface. Composites (bottom→top): white paper → optional 20% reference ghost →
 * paint layer → 1-bit black line art. Pointer routing (pen priority, palm rejection, pinch/pan,
 * stylus-only) lives in the JVM-tested [InputRouter]; this view only adapts input, draws, and drives
 * BOOX partial / fast refreshes and the BOOX TouchHelper hardware pen layer.
 *
 * Input sources, all on the UI thread and all fed into the same [InputRouter]:
 *  - MotionEvents (fingers: pan / pinch / finger painting; pens off BOOX or when raw drawing is off);
 *  - on BOOX, TouchHelper raw pen strokes via [RawDrawingController] (pointer id [RAW_POINTER_ID]),
 *    view-local px like MotionEvents. If the firmware ALSO delivers the pen as MotionEvents, the
 *    router's pen-priority rule lets whichever source begins first own the stroke and ignores the
 *    duplicate; a MotionEvent pen-down older than the last raw stroke end is dropped outright.
 *
 * Latency notes:
 *  - The paint layer is a [TiledPaintLayer]: only tiles touched since the last frame are copied and
 *    re-uploaded to the GPU (instead of the whole 7.7 MB bitmap per brush sample / frame).
 *  - Refreshes are coalesced through [RefreshCoalescer] into ONE invalidate / EPD partial refresh per
 *    display frame, however many stylus samples arrived in that frame.
 *  - While the BOOX pen layer draws a stroke ([hwPreview]) no EPD refresh is issued at all. At pen-up
 *    the stroke is committed to the paint layer right away, but its refresh is DEBOUNCED to the end of
 *    the pen burst ([RawDrawingController.schedulePenUpFlush], [PenBurst]): the pen layer stays live
 *    so the next stroke of a back-and-forth coloring burst is drawn by the firmware instantly, and
 *    after ~450 ms of pen idle the layer is hidden, the committed (region-clipped, colored) strokes
 *    plus the whole hardware trace area get ONE clean partial refresh, and the pen layer comes back
 *    once that refresh's frame has been drawn. Anything else that refreshes (fill, undo, reset, tool /
 *    colour change, finger gesture, overlay, pause) folds the pending burst into its refresh.
 *
 * Pressure: TouchHelper `TouchPoint.pressure` (raw digitizer units) is normalized with
 * [RawPressureNormalizer]; MotionEvent pressure is already normalized by Android. The controller
 * turns it into a smoothly tapering crayon width; on BOOX the firmware draws the live trace with its
 * pressure-sensitive FOUNTAIN pen at the widest (firm-press) width.
 *
 * Diagnostics ([DiagLog]): one summary per stroke (source, points, pressure range, paint CPU, frame
 * upload / draw time, whether the pen layer was live at pen-down), per refresh flush and per fill.
 */
@SuppressLint("ViewConstructor")
class ColoringCanvasView(
    context: Context,
    val controller: ColoringCanvasController,
    private val lineOverlay: Bitmap,
) : View(context) {

    private val cw = controller.width
    private val ch = controller.height
    private val paintLayer = TiledPaintLayer(controller.paint, cw, ch)

    var refBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var ghostVisible: Boolean = false
        set(value) { field = value; refreshWholeView(EinkMode.NORMAL) }

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

    private val baseMatrix = Matrix()
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private val matrixValues = FloatArray(9)
    private var viewScale = 1f
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f

    private var strokesSinceFull = 0
    /** The BOOX hardware pen layer is drawing the current stroke: no EPD refresh until pen-up. */
    private var hwPreview = false
    private var stroking = false
    /** Union of everything the current stroke changed (incl. a cancel's rollback) → one clean refresh at the end. */
    private var strokeDirty = DirtyRect.EMPTY
    private val tmpRect = RectF()
    private val refreshes = RefreshCoalescer()
    private val flushRefresh = Runnable { flushPendingRefresh() }

    // Hardware pen trace bookkeeping (view px): the trace is NOT region-clipped and may extend past
    // the painted rect (不涂出线) or the canvas image, so its own bounds are refreshed with the burst.
    private val trace = TraceBounds()
    /** Committed pen strokes + hardware traces awaiting the debounced end-of-burst refresh. */
    private val penBurst = PenBurst()
    /** A refresh was issued: resume the pen layer ≈2 frames after its frame is drawn. */
    private var resumeAfterDraw = false
    /** The current finger gesture changed what is shown (pan / zoom / finger paint) → pen layer held. */
    private var fingerVisual = false
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
    /** Last refresh issued by [flushPendingRefresh], reported with the next logged frame. */
    private var lastFlushInfo: String? = null

    private var currentTool: Tool = controller.tool
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
                if (endPending) logStrokeEnd() // the stroke this one pre-empted (pen priority)
                if (DiagLog.enabled) {
                    stats.begin(
                        if (feedingRaw) StrokeStats.Source.RAW else StrokeStats.Source.MOTION, kind,
                        renderEnabledAtBegin = if (feedingRaw) raw.lastBeginRenderWasEnabled else raw.isRenderEnabled,
                        nowMs = SystemClock.uptimeMillis(), eventTimeMs = feedEventTime,
                    )
                    stats.point(pressure, feedRawPressure)
                }
                // Finger painting: its FAST frames must reach the panel → hold the pen layer hidden
                // (before the first dab syncs). Pens get the hardware pen layer instead.
                if (isPen(kind)) startPenLayerStroke(x, y) else beginFingerVisual()
            }

            override fun onStrokePoint(x: Float, y: Float, pressure: Float, time: Long) {
                if (hwPreview) trace.add(x, y)
                stats.point(pressure, feedRawPressure)
            }

            override fun onStrokeEnd(rect: DirtyRect, x: Float, y: Float, pressure: Float, time: Long, cancelled: Boolean) {
                stroking = false
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
                } else if (!all.isEmpty) {
                    // One clean (non-FAST) refresh of the whole stroke in color.
                    requestRefresh(all, EinkMode.NORMAL)
                }
                if (!cancelled) {
                    strokesSinceFull++
                    onGhostHint?.invoke(strokesSinceFull >= GHOST_HINT_EVERY)
                    onStrokeFinished?.invoke()
                }
                onStrokeEnded?.invoke()
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
        // End of a pen burst (idle timeout, tool / colour change, raw off, finger gesture, failure):
        // ONE frame-aligned refresh of everything the burst drew.
        onPenUpFlush = { scheduleFlush() },
    )

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { syncRawBounds() }

    init {
        setBackgroundColor(Color.WHITE)
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
        currentTool = tool
        raw.setStroke(rawStrokeWidthPx(tool, brush), if (tool == Tool.ERASER) Color.WHITE else color or (0xFF shl 24), tool == Tool.CRAYON)
        raw.setSuspended(suspendRaw)
        raw.setToolAllowsRaw(tool == Tool.CRAYON || tool == Tool.ERASER)
    }

    /** Host (activity) resumed / paused. */
    fun setHostResumed(resumed: Boolean) = raw.setHostResumed(resumed)

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

    fun clearGhosting() {
        strokesSinceFull = 0
        onGhostHint?.invoke(false)
        if (!initDone) return
        penBurst.clear() // the full flash covers the pending burst
        raw.hidePenLayer()
        DiagLog.log(DiagLog.DRAW, "clearGhosting (GC)")
        OnyxEinkBridge.clearGhosting(this)
        resumePenLayerAfterDraw()
    }

    /** Full-view refresh with the BOOX pen layer hidden around it (no-op handshake elsewhere). */
    private fun refreshWholeView(mode: EinkMode) {
        if (!initDone) { invalidate(); return }
        if (hwPreview) { invalidate(); return } // mid-stroke: shown by the pen-up refresh
        penBurst.clear() // the whole-view refresh covers the pending burst
        raw.hidePenLayer()
        OnyxEinkBridge.refresh(this, mode)
        resumePenLayerAfterDraw()
    }

    val isReleased: Boolean get() = paintLayer.isReleased

    /**
     * Copy of the current paint pixels (unpremultiplied ARGB, row-major `width × height`) for
     * auto-save / export. A plain array copy (~2 ms) is the only UI-thread cost; turning it into a
     * Bitmap, compositing and PNG encoding all happen on a background thread. Raw pen callbacks are
     * delivered on the UI thread too, so this never races a stroke.
     */
    fun snapshotPixels(): IntArray = controller.paint.copyOf()

    /** Releases the display tiles and TouchHelper; call once the view has left the window. */
    fun release() {
        // Disposed mid-stroke (e.g. Back while the pen is down): close raw drawing (rolling back a raw
        // stroke) so the EPD pen layer is never left owned by a dead view, then roll back the rest.
        raw.release()
        abortActiveStroke()
        controller.onDirty = null
        clearPendingRefresh()
        paintLayer.release()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DiagLog.log(DiagLog.CANVAS) { "attached canvas=${cw}x$ch hwAccel=$isHardwareAccelerated touchHelper=${OnyxEinkBridge.isTouchHelperAvailable}" }
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        raw.setWindowFocused(hasWindowFocus())
        raw.setVisible(isEffectivelyVisible())
        raw.setAttached(true)
        syncRawBounds()
    }

    override fun onDetachedFromWindow() {
        DiagLog.log(DiagLog.CANVAS, "detached")
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        // Close TouchHelper first (aborts a raw stroke), then any MotionEvent stroke: never stay stuck
        // in stroking / pen-layer mode (that would also defer auto-saves forever). Tiles are kept so
        // a re-attach can redraw.
        raw.setAttached(false)
        abortActiveStroke()
        clearPendingRefresh()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Dialogs, the notification shade, system install prompts… take focus: pen layer off.
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
        if (stroking || hwPreview) router.cancel()
        hwPreview = false
        stroking = false
        strokeDirty = DirtyRect.EMPTY
        trace.reset()
    }

    private fun clearPendingRefresh() {
        removeCallbacks(flushRefresh)
        refreshes.clear()
        penBurst.clear()
        resumeAfterDraw = false
        fingerVisual = false
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
        if (!raw.isEnabled) return
        // Same burst: cancels the pending end-of-burst refresh, and brings the pen layer back if it
        // was hidden for a refresh.
        raw.onPenStrokeStart()
        if (!raw.isEnabled) return
        hwPreview = true
        // A refresh still queued (e.g. the burst's refresh when the pen came down within the same
        // vsync) must not hit the EPD while the pen layer owns the screen: fold it into this burst's
        // clean refresh instead (the burst's own areas simply stay pending).
        removeCallbacks(flushRefresh)
        refreshes.drain()?.let { strokeDirty = strokeDirty.union(it.rect) }
        trace.reset()
        trace.add(x, y)
    }

    /** Hardware pen stroke ended: commit its areas to the burst and debounce the refresh. */
    private fun endPenLayerStroke(all: DirtyRect, cancelled: Boolean) {
        hwPreview = false
        raw.onPenStrokeEnd(cancelled)
        penBurst.addStroke(all)
        trace.refreshRect(rawStrokeWidthPx(currentTool, controller.brushSize), TRACE_PAD_PX)?.let { penBurst.addTrace(it) }
        trace.reset()
        if (cancelled) {
            // Rolled back (aborted raw stroke, pen priority…): show the rollback right away.
            raw.hidePenLayer()
            scheduleFlush()
        } else {
            // Keep the pen layer live for the next stroke of the burst; refresh once it is over.
            raw.schedulePenUpFlush()
        }
    }

    /** One frame-aligned flush of everything pending (coalesced refreshes + the pen burst). */
    private fun scheduleFlush() {
        removeCallbacks(flushRefresh)
        postOnAnimation(flushRefresh)
    }

    /** A refresh was issued: resume the pen layer ≈2 frames after its frame is drawn (fallback timer). */
    private fun resumePenLayerAfterDraw() {
        resumeAfterDraw = true
        raw.schedulePenLayerResume(RawDrawingController.RENDER_RESUME_FALLBACK_MS)
    }

    /** The finger gesture changes what is shown: hold the pen layer hidden (flushes a pen burst). */
    private fun beginFingerVisual() {
        if (fingerVisual) return
        fingerVisual = true
        raw.setFingerGesture(true)
    }

    private fun endFingerVisual() {
        fingerVisual = false
        raw.setFingerGesture(false)
    }

    /**
     * Hardware trace width in screen px: the brush (eraser ×1.5) diameter at the current zoom; for the
     * pressure-sensitive crayon trace the firm-press (maximum) diameter.
     */
    private fun rawStrokeWidthPx(tool: Tool, brush: BrushSize): Float =
        RawDrawingGeometry.traceWidthPx(brush.radius, tool == Tool.ERASER, viewScale, pressureSensitive = tool == Tool.CRAYON)

    private fun syncRawStrokeWidth() {
        raw.setStroke(
            rawStrokeWidthPx(currentTool, controller.brushSize),
            if (currentTool == Tool.ERASER) Color.WHITE else controller.color or (0xFF shl 24),
            currentTool == Tool.CRAYON,
        )
    }

    // ---------------- refresh ----------------

    /**
     * Marks changed paint pixels for upload (done once per frame in [onDraw]) and queues a coalesced
     * refresh, unless the BOOX pen layer owns the screen (then we refresh once on pen-up).
     */
    private fun syncRect(r: DirtyRect) {
        if (paintLayer.isReleased) return
        val c = r.clamp(cw, ch)
        if (c.isEmpty) return
        paintLayer.markDirty(c)
        if (stroking) strokeDirty = strokeDirty.union(c)
        if (hwPreview) return // the hardware pen layer is showing the trace; refresh once on pen-up
        requestRefresh(c, if (stroking) EinkMode.FAST else EinkMode.NORMAL)
    }

    /** Queues [c] (canvas coords) for the next frame's single partial refresh. */
    private fun requestRefresh(c: DirtyRect, mode: EinkMode) {
        if (refreshes.request(c, mode)) postOnAnimation(flushRefresh)
    }

    private fun flushPendingRefresh() {
        // Never issue an EPD refresh while the hardware pen layer owns the screen: startPenLayerStroke()
        // already folded pending work into strokeDirty, and the burst stays pending, so nothing is lost.
        if (hwPreview) return
        raw.cancelPenUpFlush() // this refresh takes the pending burst with it
        val flush = penBurst.take(refreshes.drain())
        if (flush != null && !paintLayer.isReleased) {
            val rect = if (flush.canvasRect.isEmpty) Rect() else canvasToViewRect(flush.canvasRect)
            if (!flush.viewRect.isEmpty) rect.union(flush.viewRect.left, flush.viewRect.top, flush.viewRect.right, flush.viewRect.bottom)
            if (rect.intersect(0, 0, width, height)) {
                // FAST (DU) frames only happen during finger strokes, which already hold the pen layer
                // hidden for the whole gesture (no per-frame firmware toggles).
                if (flush.mode != EinkMode.FAST) raw.hidePenLayer()
                val t0 = System.nanoTime()
                OnyxEinkBridge.refreshRect(this, rect, flush.mode)
                if (DiagLog.enabled) {
                    lastFlushInfo = "refresh=${flush.mode.name} epdCall=${DiagLog.ms(System.nanoTime() - t0)}ms rect=${rect.toShortString()}" +
                        if (flush.viewRect.isEmpty) "" else " +trace"
                }
                resumePenLayerAfterDraw()
                return
            }
        }
        // Single resume point also when there was nothing to refresh; no-op while a finger gesture
        // holds the layer or when it is already showing, and never ahead of a refresh still waiting
        // for its frame to be drawn.
        if (!resumeAfterDraw) raw.schedulePenLayerResume()
    }

    private fun canvasToViewRect(c: DirtyRect): Rect {
        tmpRect.set(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat())
        viewMatrix.mapRect(tmpRect)
        return Rect(tmpRect.left.toInt() - 2, tmpRect.top.toInt() - 2, tmpRect.right.toInt() + 2, tmpRect.bottom.toInt() + 2)
    }

    // ---------------- layout / transform / draw ----------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = minOf(w.toFloat() / cw, h.toFloat() / ch)
        val dx = (w - cw * scale) / 2f
        val dy = (h - ch * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        updateMatrix()
        syncRawBounds()
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
        beginFingerVisual()
        updateMatrix()
        if (zoom != oldZoom) onZoomChanged?.invoke(zoom)
        invalidate()
    }

    private fun clampPan() {
        if (zoom <= 1f) { panX = 0f; panY = 0f; return }
        val w = width.toFloat()
        val h = height.toFloat()
        panX = panX.coerceIn(w - w * zoom, 0f)
        panY = panY.coerceIn(h - h * zoom, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        if (paintLayer.isReleased || lineOverlay.isRecycled) return
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.concat(viewMatrix)
        if (ghostVisible) refBitmap?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, null, canvasRect, ghostPaint) }
        val t0 = System.nanoTime()
        val tilesCopied = paintLayer.flush()
        val t1 = System.nanoTime()
        paintLayer.draw(canvas, bitmapPaint)
        canvas.drawBitmap(lineOverlay, 0f, 0f, linePaint)
        canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), borderPaint)
        canvas.restore()
        if (tilesCopied > 0) logFrame(tilesCopied, t1 - t0, System.nanoTime() - t1)
        if (resumeAfterDraw) {
            // The refresh's frame is recorded now: resume scribble ≈2 frames later, after it has been
            // rendered and posted to the EPD (replaces the fallback timer; one pending resume only).
            resumeAfterDraw = false
            raw.schedulePenLayerResume()
        }
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

    private fun kindOf(e: MotionEvent, index: Int): PointerKind = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
        MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        // Some digitizer firmwares report the EMR pen without a tool type: never treat it as a finger.
        else -> if (e.isFromSource(InputDevice.SOURCE_STYLUS)) PointerKind.STYLUS else PointerKind.UNKNOWN
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val kind = kindOf(e, i)
                // A finger / palm going down does NOT hide the BOOX pen layer by itself: a palm resting
                // before the pen lands used to leave the layer off at pen-down, so the stroke only
                // appeared with its pen-up refresh. The layer is held hidden only once the finger
                // gesture actually changes the picture (finger paint, pan / zoom: beginFingerVisual).
                // A pen MotionEvent that went down before the last raw stroke ended is that same
                // physical stroke, delivered late by the regular input pipeline: drop it (its later
                // moves / up are ignored because the router never registered the pointer).
                if (isPen(kind) && raw.isOpen && e.eventTime <= lastRawStrokeEndUptime) return true
                val t0 = System.nanoTime()
                feedMotion(e.getPressure(i), e.eventTime)
                router.down(e.getPointerId(i), kind, e.getX(i), e.getY(i), e.getTouchMajor(i), StrokePressure.normalize(e.getPressure(i), 1f), e.eventTime)
                endFeed(t0)
                // Deliver the rest of a stroke's samples immediately instead of batching them to vsync:
                // up to one frame less input latency for the brush. Rendering stays one upload + one
                // refresh per frame thanks to the coalescing above.
                if (router.mode == InputRouter.Mode.STROKE && router.activeId == e.getPointerId(i)) requestUnbufferedDispatch(e)
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
        private const val TRACE_PAD_PX = 6f
    }
}
