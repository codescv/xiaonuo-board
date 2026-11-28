package dev.xiaonuoboard.app.eink

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import dev.xiaonuoboard.app.engine.DirtyRect

/**
 * [RawDrawingBackend] on top of Onyx `TouchHelper` (onyxsdk-pen 1.5.9). Constructed only when
 * [OnyxEinkBridge.isTouchHelperAvailable] (BOOX + stylus + arm64), because creating a TouchHelper
 * instantiates `RawInputReader`, whose static initializer loads `libonyx_pen_touch_reader.so`.
 *
 * Facts verified from the 1.5.9 bytecode:
 *  - `TouchHelper.create(view, cb)` uses `SFTouchRender` on stylus devices; its reader thread maps
 *    raw digitizer points through `EpdController.getRawTouchPointToScreenMatrix()` and subtracts the
 *    host view's `getLocationOnScreen()` (refreshed on start / resume), so every [TouchPoint] is in
 *    VIEW-LOCAL px; `setLimitRect(Rect, List)` likewise takes view-local rects (it maps them with
 *    `EpdController.mapToRawTouchPoint(view, rect)`).
 *  - `SFTouchRender.ReaderCallback` re-posts every callback with `hostView.post(...)`, i.e. they
 *    already arrive on the UI thread, in order (… moves, point list, end). [deliver] still marshals
 *    defensively in case another renderer / firmware calls from the reader thread, and drops events
 *    from a closed session (View.post runnables queued while detached run on re-attach).
 *  - `openRawDrawing()` starts the reader + pen layer but leaves the cached enabled flags false and
 *    resets the stroke style, so we force a known disabled state and set the style afterwards.
 *  - Pen-up auto refresh is on by default (500 ms); it is turned off because the canvas issues its
 *    own refresh with the real (region-clipped, colored) stroke.
 *  - `TouchPoint.pressure` is the RAW digitizer value (`RawInputReader.pressReceived` builds it with
 *    `(float) pressureInt`), scaled by `EpdController.getMaxTouchPressure()`; it is forwarded raw and
 *    normalized by the canvas.
 *  - `setStrokeStyle` / `setStrokeColor` are firmware-global (`EpdController` statics); the style is
 *    reset to PENCIL by `openRawDrawing()` / `closeRawDrawing()` only.
 */
