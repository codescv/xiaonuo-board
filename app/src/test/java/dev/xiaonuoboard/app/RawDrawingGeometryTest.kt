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
    fun hardwarePreviewColorUsesSolidBlackForAllCrayonsAndWhiteForEraser() {
        // Eraser always previews in white regardless of selected color.
        assertEquals(0xFFFFFFFF.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFFE61919.toInt(), eraser = true))
        assertEquals(0xFFFFFFFF.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFF000000.toInt(), eraser = true))
        assertEquals(0xFFFFFFFF.toInt(), RawDrawingGeometry.hardwarePreviewColor(0xFF3C3C6C.toInt(), eraser = true))

        // Every crayon color — including Bluey dark navy (#3C3C6C), near-black (#111111),
        // yellow (#FFD600 / #E4CC6C), pastel cream (#FCF7D4), bright red (#FC3C3C), and white —
        // maps to exact Color.BLACK (0xFF000000) so Kaleido Plus fast hardware scribble drives all
        // three RGB subpixels at 300 PPI full contrast until the 500 ms pen-up burst repaint.
        val sampleColors = listOf(
            0xFF000000.toInt(),
            0xFF111111.toInt(),
            0xFF3C3C6C.toInt(), // Bluey dark navy
            0xFFE4CC6C.toInt(), // Bluey yellow
            0xFF84E4FC.toInt(), // Bluey sky blue
            0xFFFC3C3C.toInt(), // Bluey bright red
            0xFFFC846C.toInt(), // Bluey coral
            0xFFCCE4FC.toInt(), // Bluey pale blue
            0xFFFFD600.toInt(), // Palette yellow
            0xFFFCF7D4.toInt(), // Pastel cream
            0x000077B6,         // Zero-alpha input
            0xFFFFFFFF.toInt(), // White crayon
        )
        for (c in sampleColors) {
            assertEquals(
                "crayon #${Integer.toHexString(c)} must preview in exact Color.BLACK",
                0xFF000000.toInt(),
                RawDrawingGeometry.hardwarePreviewColor(c, eraser = false),
            )
        }
    }

    @Test
    fun allKaleidoPaletteCrayonsProduceSolidBlackHardwarePreview() {
        for (crayon in KaleidoPalette.CRAYONS) {
            assertEquals(
                "${crayon.id} must preview in exact Color.BLACK (0xFF000000)",
                0xFF000000.toInt(),
                RawDrawingGeometry.hardwarePreviewColor(crayon.argb, eraser = false),
            )
        }
    }
}
