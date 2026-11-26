package dev.xiaonuoboard.app.eink

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppStrings

/** App-level E-Ink refresh modes (mapped onto Onyx `UpdateMode` in [OnyxSdk]). */
enum class EinkMode {
    /** Balanced partial refresh (Onyx GU). */
    NORMAL,
    /** BOOX "Regal" clear mode — sharp UI, low ghosting (default for browsing pages). */
    REGAL,
    /** Fast DU mode for pen strokes & panning (some ghosting). */
    FAST,
    /** Full-waveform flash (GC): clears ghosting. */
    FULL,
    ;

    fun label(strings: AppStrings = AppI18n.strings()): String = strings.einkModeLabel(this)
}

/** User-selectable BOOX SDK integration level (Settings → BOOX SDK Mode). */
enum class BooxSdkMode {
    /** Completely bypass Onyx SDK (identical to pre-SDK standard Android `View.invalidate()`). */
    OFF,
    /** BOOX EPD partial/fast waveforms only; paint strokes directly in real color without TouchHelper. */
    EPD_ONLY,
    /** Full BOOX SDK: EPD waveforms + TouchHelper hardware raw drawing overlay. */
    FULL,
    ;

    fun label(strings: AppStrings = AppI18n.strings()): String = strings.booxSdkModeLabel(this)
}

/** What the BOOX integration is actually doing on this device (Settings → 「BOOX 墨水屏」). */
enum class EinkSupport {
    /** Not a BOOX device (or a JVM test): standard `View.invalidate()` everywhere. */
    NOT_BOOX,
    /** BOOX hardware detected, but user manually switched BOOX SDK mode to [BooxSdkMode.OFF]. */
    BOOX_SDK_DISABLED,
    /** BOOX hardware detected but the bundled Onyx SDK failed to initialize → standard refresh. */
    BOOX_SDK_FAILED,
    /** BOOX EPD partial / fast refresh active; pen strokes use the standard (non-raw) path. */
    BOOX_EPD,
    /** BOOX EPD refresh + TouchHelper low-latency raw pen drawing. */
    BOOX_EPD_TOUCH_HELPER,
}

/**
 * Bridge to the bundled BOOX Onyx SDK (`onyxsdk-device` [EpdController] + `onyxsdk-pen` TouchHelper).
 *
 * The SDK classes are always on the classpath (also on phones, emulators and JVM tests), so the
 * gate is the HARDWARE: [isBooxDevice] is decided from `Build` + the BOOX framework class alone and
 * no Onyx class is touched until it is true. Off BOOX every call degrades to `View.invalidate()`
 * without ever loading Onyx classes or native libraries. On BOOX every SDK call is wrapped so a
 * firmware quirk degrades to the standard path instead of crashing.
 */
object OnyxEinkBridge {
    private const val TAG = "OnyxEinkBridge"
    private const val BOOX_FRAMEWORK_CLASS = "android.onyx.hardware.DeviceController"
    const val SDK_VERSIONS = "onyxsdk-device 1.3.7 · onyxsdk-pen 1.5.9"

    /**
     * Pure BOOX detection rule (unit-tested). Onyx devices report `ONYX` as manufacturer and
     * `ONYX`/`BOOX`-prefixed brands; the BOOX firmware also ships [BOOX_FRAMEWORK_CLASS].
     * Deliberately NOT `Device.currentDevice()`: that matches stock Qualcomm board platforms.
     */
    internal fun detectBoox(manufacturer: String?, brand: String?, hasBooxFramework: Boolean): Boolean {
        val m = manufacturer.orEmpty().lowercase()
        val b = brand.orEmpty().lowercase()
        return m.contains("onyx") || b.contains("onyx") || b.contains("boox") || hasBooxFramework
    }

    /** True on Onyx BOOX hardware. Never touches Onyx SDK classes (safe on JVM / any device). */
    val isBooxDevice: Boolean by lazy {
        detectBoox(Build.MANUFACTURER, Build.BRAND, hasClass(BOOX_FRAMEWORK_CLASS))
    }

