package dev.xiaonuoboard.app

import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import dev.xiaonuoboard.app.eink.BooxSdkMode
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.EinkSupport
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.i18n.EnStrings
import dev.xiaonuoboard.app.i18n.JaStrings
import dev.xiaonuoboard.app.i18n.ZhStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Onyx SDK is bundled, so its classes exist on every device and in JVM tests. These tests pin
 * down that off BOOX hardware the bridge never touches them and always degrades to invalidate().
 */
class OnyxEinkBridgeTest {

    private class CountingView : View(null) {
        var invalidates = 0
        override fun invalidate() { invalidates++ }
    }

    @Test
    fun booxDetectionRule() {
        assertTrue(OnyxEinkBridge.detectBoox("ONYX", "ONYX", false))
        assertTrue(OnyxEinkBridge.detectBoox("Onyx", "BOOX", false))
        assertTrue(OnyxEinkBridge.detectBoox("unknown", "BOOX_Nova", false))
        assertTrue("BOOX framework class alone", OnyxEinkBridge.detectBoox(null, null, true))
        assertFalse(OnyxEinkBridge.detectBoox("Google", "google", false))
        assertFalse(OnyxEinkBridge.detectBoox("samsung", "samsung", false))
        // Stock Qualcomm phones that onyxsdk-device's Device.currentDevice() would map to SDMDevice.
        assertFalse(OnyxEinkBridge.detectBoox("Xiaomi", "Redmi", false))
        assertFalse(OnyxEinkBridge.detectBoox(null, null, false))
        assertFalse(OnyxEinkBridge.detectBoox("", "", false))
    }

    @Test
    fun jvmIsNotBooxAndEveryCallDegradesWithoutTouchingTheSdk() {
        assertFalse(OnyxEinkBridge.isBooxDevice)
        OnyxEinkBridge.init(ContextWrapper(null))
        assertFalse(OnyxEinkBridge.isAvailable)
        assertFalse(OnyxEinkBridge.isTouchHelperAvailable)
        assertEquals(EinkSupport.NOT_BOOX, OnyxEinkBridge.support)
        assertTrue(OnyxEinkBridge.statusText(ZhStrings).contains("未检测到 BOOX"))
        assertTrue(OnyxEinkBridge.statusText(EnStrings).contains("No BOOX device detected"))
        assertTrue(OnyxEinkBridge.statusText(JaStrings).contains("BOOX 端末未検出"))

        val v = CountingView()
        assertFalse(OnyxEinkBridge.setViewDefaultMode(v, EinkMode.REGAL))
        assertFalse(OnyxEinkBridge.enablePost(v, 1))
        assertFalse(OnyxEinkBridge.resetViewUpdateMode(v))
        assertFalse(OnyxEinkBridge.isColorEink)
        assertEquals(OnyxEinkBridge.MONO_PEN_RESUME_DELAY_MS, OnyxEinkBridge.penResumeDelayMs)
        assertEquals(OnyxEinkBridge.COLOR_PEN_RESUME_DELAY_MS, OnyxEinkBridge.penResumeDelayMs(isColor = true))
        assertEquals(OnyxEinkBridge.MONO_PEN_RESUME_DELAY_MS, OnyxEinkBridge.penResumeDelayMs(isColor = false))
        assertEquals(500L, OnyxEinkBridge.COLOR_PEN_RESUME_DELAY_MS)
        assertEquals(150L, OnyxEinkBridge.MONO_PEN_RESUME_DELAY_MS)

        var repaintRuns = 0
        OnyxEinkBridge.withHandwritingRepaint(v) { repaintRuns++ }
        assertEquals(1, repaintRuns)
        var forcedPostRuns = 0
        OnyxEinkBridge.withForcedPost(v, EinkMode.NORMAL, forceUnfreeze = false) { forcedPostRuns++ }
        OnyxEinkBridge.withForcedPost(v, EinkMode.NORMAL, forceUnfreeze = true) { forcedPostRuns++ }
        OnyxEinkBridge.withForcedPost(v, mode = null, forceUnfreeze = true) { forcedPostRuns++ }
        assertEquals(3, forcedPostRuns)
        assertFalse(OnyxEinkBridge.repaintEverything(EinkMode.FULL))

        assertFalse(OnyxEinkBridge.enterFastStroke())
        assertFalse(OnyxEinkBridge.isInFastStroke)
        assertFalse(OnyxEinkBridge.exitFastStroke(refresh = false))
        assertFalse(OnyxEinkBridge.isInFastStroke)

        OnyxEinkBridge.refresh(v, EinkMode.FAST)
        OnyxEinkBridge.refreshRect(v, 1, 2, 30, 40, EinkMode.NORMAL)
        OnyxEinkBridge.refreshRect(v, Rect(), EinkMode.NORMAL)
        OnyxEinkBridge.clearGhosting(v)
        assertEquals("each refresh falls back to exactly one invalidate()", 4, v.invalidates)
        OnyxEinkBridge.reportRawDrawingFailure("test")
        assertFalse("Onyx classes were touched off BOOX", OnyxEinkBridge.sdkTouched)
    }

    /**
     * Documents WHY the hardware gate exists: onyxsdk-device's own BOOX detection cannot even
     * initialize off device. The bridge must stay safe even after the class is poisoned.
     */
    @Test
    fun onyxDeviceDetectionIsUnsafeOffDeviceButBridgeStaysSafe() {
        val threw = try {
            Class.forName("com.onyx.android.sdk.device.Device", true, javaClass.classLoader)
            false
        } catch (_: Throwable) {
            true // ExceptionInInitializerError (first time) / NoClassDefFoundError (afterwards)
        }
        assertTrue("Device.<clinit> unexpectedly succeeded on the JVM", threw)
        val v = CountingView()
        OnyxEinkBridge.refresh(v)
        OnyxEinkBridge.clearGhosting(v)
        assertEquals(2, v.invalidates)
        assertFalse(OnyxEinkBridge.isAvailable)
    }

    @Test
    fun sdkModePersistsInBridgeAndDiagSummaryWithoutTouchingSdkOffBoox() {
        val original = OnyxEinkBridge.sdkMode
        try {
            for (mode in BooxSdkMode.entries) {
                OnyxEinkBridge.setSdkMode(mode)
                assertEquals(mode, OnyxEinkBridge.sdkMode)
                assertTrue(OnyxEinkBridge.diagSummary().contains("sdkMode=${mode.name}"))
                assertFalse(OnyxEinkBridge.isAvailable)
                assertFalse(OnyxEinkBridge.isTouchHelperAvailable)
            }
            assertFalse("Onyx classes were touched off BOOX", OnyxEinkBridge.sdkTouched)
        } finally {
            OnyxEinkBridge.setSdkMode(original)
        }
    }
}
