package dev.xiaonuoboard.app.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.DirtyTileSet
import dev.xiaonuoboard.app.engine.TileGrid

/**
 * GPU-friendly display copy of the controller's paint buffer, split into small tile bitmaps.
 *
 * With hardware acceleration, HWUI re-uploads a bitmap's ENTIRE texture whenever its pixels change.
 * A single 1200×1600 ARGB_8888 paint bitmap therefore cost a 7.7 MB texture upload on every frame
 * of a stroke (plus a JNI `setPixels` per brush sample). Splitting it into [tileSize]² tiles means
 * only the handful of tiles a stroke touched are copied and re-uploaded, and copies are deferred to
 * draw time so each changed pixel is copied at most once per frame no matter how many samples hit it.
 *
 * Not thread-safe: mark / draw on the UI thread (where the controller mutates [source]).
 */
class TiledPaintLayer(
    private val source: IntArray,
    val width: Int,
    val height: Int,
    tileSize: Int = DEFAULT_TILE_SIZE,
) {
    private val grid = TileGrid(width, height, tileSize)
    private val dirty = DirtyTileSet(grid)
    private val tiles: Array<Bitmap> = Array(grid.count) {
        Bitmap.createBitmap(grid.tileWidth(it), grid.tileHeight(it), Bitmap.Config.ARGB_8888)
    }
    private val tileX = FloatArray(grid.count) { grid.left(it).toFloat() }
    private val tileY = FloatArray(grid.count) { grid.top(it).toFloat() }

    var isReleased: Boolean = false
        private set

    init {
        require(source.size == width * height)
        dirty.markAll()
    }

    /** Records that [rect] of [source] changed; the copy happens lazily in [flush] / [draw]. */
    fun markDirty(rect: DirtyRect) = dirty.mark(rect)

    fun markAllDirty() = dirty.markAll()

    /** Copies pending changes from [source] into the tile bitmaps. Returns the number of tiles updated. */
    fun flush(): Int {
        if (isReleased) return 0
        return dirty.drain { i, l, t, r, b ->
            tiles[i].setPixels(source, t * width + l, width, l - grid.left(i), t - grid.top(i), r - l, b - t)
        }
    }

    /** Draws all tiles at canvas coordinates (the caller has already applied the view matrix). */
    fun draw(canvas: Canvas, paint: Paint?) {
        if (isReleased) return
        flush()
        for (i in tiles.indices) canvas.drawBitmap(tiles[i], tileX[i], tileY[i], paint)
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        dirty.clear()
        for (t in tiles) if (!t.isRecycled) t.recycle()
    }

    companion object {
        /** 1200×1600 → 6×8 = 48 tiles of 160 KB: few draw calls, small per-frame uploads. */
        const val DEFAULT_TILE_SIZE = 200
    }
}
