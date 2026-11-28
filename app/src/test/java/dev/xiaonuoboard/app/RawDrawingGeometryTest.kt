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
    fun hardwarePreviewColorUsesBooxNotesVividPaletteAndWhiteForEraser() {
        // Eraser always previews in white regardless of selected color.
        assertEquals(RawDrawingGeometry.HW_WHITE, RawDrawingGeometry.hardwarePreviewColor(0xFFE61919.toInt(), eraser = true))
        assertEquals(RawDrawingGeometry.HW_WHITE, RawDrawingGeometry.hardwarePreviewColor(0xFF000000.toInt(), eraser = true))
        assertEquals(RawDrawingGeometry.HW_WHITE, RawDrawingGeometry.hardwarePreviewColor(0xFF3C3C6C.toInt(), eraser = true))

        // Every verified BOOX Notes hardware color is an exact fixed point.
        for (hw in RawDrawingGeometry.BOOX_HARDWARE_PALETTE) {
            assertEquals(
                "BOOX Notes color #${Integer.toHexString(hw)} must map to itself",
                hw,
                RawDrawingGeometry.hardwarePreviewColor(hw, eraser = false),
            )
        }

        // Problematic Bluey / Elsa / Kaleido colors map accurately without false blue tints:
        // - Near-white pale tints (#D4E9F9, #D3E9F9, #F4F2DD) map to neutral dark gray (#404040), never blue.
        // - Dark charcoal/slate (#3B3E63, #3C3C6C, #24243C) map to black (#000000), never blue.
        // - True saturated blues (#000084, #283878, #2400FF) stay navy blue (#000084).
        // - Light coral-red (#FD7874, #FC846C) maps to verified orange-red (#FF4400), distinct from bright red (#F44442 -> #FF0000).
        assertEquals(RawDrawingGeometry.HW_BLACK, RawDrawingGeometry.hardwarePreviewColor(0xFF111111.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_DARK_GRAY, RawDrawingGeometry.hardwarePreviewColor(0xFF8D99AE.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_DARK_GRAY, RawDrawingGeometry.hardwarePreviewColor(0xFFD4E9F9.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_DARK_GRAY, RawDrawingGeometry.hardwarePreviewColor(0xFFD3E9F9.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_DARK_GRAY, RawDrawingGeometry.hardwarePreviewColor(0xFFF4F2DD.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_BLACK, RawDrawingGeometry.hardwarePreviewColor(0xFF3C3C24.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_BLACK, RawDrawingGeometry.hardwarePreviewColor(0xFF3B3E63.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_BLACK, RawDrawingGeometry.hardwarePreviewColor(0xFF3C3C6C.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_BLACK, RawDrawingGeometry.hardwarePreviewColor(0xFF24243C.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_NAVY_BLUE, RawDrawingGeometry.hardwarePreviewColor(0xFF283878.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_GOLD_YELLOW, RawDrawingGeometry.hardwarePreviewColor(0xFFE4CC6C.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_GOLD_YELLOW, RawDrawingGeometry.hardwarePreviewColor(0xFFFFD600.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_DARK_GRAY, RawDrawingGeometry.hardwarePreviewColor(0xFFFCF7D4.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_SKY_BLUE, RawDrawingGeometry.hardwarePreviewColor(0xFF80DBFA.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_SKY_BLUE, RawDrawingGeometry.hardwarePreviewColor(0xFF54CCFC.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_AQUA, RawDrawingGeometry.hardwarePreviewColor(0xFFBADFEE.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_AQUA, RawDrawingGeometry.hardwarePreviewColor(0xFFCCE4FC.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_RED, RawDrawingGeometry.hardwarePreviewColor(0xFFF44442.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_RED, RawDrawingGeometry.hardwarePreviewColor(0xFFFC3C3C.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_ORANGE_RED, RawDrawingGeometry.hardwarePreviewColor(0xFFFD7874.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_ORANGE_RED, RawDrawingGeometry.hardwarePreviewColor(0xFFFC846C.toInt(), eraser = false))
        assertEquals(RawDrawingGeometry.HW_VIOLET, RawDrawingGeometry.hardwarePreviewColor(0xFFFC6CB4.toInt(), eraser = false))
        // Zero-alpha input is still returned opaque in the matching vivid BOOX color.
        assertEquals(RawDrawingGeometry.HW_SKY_BLUE, RawDrawingGeometry.hardwarePreviewColor(0x000077B6, eraser = false))
    }

    @Test
    fun allKaleidoPaletteCrayonsMapToVerifiedPureChromaBooxHardwareColors() {
        val expected = mapOf(
            "red" to RawDrawingGeometry.HW_RED,
            "orange" to RawDrawingGeometry.HW_ORANGE_RED,
            "yellow" to RawDrawingGeometry.HW_GOLD_YELLOW,
            "apple_green" to RawDrawingGeometry.HW_GREEN,
            "forest_green" to RawDrawingGeometry.HW_DARK_GREEN,
            "sky_blue" to RawDrawingGeometry.HW_AQUA,
            "ice_blue" to RawDrawingGeometry.HW_SKY_BLUE,
            "royal_blue" to RawDrawingGeometry.HW_NAVY_BLUE,
            "lavender" to RawDrawingGeometry.HW_VIOLET,
            "magenta" to RawDrawingGeometry.HW_VIOLET,
            "peppa_pink" to RawDrawingGeometry.HW_VIOLET,
            "peach" to RawDrawingGeometry.HW_ORANGE_RED,
            "caramel" to RawDrawingGeometry.HW_ORANGE_RED,
            "warm_gray" to RawDrawingGeometry.HW_DARK_GRAY,
            "black" to RawDrawingGeometry.HW_BLACK,
            "white" to RawDrawingGeometry.HW_WHITE,
        )
        for (crayon in KaleidoPalette.CRAYONS) {
            val mapped = RawDrawingGeometry.hardwarePreviewColor(crayon.argb, eraser = false)
            assertTrue(
                "${crayon.id} mapped to #${Integer.toHexString(mapped)} outside BOOX_HARDWARE_PALETTE",
                mapped in RawDrawingGeometry.BOOX_HARDWARE_PALETTE,
            )
            assertEquals("${crayon.id} expected vivid BOOX color", expected[crayon.id], mapped)
        }
    }

    @Test
    fun booxHardwarePaletteGuaranteesFullAlphaAndPureChromaForAllChromaticColors() {
        for (c in RawDrawingGeometry.BOOX_HARDWARE_PALETTE) {
            assertEquals("must be fully opaque", 0xFF, (c ushr 24) and 0xFF)
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val isNeutral = r == g && g == b
            if (!isNeutral) {
                assertEquals(
                    "chromatic BOOX color #${Integer.toHexString(c)} must have S=1.0 (min channel == 0)",
                    0,
                    minOf(r, g, b),
                )
                assertTrue(
                    "chromatic BOOX color #${Integer.toHexString(c)} must have max channel >= 0x80",
                    maxOf(r, g, b) >= 0x80,
                )
            }
        }
    }
}
