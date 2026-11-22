package dev.xiaonuoboard.app.eink

import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.StrokePressure
import kotlin.math.ceil
import kotlin.math.floor

/** Pure (JVM-tested) coordinate helpers for the BOOX TouchHelper pen layer. All px are view-local. */
object RawDrawingGeometry {
    /**
     * Converts overlay bounds in WINDOW coordinates into TouchHelper exclude rects in the canvas
     * view's local coordinates (TouchHelper limit / exclude rects and TouchPoints are view-local),
     * clipped to [limit]; overlays not intersecting the canvas are dropped.
     */
    fun windowToLocalExcludes(windowRects: List<DirtyRect>, viewWindowX: Int, viewWindowY: Int, limit: DirtyRect): List<DirtyRect> =
        windowRects.mapNotNull { w ->
            DirtyRect(
                maxOf(w.left - viewWindowX, limit.left),
                maxOf(w.top - viewWindowY, limit.top),
                minOf(w.right - viewWindowX, limit.right),
                minOf(w.bottom - viewWindowY, limit.bottom),
            ).takeUnless { it.isEmpty }
        }

    /**
     * onyxsdk-pen 1.5.9 `RawInputReader.setExcludeRect(list)` is a no-op for an EMPTY list (neither
     * `nativeSetExcludeRegion` nor `EpdController.setScreenHandWritingRegionExclude` is called) and
     * the native reader is a process-wide singleton, so an overlay's exclusion would outlive it.
     * "No exclusions" is therefore sent as one zero-area rect far off screen, which overwrites both
     * the native and the SurfaceFlinger exclude regions (zero area also skips Onyx's empty-region
     * error log).
     */
    fun touchHelperExcludes(exclude: List<DirtyRect>): List<DirtyRect> = exclude.ifEmpty { listOf(CLEAR_EXCLUDES) }

    val CLEAR_EXCLUDES = DirtyRect(-10_000, -10_000, -10_000, -10_000)

    /**
     * Hardware trace width in screen px: the brush diameter (eraser ×1.5, like the engine) at the
     * zoom. With [pressureSensitive] (firmware FOUNTAIN pen, whose width is the MAXIMUM it reaches at
     * full pressure) it is the firm-press diameter ([StrokePressure.MAX_MULT]), so the live trace
     * spans the same range as the pressure-modulated crayon, and the pen-up refresh rect derived from
     * it always covers the widest trace.
     */
    fun traceWidthPx(brushRadius: Float, eraser: Boolean, viewScale: Float, pressureSensitive: Boolean = false): Float =
        brushRadius * (if (eraser) ERASER_SCALE else 1f) * 2f * viewScale *
            (if (pressureSensitive && !eraser) StrokePressure.MAX_MULT else 1f)

    const val ERASER_SCALE = 1.5f
}

/**
 * Bounds of the hardware pen trace of one stroke. The trace is drawn by the EPD pen layer, NOT
 * clipped to the colored region (不涂出线) nor to the canvas image, so the pen-up refresh must cover
 * it in addition to the painted rect, or pieces of the black/colored trace would stay on the panel.
 */
class TraceBounds {
    private var l = 0f
    private var t = 0f
    private var r = 0f
    private var b = 0f
    var isEmpty = true
        private set

    fun reset() { isEmpty = true }

    fun add(x: Float, y: Float) {
        if (isEmpty) {
            l = x; r = x; t = y; b = y
            isEmpty = false
            return
        }
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
    }

    /** Integer refresh rect covering every trace pixel: points ± half the width + [pad]. */
    fun refreshRect(strokeWidth: Float, pad: Float): DirtyRect? {
        if (isEmpty) return null
        val m = strokeWidth / 2f + pad
        return DirtyRect(floor(l - m).toInt(), floor(t - m).toInt(), ceil(r + m).toInt() + 1, ceil(b + m).toInt() + 1)
    }
}
