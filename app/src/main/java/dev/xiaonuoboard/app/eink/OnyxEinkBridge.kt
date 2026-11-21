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

    private val methodCache = HashMap<String, Method?>()

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (_: Throwable) {
        null
    }

    private fun method(name: String, vararg params: Class<*>): Method? {
        val key = name + params.joinToString(prefix = "(", postfix = ")") { it.name }
        return synchronized(methodCache) {
            methodCache.getOrPut(key) {
                try {
                    epdClass?.getMethod(name, *params)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private fun updateMode(mode: EinkMode): Any? {
        val cls = updateModeClass ?: return null
        val constants = cls.enumConstants ?: return null
        for (n in mode.onyxNames) {
            constants.firstOrNull { (it as Enum<*>).name == n }?.let { return it }
        }
        return null
    }

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
        val cls = updateModeClass ?: return false
        return invokeStatic(method("setViewDefaultUpdateMode", View::class.java, cls), view, um)
    }

    /** Refreshes a view with a given waveform; falls back to `invalidate()`. */
    fun refresh(view: View, mode: EinkMode = EinkMode.NORMAL) {
        val um = updateMode(mode)
        val cls = updateModeClass
        if (um != null && cls != null && invokeStatic(method("invalidate", View::class.java, cls), view, um)) return
        view.invalidate()
    }

    /** Partial refresh of a screen-space rect (after a fill/stroke). */
    fun refreshRect(view: View, rect: Rect, mode: EinkMode = EinkMode.NORMAL) {
        val um = updateMode(mode)
        val cls = updateModeClass
        if (um != null && cls != null) {
            val m = method(
                "invalidate", View::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, cls,
            )
            if (invokeStatic(m, view, rect.left, rect.top, rect.right, rect.bottom, um)) return
        }
        // Rect-scoped invalidate is deprecated (HW-accelerated views redraw fully anyway); BOOX handles
        // the partial waveform above, everything else just redraws.
        view.invalidate()
    }

    /** 「✨ 清残影」: one full GC flash to clear accumulated ghosting. */
    fun clearGhosting(view: View) {
        val um = updateMode(EinkMode.FULL)
        val cls = updateModeClass
        if (um != null && cls != null) {
            if (invokeStatic(method("repaintEveryThing", cls), um)) return
            if (invokeStatic(method("invalidate", View::class.java, cls), view, um)) return
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
        val ok = invokeStatic(method("enterScribbleMode", View::class.java), view)
        if (!ok) return false
        invokeStatic(method("setStrokeWidth", Float::class.javaPrimitiveType!!), strokeWidth)
        invokeStatic(method("setStrokeColor", Int::class.javaPrimitiveType!!), 0xFF000000.toInt())
        if (limit != null) {
            // Optional on older firmware; failure is non-fatal.
            invokeStatic(
                method("setScreenHandWritingRegionLimit", View::class.java, IntArray::class.java),
                view, intArrayOf(limit.left, limit.top, limit.right, limit.bottom),
            )
        }
        return ok
    }

    fun leaveScribble(view: View) {
        if (!isAvailable) return
        invokeStatic(method("leaveScribbleMode", View::class.java), view)
    }

    private val F = Float::class.javaPrimitiveType!!

    fun strokeStart(baseWidth: Float, x: Float, y: Float, pressure: Float, size: Float, time: Long): Boolean =
        isAvailable && invokeStatic(method("startStroke", F, F, F, F, F, F), baseWidth, x, y, pressure, size, time.toFloat())

    fun strokeAdd(baseWidth: Float, x: Float, y: Float, pressure: Float, size: Float, time: Long): Boolean =
        isAvailable && invokeStatic(method("addStrokePoint", F, F, F, F, F, F), baseWidth, x, y, pressure, size, time.toFloat())

    fun strokeFinish(baseWidth: Float, x: Float, y: Float, pressure: Float, size: Float, time: Long): Boolean =
        isAvailable && invokeStatic(method("finishStroke", F, F, F, F, F, F), baseWidth, x, y, pressure, size, time.toFloat())

    /**
     * Onyx `TouchHelper` (pen SDK) probe. `TouchHelper.create(View, RawInputCallback)` requires a
     * subclass of the abstract `RawInputCallback`, which cannot be synthesized via reflection; we
     * therefore only detect it and use the static scribble API above for the hardware preview.
     */
    val hasTouchHelper: Boolean by lazy { loadClass("com.onyx.android.sdk.pen.TouchHelper") != null }
}
