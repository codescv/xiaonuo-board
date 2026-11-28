package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.eink.RawDrawingGeometry
import dev.xiaonuoboard.app.eink.TraceBounds
import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.KaleidoPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** View-local coordinate conventions of the BOOX TouchHelper pen layer. */
class RawDrawingGeometryTest {

    @Test
    fun overlayWindowRectsBecomeClippedViewLocalExcludes() {
        // Canvas view sits at (12, 140) in the window and is 800×1000.
        val limit = DirtyRect(0, 0, 800, 1000)
        val refButton = DirtyRect(12 + 730, 140 + 6, 12 + 794, 140 + 70) // top-right overlay button
        val toast = DirtyRect(12 + 300, 140 + 950, 12 + 500, 140 + 1010) // sticks out below the canvas
        val toolbar = DirtyRect(0, 0, 900, 120) // entirely above the canvas
        val ex = RawDrawingGeometry.windowToLocalExcludes(listOf(refButton, toast, toolbar), 12, 140, limit)
        assertEquals(listOf(DirtyRect(730, 6, 794, 70), DirtyRect(300, 950, 500, 1000)), ex)
    }

    @Test
    fun excludesAreClippedToAPartiallyVisibleLimit() {
        val limit = DirtyRect(0, 200, 800, 1000) // top 200 px scrolled off / clipped
        val ex = RawDrawingGeometry.windowToLocalExcludes(listOf(DirtyRect(0, 0, 100, 300)), 0, 0, limit)
        assertEquals(listOf(DirtyRect(0, 200, 100, 300)), ex)
    }

    @Test
    fun traceWidthFollowsBrushEraserAndZoom() {
        val r = BrushSize.MEDIUM.radius
        assertEquals(r * 2f * 0.5f, RawDrawingGeometry.traceWidthPx(r, eraser = false, viewScale = 0.5f), 1e-4f)
        assertEquals(r * 1.5f * 2f * 1.5f, RawDrawingGeometry.traceWidthPx(r, eraser = true, viewScale = 1.5f), 1e-4f)
    }

    @Test
    fun traceRefreshRectCoversEveryTracePixel() {
        val t = TraceBounds()
        assertNull(t.refreshRect(10f, 6f))
        t.add(100.4f, 200.6f)
        t.add(50.2f, 260.1f)
        t.add(80f, 190f)
        val r = t.refreshRect(strokeWidth = 10f, pad = 6f)!!
        // x ∈ [50.2, 100.4], y ∈ [190, 260.1], ± (5 + 6)
        assertTrue(r.left <= 39 && r.top <= 179 && r.right >= 112 && r.bottom >= 272)
        assertEquals(DirtyRect(39, 179, 113, 273), r)
        t.reset()
        assertTrue(t.isEmpty)
        t.add(5f, 5f)
        assertEquals(DirtyRect(-1, -1, 12, 12), t.refreshRect(2f, 5f))
    }

    @Test
    fun emptyExcludesAreSentAsAClearingSentinel() {
        // An empty list would be a no-op in onyxsdk-pen 1.5.9 and leave the previous overlay excluded.
        assertEquals(listOf(RawDrawingGeometry.CLEAR_EXCLUDES), RawDrawingGeometry.touchHelperExcludes(emptyList()))
        val s = RawDrawingGeometry.CLEAR_EXCLUDES
        assertTrue("zero area (skips Onyx's empty-region log)", s.isEmpty)
        assertTrue("far off screen", s.right < 0 && s.bottom < 0)
        val one = listOf(DirtyRect(1, 2, 3, 4))
        assertEquals(one, RawDrawingGeometry.touchHelperExcludes(one))
    }