class OnyxTouchHelperBackend(
    private val view: View,
    private val listener: RawPenListener,
) : RawDrawingBackend {
    private val main = Handler(Looper.getMainLooper())
    private var helper: TouchHelper? = null
    /** Bumped on every open / close; events tagged with an older session are dropped. */
    private var session = 0
    /** Last stroke config: re-applied after every enable / render-enable (which resets pen defaults). */
    private var lastWidth = 1f
    private var lastColor = 0xFF000000.toInt()
    private var lastPressureSensitive = false

    private inline fun call(name: String, block: (TouchHelper) -> Unit): Boolean {
        val h = helper ?: return false
        return try {
            block(h)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "TouchHelper.$name failed", t)
            DiagLog.log(DiagLog.TOUCH, "$name FAILED: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Applies brush enable → stroke style → width → color in strict order (Onyx resets defaults on enable). */
    private fun applyStroke(h: TouchHelper) {
        h.setBrushRawDrawingEnabled(true)
        h.setStrokeStyle(strokeStyle(lastPressureSensitive))
        h.setStrokeWidth(lastWidth)
        h.setStrokeColor(lastColor)
    }

    override fun open(
        limit: DirtyRect,
        exclude: List<DirtyRect>,
        strokeWidth: Float,
        strokeColor: Int,
        pressureSensitive: Boolean,
    ): Boolean {
        close()
        lastWidth = strokeWidth
        lastColor = strokeColor
        lastPressureSensitive = pressureSensitive
        val t0 = System.nanoTime()
        return try {
            val h = TouchHelper.create(view, Callback(++session))
            helper = h
            h.setLimitRect(limit.toRect(), excludes(exclude))
            h.openRawDrawing()
            // Deprecated in 1.5.9 but still honoured (RawInputReader.onPenUpRefresh checks the flag);
            // it only schedules a no-op onPenUpRefresh callback, the canvas refreshes itself.
            @Suppress("DEPRECATION")
            h.setPenUpRefreshEnabled(false)
            h.forceSetRawDrawingEnabled(false)
            applyStroke(h)
            DiagLog.log(DiagLog.TOUCH) {
                "open ok session=$session limit=$limit excludes=${exclude.size} width=$strokeWidth " +
                    "color=#${Integer.toHexString(strokeColor)} style=${styleName(pressureSensitive)} in ${DiagLog.ms(System.nanoTime() - t0)}ms"
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "TouchHelper open failed", t)
            DiagLog.log(DiagLog.TOUCH, "open FAILED: ${t.javaClass.simpleName}: ${t.message}")
            close()
            false
        }
    }

    override fun setLimit(limit: DirtyRect, exclude: List<DirtyRect>): Boolean =
        call("setLimitRect") {
            it.setLimitRect(limit.toRect(), excludes(exclude))
            DiagLog.log(DiagLog.TOUCH) { "setLimit $limit excludes=$exclude" }
        }

    private fun excludes(exclude: List<DirtyRect>) = RawDrawingGeometry.touchHelperExcludes(exclude).map { it.toRect() }

    override fun setEnabled(enabled: Boolean): Boolean = call("setRawDrawingEnabled") {
        // Apply stroke before enabling (OnyxPenDemo order) and re-assert after because
        // setRawDrawingEnabled(true) calls resetPenDefaultRawDrawing().
        if (enabled) applyStroke(it)
        it.setRawDrawingEnabled(enabled)
        if (enabled) applyStroke(it)
    }

    override fun setRenderEnabled(enabled: Boolean): Boolean =
        call("setRawDrawingRenderEnabled") {
            // Latch brush → style → width → color immediately before entering PEN_DRAWING (2),
            // matching OnyxPenDemo's PenManager.applyBrushTouchHelperParams (setRawDrawingRenderEnabled
            // does not call resetPenDefaultRawDrawing, so avoid redundant post-resume IPCs).
            if (enabled) applyStroke(it)
            it.setRawDrawingRenderEnabled(enabled)
        }

    override fun setStroke(width: Float, color: Int, pressureSensitive: Boolean): Boolean {
        lastWidth = width
        lastColor = color
        lastPressureSensitive = pressureSensitive
        return call("setStroke") {
            applyStroke(it)
            DiagLog.log(DiagLog.TOUCH) { "setStroke width=$width color=#${Integer.toHexString(color)} style=${styleName(pressureSensitive)}" }
        }
    }

    override fun close() {
        if (helper != null) {
            call("closeRawDrawing") { it.closeRawDrawing() }
            DiagLog.log(DiagLog.TOUCH, "close session=$session")
        }
        helper = null
        session++
    }

    /**
     * FOUNTAIN is the firmware's pressure-sensitive pen (BOOX Notes' pressure pens): the trace gets
     * thicker / thinner with pen pressure, matching the app's pressure-modulated crayon. PENCIL is
     * the constant-width style (eraser: predictable white trace). Both are drawn by the EPD
     * firmware via `EpdController.setStrokeStyle` (onyxsdk-pen 1.5.9 → `BaseDevice.setStrokeStyle`
     * reflection), so neither costs app-side latency.
     */
    private fun strokeStyle(pressureSensitive: Boolean): Int =
        if (pressureSensitive) TouchHelper.STROKE_STYLE_FOUNTAIN else TouchHelper.STROKE_STYLE_PENCIL

    private fun styleName(pressureSensitive: Boolean) = if (pressureSensitive) "FOUNTAIN" else "PENCIL"

    /** One callback per TouchHelper session; primitives are copied at once (points may be reused). */
    private inner class Callback(private val owner: Int) : RawInputCallback() {
        private inline fun deliver(crossinline block: () -> Unit) {
            if (Looper.myLooper() === Looper.getMainLooper()) {
                if (owner == session) block()
            } else {
                main.post { if (owner == session) block() }
            }
        }

        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) = begin(p, erasing = false)
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) = end(p, erasing = false)
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) = move(p, erasing = false)
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) = points(list, erasing = false)
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) = begin(p, erasing = true)
        override fun onEndRawErasing(b: Boolean, p: TouchPoint) = end(p, erasing = true)
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) = move(p, erasing = true)
        override fun onRawErasingTouchPointListReceived(list: TouchPointList) = points(list, erasing = true)

        private fun begin(p: TouchPoint, erasing: Boolean) {
            val x = p.x; val y = p.y; val pr = p.pressure; val t = p.timestamp
            deliver { listener.onRawBegin(x, y, pr, t, erasing) }
        }

        private fun move(p: TouchPoint, erasing: Boolean) {
            val x = p.x; val y = p.y; val pr = p.pressure; val t = p.timestamp
            deliver { listener.onRawMove(x, y, pr, t, erasing) }
        }

        private fun end(p: TouchPoint, erasing: Boolean) {
            val x = p.x; val y = p.y; val pr = p.pressure; val t = p.timestamp
            deliver { listener.onRawEnd(x, y, pr, t, erasing) }
        }

        private fun points(list: TouchPointList, erasing: Boolean) {
            val pts = list.points ?: return
            val n = pts.size
            val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n); val ts = LongArray(n)
            for (i in 0 until n) {
                val p = pts[i]
                xs[i] = p.x; ys[i] = p.y; ps[i] = p.pressure; ts[i] = p.timestamp
            }
            deliver { listener.onRawPoints(xs, ys, ps, ts, erasing) }
        }
    }

    private fun DirtyRect.toRect() = Rect(left, top, right, bottom)

    private companion object {
        const val TAG = "OnyxTouchHelper"
    }
}
