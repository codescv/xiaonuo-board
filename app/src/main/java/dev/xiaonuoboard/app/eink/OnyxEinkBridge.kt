package dev.xiaonuoboard.app.eink

import android.graphics.Rect
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/** App-level E-Ink refresh modes (mapped onto Onyx `UpdateMode` values). */
enum class EinkMode(val label: String, val onyxNames: List<String>) {
    /** Balanced partial refresh. */
    NORMAL("标准", listOf("GU", "GC")),
    /** BOOX "Regal" clear mode — sharp UI, low ghosting (default for browsing pages). */
    REGAL("REGAL 清晰", listOf("REGAL", "GU")),
    /** Fast A2/DU mode for pen strokes & panning (some ghosting). */
    FAST("快速", listOf("DU", "ANIMATION", "A2")),
    /** Full-waveform flash: clears ghosting. */
    FULL("全刷", listOf("GC", "GC4")),
}

/**
 * Reflection-safe bridge to the BOOX Onyx SDK (`com.onyx.android.sdk.api.device.epd.EpdController`
 * and the scribble/raw-drawing static APIs).
 *
 * The SDK is NOT a compile-time dependency: on BOOX firmware the classes are provided by the system
 * framework / preinstalled libraries; on any other Android device or emulator every call quietly
 * degrades to standard `View.invalidate()` behaviour, so the app still works everywhere.
 */
object OnyxEinkBridge {
    private const val TAG = "OnyxEinkBridge"
    private const val EPD_CONTROLLER = "com.onyx.android.sdk.api.device.epd.EpdController"
    private const val UPDATE_MODE = "com.onyx.android.sdk.api.device.epd.UpdateMode"
    private const val LEGACY_EPD_CONTROLLER = "android.onyx.hardware.DeviceController"

    private val epdClass: Class<*>? by lazy { loadClass(EPD_CONTROLLER) }
    private val updateModeClass: Class<*>? by lazy { loadClass(UPDATE_MODE) }

    /** True when running on BOOX firmware with the EPD controller available. */
    val isAvailable: Boolean by lazy { epdClass != null && updateModeClass != null }

    /** True when the device reports itself as an Onyx/BOOX device (even if the SDK classes are hidden). */
    val isBooxDevice: Boolean by lazy {
        val m = android.os.Build.MANUFACTURER.orEmpty().lowercase()
        val b = android.os.Build.BRAND.orEmpty().lowercase()
        m.contains("onyx") || b.contains("onyx") || b.contains("boox") || loadClass(LEGACY_EPD_CONTROLLER) != null
    }

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (_: Throwable) {
        null
    }

    private fun method(name: String, vararg params: Class<*>): Method? = try {
        epdClass?.getMethod(name, *params)
    } catch (_: Throwable) {
        null
    }

    private val F = Float::class.javaPrimitiveType!!
    private val I = Int::class.javaPrimitiveType!!

    // Hot-path reflection targets are resolved ONCE. Previously every call (including one per stylus
    // sample for addStrokePoint, and one per refresh) rebuilt a signature string with joinToString,
    // took a lock, and cloned the UpdateMode enum array via Class.getEnumConstants().
    private val mSetViewDefault: Method? by lazy { updateModeClass?.let { method("setViewDefaultUpdateMode", View::class.java, it) } }
    private val mInvalidateView: Method? by lazy { updateModeClass?.let { method("invalidate", View::class.java, it) } }
    private val mInvalidateRect: Method? by lazy { updateModeClass?.let { method("invalidate", View::class.java, I, I, I, I, it) } }
    private val mRepaintAll: Method? by lazy { updateModeClass?.let { method("repaintEveryThing", it) } }
    private val mEnterScribble: Method? by lazy { method("enterScribbleMode", View::class.java) }
    private val mLeaveScribble: Method? by lazy { method("leaveScribbleMode", View::class.java) }
    private val mSetStrokeWidth: Method? by lazy { method("setStrokeWidth", F) }
    private val mSetStrokeColor: Method? by lazy { method("setStrokeColor", I) }
    private val mSetRegionLimit: Method? by lazy { method("setScreenHandWritingRegionLimit", View::class.java, IntArray::class.java) }
    private val mStartStroke: Method? by lazy { method("startStroke", F, F, F, F, F, F) }
    private val mAddStrokePoint: Method? by lazy { method("addStrokePoint", F, F, F, F, F, F) }
    private val mFinishStroke: Method? by lazy { method("finishStroke", F, F, F, F, F, F) }

