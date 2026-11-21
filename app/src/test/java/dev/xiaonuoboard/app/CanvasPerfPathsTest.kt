package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.DirtyRectUndoStack
import dev.xiaonuoboard.app.engine.FloodFill
import dev.xiaonuoboard.app.engine.RegionMap
import dev.xiaonuoboard.app.engine.RegionMapEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Coverage for the latency optimizations in the fill / undo / progress paths. */
class CanvasPerfPathsTest {
    private val red = 0xFFE61919.toInt()
    private val blue = 0xFF1E4FD8.toInt()

    /** The original (pre-optimization) fill: bbox scan, write every matching pixel. */
    private fun referenceFill(map: RegionMap, id: Int, color: Int, paint: IntArray, bleed: Int) {
        val w = map.width
        val h = map.height
        val rect = map.bounds[id].inset(bleed).clamp(w, h)
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val l = map.labels[y * w + x]
            var hit = l == id
            if (!hit && l == RegionMap.BOUNDARY && bleed > 0) {
                loop@ for (yy in maxOf(0, y - bleed)..minOf(h - 1, y + bleed)) for (xx in maxOf(0, x - bleed)..minOf(w - 1, x + bleed)) {
                    if (map.labels[yy * w + xx] == id) { hit = true; break@loop }
                }
            }
            if (hit) paint[y * w + x] = color
        }
    }

    private fun randomArt(w: Int, h: Int, seed: Int): BooleanArray {
        val rnd = Random(seed)
        val ink = TestArt.blank(w, h)
        repeat(8) {
            val x0 = rnd.nextInt(0, w - 20)
            val y0 = rnd.nextInt(0, h - 20)
            TestArt.rect(ink, w, x0, y0, minOf(w, x0 + rnd.nextInt(15, 90)), minOf(h, y0 + rnd.nextInt(15, 90)), t = rnd.nextInt(1, 3))
        }
        repeat(4) { TestArt.vline(ink, w, rnd.nextInt(0, w - 3), rnd.nextInt(0, h / 2), h - 1) }
        return ink
    }

    @Test
    fun optimizedFillMatchesReferenceOnRandomArtAndBleeds() {
        val w = 160
        val h = 120
        for (seed in 1..6) for (bleed in 0..2) {
            val ink = randomArt(w, h, seed)
            val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
            val rnd = Random(seed * 31 + bleed)
            val fast = IntArray(w * h) { if (rnd.nextInt(5) == 0) blue else 0 }
            val ref = fast.copyOf()
            for (id in 1..map.regionCount) {
                val color = if (id % 2 == 0) red else blue
                FloodFill.fill(map, id, color, fast, bleed)
                referenceFill(map, id, color, ref, bleed)
                assertArrayEquals("seed=$seed bleed=$bleed id=$id", ref, fast)
            }
        }
    }

    @Test
    fun fillReturnsTightChangedRectAndCapturesBeforeEveryWrite() {
        val w = 120
        val h = 100
        // L-shaped region: big bounding box, small area.
        val ink = TestArt.blank(w, h)
        TestArt.rect(ink, w, 0, 0, w, h)
        for (y in 10 until h) for (x in 10 until w) ink[y * w + x] = true
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val id = map.regionAt(5, 5)
        assertTrue(id > 0)
        val paint = IntArray(w * h)
        // Pre-paint part of the region with the target color: those pixels must not be "changed".
        for (y in 2 until 10) for (x in 2 until 10) paint[y * w + x] = red
        val before = paint.copyOf()
        val captured = ArrayList<DirtyRect>()
        val rect = FloodFill.fill(map, id, red, paint, bleed = 1) { r ->
            // Must be called BEFORE the pixels in r are written.
            for (y in r.top until r.bottom) for (x in r.left until r.right) {
                assertEquals("pixel $x,$y written before capture", before[y * w + x], paint[y * w + x])
            }
            captured += r
        }
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (i in paint.indices) if (paint[i] != before[i]) {
            val x = i % w; val y = i / w
            assertTrue("changed pixel $x,$y not captured", captured.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom })
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        assertEquals(DirtyRect(minX, minY, maxX + 1, maxY + 1), rect)
        // Re-filling with the same color touches nothing and asks the undo stack for nothing.
        captured.clear()
        assertTrue(FloodFill.fill(map, id, red, paint, bleed = 1) { captured += it }.isEmpty)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun thinRegionWithHugeBoundingBoxSnapshotsOnlyTouchedUndoTiles() {
        val w = 640
        val h = 640
        // A 4-px-wide diagonal staircase region spanning the whole canvas inside solid ink.
        val ink = BooleanArray(w * h) { true }
        for (y in 0 until h) {
            val x = y * (w - 4) / h
            for (dx in 0 until 4) ink[y * w + x + dx] = false
        }
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val id = map.regionAt(1, 0)
        val bb = map.bounds[id]
        assertTrue("bbox $bb should span ~the whole canvas", bb.width >= w - 2 && bb.height == h)
        val paint = IntArray(w * h)
        val undo = DirtyRectUndoStack(paint, w, h)
        undo.beginAction()
        FloodFill.fill(map, id, red, paint, bleed = 1) { undo.capture(it) }
        val tiles = undo.pendingTileCount
        // The whole-bbox capture used to snapshot all 10×10 = 100 tiles; a diagonal touches ≤3 per tile row.
        assertTrue("captured $tiles tiles", tiles in 10..30)
        assertTrue(undo.commitAction())
        undo.undo()
        assertTrue(paint.all { it == 0 })
    }

    @Test
    fun undoTableIsResetBetweenActions() {
        val w = 200
        val h = 150
        val paint = IntArray(w * h)
        val undo = DirtyRectUndoStack(paint, w, h)
        undo.beginAction()
        undo.capture(10, 10, 20, 20)
        for (y in 10 until 20) for (x in 10 until 20) paint[y * w + x] = red
        assertEquals(1, undo.pendingTileCount)
        assertTrue(undo.commitAction())
        assertFalse(undo.inAction)
        val afterFirst = paint.copyOf()
        // Same tile, new action: it must be snapshotted again (post-first-action pre-image).
        undo.beginAction()
        undo.capture(DirtyRect(12, 12, 30, 30))
        assertEquals(1, undo.pendingTileCount)
        for (y in 12 until 30) for (x in 12 until 30) paint[y * w + x] = blue
        assertTrue(undo.commitAction())
        undo.undo()
        assertArrayEquals(afterFirst, paint)
        undo.undo()
        assertTrue(paint.all { it == 0 })
    }

    @Test
    fun clippedEdgeTilesOfNonMultipleCanvasCaptureAndUndoExactly() {
        val w = 200 // 3×64 + 8
        val h = 150 // 2×64 + 22
        val rnd = Random(7)
        val paint = IntArray(w * h) { rnd.nextInt() }
        val original = paint.copyOf()
        val undo = DirtyRectUndoStack(paint, w, h)
        undo.beginAction()
        // Bottom-right corner tile is only 8×22 px; also overshoot the canvas to exercise clamping.
        undo.capture(192, 128, 260, 400)
        assertEquals(1, undo.pendingTileCount)
        for (y in 128 until 150) for (x in 192 until 200) paint[y * w + x] = red
        // Right-edge column tiles (8 px wide) and bottom-row tiles (22 px tall) in the same action.
        undo.capture(DirtyRect(195, 0, 200, 130))
        undo.capture(DirtyRect(0, 140, 70, 150))
        for (y in 0 until 130) for (x in 195 until 200) paint[y * w + x] = blue
        for (y in 140 until 150) for (x in 0 until 70) paint[y * w + x] = blue
        assertEquals(1 + 2 + 2, undo.pendingTileCount) // corner + 2 right-column + 2 bottom-row tiles
        assertTrue(undo.commitAction())
        val r = undo.undo()!!
        assertArrayEquals(original, paint)
        assertTrue(r.right == w && r.bottom == h)
    }

    @Test
    fun fillTouchingCanvasEdgesBleedsAndUndoesExactly() {
        val w = 70
        val h = 50
        // Lines touch all four borders; regions reach x=0, x=w-1, y=0, y=h-1.
        val ink = TestArt.blank(w, h)
        TestArt.vline(ink, w, 30, 0, h)
        TestArt.hline(ink, w, 20, 0, w)
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        for (id in 1..map.regionCount) {
            val paint = IntArray(w * h)
            val ref = IntArray(w * h)
            val undo = DirtyRectUndoStack(paint, w, h)
            undo.beginAction()
            FloodFill.fill(map, id, red, paint, bleed = 1) { undo.capture(it) }
            referenceFill(map, id, red, ref, bleed = 1)
            assertArrayEquals("region $id", ref, paint)
            assertTrue(undo.commitAction())
            undo.undo()
            assertTrue(paint.all { it == 0 })
        }
    }

    @Test
    fun cancelledOrClearedActionsLeaveNoStaleTiles() {
        val w = 128
        val h = 128
        val paint = IntArray(w * h)
        val undo = DirtyRectUndoStack(paint, w, h)
        undo.beginAction()
        undo.capture(0, 0, 128, 128)
        assertEquals(4, undo.pendingTileCount)
        undo.cancelAction()
        assertFalse(undo.inAction)
        assertEquals(0, undo.pendingTileCount)
        // Captures outside an action are ignored.
        undo.capture(0, 0, 10, 10)
        assertEquals(0, undo.pendingTileCount)
        assertFalse(undo.commitAction())

        undo.beginAction()
        undo.capture(0, 0, 10, 10)
        undo.clear()
        assertEquals(0, undo.pendingTileCount)
        undo.beginAction()
        paint[5] = red // write after a capture of a fresh action
        assertFalse("no tile captured → nothing to commit", undo.commitAction())
        assertEquals(0, undo.size)

        undo.beginAction()
        undo.capture(-50, -50, 5, 5) // clamped
        paint[0] = blue
        assertTrue(undo.commitAction())
        undo.undo()
        assertEquals(red, paint[5])
        assertEquals(0, paint[0])
    }

    @Test
    fun progressOnSnapshotMatchesLiveProgressAndIgnoresLaterEdits() {
        val (ink, w, h) = TestArt.twoBoxes()
        val c = ColoringCanvasController(w, h, ink, closeRadius = 0)
        assertEquals(0f, c.progress(), 0f)
        c.color = red
        c.tapFill(30, 40)
        val snap = c.paint.copyOf()
        val live = c.progress()
        assertEquals(live, c.progress(snap), 0f)
        assertTrue(live > 0f && live < 1f)
        c.tapFill(70, 40)
        c.tapFill(5, 5)
        assertEquals(1f, c.progress(), 0f)
        // The snapshot is independent of later edits (safe to scan on a background thread).
        assertEquals(live, c.progress(snap), 0f)
    }
}