    @Test
    fun hardwarePreviewColorSnapsNearBlackDarkensYellowAndPastelsAndWhitesOutEraser() {
        // Eraser always previews in white regardless of selected color.
        assertEquals(0xFFFFFFFF.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFFE61919.toInt(), eraser = true))
        assertEquals(0xFFFFFFFF.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFF000000.toInt(), eraser = true))

        // Vivid dark / mid colors (lum <= 0.48) pass through unchanged (always opaque).
        assertEquals(0xFFE61919.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFFE61919.toInt(), eraser = false))
        assertEquals(0xFF0077B6.toInt(), RawDrawingGeometry.hardwarePreviewColor(0x000077B6, eraser = false))
        assertEquals(0xFF38B000.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFF38B000.toInt(), eraser = false))
        assertEquals(0xFF000000.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFF000000.toInt(), eraser = false))

        // Near-black (KaleidoPalette black #111111, charcoal #222222) snaps to exact Color.BLACK
        // (0xFF000000) so BOOX firmware takes the 300 PPI solid-black scribble path instead of gray.
        assertEquals(0xFF000000.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFF111111.toInt(), eraser = false))
        assertEquals(0xFF000000.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFF222222.toInt(), eraser = false))

        // Vivid yellow (#FFD600) is darkened by perceived luminance (<= 0.37) with all channels < 128
        // so it stays bold and visible on Kaleido Plus's fast scribble waveform.
        val yellow = RawDrawingGeometry.hardwarePreviewColor(0xFFFFD600.toInt(), eraser = false)
        assertEquals(0xFF, yellow ushr 24)
        val yR = (yellow ushr 16) and 0xFF
        val yG = (yellow ushr 8) and 0xFF
        val yB = yellow and 0xFF
        val yLum = (0.299f * yR + 0.587f * yG + 0.114f * yB) / 255f
        assertTrue("yellow preview luminance <= 0.37 (got $yLum, rgb=$yR,$yG,$yB)", yLum <= 0.37f)
        assertTrue("yellow preview keeps golden hue (R > G > B, got $yR,$yG,$yB)", yR > yG && yG > yB)
        assertTrue("yellow preview darkens all channels < 128 for 1-bit CFA visibility (got $yR,$yG,$yB)", yR < 128 && yG < 128)

        // Pale pastel yellow (#FCF7D4) is also darkened to luminance <= 0.37 and saturated >= 0.54.
        val paleYellow = RawDrawingGeometry.hardwarePreviewColor(0xFFFCF7D4.toInt(), eraser = false)
        assertEquals(0xFF, paleYellow ushr 24)
        val pyR = (paleYellow ushr 16) and 0xFF
        val pyG = (paleYellow ushr 8) and 0xFF
        val pyB = paleYellow and 0xFF
        val pyLum = (0.299f * pyR + 0.587f * pyG + 0.114f * pyB) / 255f
        val pyMax = maxOf(pyR, pyG, pyB) / 255f
        val pyMin = minOf(pyR, pyG, pyB) / 255f
        val pySat = (pyMax - pyMin) / pyMax
        assertTrue("luminance <= 0.37 (got $pyLum)", pyLum <= 0.37f)
        assertTrue("saturation boosted >= 0.54 (got $pySat)", pySat >= 0.54f)
        assertTrue("still yellow-ish (R >= G > B): $pyR,$pyG,$pyB", pyR >= pyG && pyG > pyB)

        // Pale pink & pale cyan also get darkened by luminance while preserving dominant channel.
        val palePink = RawDrawingGeometry.hardwarePreviewColor(0xFFFFE0E8.toInt(), eraser = false)
        val ppR = (palePink ushr 16) and 0xFF
        val ppG = (palePink ushr 8) and 0xFF
        val ppB = palePink and 0xFF
        val ppLum = (0.299f * ppR + 0.587f * ppG + 0.114f * ppB) / 255f
        assertTrue("pale pink stays red-dominant (got $ppR,$ppG,$ppB)", ppR > ppG)
        assertTrue("pale pink luminance <= 0.37 (got $ppLum)", ppLum <= 0.37f)

        // White / light neutral gray snaps to OnyxPenDemo's DARK_GRAY (#404040) so it shows up clearly on white paper.
        assertEquals(0xFF404040.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFFFFFFFF.toInt(), eraser = false))
        assertEquals(0xFF404040.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFFCCCCCC.toInt(), eraser = false))
    }

    @Test
    fun allKaleidoPaletteCrayonsProduceHighContrastHardwarePreview() {
        for (crayon in KaleidoPalette.CRAYONS) {
            val preview = RawDrawingGeometry.hardwarePreviewColor(crayon.argb, eraser = false)
            assertEquals("opaque alpha for ${crayon.id}", 0xFF, preview ushr 24)
            val r = (preview ushr 16) and 0xFF
            val g = (preview ushr 8) and 0xFF
            val b = preview and 0xFF
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            assertTrue("${crayon.id} preview lum <= 0.48 (got $lum, #${Integer.toHexString(preview)})", lum <= 0.48f)
            if (r == g && g == b) {
                assertTrue(
                    "neutral ${crayon.id} must be exact Color.BLACK or DARK_GRAY (got #${Integer.toHexString(preview)})",
                    preview == 0xFF000000.toInt() || preview == 0xFF404040.toInt(),
                )
            }
        }
    }
}