    /** Onyx UpdateMode constant per [EinkMode] (index = ordinal), resolved once. */
    private val updateModes: Array<Any?> by lazy {
        val constants = updateModeClass?.enumConstants
        Array(EinkMode.entries.size) { idx ->
            constants?.let { resolveUpdateMode(EinkMode.entries[idx], it) }
        }
    }

    private fun resolveUpdateMode(mode: EinkMode, constants: Array<out Any>): Any? {
        for (n in mode.onyxNames) {
            constants.firstOrNull { (it as Enum<*>).name == n }?.let { return it }
        }
        return null
    }

    private fun updateMode(mode: EinkMode): Any? = if (updateModeClass == null) null else updateModes[mode.ordinal]

    private fun invokeStatic(m: Method?, vararg args: Any?): Boolean {
        if (m == null) return false
        return try {
            m.invoke(null, *args)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Onyx call ${m.name} failed: ${t.message}")
            false
        }
    }

    /** Sets the default waveform for a view (e.g. REGAL for browsing pages). */
    fun setViewDefaultMode(view: View, mode: EinkMode): Boolean {
        val um = updateMode(mode) ?: return false
        return invokeStatic(mSetViewDefault, view, um)
    }

    /** Refreshes a view with a given waveform; falls back to `invalidate()`. */
    fun refresh(view: View, mode: EinkMode = EinkMode.NORMAL) {
        val um = updateMode(mode)
        if (um != null && invokeStatic(mInvalidateView, view, um)) return
        view.invalidate()
    }

    /** Partial refresh of a screen-space rect (after a fill/stroke). */
    fun refreshRect(view: View, rect: Rect, mode: EinkMode = EinkMode.NORMAL) {
        val um = updateMode(mode)
        if (um != null && invokeStatic(mInvalidateRect, view, rect.left, rect.top, rect.right, rect.bottom, um)) return
        // Rect-scoped invalidate is deprecated (HW-accelerated views redraw fully anyway); BOOX handles
        // the partial waveform above, everything else just redraws.
        view.invalidate()
    }

    /** 「✨ 清残影」: one full GC flash to clear accumulated ghosting. */
    fun clearGhosting(view: View) {
        val um = updateMode(EinkMode.FULL)
        if (um != null) {
            if (invokeStatic(mRepaintAll, um)) return
            if (invokeStatic(mInvalidateView, view, um)) return
        }
        // Non-BOOX fallback: plain redraw (LCDs have no ghosting).
        view.invalidate()
    }

    // ---------- Low-latency stylus preview (Onyx scribble / raw drawing) ----------

    /**
     * Enters BOOX scribble (raw drawing) mode for [view] so that the EPD controller paints the pen
     * trace directly in hardware (<30 ms) while the app renders the real crayon color on pen-up.
     * Returns false on non-BOOX devices (caller then draws its own in-view preview).
     */
    fun enterScribble(view: View, strokeWidth: Float, limit: Rect?): Boolean {
        if (!isAvailable) return false
        val ok = invokeStatic(mEnterScribble, view)
        if (!ok) return false
        invokeStatic(mSetStrokeWidth, strokeWidth)
        invokeStatic(mSetStrokeColor, 0xFF000000.toInt())
        if (limit != null) {
            // Optional on older firmware; failure is non-fatal.
            invokeStatic(mSetRegionLimit, view, intArrayOf(limit.left, limit.top, limit.right, limit.bottom))
        }
        return ok
    }

    fun leaveScribble(view: View) {
        if (!isAvailable) return
        invokeStatic(mLeaveScribble, view)
    }

    fun strokeStart(baseWidth: Float, x: Float, y: Float, pressure: Float, size: Float, time: Long): Boolean =
        isAvailable && invokeStatic(mStartStroke, baseWidth, x, y, pressure, size, time.toFloat())

    fun strokeAdd(baseWidth: Float, x: Float, y: Float, pressure: Float, size: Float, time: Long): Boolean =
        isAvailable && invokeStatic(mAddStrokePoint, baseWidth, x, y, pressure, size, time.toFloat())

    fun strokeFinish(baseWidth: Float, x: Float, y: Float, pressure: Float, size: Float, time: Long): Boolean =
        isAvailable && invokeStatic(mFinishStroke, baseWidth, x, y, pressure, size, time.toFloat())

    /**
     * Onyx `TouchHelper` (pen SDK) probe. `TouchHelper.create(View, RawInputCallback)` requires a
     * subclass of the abstract `RawInputCallback`, which cannot be synthesized via reflection; we
     * therefore only detect it and use the static scribble API above for the hardware preview.
     */
    val hasTouchHelper: Boolean by lazy { loadClass("com.onyx.android.sdk.pen.TouchHelper") != null }
}
