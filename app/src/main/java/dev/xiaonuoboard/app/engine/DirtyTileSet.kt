package dev.xiaonuoboard.app.engine

/**
 * Fixed tiling of a `width × height` canvas into `tileSize` squares (edge tiles are clipped).
 * Tile `i` sits at column `i % cols`, row `i / cols`.
 */
class TileGrid(val width: Int, val height: Int, val tileSize: Int) {
    init {
        require(width > 0 && height > 0 && tileSize > 0)
    }

    val cols: Int = (width + tileSize - 1) / tileSize
    val rows: Int = (height + tileSize - 1) / tileSize
    val count: Int get() = cols * rows

    fun left(i: Int): Int = (i % cols) * tileSize
    fun top(i: Int): Int = (i / cols) * tileSize
    fun tileWidth(i: Int): Int = minOf(tileSize, width - left(i))
    fun tileHeight(i: Int): Int = minOf(tileSize, height - top(i))
}

/**
 * Tracks which tiles of a [TileGrid] have pending (not yet uploaded) changes, and the sub-rect of
 * each tile that changed. Marking is O(tiles touched) and allocation-free, so it can run for every
 * brush sample; [drain] hands each dirty tile's changed sub-rect to the uploader exactly once.
 *
 * This is what lets the view re-upload only the few small tile textures a stroke touched instead
 * of the whole 1200×1600 ARGB (7.7 MB) paint bitmap on every frame.
 */
class DirtyTileSet(val grid: TileGrid) {
    private val dirty = BooleanArray(grid.count)
    private val dl = IntArray(grid.count)
    private val dt = IntArray(grid.count)
    private val dr = IntArray(grid.count)
    private val db = IntArray(grid.count)

    /** Number of tiles currently dirty. */
    var dirtyCount: Int = 0
        private set

    val isEmpty: Boolean get() = dirtyCount == 0

    fun isDirty(i: Int): Boolean = dirty[i]

    fun markAll() = mark(0, 0, grid.width, grid.height)

    fun mark(rect: DirtyRect) = mark(rect.left, rect.top, rect.right, rect.bottom)

    /** Marks canvas pixels `[left, right) x [top, bottom)` (clamped to the canvas) as changed. */
    fun mark(left: Int, top: Int, right: Int, bottom: Int) {
        val l = left.coerceIn(0, grid.width)
        val t = top.coerceIn(0, grid.height)
        val r = right.coerceIn(0, grid.width)
        val b = bottom.coerceIn(0, grid.height)
        if (r <= l || b <= t) return
        val ts = grid.tileSize
        val tx0 = l / ts
        val tx1 = (r - 1) / ts
        val ty0 = t / ts
        val ty1 = (b - 1) / ts
        for (ty in ty0..ty1) {
            val y0 = ty * ts
            val ct = maxOf(t, y0)
            val cb = minOf(b, y0 + ts)
            for (tx in tx0..tx1) {
                val i = ty * grid.cols + tx
                val x0 = tx * ts
                val cl = maxOf(l, x0)
                val cr = minOf(r, x0 + ts)
                if (!dirty[i]) {
                    dirty[i] = true
                    dirtyCount++
                    dl[i] = cl; dt[i] = ct; dr[i] = cr; db[i] = cb
                } else {
                    if (cl < dl[i]) dl[i] = cl
                    if (ct < dt[i]) dt[i] = ct
                    if (cr > dr[i]) dr[i] = cr
                    if (cb > db[i]) db[i] = cb
                }
            }
        }
    }

    /**
     * Calls [action] once per dirty tile with the tile index and the changed rect in CANVAS
     * coordinates (`[left, right) x [top, bottom)`, always inside the tile), then clears everything.
     * @return the number of tiles drained.
     */
    inline fun drain(action: (index: Int, left: Int, top: Int, right: Int, bottom: Int) -> Unit): Int {
        if (isEmpty) return 0
        var n = 0
        for (i in 0 until grid.count) {
            if (!isDirty(i)) continue
            action(i, leftOf(i), topOf(i), rightOf(i), bottomOf(i))
            n++
        }
        clear()
        return n
    }

    fun clear() {
        if (dirtyCount == 0) return
        dirty.fill(false)
        dirtyCount = 0
    }

    @PublishedApi internal fun leftOf(i: Int) = dl[i]
    @PublishedApi internal fun topOf(i: Int) = dt[i]
    @PublishedApi internal fun rightOf(i: Int) = dr[i]
    @PublishedApi internal fun bottomOf(i: Int) = db[i]
}
