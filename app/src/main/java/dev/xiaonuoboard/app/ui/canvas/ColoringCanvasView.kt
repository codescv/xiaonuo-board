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
import android.view.MotionEvent
import android.view.View
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.eink.RefreshCoalescer
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.InputRouter
import dev.xiaonuoboard.app.engine.PointerKind

/**
 * Native coloring surface. Composites (bottom→top): white paper → optional 20% reference ghost →
 * paint layer → 1-bit black line art. Pointer routing (pen priority, palm rejection, pinch/pan,
 * stylus-only) lives in the JVM-tested [InputRouter]; this view only adapts MotionEvents, draws,
 * and drives BOOX partial / fast refreshes and the hardware scribble preview.
 *
 * Latency notes:
 *  - The paint layer is a [TiledPaintLayer]: only tiles touched since the last frame are copied and
 *    re-uploaded to the GPU (instead of the whole 7.7 MB bitmap per brush sample / frame).
 *  - Refreshes are coalesced through [RefreshCoalescer] into ONE invalidate / EPD partial refresh per
 *    display frame, however many stylus samples arrived in that frame.
 *  - Strokes request unbuffered input dispatch so samples reach the brush (and the BOOX hardware
 *    pen layer) as soon as the digitizer reports them instead of being batched to the next vsync.
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
        set(value) { field = value; OnyxEinkBridge.refresh(this, EinkMode.NORMAL) }

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
    private var hwPreview = false
    private var stroking = false
    /** Union of everything the current stroke changed (incl. a cancel's rollback) → one clean refresh at the end. */
    private var strokeDirty = DirtyRect.EMPTY
    private val screenLoc = IntArray(2)
    private val tmpRect = RectF()
    private val refreshes = RefreshCoalescer()
    private val flushRefresh = Runnable { flushPendingRefresh() }

    /** True while a crayon / eraser stroke is in progress (callers defer heavy work until it ends). */
    val isStroking: Boolean get() = stroking

    private val router = InputRouter(
        controller = controller,
        listener = object : InputRouter.Listener {
            override fun onTransform(scale: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {
                applyTransform(scale, focusX, focusY, dx, dy)
            }

            override fun onStrokeStart(kind: PointerKind, x: Float, y: Float, pressure: Float, time: Long) {
                stroking = true
                if (kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP) startHwPreview(x, y, pressure, time)
            }

            override fun onStrokePoint(x: Float, y: Float, pressure: Float, time: Long) {
                if (hwPreview) OnyxEinkBridge.strokeAdd(strokeWidthPx(), x + screenLoc[0], y + screenLoc[1], pressure, 1f, time)
            }

            override fun onStrokeEnd(rect: DirtyRect, x: Float, y: Float, pressure: Float, time: Long, cancelled: Boolean) {
                stopHwPreview(x, y, pressure, time, cancelled)
                stroking = false
                // One clean (non-FAST) refresh of the whole stroke in color, after the scribble layer is
                // gone. Uses the union of everything synced during the stroke so a cancelled stroke's
                // rollback (reported with an empty rect) is also repainted cleanly, not left in DU/scribble.
                val all = strokeDirty.union(rect.clamp(cw, ch))
                strokeDirty = DirtyRect.EMPTY
                if (!all.isEmpty) requestRefresh(all, EinkMode.NORMAL)
                if (!cancelled) {
                    strokesSinceFull++
                    onGhostHint?.invoke(strokesSinceFull >= GHOST_HINT_EVERY)
                    onStrokeFinished?.invoke()
                }
                onStrokeEnded?.invoke()
            }

            override fun onFilled(rect: DirtyRect) {
                strokesSinceFull++
                onGhostHint?.invoke(strokesSinceFull >= GHOST_HINT_EVERY)
                onFilled?.invoke()
            }
        },
        toCanvas = { p -> inverse.mapPoints(p) },
        tapSlop = TAP_SLOP_PX,
        palmTouchMajor = PALM_TOUCH_MAJOR_PX,
    )

    init {
        setBackgroundColor(Color.WHITE)
        controller.onDirty = { r -> syncRect(r) }
        isFocusable = true
    }

    fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        updateMatrix()
        onZoomChanged?.invoke(zoom)
        OnyxEinkBridge.refresh(this, EinkMode.NORMAL)
    }

    fun clearGhosting() {
        strokesSinceFull = 0
        onGhostHint?.invoke(false)
        OnyxEinkBridge.clearGhosting(this)
    }

    val isReleased: Boolean get() = paintLayer.isReleased

    /**
     * Copy of the current paint pixels (unpremultiplied ARGB, row-major `width × height`) for
     * auto-save / export. A plain array copy (~2 ms) is the only UI-thread cost; turning it into a
     * Bitmap, compositing and PNG encoding all happen on a background thread.
     */
    fun snapshotPixels(): IntArray = controller.paint.copyOf()

    /** Releases the display tiles; call once the view has left the window. */
    fun release() {
        controller.onDirty = null
        removeCallbacks(flushRefresh)
        refreshes.clear()
        paintLayer.release()
    }

    /**
     * Marks changed paint pixels for upload (done once per frame in [onDraw]) and queues a coalesced
     * refresh, unless the BOOX scribble layer owns the screen (then we refresh once on pen-up).
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
        val batch = refreshes.drain() ?: return
        if (paintLayer.isReleased) return
        refreshCanvasRect(batch.rect, batch.mode)
    }

    private fun refreshCanvasRect(c: DirtyRect, mode: EinkMode) {
        tmpRect.set(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat())
        viewMatrix.mapRect(tmpRect)
        val rect = Rect(tmpRect.left.toInt() - 2, tmpRect.top.toInt() - 2, tmpRect.right.toInt() + 2, tmpRect.bottom.toInt() + 2)
        OnyxEinkBridge.refreshRect(this, rect, mode)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = minOf(w.toFloat() / cw, h.toFloat() / ch)
        val dx = (w - cw * scale) / 2f
        val dy = (h - ch * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        updateMatrix()
    }

    private fun updateMatrix() {
        viewMatrix.set(baseMatrix)
        viewMatrix.postScale(zoom, zoom)
        viewMatrix.postTranslate(panX, panY)
        viewMatrix.invert(inverse)
        viewMatrix.getValues(matrixValues)
        viewScale = matrixValues[Matrix.MSCALE_X]
    }

    private fun applyTransform(scale: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {
        val newZoom = (zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newZoom / zoom
        panX = focusX - (focusX - panX) * f + dx
        panY = focusY - (focusY - panY) * f + dy
        zoom = newZoom
        clampPan()
        updateMatrix()
        if (scale != 1f) onZoomChanged?.invoke(zoom)
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
        paintLayer.draw(canvas, bitmapPaint)
        canvas.drawBitmap(lineOverlay, 0f, 0f, linePaint)
        canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), borderPaint)
        canvas.restore()
    }

    private fun kindOf(e: MotionEvent, index: Int): PointerKind = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
        MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.UNKNOWN
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                router.down(e.getPointerId(i), kindOf(e, i), e.getX(i), e.getY(i), e.getTouchMajor(i), e.getPressure(i), e.eventTime)
                // Deliver the rest of a stroke's samples immediately instead of batching them to vsync:
                // up to one frame less input latency for the brush and the BOOX hardware pen trace.
                // Rendering stays one upload + one refresh per frame thanks to the coalescing above.
                if (router.mode == InputRouter.Mode.STROKE) requestUnbufferedDispatch(e)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount) {
                        router.move(e.getPointerId(i), e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h), e.getHistoricalEventTime(h))
                    }
                }
                for (i in 0 until e.pointerCount) {
                    router.move(e.getPointerId(i), e.getX(i), e.getY(i), e.getPressure(i), e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                router.up(e.getPointerId(i), e.getX(i), e.getY(i), e.getPressure(i), e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> router.cancel()
            else -> return super.onTouchEvent(e)
        }
        return true
    }

    private fun strokeWidthPx(): Float = controller.brushSize.radius * 2f * viewScale

    /** BOOX scribble API works in SCREEN coordinates → offset view-local points by the view's location. */
    private fun startHwPreview(x: Float, y: Float, pressure: Float, time: Long) {
        if (!OnyxEinkBridge.isAvailable) return
        getLocationOnScreen(screenLoc)
        val limit = Rect(screenLoc[0], screenLoc[1], screenLoc[0] + width, screenLoc[1] + height)
        hwPreview = OnyxEinkBridge.enterScribble(this, strokeWidthPx(), limit) &&
            OnyxEinkBridge.strokeStart(strokeWidthPx(), x + screenLoc[0], y + screenLoc[1], pressure, 1f, time)
        if (!hwPreview) OnyxEinkBridge.leaveScribble(this)
    }

    private fun stopHwPreview(x: Float, y: Float, pressure: Float, time: Long, cancel: Boolean) {
        if (!hwPreview) return
        if (!cancel) OnyxEinkBridge.strokeFinish(strokeWidthPx(), x + screenLoc[0], y + screenLoc[1], pressure, 1f, time)
        OnyxEinkBridge.leaveScribble(this)
        hwPreview = false
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
        const val TAP_SLOP_PX = 24f
        const val PALM_TOUCH_MAJOR_PX = 120f
        /** Highlight the ghost-clearing full refresh button after this many partial updates. */
        const val GHOST_HINT_EVERY = 15
    }
}
