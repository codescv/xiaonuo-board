package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.ai.LineArtSanitizer
import dev.xiaonuoboard.app.engine.RefAligner
import dev.xiaonuoboard.app.engine.RegionMapEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LineArtSanitizerTest {

    /** Gray anti-aliased AI-style drawing: light-gray background noise, mid-gray lines, a 1px gap. */
    private fun noisyDrawing(w: Int, h: Int, rnd: Random = Random(42)): IntArray {
        val px = IntArray(w * h) {
            val g = 240 + rnd.nextInt(16) // off-white paper with jpeg-ish noise
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        fun dark(x: Int, y: Int, g: Int) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        // Box 20..180 × 20..140 with 3px gray (90) lines and soft edges.
        for (t in 0 until 3) {
            for (x in 20..180) { dark(x, 20 + t, 90); dark(x, 140 - t, 90) }
            for (y in 20..140) { dark(20 + t, y, 90); dark(180 - t, y, 90) }
            for (y in 20..140) if (y != 80) dark(100 + t, y, 90) // divider with a 1px gap at y=80
        }
        // Isolated speckles (dust) that must be removed.
        repeat(40) { dark(rnd.nextInt(w), rnd.nextInt(h), 60) }
        return px
    }

    @Test
    fun binarizesDespecklesAndClosesGaps() {
        val w = 200
        val h = 160
        val r = LineArtSanitizer.sanitize(noisyDrawing(w, h), w, h)
        // Outside + left + right after the 1px divider gap is closed.
        assertEquals(3, r.regionCount)
        assertTrue("speckles removed: ${r.removedSpeckles}", r.removedSpeckles >= 30)
        assertTrue(r.passesQc)
        // Lines survive.
        assertTrue(r.ink[21 * w + 50])
        // Paper stays white.
        assertFalse(r.ink[60 * w + 50])
    }

    @Test
    fun otsuSeparatesBimodalHistogram() {
        val gray = IntArray(1000) { if (it < 300) 30 else 230 }
        val t = LineArtSanitizer.otsu(gray)
        assertTrue("threshold $t", t in 30 until 230)
    }

    @Test
    fun transparentPixelsAreTreatedAsPaper() {
        val lum = LineArtSanitizer.luminance(intArrayOf(0x00000000, 0xFF000000.toInt()))
        assertEquals(255, lum[0])
        assertEquals(0, lum[1])
    }

    @Test
    fun blankImageFailsQc() {
        val w = 50
        val h = 50
        val r = LineArtSanitizer.sanitize(IntArray(w * h) { 0xFFFFFFFF.toInt() }, w, h)
        assertFalse(r.passesQc)
    }

    @Test
    fun refAlignerFlattensEachRegionToOneColorAndKeepsLinesBlack() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.label(ink, w, h)
        val rnd = Random(1)
        // Drifted colorization: left mostly red with noise, right mostly blue, line pixels colored too.
        val ref = IntArray(w * h) { i ->
            val x = i % w
            val noise = rnd.nextInt(100) < 8
            when {
                noise -> 0xFF00FF00.toInt()
                x < 50 -> 0xFFE61919.toInt()
                else -> 0xFF0077B6.toInt()
            }
        }
        val flat = RefAligner.flatten(ref, map, ink)
        val left = map.regionAt(30, 40)
        val right = map.regionAt(70, 40)
        for (i in flat.indices) {
            when (map.labels[i]) {
                0 -> assertEquals(0xFF111111.toInt(), flat[i])
                left -> assertEquals(0xFFE01010.toInt() and 0xFFF0F0F0.toInt(), flat[i] and 0xFFF0F0F0.toInt())
                right -> assertEquals(0xFF0070B0.toInt(), flat[i] and 0xFFF0F0F0.toInt())
            }
        }
    }
}
