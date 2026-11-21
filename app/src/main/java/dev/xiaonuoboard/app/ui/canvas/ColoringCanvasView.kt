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
import android.view.ScaleGestureDetector
import android.view.View
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.PointerKind
import dev.xiaonuoboard.app.engine.Tool
import kotlin.math.hypot

/**
 * Native coloring surface. Composites (bottom→top): white paper → optional 20% reference ghost →
 * paint layer → 1-bit black line art. Handles tap-to-fill, crayon/eraser strokes, stylus/finger
 * separation, pinch-zoom/pan (1×–3×) and BOOX partial / fast refreshes.
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
    private val linePaint = Paint() // no filtering: keep 1-bit lines crisp at integer zooms
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 51 } // ~20%
    private val borderPaint = Paint().apply { color = 0xFF111111.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }

    private val baseMatrix = Matrix()
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f

    private val pts = FloatArray(2)
    private var downX = 0f
    private var downY = 0f
    private var tapCandidate = false
    private var stroking = false
    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var multiTouch = false
    private var hwPreview = false
    private var strokesSinceFull = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val f = newZoom / zoom
            // Zoom about the focal point.
            panX = d.focusX - (d.focusX - panX) * f
            panY = d.focusY - (d.focusY - panY) * f
            zoom = newZoom
            clampPan()
            updateMatrix()
            onZoomChanged?.invoke(zoom)
            invalidate()
            return true
        }
    })

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

    /** Copies changed paint pixels into the display bitmap and schedules a partial refresh. */
    private fun syncRect(r: DirtyRect, mode: EinkMode = EinkMode.NORMAL) {
        val c = r.clamp(cw, ch)
        if (c.isEmpty) return
        paintBitmap.setPixels(controller.paint, c.top * cw + c.left, cw, c.left, c.top, c.width, c.height)
        val screen = RectF(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat())
        viewMatrix.mapRect(screen)
        val rect = Rect(screen.left.toInt() - 2, screen.top.toInt() - 2, screen.right.toInt() + 2, screen.bottom.toInt() + 2)
        OnyxEinkBridge.refreshRect(this, rect, if (stroking) EinkMode.FAST else mode)
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
    }

    private fun clampPan() {
        if (zoom <= 1f) { panX = 0f; panY = 0f; return }
        val w = width.toFloat()
        val h = height.toFloat()
        panX = panX.coerceIn(w - w * zoom, 0f)
        panY = panY.coerceIn(h - h * zoom, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.concat(viewMatrix)
        if (ghostVisible) refBitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, cw, ch), ghostPaint) }
        canvas.drawBitmap(paintBitmap, 0f, 0f, bitmapPaint)
        canvas.drawBitmap(lineOverlay, 0f, 0f, linePaint)
        canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), borderPaint)
        canvas.restore()
    }

    private fun toCanvas(x: Float, y: Float): FloatArray {
        pts[0] = x; pts[1] = y
        inverse.mapPoints(pts)
        return pts
    }

    private fun kindOf(e: MotionEvent, index: Int = 0): PointerKind = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
        MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.UNKNOWN
    }

    /** Palm rejection heuristic: very large contact area finger touches are ignored. */
    private fun isPalm(e: MotionEvent): Boolean =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && e.getTouchMajor(0) > PALM_TOUCH_MAJOR_PX

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Pinch / two-finger pan is always allowed with fingers.
        if (e.pointerCount > 1 || multiTouch) scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                downX = e.x; downY = e.y
                val kind = kindOf(e)
                if (isPalm(e)) return true
                tapCandidate = controller.tool == Tool.FILL
                if (controller.tool != Tool.FILL) {
                    if (controller.accepts(kind)) {
                        val p = toCanvas(e.x, e.y)
                        stroking = controller.strokeBegin(p[0], p[1], kind)
                        if (stroking && kind == PointerKind.STYLUS) startHwPreview(e)
                    } else if (zoom > 1f) {
                        // 仅笔模式 + finger → pan instead of drawing.
                        panning = true; lastPanX = e.x; lastPanY = e.y
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                tapCandidate = false
                if (stroking && kindOf(e) == PointerKind.FINGER) {
                    controller.strokeCancel(); stroking = false; stopHwPreview(e, cancel = true)
                }
                panning = true
                lastPanX = midX(e); lastPanY = midY(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (stroking) {
                    for (h in 0 until e.historySize) {
                        val p = toCanvas(e.getHistoricalX(h), e.getHistoricalY(h))
                        controller.strokeMove(p[0], p[1])
                        if (hwPreview) OnyxEinkBridge.strokeAdd(strokeWidthPx(), e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h), 1f, e.eventTime)
                    }
                    val p = toCanvas(e.x, e.y)
                    controller.strokeMove(p[0], p[1])
                    if (hwPreview) OnyxEinkBridge.strokeAdd(strokeWidthPx(), e.x, e.y, e.pressure, 1f, e.eventTime)
                } else if (panning) {
                    val mx = if (e.pointerCount > 1) midX(e) else e.x
                    val my = if (e.pointerCount > 1) midY(e) else e.y
                    if (zoom > 1f) {
                        panX += mx - lastPanX; panY += my - lastPanY
                        clampPan(); updateMatrix(); invalidate()
                    }
                    lastPanX = mx; lastPanY = my
                } else if (tapCandidate && hypot(e.x - downX, e.y - downY) > TAP_SLOP_PX) {
                    tapCandidate = false
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastPanX = e.getX(remaining); lastPanY = e.getY(remaining)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (stroking) {
                    stopHwPreview(e, cancel = false)
                    stroking = false
                    val r = controller.strokeEnd()
                    // Commit the stroke in color with a clean (non-FAST) partial refresh.
                    syncRect(r, EinkMode.NORMAL)
                    strokesSinceFull++
                    onStrokeFinished?.invoke()
                } else if (tapCandidate && !multiTouch) {
                    val p = toCanvas(e.x, e.y)
                    if (p[0] in 0f..cw.toFloat() && p[1] in 0f..ch.toFloat()) {
                        val r = controller.tapFill(p[0].toInt(), p[1].toInt())
                        if (!r.isEmpty) {
                            strokesSinceFull++
                            onFilled?.invoke()
                        }
                    }
                }
                tapCandidate = false; panning = false; multiTouch = false
                onGhostHint?.invoke(strokesSinceFull >= GHOST_HINT_EVERY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (stroking) { controller.strokeCancel(); stopHwPreview(e, cancel = true) }
                stroking = false; tapCandidate = false; panning = false; multiTouch = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun midX(e: MotionEvent) = if (e.pointerCount > 1) (e.getX(0) + e.getX(1)) / 2f else e.x
    private fun midY(e: MotionEvent) = if (e.pointerCount > 1) (e.getY(0) + e.getY(1)) / 2f else e.y

    private fun strokeWidthPx(): Float {
        val scale = FloatArray(9).also { viewMatrix.getValues(it) }[Matrix.MSCALE_X]
        return controller.brushSize.radius * 2f * scale
    }

    private fun startHwPreview(e: MotionEvent) {
        if (!OnyxEinkBridge.isAvailable) return
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        hwPreview = OnyxEinkBridge.enterScribble(this, strokeWidthPx(), Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)) &&
            OnyxEinkBridge.strokeStart(strokeWidthPx(), e.x, e.y, e.pressure, 1f, e.eventTime)
        if (!hwPreview) OnyxEinkBridge.leaveScribble(this)
    }

    private fun stopHwPreview(e: MotionEvent, cancel: Boolean) {
        if (!hwPreview) return
        if (!cancel) OnyxEinkBridge.strokeFinish(strokeWidthPx(), e.x, e.y, e.pressure, 1f, e.eventTime)
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
