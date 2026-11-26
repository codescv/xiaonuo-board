package dev.xiaonuoboard.app.engine

import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppStrings

/**
 * Extracts the dominant "recommended" crayon colors from a reference (colored) image.
 *
 * Pixels are quantized to 4 bits/channel (4096 bins — exactly the Kaleido Plus color depth),
 * near-black line pixels, near-white paper and transparent pixels are ignored, and the most
 * populated bins are returned (averaged to their true mean color) while skipping colors that
 * are perceptually too close to an already chosen one.
 */
object PaletteExtractor {

    fun extract(argb: IntArray, count: Int = 6, stride: Int = 1, minDistance: Int = 48): List<Int> {
        val hist = IntArray(4096)
        val sumR = LongArray(4096)
        val sumG = LongArray(4096)
        val sumB = LongArray(4096)
        var i = 0
        val step = stride.coerceAtLeast(1)
        while (i < argb.size) {
            val c = argb[i]
            i += step
            val a = c ushr 24
            if (a < 128) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < 60) continue                      // line art / near black
            if (min > 235) continue                     // paper / near white
            if (max - min < 12 && max > 200) continue   // light grey paper noise
            val bin = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            hist[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
        }
        val order = (0 until 4096).filter { hist[it] > 0 }.sortedByDescending { hist[it] }
        val chosen = ArrayList<Int>(count)
        val minD2 = minDistance * minDistance
        for (bin in order) {
            val n = hist[bin]
            val color = (0xFF shl 24) or
                ((sumR[bin] / n).toInt() shl 16) or
                ((sumG[bin] / n).toInt() shl 8) or
                (sumB[bin] / n).toInt()
            if (chosen.none { distance2(it, color) < minD2 }) chosen += color
            if (chosen.size == count) break
        }
        return chosen
    }

    fun distance2(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        // Weighted "redmean-ish" distance is overkill here; plain RGB works for flat crayon colors.
        return dr * dr + dg * dg + db * db
    }

    fun toHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

    fun parseHex(hex: String): Int? {
        val s = hex.trim().removePrefix("#")
        if (s.length != 6) return null
        return s.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }
}

/** Kaleido Plus–tuned 16-color high-saturation crayon palette (design spec §3.4). */
object KaleidoPalette {
    data class Crayon(val id: String, val argb: Int) {
        fun name(strings: AppStrings = AppI18n.strings()): String = strings.crayonName(id)
    }

    val CRAYONS: List<Crayon> = listOf(
        Crayon("red", 0xFFE61919.toInt()),
        Crayon("orange", 0xFFFF6F00.toInt()),
        Crayon("yellow", 0xFFFFD600.toInt()),
        Crayon("apple_green", 0xFF38B000.toInt()),
        Crayon("forest_green", 0xFF007F5F.toInt()),
        Crayon("sky_blue", 0xFF00B4D8.toInt()),
        Crayon("ice_blue", 0xFF0077B6.toInt()),
        Crayon("royal_blue", 0xFF2400FF.toInt()),
        Crayon("lavender", 0xFF9D4EDD.toInt()),
        Crayon("magenta", 0xFFFF006E.toInt()),
        Crayon("peppa_pink", 0xFFFF70A6.toInt()),
        Crayon("peach", 0xFFFFB58A.toInt()),
        Crayon("caramel", 0xFFA45A2A.toInt()),
        Crayon("warm_gray", 0xFF8D99AE.toInt()),
        Crayon("black", 0xFF111111.toInt()),
        Crayon("white", 0xFFFFFFFF.toInt()),
    )
}
