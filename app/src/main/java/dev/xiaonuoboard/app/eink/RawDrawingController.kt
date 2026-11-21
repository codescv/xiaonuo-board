package dev.xiaonuoboard.app.eink

import dev.xiaonuoboard.app.engine.DirtyRect

/** Primitive raw-pen events (view-local px), delivered on the UI thread by a [RawDrawingBackend]. */
interface RawPenListener {
    fun onRawBegin(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean)
    fun onRawMove(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean)
    /** The whole stroke's points, delivered right before [onRawEnd]. */
    fun onRawPoints(xs: FloatArray, ys: FloatArray, pressures: FloatArray, times: LongArray, erasing: Boolean)
    fun onRawEnd(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean)
}

/**
 * Thin seam over Onyx `TouchHelper` (see [OnyxTouchHelperBackend]) so the lifecycle / stroke state
 * machine below is JVM-testable. All rects are view-local px. Every call returns false on failure.
 */
interface RawDrawingBackend {
    /** Creates + opens raw drawing for the host view, leaving input AND render disabled. */
    fun open(limit: DirtyRect, exclude: List<DirtyRect>, strokeWidth: Float, strokeColor: Int): Boolean
    fun setLimit(limit: DirtyRect, exclude: List<DirtyRect>): Boolean
    /** Raw input reader + hardware pen render together (`TouchHelper.setRawDrawingEnabled`). */
    fun setEnabled(enabled: Boolean): Boolean
    /** Hardware pen render only (`TouchHelper.setRawDrawingRenderEnabled`); input keeps flowing. */
    fun setRenderEnabled(enabled: Boolean): Boolean
    fun setStroke(width: Float, color: Int): Boolean
    fun close()
}

/** Receives raw pen strokes (view-local px) — the canvas feeds them into its `InputRouter`. */
interface RawStrokeSink {
    fun rawStrokeBegin(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean)
    fun rawStrokeMove(x: Float, y: Float, pressure: Float, time: Long)
    fun rawStrokeEnd(x: Float, y: Float, pressure: Float, time: Long)
    /** The raw stroke can no longer complete (raw drawing paused / closed mid-stroke): roll it back. */
    fun rawStrokeAbort()
}

interface UiScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/**
 * UI-thread state machine for BOOX TouchHelper raw drawing on the coloring canvas.
 *
 * Lifecycle: raw drawing is OPEN while the view is attached, laid out (non-empty limit rect), not
 * released and TouchHelper is available; it is ENABLED (raw input + hardware pen layer) only while
 * additionally the window has focus, the view is visible, the host is resumed, no overlay / dialog
 * suspends it and the tool is a freehand tool (crayon / eraser — never the paint bucket, whose taps
 * must reach the normal MotionEvent path). Disabling or closing mid-stroke aborts the stroke.
 *
 * Strokes: raw callbacks become begin / move / end on the [RawStrokeSink]. Stale callbacks (posted
 * before a pause / close) and orphan moves / ends are dropped. The full point list is only used
 * when no per-point moves arrived.
 *
 * Refresh handshake (Onyx pattern): before the canvas issues one of its own partial refreshes (the
 * committed color stroke at pen-up, an undo, a reset…) [hidePenLayer] turns the hardware pen layer
 * off (`setRawDrawingRenderEnabled(false)`, i.e. leave scribble mode) so the refresh reaches the
 * panel and replaces the black hardware trace; once it is issued [schedulePenLayerResume] turns the
 * layer back on after [RENDER_RESUME_DELAY_MS]. A pen stroke starting earlier re-enables it
 * immediately (the canvas folds any still-pending refresh into that stroke's pen-up refresh).
 *
 * Self-healing: if [MAX_MISSES] consecutive pen strokes arrive only as MotionEvents while raw
 * drawing is enabled (TouchHelper not delivering), raw drawing is shut down and [onFailure] fires,
 * so the canvas keeps working on the standard path.
 */
