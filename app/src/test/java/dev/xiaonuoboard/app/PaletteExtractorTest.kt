package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.KaleidoPalette
import dev.xiaonuoboard.app.engine.PaletteExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteExtractorTest {

    private fun image(vararg parts: Pair<Int, Int>): IntArray {
        val out = ArrayList<Int>()
        for ((color, n) in parts) repeat(n) { out += color }
        return out.toIntArray()
    }

    @Test
    fun returnsDominantColorsInFrequencyOrderIgnoringLinesAndPaper() {
        val red = 0xFFE61919.toInt()
        val blue = 0xFF0077B6.toInt()
        val yellow = 0xFFFFD600.toInt()
        val px = image(
            0xFFFFFFFF.toInt() to 50_000, // paper
            0xFF111111.toInt() to 20_000, // line art
            blue to 9_000,
            red to 6_000,
            yellow to 3_000,
        )
        val colors = PaletteExtractor.extract(px)
        assertEquals(listOf(blue, red, yellow), colors)
    }

    @Test
    fun mergesNearDuplicatesAndCapsAtSix() {
        val px = ArrayList<Int>()
        KaleidoPalette.CRAYONS.take(13).forEachIndexed { i, c -> repeat(1000 + i * 10) { px += c.argb } }
        // Near-duplicate of the most frequent color must not become its own entry.
        repeat(5000) { px += 0xFFE81B1B.toInt() }
        val colors = PaletteExtractor.extract(px.toIntArray(), count = 6)
        assertEquals(6, colors.size)
        for (i in colors.indices) for (j in i + 1 until colors.size) {
            assertTrue(PaletteExtractor.distance2(colors[i], colors[j]) >= 48 * 48)
        }
    }

    @Test
    fun transparentPixelsAreIgnored() {
        val px = image(0x00E61919 to 10_000, 0xFF38B000.toInt() to 10)
        assertEquals(listOf(0xFF38B000.toInt()), PaletteExtractor.extract(px))
    }

    @Test
    fun hexRoundTrip() {
        assertEquals("#E61919", PaletteExtractor.toHex(0xFFE61919.toInt()))
        assertEquals(0xFFE61919.toInt(), PaletteExtractor.parseHex("#e61919"))
        assertEquals(null, PaletteExtractor.parseHex("nope"))
    }

    @Test
    fun kaleidoPaletteHasSixteenDistinctCrayons() {
        assertEquals(16, KaleidoPalette.CRAYONS.size)
        assertEquals(16, KaleidoPalette.CRAYONS.map { it.argb }.toSet().size)
    }
}
