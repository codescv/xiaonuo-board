package dev.xiaonuoboard.app.eink

import dev.xiaonuoboard.app.engine.DirtyRect

/**
 * Refresh work of a burst of BOOX hardware-drawn pen strokes, refreshed ONCE when the burst ends
 * (see [RawDrawingController.schedulePenUpFlush]) instead of once per pen-up.
 *
 * Two kinds of area accumulate: what the strokes changed in the paint layer ([strokeDirty], canvas
 * px — region-clipped, coloured) and where the firmware drew its hardware trace ([traceDirty], view
 * px — not region-clipped, may extend past the painted rect or the canvas image). Both must be
 * repainted by the colour refresh so no trace is left behind.
 *
 * Pure bookkeeping (no Android types) so the merge rules are JVM-tested.
 */
class PenBurst {
    /** Union of the burst's committed (and rolled-back) paint changes, canvas px. */
    var strokeDirty: DirtyRect = DirtyRect.EMPTY
        private set
    /** Union of the burst's hardware trace bounds, view px. */
    var traceDirty: DirtyRect = DirtyRect.EMPTY
        private set

    val isEmpty: Boolean get() = strokeDirty.isEmpty && traceDirty.isEmpty

    fun addStroke(canvasRect: DirtyRect) {
        if (!canvasRect.isEmpty) strokeDirty = strokeDirty.union(canvasRect)
    }

    fun addTrace(viewRect: DirtyRect) {
        if (!viewRect.isEmpty) traceDirty = traceDirty.union(viewRect)
    }

    fun clear() {
        strokeDirty = DirtyRect.EMPTY
        traceDirty = DirtyRect.EMPTY
    }

    /**
     * One refresh: [canvasRect] (canvas px, may be empty) must be mapped to the view and unioned
     * with [viewRect] (view px, may be empty), refreshed with [mode].
     */
    data class Flush(val canvasRect: DirtyRect, val viewRect: DirtyRect, val mode: EinkMode)

    /**
     * Takes the burst together with a coalesced [batch] (e.g. a fill / undo queued for this frame)
     * into ONE refresh and clears the burst. Pen work is never refreshed weaker than NORMAL (GU,
     * colour): a FAST (DU) frame would leave the black hardware trace / monochrome ghosts behind.
     * Returns null when there is nothing to refresh.
     */
    fun take(batch: RefreshCoalescer.Batch?): Flush? {
        val pen = !isEmpty
        if (batch == null && !pen) return null
        val canvas = (batch?.rect ?: DirtyRect.EMPTY).union(strokeDirty)
        val mode = when {
            batch == null -> EinkMode.NORMAL
            pen -> RefreshCoalescer.stronger(batch.mode, EinkMode.NORMAL)
            else -> batch.mode
        }
        val flush = Flush(canvas, traceDirty, mode)
        clear()
        return flush
    }
}
