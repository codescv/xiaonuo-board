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
    private var pending: HashMap<Int, Tile>? = null
    private var pendingRect: DirtyRect = DirtyRect.EMPTY

    var totalBytes: Long = 0
        private set

    val size: Int get() = entries.size
    val canUndo: Boolean get() = entries.isNotEmpty()
    val inAction: Boolean get() = pending != null

    fun beginAction() {
        pending = HashMap()
        pendingRect = DirtyRect.EMPTY
    }

    /** Snapshots every not-yet-captured tile that intersects [rect]. Must be called BEFORE writing. */
    fun capture(rect: DirtyRect) {
        val p = pending ?: return
        val r = rect.clamp(width, height)
        if (r.isEmpty) return
        pendingRect = pendingRect.union(r)
        val tx0 = r.left / tileSize
        val tx1 = (r.right - 1) / tileSize
        val ty0 = r.top / tileSize
        val ty1 = (r.bottom - 1) / tileSize
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val key = ty * tilesX + tx
            if (!p.containsKey(key)) p[key] = Tile(tx, ty, encodeTile(tx, ty))
        }
    }

    /** Finishes the action. Returns false (and discards it) if the action touched nothing or changed nothing. */
    fun commitAction(): Boolean {
        val p = pending ?: return false
        pending = null
        if (p.isEmpty()) return false
        // Drop tiles whose content did not change (e.g. filling a region with its current color).
        val changed = p.values.filter { !tileUnchanged(it) }
        if (changed.isEmpty()) return false
        val entry = Entry(changed, pendingRect)
        entries.addLast(entry)
        totalBytes += entry.bytes
        trim()
        return true
    }

    fun cancelAction() {
        pending = null
    }

    /** Restores the most recent action. Returns the dirty rect to redraw, or null when nothing to undo. */
    fun undo(): DirtyRect? {
        if (pending != null) commitAction()
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
        pending = null
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
        val out = IntArray(r.width * r.height * 2)
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
