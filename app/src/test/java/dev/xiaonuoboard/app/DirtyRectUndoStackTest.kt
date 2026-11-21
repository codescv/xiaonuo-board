package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.CanvasSpec
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.DirtyRectUndoStack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirtyRectUndoStackTest {
    private val w = 200
    private val h = 150

    private fun write(stack: DirtyRectUndoStack, paint: IntArray, rect: DirtyRect, color: Int) {
        stack.beginAction()
        stack.capture(rect)
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) paint[y * w + x] = color
        stack.commitAction()
    }

    @Test
    fun undoRestoresExactPixels() {
        val paint = IntArray(w * h)
        val stack = DirtyRectUndoStack(paint, w, h)
        write(stack, paint, DirtyRect(10, 10, 60, 40), 1)
        val afterFirst = paint.copyOf()
        write(stack, paint, DirtyRect(30, 20, 150, 120), 2)
        assertEquals(2, stack.size)
        val r = stack.undo()
        assertTrue(r != null && !r.isEmpty)
        assertArrayEquals(afterFirst, paint)
        stack.undo()
        assertTrue(paint.all { it == 0 })
        assertNull(stack.undo())
    }

    @Test
    fun capturesTileOnlyOnceSoMultiSegmentStrokesUndoAtomically() {
        val paint = IntArray(w * h)
        val stack = DirtyRectUndoStack(paint, w, h)
        stack.beginAction()
        for (i in 0 until 50) {
            val rect = DirtyRect(i * 2, 50, i * 2 + 10, 60)
            stack.capture(rect)
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) paint[y * w + x] = 7
        }
        assertTrue(stack.commitAction())
        assertEquals(1, stack.size)
        stack.undo()
        assertTrue(paint.all { it == 0 })
    }

    @Test
    fun unchangedActionIsDropped() {
        val paint = IntArray(w * h)
        val stack = DirtyRectUndoStack(paint, w, h)
        stack.beginAction()
        stack.capture(DirtyRect(0, 0, 50, 50))
        assertFalse(stack.commitAction())
        assertEquals(0, stack.size)
    }

    @Test
    fun keepsAtMostTwentySteps() {
        val paint = IntArray(w * h)
        val stack = DirtyRectUndoStack(paint, w, h)
        repeat(30) { i -> write(stack, paint, DirtyRect(i, i, i + 20, i + 20), i + 1) }
        assertEquals(20, stack.size)
    }

    @Test
    fun fullCanvasHistoryStaysUnderFifteenMegabytes() {
        val cw = CanvasSpec.WIDTH
        val ch = CanvasSpec.HEIGHT
        val paint = IntArray(cw * ch)
        val stack = DirtyRectUndoStack(paint, cw, ch)
        val full = DirtyRect(0, 0, cw, ch)
        // 20 worst-case full-canvas actions with noisy (incompressible-ish) content.
        repeat(25) { step ->
            stack.beginAction()
            stack.capture(full)
            for (i in paint.indices) paint[i] = (i * 31 + step * 7919) xor (i ushr 3)
            stack.commitAction()
        }
        assertTrue("undo bytes ${stack.totalBytes}", stack.totalBytes <= 15L * 1024 * 1024)
        assertTrue(stack.size >= 1)
    }

    @Test
    fun flatColorFullCanvasFillIsTiny() {
        val cw = CanvasSpec.WIDTH
        val ch = CanvasSpec.HEIGHT
        val paint = IntArray(cw * ch)
        val stack = DirtyRectUndoStack(paint, cw, ch)
        repeat(20) { step ->
            stack.beginAction()
            stack.capture(DirtyRect(0, 0, cw, ch))
            paint.fill(0xFF000000.toInt() or step + 1)
            stack.commitAction()
        }
        assertEquals(20, stack.size)
        // RLE: each 64×64 tile collapses to a single run.
        assertTrue("bytes ${stack.totalBytes}", stack.totalBytes < 1L * 1024 * 1024)
    }
}