    private fun hasClass(name: String): Boolean = try {
        Class.forName(name, false, OnyxEinkBridge::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

    /** Test hook: becomes true the first time any Onyx SDK class is touched. */
    @Volatile
    internal var sdkTouched: Boolean = false
        private set

    @Volatile private var processInitDone = false
    @Volatile private var epdReady = false
    @Volatile private var touchHelperReady = false
    @Volatile private var deviceInfo: String? = null
    @Volatile private var failure: String? = null
    @Volatile private var rawDrawingFailure: String? = null
    @Volatile private var maxPressure: Float = 0f

    /** User-selected BOOX SDK mode (persisted in Settings). Defaults to [BooxSdkMode.OFF]. */
    @Volatile
    var sdkMode: BooxSdkMode = BooxSdkMode.OFF
        private set

    fun setSdkMode(mode: BooxSdkMode) {
        if (sdkMode == mode) return
        sdkMode = mode
        DiagLog.log(DiagLog.ONYX, "sdkMode=${mode.name}")
    }

    /**
     * One-time setup; call from `Activity.onCreate` before any canvas is created. No-op off BOOX.
     * Probes the EPD controller and whether TouchHelper raw drawing can run here.
     */
    @Synchronized
    fun init(context: Context) {
        if (processInitDone || !isBooxDevice) return
        processInitDone = true
        sdkTouched = true
        val app = context.applicationContext
        try {
            OnyxSdk.initProcess(app)
            deviceInfo = OnyxSdk.probeEpd()
            epdReady = true
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "Onyx EPD init failed; using standard refresh", t)
            DiagLog.log(DiagLog.ONYX, "init FAILED: $failure")
            return
        }
        maxPressure = try { OnyxSdk.maxTouchPressure() } catch (_: Throwable) { 0f }
        touchHelperReady = try {
            Build.SUPPORTED_ABIS.orEmpty().contains("arm64-v8a") && OnyxSdk.hasStylus(app)
        } catch (t: Throwable) {
            Log.w(TAG, "Onyx stylus probe failed", t)
            DiagLog.log(DiagLog.ONYX, "stylus probe failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        DiagLog.log(DiagLog.ONYX, "init ok: device=$deviceInfo touchHelperReady=$touchHelperReady maxTouchPressure=$maxPressure")
    }

    /**
     * Raw `TouchPoint.pressure` scale reported by the BOOX framework (`EpdController.getMaxTouchPressure()`),
     * or 0 when unknown (not BOOX / SDK failed). See [dev.xiaonuoboard.app.engine.RawPressureNormalizer].
     */
    val maxTouchPressure: Float get() = maxPressure

    /** Human-readable reason why BOOX features are degraded, if any (diagnostics). */
    val failureReason: String? get() = failure ?: rawDrawingFailure

    /** Multi-line BOOX status block for the diagnostic log header. */
    fun diagSummary(): String = buildString {
        append("sdkMode=").append(sdkMode.name)
        append(" isBooxDevice=").append(isBooxDevice)
        append(" isAvailable=").append(isAvailable)
        append(" hasTouchHelper=").append(isTouchHelperAvailable)
        append(" touchHelperReady=").append(touchHelperReady)
        append(" support=").append(support.name).append('\n')
        append("  sdk=").append(SDK_VERSIONS)
        append(" device=").append(deviceInfo ?: "-")
        append(" maxTouchPressure=").append(maxPressure).append('\n')
        append("  initFailure=").append(failure ?: "-")
        append(" rawDrawingFailure=").append(rawDrawingFailure ?: "-")
    }

    /** True when running on BOOX hardware with a working EPD controller and SDK not disabled by user. */
    val isAvailable: Boolean get() = sdkMode != BooxSdkMode.OFF && isBooxDevice && epdReady

    /** True when TouchHelper raw drawing may be used (FULL mode + BOOX + EPD + stylus + arm64, no runtime failure). */
    val isTouchHelperAvailable: Boolean
        get() = sdkMode == BooxSdkMode.FULL && isAvailable && touchHelperReady && rawDrawingFailure == null

    val support: EinkSupport
        get() = when {
            !isBooxDevice -> EinkSupport.NOT_BOOX
            sdkMode == BooxSdkMode.OFF -> EinkSupport.BOOX_SDK_DISABLED
            !epdReady -> EinkSupport.BOOX_SDK_FAILED
            isTouchHelperAvailable -> EinkSupport.BOOX_EPD_TOUCH_HELPER
            else -> EinkSupport.BOOX_EPD
        }

    /** Human-readable status for Settings → BOOX E-Ink. */
    fun statusText(strings: AppStrings = AppI18n.strings()): String = when (support) {
        EinkSupport.NOT_BOOX ->
            strings.booxStatusNotBoox(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())
        EinkSupport.BOOX_SDK_DISABLED ->
            strings.booxStatusSdkDisabled(Build.MODEL.orEmpty())
        EinkSupport.BOOX_SDK_FAILED ->
            strings.booxStatusSdkFailed(failure ?: strings.booxStatusUninitialized)
        EinkSupport.BOOX_EPD_TOUCH_HELPER ->
            strings.booxStatusEpdTouchHelper(Build.MODEL.orEmpty(), SDK_VERSIONS)
        EinkSupport.BOOX_EPD -> {
            val why = if (sdkMode == BooxSdkMode.EPD_ONLY) {
                strings.booxStatusEpdOnlyReason
            } else {
                rawDrawingFailure ?: strings.booxStatusNoStylusReason
            }
            strings.booxStatusEpd(Build.MODEL.orEmpty(), why)
        }
    }

    val statusText: String get() = statusText(AppI18n.strings())

    /** Diagnostic line (device class detected by the SDK). */
    val deviceDetail: String? get() = deviceInfo

    /** Called when TouchHelper misbehaves at runtime: later canvases use the standard pen path. */
    fun reportRawDrawingFailure(reason: String) {
        if (rawDrawingFailure == null) {
            rawDrawingFailure = reason
            Log.w(TAG, "TouchHelper raw drawing disabled: $reason")
            DiagLog.log(DiagLog.ONYX, "raw drawing disabled for later canvases: $reason")
        }
    }

    private inline fun onyx(name: String, block: () -> Unit): Boolean {
        if (!isAvailable) return false
        return try {
            block()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Onyx $name failed: ${t.message}")
            DiagLog.log(DiagLog.ONYX, "$name failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Sets the default waveform for a view (e.g. REGAL for browsing pages). */
    fun setViewDefaultMode(view: View, mode: EinkMode): Boolean {
        var ok = false
        onyx("setViewDefaultUpdateMode") { ok = OnyxSdk.setViewDefaultMode(view, mode) }
        return ok
    }

    /**
     * Refreshes a view with a given waveform. The Onyx call (BOOX `View.invalidate(…, mode)` /
     * `invalidateWithUpdateMode` via reflection) already invalidates the view tagged with the
     * waveform; a plain `View.invalidate()` is only added when it did not (off BOOX, a throwing or —
     * since the SDK's reflection swallows failures — silently ignored call: the view is then not
     * dirty), so the canvas never stacks an untagged full-view invalidate on its tagged partial one.
     */
    fun refresh(view: View, mode: EinkMode = EinkMode.NORMAL) {
        val tagged = onyx("invalidate") { OnyxSdk.invalidate(view, mode) }
        if (!tagged || !view.isDirty) view.invalidate()
    }

    /** Partial refresh of a view-local rect (after a fill/stroke). */
    fun refreshRect(view: View, rect: Rect, mode: EinkMode = EinkMode.NORMAL) =
        refreshRect(view, rect.left, rect.top, rect.right, rect.bottom, mode)

    fun refreshRect(view: View, left: Int, top: Int, right: Int, bottom: Int, mode: EinkMode = EinkMode.NORMAL) {
        // Rect-scoped View.invalidate is deprecated (HW-accelerated views redraw fully anyway); BOOX
        // applies the partial waveform to the rect, everything else just redraws.
        val tagged = onyx("invalidateRect") { OnyxSdk.invalidate(view, left, top, right, bottom, mode) }
        if (!tagged || !view.isDirty) view.invalidate()
    }

    /** 「✨ 清残影」: one full GC flash to clear accumulated ghosting. */
    fun clearGhosting(view: View) {
        view.invalidate()
        if (onyx("repaintEveryThing") { OnyxSdk.repaintEverything(EinkMode.FULL) }) return
        onyx("invalidate(GC)") { OnyxSdk.invalidate(view, EinkMode.FULL) }
    }
}
