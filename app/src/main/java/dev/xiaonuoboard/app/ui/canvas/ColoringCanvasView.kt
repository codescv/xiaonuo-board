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
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.InputRouter
import dev.xiaonuoboard.app.engine.PointerKind

/**
 * Native coloring surface. Composites (bottom→top): white paper → optional 20% reference ghost →
 * paint layer → 1-bit black line art. Pointer routing (pen priority, palm rejection, pinch/pan,
 * stylus-only) lives in the JVM-tested [InputRouter]; this view only adapts MotionEvents, draws,
 * and drives BOOX partial / fast refreshes and the hardware scribble preview.
 */
@SuppressLint("ViewConstructor")
class ColoringCanvasView(
    context: Context,
    val controller: ColoringCanvasController,
    private val lineOverlay: Bitmap,
) : View(context) {

    private val cw = controller.width
    private val ch = controller.height
    val paintBitmap: Bitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)

    var refBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var ghostVisible: Boolean = false
        set(value) { field = value; OnyxEinkBridge.refresh(this, EinkMode.NORMAL) }

    var onFilled: (() -> Unit)? = null
    var onStrokeFinished: (() -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    /** true once enough partial refreshes accumulated that the ✨ 清残影 button should be highlighted. */
    var onGhostHint: ((Boolean) -> Unit)? = null

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
    private val screenLoc = IntArray(2)
    private val tmpRect = RectF()

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
                // One clean (non-FAST) refresh of the whole stroke in color, after the scribble layer is gone.
                if (!rect.isEmpty) refreshCanvasRect(rect, EinkMode.NORMAL)
                if (!cancelled) {
                    strokesSinceFull++
                    onGhostHint?.invoke(strokesSinceFull >= GHOST_HINT_EVERY)
                    onStrokeFinished?.invoke()
                }
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
        paintBitmap.setPixels(controller.paint, 0, cw, 0, 0, cw, ch)
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

    /** Releases the large display bitmap; call once the view has left the window. */
    fun release() {
        controller.onDirty = null
        if (!paintBitmap.isRecycled) paintBitmap.recycle()
    }

    /** Copies changed paint pixels into the display bitmap; refreshes unless the BOOX scribble layer owns the screen. */
    private fun syncRect(r: DirtyRect) {
        val c = r.clamp(cw, ch)
        if (c.isEmpty || paintBitmap.isRecycled) return
        paintBitmap.setPixels(controller.paint, c.top * cw + c.left, cw, c.left, c.top, c.width, c.height)
        if (hwPreview) return // the hardware pen layer is showing the trace; refresh once on pen-up
        refreshCanvasRect(c, if (stroking) EinkMode.FAST else EinkMode.NORMAL)
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
        if (paintBitmap.isRecycled) return
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.concat(viewMatrix)
        if (ghostVisible) refBitmap?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, null, canvasRect, ghostPaint) }
        canvas.drawBitmap(paintBitmap, 0f, 0f, bitmapPaint)
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
