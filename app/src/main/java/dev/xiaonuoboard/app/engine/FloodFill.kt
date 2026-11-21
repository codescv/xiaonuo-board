package dev.xiaonuoboard.app.engine

/**
 * Tap-to-fill ("油漆桶") over a precomputed [RegionMap].
 *
 * Lookup is O(1) via [RegionMap.regionAt]; the write is bounded to the region's bounding box.
 * Fill also bleeds [bleed] px into adjacent line (boundary) pixels so that, once the black
 * line-art layer is composited on top, there is no white fringe between color and line.
 */
object FloodFill {

    /**
     * Fills region [regionId] of [paint] (ARGB, row-major, same size as [map]) with [color].
     *
     * Only pixels whose value actually changes are written: each row is scanned once into a scratch
     * list, then [beforeWrite] is invoked with that row's changed span *before* the row is mutated.
     * The undo stack therefore snapshots only tiles that really change (re-filling a region, or a
     * huge background region whose bounding box spans the whole page, no longer captures and RLE-
     * encodes hundreds of untouched tiles on the UI thread).
     *
     * @param beforeWrite invoked (possibly many times, once per changed row) with the rect about to
     *   be mutated (used by the undo stack).
     * @return the tight bounding rect of changed pixels, or [DirtyRect.EMPTY] if nothing changed.
     */
    fun fill(
        map: RegionMap,
        regionId: Int,
        color: Int,
        paint: IntArray,
        bleed: Int = 1,
        beforeWrite: ((DirtyRect) -> Unit)? = null,
    ): DirtyRect {
        if (regionId <= 0 || regionId > map.regionCount) return DirtyRect.EMPTY
        val w = map.width
        val h = map.height
        val labels = map.labels
        val rect = map.bounds[regionId].inset(bleed).clamp(w, h)
        if (rect.isEmpty) return DirtyRect.EMPTY
        val xs = IntArray(rect.width)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in rect.top until rect.bottom) {
            val row = y * w
            var n = 0
            for (x in rect.left until rect.right) {
                val i = row + x
                if (paint[i] == color) continue
                val l = labels[i]
                if (l == regionId ||
                    (l == RegionMap.BOUNDARY && bleed > 0 && touchesRegion(labels, w, h, x, y, regionId, bleed))
                ) {
                    xs[n++] = x
                }
            }
            if (n == 0) continue
            val first = xs[0]
            val last = xs[n - 1]
            beforeWrite?.invoke(DirtyRect(first, y, last + 1, y + 1))
            for (k in 0 until n) paint[row + xs[k]] = color
            if (first < minX) minX = first
            if (last > maxX) maxX = last
            if (y < minY) minY = y
            maxY = y
        }
        return if (maxX < 0) DirtyRect.EMPTY else DirtyRect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun touchesRegion(labels: IntArray, w: Int, h: Int, x: Int, y: Int, id: Int, r: Int): Boolean {
        if (r == 1) {
            // Fast path for the default 1px bleed: unrolled 8-neighbourhood, no nested loops.
            val i = y * w + x
            val hasL = x > 0
            val hasR = x < w - 1
            if (hasL && labels[i - 1] == id) return true
            if (hasR && labels[i + 1] == id) return true
            if (y > 0) {
                val u = i - w
                if (labels[u] == id || (hasL && labels[u - 1] == id) || (hasR && labels[u + 1] == id)) return true
            }
            if (y < h - 1) {
                val d = i + w
                if (labels[d] == id || (hasL && labels[d - 1] == id) || (hasR && labels[d + 1] == id)) return true
            }
            return false
        }
        val y0 = maxOf(0, y - r)
        val y1 = minOf(h - 1, y + r)
        val x0 = maxOf(0, x - r)
        val x1 = minOf(w - 1, x + r)
        for (yy in y0..y1) {
            val row = yy * w
            for (xx in x0..x1) if (labels[row + xx] == id) return true
        }
        return false
    }
}

