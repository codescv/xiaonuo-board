package dev.xiaonuoboard.app.engine

/**
 * Tile-based dirty-rect undo stack for the paint layer.
 *
 * Usage per user action:
 * ```
 * undo.beginAction()
 * undo.capture(rect)   // before every write; tiles are copied only the first time they are touched
 * ... mutate paint ...
 * undo.commitAction()
 * ```
 * Captured tile pre-images are run-length encoded (paint layers are flat crayon colors, so RLE is
 * extremely effective) and the stack is bounded by both [maxSteps] and [maxBytes] (default 20 steps /
 * 15 MB to fit the Nova Air C's 3 GB RAM alongside the 1200×1600 ARGB layers).
 */
class DirtyRectUndoStack(
    private val paint: IntArray,
    private val width: Int,
    private val height: Int,
    val maxSteps: Int = 20,
    val maxBytes: Long = 15L * 1024 * 1024,
    private val tileSize: Int = 64,
) {
    private class Tile(val tx: Int, val ty: Int, val rle: IntArray) {
        val bytes: Long get() = rle.size * 4L + 32
    }

    private class Entry(val tiles: List<Tile>, val rect: DirtyRect) {
        val bytes: Long = tiles.sumOf { it.bytes } + 48
    }

    private val tilesX = (width + tileSize - 1) / tileSize
    private val tilesY = (height + tileSize - 1) / tileSize
    private val entries = ArrayDeque<Entry>()

    // Pending action state. capture() runs for every brush sample on the UI thread, so it is kept
    // allocation-free: an index-addressed tile table (no boxed HashMap keys), the touched-tile list,
    // and the pending bounds as plain ints instead of a fresh DirtyRect per union.
    private var actionOpen = false
    private val pendingTiles = arrayOfNulls<Tile>(tilesX * tilesY)
    private val touched = ArrayList<Tile>()
    private var pendL = 0
    private var pendT = 0
    private var pendR = 0
    private var pendB = 0
    /** Worst-case RLE scratch buffer (one 2-int run per pixel), reused for every tile encode. */
    private val rleScratch = IntArray(tileSize * tileSize * 2)

    var totalBytes: Long = 0
        private set

    val size: Int get() = entries.size
    val canUndo: Boolean get() = entries.isNotEmpty()
    val inAction: Boolean get() = actionOpen

    /** Number of tiles snapshotted so far in the open action (diagnostics / tests). */
    val pendingTileCount: Int get() = touched.size

    /** Tiles held by the most recent undo step, 0 when empty (diagnostics). */
    val lastStepTileCount: Int get() = entries.lastOrNull()?.tiles?.size ?: 0

    fun beginAction() {
        resetPending()
        actionOpen = true
    }

    /** Snapshots every not-yet-captured tile that intersects [rect]. Must be called BEFORE writing. */
    fun capture(rect: DirtyRect) = capture(rect.left, rect.top, rect.right, rect.bottom)

    /** Allocation-free variant of [capture] for hot paths (`[left, right) x [top, bottom)`). */
    fun capture(left: Int, top: Int, right: Int, bottom: Int) {
        if (!actionOpen) return
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(0, width)
        val b = bottom.coerceIn(0, height)
        if (r <= l || b <= t) return
        if (touched.isEmpty() && pendR <= pendL) {
            pendL = l; pendT = t; pendR = r; pendB = b
        } else {
            if (l < pendL) pendL = l
            if (t < pendT) pendT = t
            if (r > pendR) pendR = r
            if (b > pendB) pendB = b
        }
        val tx0 = l / tileSize
        val tx1 = (r - 1) / tileSize
        val ty0 = t / tileSize
        val ty1 = (b - 1) / tileSize
        for (ty in ty0..ty1) {
            val rowKey = ty * tilesX
            for (tx in tx0..tx1) {
                val key = rowKey + tx
                if (pendingTiles[key] == null) {
                    val tile = Tile(tx, ty, encodeTile(tx, ty))
                    pendingTiles[key] = tile
                    touched.add(tile)
                }
            }
        }
    }

    /** Finishes the action. Returns false (and discards it) if the action touched nothing or changed nothing. */
    fun commitAction(): Boolean {
        if (!actionOpen) return false
        val captured = ArrayList(touched)
        val rect = if (pendR > pendL && pendB > pendT) DirtyRect(pendL, pendT, pendR, pendB) else DirtyRect.EMPTY
        resetPending()
        if (captured.isEmpty()) return false
        // Drop tiles whose content did not change (e.g. filling a region with its current color).
        val changed = captured.filter { !tileUnchanged(it) }
        if (changed.isEmpty()) return false
        val entry = Entry(changed, rect)
        entries.addLast(entry)
        totalBytes += entry.bytes
        trim()
        return true
    }

    fun cancelAction() {
        resetPending()
    }

    private fun resetPending() {
        for (t in touched) pendingTiles[t.ty * tilesX + t.tx] = null
        touched.clear()
        actionOpen = false
        pendL = 0; pendT = 0; pendR = 0; pendB = 0
    }

    /** Restores the most recent action. Returns the dirty rect to redraw, or null when nothing to undo. */
    fun undo(): DirtyRect? {
        if (actionOpen) commitAction()
        val e = entries.removeLastOrNull() ?: return null
        totalBytes -= e.bytes
        var rect = DirtyRect.EMPTY
        for (t in e.tiles) {
            decodeTile(t)
            rect = rect.union(tileRect(t.tx, t.ty))
        }
        return rect
    }

    fun clear() {
        entries.clear()
        resetPending()
        totalBytes = 0
    }

    private fun trim() {
        while (entries.size > maxSteps || (totalBytes > maxBytes && entries.size > 1)) {
            val e = entries.removeFirst()
            totalBytes -= e.bytes
        }
    }

    private fun tileRect(tx: Int, ty: Int): DirtyRect {
        val l = tx * tileSize
        val t = ty * tileSize
        return DirtyRect(l, t, minOf(l + tileSize, width), minOf(t + tileSize, height))
    }

    /** RLE as pairs (runLength, argb). */
    private fun encodeTile(tx: Int, ty: Int): IntArray {
        val r = tileRect(tx, ty)
        val out = rleScratch
        var n = 0
        var runColor = 0
        var runLen = 0
        for (y in r.top until r.bottom) {
            val row = y * width
            for (x in r.left until r.right) {
                val c = paint[row + x]
                if (runLen > 0 && c == runColor) {
                    runLen++
                } else {
                    if (runLen > 0) {
                        out[n++] = runLen; out[n++] = runColor
                    }
                    runColor = c
                    runLen = 1
                }
            }
        }
        if (runLen > 0) {
            out[n++] = runLen; out[n++] = runColor
        }
        return out.copyOf(n)
    }

    private fun decodeTile(t: Tile) {
        val r = tileRect(t.tx, t.ty)
        var k = 0
        var remaining = 0
        var color = 0
        for (y in r.top until r.bottom) {
            val row = y * width
            for (x in r.left until r.right) {
                if (remaining == 0) {
                    remaining = t.rle[k++]
                    color = t.rle[k++]
                }
                paint[row + x] = color
                remaining--
            }
        }
    }

    private fun tileUnchanged(t: Tile): Boolean {
        val r = tileRect(t.tx, t.ty)
        var k = 0
        var remaining = 0
        var color = 0
        for (y in r.top until r.bottom) {
            val row = y * width
            for (x in r.left until r.right) {
                if (remaining == 0) {
                    remaining = t.rle[k++]
                    color = t.rle[k++]
                }
                if (paint[row + x] != color) return false
                remaining--
            }
        }
        return true
    }
}
