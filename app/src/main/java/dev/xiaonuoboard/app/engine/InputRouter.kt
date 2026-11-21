package dev.xiaonuoboard.app.engine

import kotlin.math.hypot

/**
 * Platform-independent pointer routing for the coloring canvas (unit-testable without Android).
 *
 * Rules:
 *  - **Pen priority**: a stylus / eraser-tip pointer always wins. If it lands while a finger stroke,
 *    finger tap or pinch is in progress, that gesture is cancelled and the pen takes over, even
 *    when the pen arrives as a secondary pointer (palm rested first).
 *  - While a pen is down, every finger pointer is ignored (palm rejection): no pinch, no pan, no paint.
 *  - Strokes are bound to the pointer id that started them; lifting that pointer ends the stroke even
 *    if other pointers (e.g. a resting palm) remain on screen, and the remaining pointers are then
 *    ignored until everything is lifted.
 *  - Very large finger contacts (touchMajor > [palmTouchMajor]) are treated as palms and ignored.
 *  - Two non-palm fingers (and no pen) → pinch zoom / pan via [Listener.onTransform].
 */
class InputRouter(
    private val controller: ColoringCanvasController,
    private val listener: Listener,
    /** Maps screen coordinates to canvas coordinates in place: `[x, y]`. */
    private val toCanvas: (FloatArray) -> Unit = {},
    private val tapSlop: Float = 24f,
    private val palmTouchMajor: Float = 120f,
) {
    interface Listener {
        /** Pinch / pan: scale about (focusX, focusY) then translate by (dx, dy), all in screen px. */
        fun onTransform(scale: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {}
        fun onStrokeStart(kind: PointerKind, x: Float, y: Float, pressure: Float, time: Long) {}
        fun onStrokePoint(x: Float, y: Float, pressure: Float, time: Long) {}
        fun onStrokeEnd(rect: DirtyRect, x: Float, y: Float, pressure: Float, time: Long, cancelled: Boolean) {}
        fun onFilled(rect: DirtyRect) {}
    }

    enum class Mode { IDLE, STROKE, TAP, PAN, PINCH, IGNORE }

    private class P(val kind: PointerKind, var x: Float, var y: Float, val palm: Boolean)

    var mode: Mode = Mode.IDLE
        private set
    /** Pointer id owning the current stroke / tap / pan, or -1. */
    var activeId: Int = -1
        private set
    private var activeIsPen = false
    private var downX = 0f
    private var downY = 0f
    private val pointers = LinkedHashMap<Int, P>()
    private val buf = FloatArray(2)
    private var pinchA = -1
    private var pinchB = -1
    private var lastDist = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    private fun isPen(kind: PointerKind) = kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP

    private fun canvas(x: Float, y: Float): FloatArray {
        buf[0] = x; buf[1] = y
        toCanvas(buf)
        return buf
    }

    fun down(id: Int, kind: PointerKind, x: Float, y: Float, touchMajor: Float = 0f, pressure: Float = 1f, time: Long = 0L) {
        val pen = isPen(kind)
        val palm = !pen && kind == PointerKind.FINGER && touchMajor > palmTouchMajor
        pointers[id] = P(kind, x, y, palm)
        if (pen) {
            if (mode == Mode.STROKE && activeIsPen) return // second pen pointer: impossible on EMR, ignore
            abortCurrent()
            beginPrimary(id, kind, x, y, pressure, time, isPenPointer = true)
            return
        }
        // Finger / mouse / unknown.
        if (activeIsPen && (mode == Mode.STROKE || mode == Mode.TAP)) return // palm while drawing
        if (mode == Mode.IGNORE || palm) return
        val fingers = pointers.entries.filter { !isPen(it.value.kind) && !it.value.palm }
        if (fingers.size == 1 && mode == Mode.IDLE) {
            beginPrimary(id, kind, x, y, pressure, time, isPenPointer = false)
        } else if (fingers.size >= 2) {
            abortCurrent()
            pinchA = fingers[0].key
            pinchB = fingers[1].key
            val a = pointers.getValue(pinchA)
            val b = pointers.getValue(pinchB)
            lastDist = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
            lastCx = (a.x + b.x) / 2f
            lastCy = (a.y + b.y) / 2f
            mode = Mode.PINCH
            activeId = -1
        }
    }

    private fun beginPrimary(id: Int, kind: PointerKind, x: Float, y: Float, pressure: Float, time: Long, isPenPointer: Boolean) {
        activeId = id
        activeIsPen = isPenPointer
        downX = x; downY = y
        if (controller.tool == Tool.FILL) {
            mode = Mode.TAP
            return
        }
        if (controller.accepts(kind)) {
            val c = canvas(x, y)
            // Notify first so the view can enter its fast/scribble preview before the first dab syncs.
            listener.onStrokeStart(kind, x, y, pressure, time)
            if (controller.strokeBegin(c[0], c[1], kind)) {
                mode = Mode.STROKE
                return
            }
            listener.onStrokeEnd(DirtyRect.EMPTY, x, y, pressure, time, cancelled = true)
        }
        // 仅笔模式 + finger → the finger pans the (zoomed) canvas instead of drawing.
        mode = if (isPenPointer) Mode.IGNORE else Mode.PAN
    }

    /** Pointer moved (call once per historical sample, then for the current sample). */
    fun move(id: Int, x: Float, y: Float, pressure: Float = 1f, time: Long = 0L) {
        val p = pointers[id] ?: return
        val px = p.x
        val py = p.y
        p.x = x; p.y = y
        when (mode) {
            Mode.STROKE -> if (id == activeId) {
                val c = canvas(x, y)
                controller.strokeMove(c[0], c[1])
                listener.onStrokePoint(x, y, pressure, time)
            }
            Mode.TAP -> if (id == activeId && hypot(x - downX, y - downY) > tapSlop) {
                // A dragged finger in fill mode pans; a dragged pen does nothing (avoid accidental fills).
                mode = if (activeIsPen) Mode.IGNORE else Mode.PAN
            }
            Mode.PAN -> if (id == activeId) listener.onTransform(1f, x, y, x - px, y - py)
            Mode.PINCH -> if (id == pinchA || id == pinchB) {
                val a = pointers[pinchA] ?: return
                val b = pointers[pinchB] ?: return
                val dist = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
                val cx = (a.x + b.x) / 2f
                val cy = (a.y + b.y) / 2f
                listener.onTransform(dist / lastDist, cx, cy, cx - lastCx, cy - lastCy)
                lastDist = dist; lastCx = cx; lastCy = cy
            }
            Mode.IDLE, Mode.IGNORE -> Unit
        }
    }

    fun up(id: Int, x: Float, y: Float, pressure: Float = 0f, time: Long = 0L) {
        pointers[id]?.let { it.x = x; it.y = y }
        when (mode) {
            Mode.STROKE -> if (id == activeId) {
                val rect = controller.strokeEnd()
                listener.onStrokeEnd(rect, x, y, pressure, time, cancelled = false)
                finishPrimary()
            }
            Mode.TAP -> if (id == activeId) {
                val c = canvas(x, y)
                val inside = c[0] >= 0f && c[1] >= 0f && c[0] < controller.width && c[1] < controller.height
                if (inside) {
                    val rect = controller.tapFill(c[0].toInt(), c[1].toInt())
                    if (!rect.isEmpty) listener.onFilled(rect)
                }
                finishPrimary()
            }
            Mode.PAN -> if (id == activeId) finishPrimary()
            Mode.PINCH -> if (id == pinchA || id == pinchB) mode = Mode.IGNORE
            Mode.IDLE, Mode.IGNORE -> Unit
        }
        pointers.remove(id)
        if (pointers.isEmpty()) reset()
    }

    fun cancel() {
        abortCurrent()
        pointers.clear()
        reset()
    }

    /**
     * Drops ONE pointer that can no longer deliver an up (e.g. the BOOX raw pen stream was paused
     * mid-stroke): a stroke it owns is rolled back, other gestures are left alone.
     */
    fun cancelPointer(id: Int) {
        if (pointers.remove(id) == null) return
        if (id == activeId) {
            abortCurrent()
            if (pointers.isNotEmpty()) mode = Mode.IGNORE
        } else if (id == pinchA || id == pinchB) {
            mode = Mode.IGNORE
        }
        if (pointers.isEmpty()) reset()
    }

    /**
     * ACTION_CANCEL of the MotionEvent stream: cancels everything except a stroke owned by
     * [keepId], a pointer that does not come from that stream (the BOOX raw pen).
     */
    fun cancelAllExcept(keepId: Int) {
        val keep = pointers[keepId]
        if (keep == null || mode != Mode.STROKE || activeId != keepId) {
            cancel()
            return
        }
        pointers.clear()
        pointers[keepId] = keep
    }

    /** After the owning pointer lifts, ignore whatever is still touching until everything is up. */
    private fun finishPrimary() {
        mode = if (pointers.size > 1) Mode.IGNORE else Mode.IDLE
        activeId = -1
        activeIsPen = false
    }

    private fun abortCurrent() {
        if (mode == Mode.STROKE) {
            controller.strokeCancel()
            listener.onStrokeEnd(DirtyRect.EMPTY, 0f, 0f, 0f, 0L, cancelled = true)
        }
        mode = Mode.IDLE
        activeId = -1
        activeIsPen = false
    }

    private fun reset() {
        mode = Mode.IDLE
        activeId = -1
        activeIsPen = false
        pinchA = -1
        pinchB = -1
    }
}
