package dev.xiaonuoboard.app.eink

import dev.xiaonuoboard.app.engine.DirtyRect

/**
 * Merges the many partial E-Ink refresh requests produced while coloring into ONE request per
 * display frame.
 *
 * A stylus delivers several samples per frame (historical points), and every sample used to issue
 * its own `EpdController.invalidate(view, rect, mode)` / `View.invalidate()` plus a JNI pixel copy.
 * E-Ink panels cannot show more than one update per frame anyway, so stacking requests only queues
 * waveforms (on BOOX this is what makes the trace lag further and further behind the pen) and burns
 * UI-thread time. The view calls [request] for each change and schedules a single flush (e.g. via
 * `postOnAnimation`) whenever [request] returns true; the flush calls [drain].
 *
 * Mode merging keeps the strongest waveform requested in the batch, so a pen-up NORMAL (colour)
 * refresh always wins over FAST (DU) previews still pending for the same frame.
 */
class RefreshCoalescer {
    data class Batch(val rect: DirtyRect, val mode: EinkMode)

    private var l = 0
    private var t = 0
    private var r = 0
    private var b = 0
    private var mode: EinkMode? = null

    val hasPending: Boolean get() = mode != null

    fun request(rect: DirtyRect, mode: EinkMode): Boolean = request(rect.left, rect.top, rect.right, rect.bottom, mode)

    /**
     * Adds `[left, right) x [top, bottom)` to the pending batch.
     * @return true when this call opened a new batch, i.e. the caller must schedule a flush.
     */
    fun request(left: Int, top: Int, right: Int, bottom: Int, mode: EinkMode): Boolean {
        if (right <= left || bottom <= top) return false
        val current = this.mode
        if (current == null) {
            l = left; t = top; r = right; b = bottom
            this.mode = mode
            return true
        }
        if (left < l) l = left
        if (top < t) t = top
        if (right > r) r = right
        if (bottom > b) b = bottom
        this.mode = stronger(current, mode)
        return false
    }

    /** Returns and clears the pending batch, or null when nothing is pending. */
    fun drain(): Batch? {
        val m = mode ?: return null
        mode = null
        return Batch(DirtyRect(l, t, r, b), m)
    }

    fun clear() {
        mode = null
    }

    companion object {
        /** Waveform strength: FAST (DU/A2 preview) < NORMAL (GU colour) < REGAL < FULL (GC flash). */
        fun rank(mode: EinkMode): Int = when (mode) {
            EinkMode.FAST -> 0
            EinkMode.NORMAL -> 1
            EinkMode.REGAL -> 2
            EinkMode.FULL -> 3
        }

        fun stronger(a: EinkMode, b: EinkMode): EinkMode = if (rank(b) > rank(a)) b else a
    }
}
