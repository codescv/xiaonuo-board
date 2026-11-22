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
    /** Single undo-capture hook instance, so per-sample brush/fill calls do not allocate a closure. */
    private val captureHook: (DirtyRect) -> Unit = { undoStack.capture(it) }

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
    // Pressure state of the current stroke (plain fields: the per-sample path stays allocation-free).
    private var baseRadius = 0f
    private var pressureEnabled = false
    private var pressureSeeded = false
    private var smoothedMult = StrokePressure.NEUTRAL
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
        val rect = FloodFill.fill(regionMap, id, fillColor, paint, bleed = 1, beforeWrite = captureHook)
        return finishAction(rect)
    }

    fun strokeBegin(x: Float, y: Float, kind: PointerKind = PointerKind.STYLUS, pressure: Float = StrokePressure.NO_PRESSURE): Boolean {
        if (tool == Tool.FILL || !accepts(kind)) return false
        val erasing = tool == Tool.ERASER || kind == PointerKind.ERASER_TIP
        baseRadius = if (erasing) brushSize.radius * 1.5f else brushSize.radius
        // Only a pen drawing with the crayon is pressure-modulated: fingers / mice have no meaningful
        // pressure, and the eraser stays predictable (it also matches the constant-width hardware
        // eraser trace on BOOX).
        pressureEnabled = !erasing && kind == PointerKind.STYLUS
        smoothedMult = if (pressureEnabled && StrokePressure.isValid(pressure)) StrokePressure.multiplier(pressure) else StrokePressure.NEUTRAL
        pressureSeeded = pressureEnabled && StrokePressure.isValid(pressure)
        brush.radius = baseRadius * smoothedMult
        brush.color = if (erasing) CrayonBrush.ERASE_COLOR else color
        undoStack.beginAction()
        strokeActive = true
        strokeRect = brush.begin(x, y, stayInsideLines, captureHook, lockRadius = baseRadius)
        if (!strokeRect.isEmpty) onDirty?.invoke(strokeRect)
        return true
    }

    /**
     * Continues the stroke to (x, y). [pressure] is normalized `[0, 1]` (see [StrokePressure]); an
     * unusable reading keeps the current width. The width is EMA-smoothed across samples and
     * interpolated along the segment by the brush.
     */
    fun strokeMove(x: Float, y: Float, pressure: Float = StrokePressure.NO_PRESSURE): DirtyRect {
        if (!strokeActive) return DirtyRect.EMPTY
        if (pressureEnabled && StrokePressure.isValid(pressure)) {
            val target = StrokePressure.multiplier(pressure)
            // The first usable reading seeds the EMA (pen-down samples may carry no pressure).
            smoothedMult = if (pressureSeeded) StrokePressure.smooth(smoothedMult, target) else target
            pressureSeeded = true
            brush.radius = baseRadius * smoothedMult
        }
        val r = brush.moveTo(x, y, captureHook)
        strokeRect = strokeRect.union(r)
        if (!r.isEmpty) onDirty?.invoke(r)
        return r
    }

    /** Current (pressure-modulated) brush radius in canvas px; diagnostics / tests. */
    val currentBrushRadius: Float get() = brush.radius

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

    /** Regions that count towards [progress] (ignoring tiny specks); depends only on the immutable region map. */
    private val bigRegions: BooleanArray by lazy {
        BooleanArray(regionMap.regionCount + 1) { it > 0 && regionMap.areas[it] >= PROGRESS_MIN_AREA }
    }
    private val bigRegionCount: Int by lazy { bigRegions.count { it } }

    /**
     * Fraction of fillable regions (ignoring tiny specks) that carry any paint.
     *
     * Pure read of [pixels] + the immutable [regionMap], so it is safe to call from a background
     * thread on a snapshot (`paint.copyOf()`), keeping the full-canvas scan off the UI thread.
     */
    fun progress(pixels: IntArray = paint): Float {
        require(pixels.size == width * height)
        val total = bigRegionCount
        if (total == 0) return 0f
        val big = bigRegions
        val counted = BooleanArray(regionMap.regionCount + 1)
        var painted = 0
        val labels = regionMap.labels
        for (i in pixels.indices) {
            val id = labels[i]
            if (id != 0 && big[id] && !counted[id] && (pixels[i] ushr 24) != 0) {
                counted[id] = true
                painted++
                if (painted == total) break
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

    companion object {
        const val PROGRESS_MIN_AREA = 200
    }
}
