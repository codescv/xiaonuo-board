package dev.xiaonuoboard.app.engine

enum class Tool { FILL, CRAYON, ERASER }

enum class BrushSize(val label: String, val radius: Float) {
    THIN("细", CrayonBrush.THIN),
    MEDIUM("中", CrayonBrush.MEDIUM),
    THICK("粗", CrayonBrush.THICK),
}

/** Pointer classification independent of android.view.MotionEvent so it is JVM-testable. */
enum class PointerKind { STYLUS, FINGER, ERASER_TIP, MOUSE, UNKNOWN }

/**
 * Platform-independent coloring state machine.
 *
 * Layers (bottom → top), composited by the Android view:
 *  1. white paper
 *  2. optional ~20% translucent reference "ghost" underlay
 *  3. [paint] — ARGB_8888 crayon layer (this class owns its pixels)
 *  4. 1-bit black line art — always on top, so lines are NEVER covered
 *
 * All mutating calls report a [DirtyRect] through [onDirty] so the view can do a partial bitmap
 * upload + partial E-Ink refresh.
 */
class ColoringCanvasController(
    val width: Int,
    val height: Int,
    val ink: BooleanArray,
    initialPaint: IntArray? = null,
    closeRadius: Int = 1,
) {
    val regionMap: RegionMap = RegionMapEngine.build(ink, width, height, closeRadius)
    val paint: IntArray = initialPaint?.copyOf() ?: IntArray(width * height)
    val undoStack = DirtyRectUndoStack(paint, width, height)
    private val brush = CrayonBrush(regionMap, paint)

    var tool: Tool = Tool.CRAYON
    var brushSize: BrushSize = BrushSize.MEDIUM
    var color: Int = KaleidoPalette.CRAYONS.first().argb
    /** 「不涂出线」: clip crayon/eraser strokes to the region under pen-down. */
    var stayInsideLines: Boolean = true
    /** 「仅笔作画(防误触)」: ignore finger/palm input for freehand tools. */
    var stylusOnly: Boolean = false

    var onDirty: ((DirtyRect) -> Unit)? = null
    /** Fired after every committed user action (fill, stroke, undo, reset) → auto-save hook. */
    var onCommitted: (() -> Unit)? = null

    private var strokeActive = false
    private var strokeRect: DirtyRect = DirtyRect.EMPTY
    var actionCount: Int = 0
        private set

    init {
        require(ink.size == width * height)
        require(paint.size == width * height)
    }

    /**
     * Whether a pointer of [kind] should be accepted by the current tool. In 仅笔 mode anything that
     * is not positively identified as a pen (finger, palm, unknown tool type) is rejected for drawing.
     */
    fun accepts(kind: PointerKind): Boolean = when (tool) {
        // The paint bucket is a deliberate single tap → both pen and finger are allowed.
        Tool.FILL -> true
        Tool.CRAYON, Tool.ERASER -> !stylusOnly || kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP || kind == PointerKind.MOUSE
    }

    fun tapFill(x: Int, y: Int, fillColor: Int = color): DirtyRect {
        val id = regionMap.regionNear(x, y)
        if (id == RegionMap.BOUNDARY) return DirtyRect.EMPTY
        undoStack.beginAction()
        val rect = FloodFill.fill(regionMap, id, fillColor, paint, bleed = 1) { undoStack.capture(it) }
        return finishAction(rect)
    }

    fun strokeBegin(x: Float, y: Float, kind: PointerKind = PointerKind.STYLUS): Boolean {
        if (tool == Tool.FILL || !accepts(kind)) return false
        val erasing = tool == Tool.ERASER || kind == PointerKind.ERASER_TIP
        brush.radius = if (erasing) brushSize.radius * 1.5f else brushSize.radius
        brush.color = if (erasing) CrayonBrush.ERASE_COLOR else color
        undoStack.beginAction()
        strokeActive = true
        strokeRect = brush.begin(x, y, stayInsideLines) { undoStack.capture(it) }
        if (!strokeRect.isEmpty) onDirty?.invoke(strokeRect)
        return true
    }

    fun strokeMove(x: Float, y: Float): DirtyRect {
        if (!strokeActive) return DirtyRect.EMPTY
        val r = brush.moveTo(x, y) { undoStack.capture(it) }
        strokeRect = strokeRect.union(r)
        if (!r.isEmpty) onDirty?.invoke(r)
        return r
    }

    /** Ends the stroke; returns the union rect of the whole stroke for the post-stroke E-Ink refresh. */
    fun strokeEnd(): DirtyRect {
        if (!strokeActive) return DirtyRect.EMPTY
        strokeActive = false
        brush.end()
        val rect = strokeRect
        strokeRect = DirtyRect.EMPTY
        if (undoStack.commitAction()) {
            actionCount++
            onCommitted?.invoke()
        }
        return rect
    }

    fun strokeCancel() {
        if (!strokeActive) return
        strokeActive = false
        brush.end()
        // Roll back the partial stroke.
        if (undoStack.commitAction()) undoStack.undo()?.let { onDirty?.invoke(it) }
        strokeRect = DirtyRect.EMPTY
    }

    fun undo(): DirtyRect? {
        val r = undoStack.undo() ?: return null
        onDirty?.invoke(r)
        actionCount++
        onCommitted?.invoke()
        return r
    }

    /** Clears all paint (undoable as a single step). */
    fun reset(): DirtyRect {
        undoStack.beginAction()
        val full = DirtyRect(0, 0, width, height)
        undoStack.capture(full)
        paint.fill(0)
        return finishAction(full)
    }

    /** Fraction of fillable regions (ignoring tiny specks) that carry any paint. */
    fun progress(): Float {
        var total = 0
        var painted = 0
        val counted = BooleanArray(regionMap.regionCount + 1)
        val big = BooleanArray(regionMap.regionCount + 1) { it > 0 && regionMap.areas[it] >= 200 }
        for (id in 1..regionMap.regionCount) if (big[id]) total++
        if (total == 0) return 0f
        val labels = regionMap.labels
        for (i in paint.indices) {
            val id = labels[i]
            if (id != 0 && big[id] && !counted[id] && (paint[i] ushr 24) != 0) {
                counted[id] = true
                painted++
            }
        }
        return painted.toFloat() / total
    }

    private fun finishAction(rect: DirtyRect): DirtyRect {
        val changed = undoStack.commitAction()
        if (!changed) return DirtyRect.EMPTY
        actionCount++
        onDirty?.invoke(rect)
        onCommitted?.invoke()
        return rect
    }
}
