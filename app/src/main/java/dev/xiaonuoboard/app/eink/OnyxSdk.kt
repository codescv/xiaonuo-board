package dev.xiaonuoboard.app.eink

import android.content.Context
import android.os.Build
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.DeviceFeatureUtil
import com.onyx.android.sdk.utils.ResManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The ONLY place (besides [OnyxTouchHelperBackend]) that references bundled Onyx SDK classes.
 *
 * Every entry point must be reached exclusively through [OnyxEinkBridge] after its BOOX hardware
 * gate: the classes are always on the classpath now, but their static initializers are NOT safe off
 * BOOX — `EpdController.<clinit>` calls `Device.currentDevice()`, whose `<clinit>` reflects on
 * `android.os.Build` / `ro.board.platform` and NPEs on JVM unit tests, and it maps several stock
 * Qualcomm board platforms (msmnile, lito, bengal, …) to `SDMDevice`, i.e. it would happily treat an
 * ordinary phone as a BOOX. Verified against onyxsdk-device 1.3.7 / onyxsdk-pen 1.5.9 bytecode.
 */
internal object OnyxSdk {
    /**
     * One-time process setup, mirroring Onyx's official OnyxPenDemo `DemoApplication`:
     * the SDK reaches BOOX framework APIs (`android.onyx.*`, hidden `View` EPD methods) via
     * reflection, which Android 9+ hidden-API enforcement blocks for a targetSdk 35 app unless the
     * app exempts them. Only ever called on a detected BOOX device.
     */
    fun initProcess(appContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        ResManager.init(appContext)
        RxBaseAction.init(appContext)
    }

    /**
     * Raw stylus pressure scale (`TouchPoint.pressure` is raw digitizer units, 0..this). The SDM / RK
     * implementations reflect the framework value and fall back to 2048 (base class: 1024).
     */
    fun maxTouchPressure(): Float = EpdController.getMaxTouchPressure()

    /** Forces `EpdController` / `Device` class init (the BOOX device probe). Throws if unusable. */
    fun probeEpd(): String {
        // Accessing a static triggers <clinit> → Device.currentDevice() detection.
        val pressure = EpdController.getMaxTouchPressure()
        return "${com.onyx.android.sdk.device.Device.currentDevice().javaClass.simpleName}, maxPressure=$pressure"
    }

    /**
     * `TouchHelper.create(View, cb)` picks the SurfaceFlinger raw-input renderer only when
     * `DeviceFeatureUtil.hasStylus(context)` is true (otherwise an app-side MotionEvent renderer
     * that this app does not need). Its reader thread loads `libonyx_pen_touch_reader.so`, which
     * onyxsdk-pen 1.5.9 ships for arm64-v8a only.
     */
    fun hasStylus(context: Context): Boolean = DeviceFeatureUtil.hasStylus(context)

    private fun updateMode(mode: EinkMode): UpdateMode = when (mode) {
        EinkMode.NORMAL -> UpdateMode.GU
        EinkMode.REGAL -> UpdateMode.REGAL
        EinkMode.FAST -> UpdateMode.DU
        EinkMode.FULL -> UpdateMode.GC
    }

    fun setViewDefaultMode(view: View, mode: EinkMode): Boolean =
        EpdController.setViewDefaultUpdateMode(view, updateMode(mode))

    fun invalidate(view: View, mode: EinkMode) = EpdController.invalidate(view, updateMode(mode))

    fun invalidate(view: View, l: Int, t: Int, r: Int, b: Int, mode: EinkMode) =
        EpdController.invalidate(view, l, t, r, b, updateMode(mode))

    fun repaintEverything(mode: EinkMode) = EpdController.repaintEveryThing(updateMode(mode))
}
