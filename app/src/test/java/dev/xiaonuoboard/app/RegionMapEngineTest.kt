package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.CanvasSpec
import dev.xiaonuoboard.app.engine.Morphology
import dev.xiaonuoboard.app.engine.RegionMap
import dev.xiaonuoboard.app.engine.RegionMapEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionMapEngineTest {

    @Test
    fun labelsOutsideLeftAndRightAsSeparateRegions() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        assertEquals(3, map.regionCount)
        val outside = map.regionAt(2, 2)
        val left = map.regionAt(30, 40)
        val right = map.regionAt(70, 40)
        assertEquals(setOf(1, 2, 3), setOf(outside, left, right))
        assertEquals(RegionMap.BOUNDARY, map.regionAt(10, 40)) // on the line
        assertEquals(RegionMap.BOUNDARY, map.regionAt(-1, 0)) // out of bounds
    }

    @Test
    fun regionAreasAndBoundsAreConsistent() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        val inkCount = ink.count { it }
        assertEquals(w * h - inkCount, map.areas.sum())
        val left = map.regionAt(30, 40)
        val b = map.bounds[left]
        assertEquals(12, b.left)
        assertEquals(49, b.right)
        assertEquals(12, b.top)
        assertEquals(68, b.bottom)
        assertEquals((49 - 12) * (68 - 12), map.areas[left])
    }

    @Test
    fun closingSealsOnePixelGap() {
        val (ink, w, h) = TestArt.twoBoxes()
        // Open a 1px gap in the divider.
        for (dx in 0 until 2) ink[40 * w + 49 + dx] = false
        val leaky = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        assertEquals("gap merges left+right without closing", 2, leaky.regionCount)
        val sealed = RegionMapEngine.build(ink, w, h, closeRadius = 1)
        assertEquals("1px closing seals the gap", 3, sealed.regionCount)
        assertNotEquals(sealed.regionAt(30, 40), sealed.regionAt(70, 40))
    }

    @Test
    fun closingSealsTwoPixelGapWithRadiusTwo() {
        val (ink, w, h) = TestArt.twoBoxes()
        for (y in 39..40) for (dx in 0 until 2) ink[y * w + 49 + dx] = false
        assertEquals(2, RegionMapEngine.build(ink, w, h, closeRadius = 0).regionCount)
        assertEquals(3, RegionMapEngine.build(ink, w, h, closeRadius = 2).regionCount)
    }

    @Test
    fun closingNeverRemovesInk() {
        val (ink, w, h) = TestArt.twoBoxes()
        val closed = Morphology.close(ink, w, h, 2)
        for (i in ink.indices) if (ink[i]) assertTrue(closed[i])
    }

    @Test
    fun regionNearSnapsTapsOnLines() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        assertNotEquals(RegionMap.BOUNDARY, map.regionNear(10, 40))
    }

    @Test
    fun spiralAndUShapesLabelCorrectly() {
        // A U-shape forces label merges in the second pass (union-find path).
        val w = 60
        val h = 60
        val ink = TestArt.blank(w, h)
        TestArt.vline(ink, w, 20, 10, 50)
        TestArt.vline(ink, w, 40, 10, 50)
        TestArt.hline(ink, w, 48, 20, 42)
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        // Inside of the U connects to the outside via the open top → only 1 region.
        assertEquals(1, map.regionCount)
    }

    @Test
    fun fullCanvasBuildsWithinBudget() {
        val w = CanvasSpec.WIDTH
        val h = CanvasSpec.HEIGHT
        val ink = TestArt.blank(w, h)
        // 10×10 grid of boxes (lines evenly spaced from edge to edge).
        for (i in 0..10) {
            TestArt.vline(ink, w, i * (w - 3) / 10, 0, h, 3)
            TestArt.hline(ink, w, i * (h - 3) / 10, 0, w, 3)
        }
        val t0 = System.nanoTime()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 1)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(100, map.regionCount)
        assertTrue("region map took $ms ms", ms < 3000)
    }
}
