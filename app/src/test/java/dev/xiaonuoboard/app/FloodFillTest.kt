package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.CrayonBrush
import dev.xiaonuoboard.app.engine.FloodFill
import dev.xiaonuoboard.app.engine.RegionMap
import dev.xiaonuoboard.app.engine.RegionMapEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloodFillTest {
    private val red = 0xFFE61919.toInt()

    @Test
    fun fillsOnlyTargetRegion() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val paint = IntArray(w * h)
        val left = map.regionAt(30, 40)
        val rect = FloodFill.fill(map, left, red, paint, bleed = 0)
        assertTrue(!rect.isEmpty)
        for (i in paint.indices) {
            val l = map.labels[i]
            if (l == left) assertEquals(red, paint[i]) else assertEquals(0, paint[i])
        }
    }

    @Test
    fun bleedsOnePixelUnderLineButNeverIntoNeighbours() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val paint = IntArray(w * h)
        val left = map.regionAt(30, 40)
        FloodFill.fill(map, left, red, paint, bleed = 1)
        // Divider is x=49..50; left region ends at x=48. 1px bleed paints x=49 (under the line)…
        assertEquals(red, paint[40 * w + 49])
        // …but not x=50 (2px away) and never the right region (x>=51).
        assertEquals(0, paint[40 * w + 50])
        for (y in 0 until h) for (x in 0 until w) {
            if (map.labels[y * w + x] != left && map.labels[y * w + x] != RegionMap.BOUNDARY) {
                assertEquals("leak at $x,$y", 0, paint[y * w + x])
            }
        }
        // Every painted boundary pixel is ink (hidden under the line layer) → zero white fringe.
        for (i in paint.indices) if (paint[i] != 0 && map.labels[i] == RegionMap.BOUNDARY) assertTrue(ink[i])
    }

    @Test
    fun noWhiteFringeBetweenFillAndLine() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val paint = IntArray(w * h)
        FloodFill.fill(map, map.regionAt(30, 40), red, paint, bleed = 1)
        // Composite: every non-ink pixel adjacent to the left region's line is colored.
        for (y in 12 until 68) {
            assertEquals(red, paint[y * w + 12])   // first pixel inside left border
            assertEquals(red, paint[y * w + 48])   // last pixel before divider
        }
    }

    @Test
    fun invalidRegionIsNoOp() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val paint = IntArray(w * h)
        assertTrue(FloodFill.fill(map, 0, red, paint).isEmpty)
        assertTrue(FloodFill.fill(map, 99, red, paint).isEmpty)
        assertTrue(paint.all { it == 0 })
    }

    @Test
    fun controllerTapFillIsUndoable() {
        val (ink, w, h) = TestArt.twoBoxes()
        val c = ColoringCanvasController(w, h, ink, closeRadius = 0)
        c.color = red
        var commits = 0
        c.onCommitted = { commits++ }
        val r = c.tapFill(30, 40)
        assertTrue(!r.isEmpty)
        assertEquals(red, c.paint[40 * w + 30])
        assertEquals(1, commits)
        // Filling again with the same color changes nothing → no new undo step.
        assertTrue(c.tapFill(30, 40).isEmpty)
        assertEquals(1, c.undoStack.size)
        c.undo()
        assertEquals(0, c.paint[40 * w + 30])
    }

    @Test
    fun crayonBrushCoalescesMicroMovesWithoutDroppingCumulativeMotionOrPressureChange() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val paint = IntArray(w * h)
        val brush = CrayonBrush(map, paint).apply { radius = 6f; color = red }
        var captures = 0
        brush.begin(30f, 40f, stayInside = false, beforeWrite = { captures++ })
        assertEquals(1, captures)

        // Micro-moves < 1.25px with same radius skip redundant stamping.
        assertTrue(brush.moveTo(30.4f, 40.2f) { captures++ }.isEmpty)
        assertTrue(brush.moveTo(30.9f, 40.3f) { captures++ }.isEmpty)
        assertEquals(1, captures)

        // Cumulative distance from (30,40) reaches >= 1.25px → stamps cleanly from last stamped point.
        assertTrue(!brush.moveTo(31.5f, 40.3f) { captures++ }.isEmpty)
        assertEquals(2, captures)

        // Micro-move with radius change >= 0.5px stamps immediately (pressure variation in place).
        brush.radius = 7f
        assertTrue(!brush.moveTo(31.6f, 40.3f) { captures++ }.isEmpty)
        assertEquals(3, captures)
        brush.end()
    }
}
