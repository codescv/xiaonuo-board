package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.CanvasSpec

/** Synthetic line-art fixtures for engine tests. */
object TestArt {
    /** Blank w×h ink mask. */
    fun blank(w: Int, h: Int) = BooleanArray(w * h)

    fun rect(ink: BooleanArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, t: Int = 2) {
        for (y in y0 until y1) for (x in x0 until x1) {
            val edge = x < x0 + t || x >= x1 - t || y < y0 + t || y >= y1 - t
            if (edge) ink[y * w + x] = true
        }
    }

    fun vline(ink: BooleanArray, w: Int, x: Int, y0: Int, y1: Int, t: Int = 2) {
        for (y in y0 until y1) for (dx in 0 until t) ink[y * w + x + dx] = true
    }

    fun hline(ink: BooleanArray, w: Int, y: Int, x0: Int, x1: Int, t: Int = 2) {
        for (x in x0 until x1) for (dy in 0 until t) ink[(y + dy) * w + x] = true
    }

    /**
     * 100×80 fixture: outer box with a vertical divider → regions: outside(1), left(2), right(3).
     */
    fun twoBoxes(): Triple<BooleanArray, Int, Int> {
        val w = 100
        val h = 80
        val ink = blank(w, h)
        rect(ink, w, 10, 10, 90, 70)
        vline(ink, w, 49, 10, 70)
        return Triple(ink, w, h)
    }

    val W = CanvasSpec.WIDTH
    val H = CanvasSpec.HEIGHT
}
