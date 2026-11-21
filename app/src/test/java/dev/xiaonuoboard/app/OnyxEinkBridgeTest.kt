package dev.xiaonuoboard.app

import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.EinkSupport
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
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
        assertTrue(OnyxEinkBridge.statusText.contains("未检测到 BOOX"))

        val v = CountingView()
        assertFalse(OnyxEinkBridge.setViewDefaultMode(v, EinkMode.REGAL))
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
}
