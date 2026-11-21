package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.CrayonBrush
import dev.xiaonuoboard.app.engine.PointerKind
import dev.xiaonuoboard.app.engine.RegionMap
import dev.xiaonuoboard.app.engine.RegionMapEngine
import dev.xiaonuoboard.app.engine.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StayInsideLinesTest {
    private val blue = 0xFF0077B6.toInt()

    @Test
    fun lockedStrokeNeverLeavesRegion() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val paint = IntArray(w * h)
        val brush = CrayonBrush(map, paint).apply { radius = 8f; color = blue }
        brush.begin(30f, 40f, stayInside = true)
        val left = map.regionAt(30, 40)
        assertEquals(left, brush.lockRegionId)
        // Wild swing across the divider, the outer border and outside the canvas.
        brush.moveTo(80f, 40f)
        brush.moveTo(95f, 75f)
        brush.moveTo(2f, 2f)
        brush.moveTo(-50f, 200f)
        brush.end()
        var painted = 0
        for (i in paint.indices) {
            if (paint[i] == 0) continue
            painted++
            val l = map.labels[i]
            assertTrue("leaked into region $l", l == left || l == RegionMap.BOUNDARY)
        }
        assertTrue(painted > 100)
    }

    @Test
    fun unlockedStrokeCrossesLines() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val paint = IntArray(w * h)
        val brush = CrayonBrush(map, paint).apply { radius = 6f; color = blue }
        brush.begin(30f, 40f, stayInside = false)
        brush.moveTo(70f, 40f)
        brush.end()
        assertEquals(blue, paint[40 * w + 70])
        assertEquals(0, brush.lockRegionId)
    }

    @Test
    fun eraserClearsOnlyLockedRegion() {
        val (ink, w, h) = TestArt.twoBoxes()
        val c = ColoringCanvasController(w, h, ink, closeRadius = 0)
        c.color = blue
        c.tapFill(30, 40)
        c.tapFill(70, 40)
        c.tool = Tool.ERASER
        c.stayInsideLines = true
        c.brushSize = BrushSize.THICK
        assertTrue(c.strokeBegin(30f, 40f))
        c.strokeMove(75f, 40f)
        c.strokeEnd()
        assertEquals(0, c.paint[40 * w + 30])
        assertEquals("right region untouched", blue, c.paint[40 * w + 75])
        c.undo()
        assertEquals(blue, c.paint[40 * w + 30])
    }

    @Test
    fun stylusOnlyRejectsFingerForBrushButNotFill() {
        val (ink, w, h) = TestArt.twoBoxes()
        val c = ColoringCanvasController(w, h, ink, closeRadius = 0)
        c.stylusOnly = true
        c.tool = Tool.CRAYON
        assertFalse(c.accepts(PointerKind.FINGER))
        assertTrue(c.accepts(PointerKind.STYLUS))
        assertFalse(c.strokeBegin(30f, 40f, PointerKind.FINGER))
        assertTrue(c.paint.all { it == 0 })
        c.tool = Tool.FILL
        assertTrue(c.accepts(PointerKind.FINGER))
    }

    @Test
    fun strokeCancelRollsBack() {
        val (ink, w, h) = TestArt.twoBoxes()
        val c = ColoringCanvasController(w, h, ink, closeRadius = 0)
        c.tool = Tool.CRAYON
        c.strokeBegin(30f, 40f)
        c.strokeMove(35f, 45f)
        c.strokeCancel()
        assertTrue(c.paint.all { it == 0 })
        assertEquals(0, c.undoStack.size)
    }
}