/**
 * Freehand crayon / eraser brush with optional "不涂出线" (stay-inside-lines) region lock.
 *
 * When [lockRegionId] > 0 every stamped pixel must belong to that region (or be a boundary pixel,
 * which is always hidden underneath the black line-art layer), so color can never leak into
 * neighbouring regions no matter how wildly the stroke swings.
 */
class CrayonBrush(
    private val map: RegionMap,
    private val paint: IntArray,
) {
    var radius: Float = 8f
    var color: Int = 0xFFE61919.toInt()
    /** Region the stroke is locked to, or 0/negative for no clipping. */
    var lockRegionId: Int = 0

    private var lastX = Float.NaN
    private var lastY = Float.NaN

    /** Starts a stroke; when [stayInside] is set, locks to the region under the pen-down point. */
    fun begin(x: Float, y: Float, stayInside: Boolean, beforeWrite: ((DirtyRect) -> Unit)? = null): DirtyRect {
        lockRegionId = if (stayInside) map.regionNear(x.toInt(), y.toInt(), radius.toInt().coerceAtLeast(3)) else 0
        if (stayInside && lockRegionId == RegionMap.BOUNDARY) {
            // Pen came down on a line with no region nearby: lock to an impossible id → paints nothing
            // but boundary pixels, which are invisible under the line layer.
            lockRegionId = Int.MAX_VALUE
        }
        lastX = x
        lastY = y
        return stampSegment(x, y, x, y, beforeWrite)
    }

    fun moveTo(x: Float, y: Float, beforeWrite: ((DirtyRect) -> Unit)? = null): DirtyRect {
        if (lastX.isNaN()) return begin(x, y, lockRegionId != 0, beforeWrite)
        val r = stampSegment(lastX, lastY, x, y, beforeWrite)
        lastX = x
        lastY = y
        return r
    }

    fun end() {
        lastX = Float.NaN
        lastY = Float.NaN
    }

    /** Stamps a capsule (thick line with round caps) from (x0,y0) to (x1,y1). */
    fun stampSegment(x0: Float, y0: Float, x1: Float, y1: Float, beforeWrite: ((DirtyRect) -> Unit)? = null): DirtyRect {
        val w = map.width
        val h = map.height
        val r = radius
        val rect = DirtyRect(
            kotlin.math.floor(minOf(x0, x1) - r).toInt(),
            kotlin.math.floor(minOf(y0, y1) - r).toInt(),
            kotlin.math.ceil(maxOf(x0, x1) + r).toInt() + 1,
            kotlin.math.ceil(maxOf(y0, y1) + r).toInt() + 1,
        ).clamp(w, h)
        if (rect.isEmpty) return DirtyRect.EMPTY
        beforeWrite?.invoke(rect)
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val r2 = r * r
        val labels = map.labels
        val lock = lockRegionId
        for (py in rect.top until rect.bottom) {
            val row = py * w
            val cy = py + 0.5f
            for (px in rect.left until rect.right) {
                val cx = px + 0.5f
                // Distance from pixel center to segment.
                val t = if (len2 == 0f) 0f else (((cx - x0) * dx + (cy - y0) * dy) / len2).coerceIn(0f, 1f)
                val qx = x0 + t * dx - cx
                val qy = y0 + t * dy - cy
                if (qx * qx + qy * qy > r2) continue
                val i = row + px
                if (lock > 0) {
                    val l = labels[i]
                    if (l != lock && l != RegionMap.BOUNDARY) continue
                }
                paint[i] = color
            }
        }
        return rect
    }

    companion object {
        /** 细 / 中 / 粗 brush radii in logical canvas px (1200×1600). */
        const val THIN = 5f
        const val MEDIUM = 12f
        const val THICK = 24f

        /** Transparent: the eraser writes this to reveal white paper. */
        const val ERASE_COLOR = 0x00000000
    }
}
