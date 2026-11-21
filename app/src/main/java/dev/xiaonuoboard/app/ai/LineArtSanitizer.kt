package dev.xiaonuoboard.app.ai

import dev.xiaonuoboard.app.engine.Morphology
import dev.xiaonuoboard.app.engine.RegionMapEngine

/**
 * On-device cleanup of AI-generated / imported line art into crisp, closed 1-bit ink:
 *  1. luminance + adaptive (local-mean, integral image) binarization blended with a global Otsu
 *     threshold so both light-gray AI strokes and dark scans binarize cleanly,
 *  2. speckle removal (drops ink blobs smaller than [Params.minSpeckleArea]),
 *  3. 1–2 px morphological closing to seal hairline gaps,
 *  4. region-count QC via [RegionMapEngine].
 */
object LineArtSanitizer {

    data class Params(
        val windowRadius: Int = 24,
        /** Pixel is ink when darker than local mean by this fraction. */
        val adaptiveBias: Float = 0.12f,
        val minSpeckleArea: Int = 24,
        val closeRadius: Int = 1,
        /** Pixels lighter than this are always paper (avoids adaptive noise in flat areas). */
        val paperFloor: Int = 225,
    )

    data class Result(
        val width: Int,
        val height: Int,
        val ink: BooleanArray,
        val regionCount: Int,
        val removedSpeckles: Int,
        val otsuThreshold: Int,
    ) {
        /** Passes QC when the image is neither blank nor a solid blob, and has a sane number of regions. */
        val passesQc: Boolean get() = regionCount in 3..400 && inkRatio in 0.005f..0.45f
        val inkRatio: Float get() = ink.count { it }.toFloat() / ink.size
    }

    fun luminance(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val c = argb[i]
        val a = c ushr 24
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val y = (r * 299 + g * 587 + b * 114) / 1000
        // Transparent pixels count as white paper.
        (y * a + 255 * (255 - a)) / 255
    }

    fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (g in gray) hist[g]++
        val total = gray.size.toDouble()
        var sum = 0.0
        for (t in 0..255) sum += t * hist[t].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    fun binarize(gray: IntArray, w: Int, h: Int, p: Params = Params()): Pair<BooleanArray, Int> {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val t = otsu(gray)
        val ink = BooleanArray(w * h)
        val r = p.windowRadius
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r)
            val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val g = gray[y * w + x]
                if (g >= p.paperFloor) continue
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w, x + r + 1)
                val area = (y1 - y0) * (x1 - x0)
                val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] -
                    integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = s.toFloat() / area
                val adaptiveInk = g < mean * (1f - p.adaptiveBias)
                ink[y * w + x] = g <= t || adaptiveInk && g < (t + p.paperFloor) / 2
            }
        }
        return ink to t
    }

    /** Removes 8-connected ink components smaller than [minArea]. Returns number removed. */
    fun removeSpeckles(ink: BooleanArray, w: Int, h: Int, minArea: Int): Int {
        if (minArea <= 1) return 0
        val visited = BooleanArray(ink.size)
        val stack = IntArray(ink.size)
        val component = IntArray(ink.size)
        var removed = 0
        for (start in ink.indices) {
            if (!ink[start] || visited[start]) continue
            var sp = 0
            var n = 0
            stack[sp++] = start
            visited[start] = true
            while (sp > 0) {
                val i = stack[--sp]
                component[n++] = i
                val x = i % w
                val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (ink[j] && !visited[j]) {
                        visited[j] = true
                        stack[sp++] = j
                    }
                }
            }
            if (n < minArea) {
                for (k in 0 until n) ink[component[k]] = false
                removed++
            }
        }
        return removed
    }

    fun sanitize(argb: IntArray, w: Int, h: Int, p: Params = Params()): Result {
        require(argb.size == w * h)
        val gray = luminance(argb)
        val (raw, t) = binarize(gray, w, h, p)
        val removed = removeSpeckles(raw, w, h, p.minSpeckleArea)
        val closed = if (p.closeRadius > 0) Morphology.close(raw, w, h, p.closeRadius) else raw
        val regions = RegionMapEngine.label(closed, w, h).regionCount
        return Result(w, h, closed, regions, removed, t)
    }

    /** Converts ink mask to ARGB line-art pixels (black ink, transparent paper). */
    fun toLineArgb(ink: BooleanArray, transparentPaper: Boolean = true): IntArray {
        val paper = if (transparentPaper) 0x00FFFFFF else 0xFFFFFFFF.toInt()
        return IntArray(ink.size) { if (ink[it]) 0xFF111111.toInt() else paper }
    }
}