class RawDrawingController(
    private val available: () -> Boolean,
    private val backendFactory: (RawPenListener) -> RawDrawingBackend,
    private val sink: RawStrokeSink,
    private val scheduler: UiScheduler,
    private val onFailure: (String) -> Unit = {},
) : RawPenListener {

    // ---- inputs ----
    private var attached = false
    private var windowFocused = false
    private var visible = false
    private var hostResumed = true
    private var suspended = false
    private var toolAllowsRaw = false
    private var released = false
    private var limit = DirtyRect.EMPTY
    private var exclude: List<DirtyRect> = emptyList()
    private var offsetX = 0
    private var offsetY = 0
    private var strokeWidth = 1f
    private var strokeColor = 0xFF000000.toInt()

    // ---- state ----
    private var backend: RawDrawingBackend? = null
    var isOpen = false
        private set
    /** Raw input + hardware pen layer armed. */
    var isEnabled = false
        private set
    var isRenderEnabled = false
        private set
    var isFailed = false
        private set
    var failureReason: String? = null
        private set
    /** A raw stroke is between begin and end. */
    var isRawStrokeActive = false
        private set
    private var anchorX = 0
    private var anchorY = 0
    private var reanchorPending = false
    private var movesInStroke = 0

    private var penStrokeOpen = false
    private var rawSeenInPenStroke = false
    var consecutiveMisses = 0
        private set

    private val resumeRender = Runnable { resumeRenderNow() }

    // ---------------- inputs ----------------

    fun setAttached(value: Boolean) { attached = value; update() }
    fun setWindowFocused(value: Boolean) { windowFocused = value; update() }
    fun setVisible(value: Boolean) { visible = value; update() }
    fun setHostResumed(value: Boolean) { hostResumed = value; update() }
    /** An overlay / dialog covers (part of) the canvas: keep the pen out of raw mode. */
    fun setSuspended(value: Boolean) { suspended = value; update() }
    fun setToolAllowsRaw(value: Boolean) { toolAllowsRaw = value; update() }

    /**
     * View-local limit rect (normally the visible canvas view bounds), exclusions (overlays drawn on
     * top of it) and the view's on-screen position. A changed position re-anchors the raw reader
     * (it caches the view location to map raw points into view coordinates).
     */
    fun setBounds(limit: DirtyRect, exclude: List<DirtyRect>, screenX: Int, screenY: Int) {
        val rectsChanged = limit != this.limit || exclude != this.exclude
        this.limit = limit
        this.exclude = exclude
        offsetX = screenX
        offsetY = screenY
        if (isOpen && !limit.isEmpty) {
            if (rectsChanged && backend?.setLimit(limit, exclude) == false) {
                fail("setLimitRect failed")
                return
            }
            if (isEnabled && (anchorX != screenX || anchorY != screenY)) {
                if (isRawStrokeActive) reanchorPending = true else reanchor()
            }
        }
        update()
    }

    fun setStroke(width: Float, color: Int) {
        if (width == strokeWidth && color == strokeColor) return
        strokeWidth = width
        strokeColor = color
        if (isOpen && backend?.setStroke(width, color) == false) fail("setStrokeWidth/Color failed")
    }

    fun release() {
        released = true
        update()
        backend = null
    }

    // ---------------- state machine ----------------

    private fun update() {
        val wantOpen = !released && !isFailed && attached && !limit.isEmpty && available()
        if (!wantOpen) {
            if (isOpen) closeBackend()
            return
        }
        if (!isOpen && !openBackend()) return
        val wantEnabled = windowFocused && visible && hostResumed && !suspended && toolAllowsRaw
        if (wantEnabled != isEnabled) applyEnabled(wantEnabled)
    }

    private fun openBackend(): Boolean {
        val b = backend ?: backendFactory(this).also { backend = it }
        if (!b.open(limit, exclude, strokeWidth, strokeColor)) {
            fail("TouchHelper open failed")
            return false
        }
        isOpen = true
        isEnabled = false
        isRenderEnabled = false
        anchorX = offsetX
        anchorY = offsetY
        return true
    }

    private fun closeBackend() {
        abortRawStroke()
        scheduler.cancel(resumeRender)
        isOpen = false
        isEnabled = false
        isRenderEnabled = false
        reanchorPending = false
        penStrokeOpen = false
        backend?.close()
    }

    private fun applyEnabled(on: Boolean) {
        if (!on) abortRawStroke()
        scheduler.cancel(resumeRender)
        if (backend?.setEnabled(on) != true) {
            fail("setRawDrawingEnabled($on) failed")
            return
        }
        isEnabled = on
        isRenderEnabled = on
        if (on) {
            // The reader re-reads the view location on resume.
            anchorX = offsetX
            anchorY = offsetY
            reanchorPending = false
        }
    }

    private fun reanchor() {
        reanchorPending = false
        val b = backend ?: return
        if (!b.setEnabled(false) || !b.setEnabled(true)) {
            fail("re-anchor failed")
            return
        }
        isRenderEnabled = true
        scheduler.cancel(resumeRender)
        anchorX = offsetX
        anchorY = offsetY
    }

    private fun fail(reason: String) {
        if (isFailed) return
        isFailed = true
        failureReason = reason
        if (isOpen) closeBackend()
        onFailure(reason)
    }

    private fun abortRawStroke() {
        if (!isRawStrokeActive) return
        isRawStrokeActive = false
        sink.rawStrokeAbort()
    }

    private fun resumeRenderNow() {
        scheduler.cancel(resumeRender)
        if (!isOpen || !isEnabled || isRenderEnabled) return
        if (backend?.setRenderEnabled(true) == true) isRenderEnabled = true else fail("setRawDrawingRenderEnabled(true) failed")
    }

    // ---------------- raw pen events ----------------

    override fun onRawBegin(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean) {
        if (!isOpen || !isEnabled) return // stale: posted before a pause / close
        if (isRawStrokeActive) abortRawStroke() // previous stroke never ended
        resumeRenderNow()
        consecutiveMisses = 0
        isRawStrokeActive = true
        movesInStroke = 0
        if (penStrokeOpen) rawSeenInPenStroke = true
        sink.rawStrokeBegin(x, y, pressure, time, erasing)
    }

    override fun onRawMove(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean) {
        if (!isRawStrokeActive) return
        movesInStroke++
        sink.rawStrokeMove(x, y, pressure, time)
    }

    override fun onRawPoints(xs: FloatArray, ys: FloatArray, pressures: FloatArray, times: LongArray, erasing: Boolean) {
        if (!isRawStrokeActive || movesInStroke > 0) return
        val n = minOf(xs.size, ys.size, pressures.size, times.size)
        for (i in 0 until n) sink.rawStrokeMove(xs[i], ys[i], pressures[i], times[i])
        movesInStroke += n
    }

    override fun onRawEnd(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean) {
        if (!isRawStrokeActive) return
        isRawStrokeActive = false
        sink.rawStrokeEnd(x, y, pressure, time)
        if (reanchorPending && isEnabled) reanchor()
    }

    // ---------------- canvas handshake ----------------

    /** A pen stroke (raw or MotionEvent) started on the canvas. */
    fun onPenStrokeStart() {
        penStrokeOpen = true
        rawSeenInPenStroke = isRawStrokeActive
        resumeRenderNow()
    }

    /** A pen stroke ended; counts strokes TouchHelper never reported (see class doc). */
    fun onPenStrokeEnd(cancelled: Boolean) {
        if (!penStrokeOpen) return
        penStrokeOpen = false
        if (cancelled || !isEnabled) return
        if (rawSeenInPenStroke) {
            consecutiveMisses = 0
        } else if (++consecutiveMisses >= MAX_MISSES) {
            fail("TouchHelper 连续 $MAX_MISSES 笔未回传笔迹")
        }
    }

    /** Before an app refresh (e.g. pen-up): hide the hardware pen layer so the refresh shows. */
    fun hidePenLayer() {
        if (!isOpen || !isEnabled || !isRenderEnabled) return
        if (backend?.setRenderEnabled(false) == true) isRenderEnabled = false else fail("setRawDrawingRenderEnabled(false) failed")
    }

    /** The app refresh was issued: bring the hardware pen layer back shortly after. */
    fun schedulePenLayerResume() {
        if (isOpen && isEnabled && !isRenderEnabled) scheduler.postDelayed(resumeRender, RENDER_RESUME_DELAY_MS)
    }

    companion object {
        const val MAX_MISSES = 3
        /** ≈2 frames: lets the post-stroke partial refresh reach the EPD before scribble resumes. */
        const val RENDER_RESUME_DELAY_MS = 32L
    }
}
