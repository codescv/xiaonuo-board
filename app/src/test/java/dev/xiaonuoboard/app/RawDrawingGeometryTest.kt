package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.eink.RawDrawingGeometry
import dev.xiaonuoboard.app.eink.TraceBounds
import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.DirtyRect
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
}
