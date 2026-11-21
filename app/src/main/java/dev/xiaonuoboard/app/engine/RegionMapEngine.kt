package dev.xiaonuoboard.app.engine

/** Logical canvas size: 3:4 portrait, matches the Nova Air C 7.8" panel aspect. */
object CanvasSpec {
    const val WIDTH = 1200
    const val HEIGHT = 1600
}

/** Inclusive-exclusive pixel rectangle `[left, right) x [top, bottom)`. */
data class DirtyRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val area: Long get() = if (isEmpty) 0L else width.toLong() * height.toLong()

    fun union(other: DirtyRect?): DirtyRect {
        if (other == null || other.isEmpty) return this
        if (isEmpty) return other
        return DirtyRect(
            minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom),
        )
    }

    fun clamp(w: Int, h: Int): DirtyRect =
        DirtyRect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))

    fun inset(d: Int): DirtyRect = DirtyRect(left - d, top - d, right + d, bottom + d)

    companion object {
        val EMPTY = DirtyRect(0, 0, 0, 0)
    }
}

/**
 * Result of region labeling.
 *
 * @property labels per-pixel region id; [BOUNDARY] (0) for line-art (ink) pixels, 1..[regionCount] otherwise.
 * @property bounds per-region bounding boxes (index = region id; index 0 unused).
 * @property areas per-region pixel counts (index = region id).
 */
class RegionMap(
    val width: Int,
    val height: Int,
    val labels: IntArray,
    val regionCount: Int,
    val bounds: Array<DirtyRect>,
    val areas: IntArray,
) {
    /** O(1) region lookup; returns [BOUNDARY] for ink or out-of-bounds coordinates. */
    fun regionAt(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) BOUNDARY else labels[y * width + x]

    /**
     * Like [regionAt] but if the tap lands on a line pixel, snaps to the nearest region within [radius]
     * pixels (kids often tap right on the line).
     */
    fun regionNear(x: Int, y: Int, radius: Int = 6): Int {
        val direct = regionAt(x, y)
        if (direct != BOUNDARY) return direct
        for (r in 1..radius) {
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                val id = regionAt(x + dx, y + dy)
                if (id != BOUNDARY) return id
            }
        }
        return BOUNDARY
    }

    companion object {
        const val BOUNDARY = 0
    }
}

/**
 * Builds the region topology from 1-bit line art:
 *  1. morphological closing (dilate → erode) of the ink mask with a small radius to seal 1–2px gaps,
 *  2. scanline union-find connected-component labeling (4-connectivity) of the remaining paper pixels.
 */
object RegionMapEngine {

    fun build(ink: BooleanArray, width: Int, height: Int, closeRadius: Int = 1): RegionMap {
        require(ink.size == width * height) { "ink size ${ink.size} != ${width}x$height" }
        val barrier = if (closeRadius > 0) Morphology.close(ink, width, height, closeRadius) else ink
        return label(barrier, width, height)
    }

    /** Two-pass scanline labeling with union-find; equivalent to flood fill but iteration-only (no recursion). */
    fun label(barrier: BooleanArray, width: Int, height: Int): RegionMap {
        val n = width * height
        val labels = IntArray(n)
        // Provisional label parents. Worst case label count ~ n/2, grow on demand.
        var parent = IntArray(1024)
        var next = 1

        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) {
                if (ra < rb) parent[rb] = ra else parent[ra] = rb
            }
        }

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                if (barrier[i]) continue
                val left = if (x > 0) labels[i - 1] else 0
                val up = if (y > 0) labels[i - width] else 0
                if (left == 0 && up == 0) {
                    if (next >= parent.size) parent = parent.copyOf(parent.size * 2)
                    parent[next] = next
                    labels[i] = next++
                } else if (left != 0 && up != 0) {
                    labels[i] = minOf(left, up)
                    if (left != up) union(left, up)
                } else {
                    labels[i] = if (left != 0) left else up
                }
            }
        }

        // Resolve to compact ids 1..count, in scan order.
        val compact = IntArray(next)
        var count = 0
        for (l in 1 until next) {
            val r = find(l)
            if (compact[r] == 0) compact[r] = ++count
            compact[l] = compact[r]
        }
        val minX = IntArray(count + 1) { Int.MAX_VALUE }
        val minY = IntArray(count + 1) { Int.MAX_VALUE }
        val maxX = IntArray(count + 1) { -1 }
        val maxY = IntArray(count + 1) { -1 }
        val areas = IntArray(count + 1)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val l = labels[i]
                if (l == 0) continue
                val id = compact[l]
                labels[i] = id
                areas[id]++
                if (x < minX[id]) minX[id] = x
                if (x > maxX[id]) maxX[id] = x
                if (y < minY[id]) minY[id] = y
                if (y > maxY[id]) maxY[id] = y
            }
        }
        val bounds = Array(count + 1) { id ->
            if (id == 0) DirtyRect.EMPTY else DirtyRect(minX[id], minY[id], maxX[id] + 1, maxY[id] + 1)
        }
        return RegionMap(width, height, labels, count, bounds, areas)
    }
}

/** Binary morphology on row-major boolean masks using separable square structuring elements. */
object Morphology {

    fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return src.copyOf()
        val tmp = BooleanArray(src.size)
        // Horizontal pass using a running count.
        for (y in 0 until h) {
            val row = y * w
            var count = 0
            for (x in 0..minOf(r, w - 1)) if (src[row + x]) count++
            for (x in 0 until w) {
                tmp[row + x] = count > 0
                val add = x + r + 1
                val rem = x - r
                if (add < w && src[row + add]) count++
                if (rem >= 0 && src[row + rem]) count--
            }
        }
        val out = BooleanArray(src.size)
        for (x in 0 until w) {
            var count = 0
            for (y in 0..minOf(r, h - 1)) if (tmp[y * w + x]) count++
            for (y in 0 until h) {
                out[y * w + x] = count > 0
                val add = y + r + 1
                val rem = y - r
                if (add < h && tmp[add * w + x]) count++
                if (rem >= 0 && tmp[rem * w + x]) count--
            }
        }
        return out
    }

    fun erode(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return src.copyOf()
        val inv = BooleanArray(src.size) { !src[it] }
        val d = dilate(inv, w, h, r)
        return BooleanArray(src.size) { !d[it] }
    }

    /** Closing seals gaps narrower than ~2r+1 px between ink strokes. */
    fun close(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        // Erosion treats out-of-image as paper so strokes touching the border are not shrunk away;
        // combine with the original ink so closing never removes real line pixels.
        val closed = erode(dilate(src, w, h, r), w, h, r)
        for (i in closed.indices) if (src[i]) closed[i] = true
        return closed
    }

    fun open(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray =
        dilate(erode(src, w, h, r), w, h, r)
}
