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

    /**
     * Color sent to the BOOX firmware hardware scribble layer.
     *
     * Pale / pastel colors (e.g. `#FCF7D4`) vanish on Kaleido Plus's fast scribble waveform while
     * drawing. For perceived luminance > 0.72, darken V (`<= 0.72`) and boost saturation (`>= 0.55`,
     * or for near-achromatic grays `S < 0.08`, cap `V <= 0.55` without inventing hue) so the live
     * hardware trace stays visible; pen-up burst repaint replaces it with the exact true color.
     */
    fun hardwarePreviewColor(argb: Int, eraser: Boolean): Int {
        if (eraser) return 0xFFFFFFFF.toInt()
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        if (lum <= PREVIEW_MAX_LUM) return argb or (0xFF shl 24)

        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxC = maxOf(rf, gf, bf)
        val minC = minOf(rf, gf, bf)
        val delta = maxC - minC

        val v = maxC
        val s = if (maxC == 0f) 0f else delta / maxC
        val h = when {
            delta == 0f -> 0f
            maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
            maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val newS: Float
        val newV: Float
        if (s < PREVIEW_GRAY_SAT) {
            newS = s
            newV = minOf(v, PREVIEW_GRAY_MAX_V)
        } else {
            newS = maxOf(s, PREVIEW_MIN_SAT)
            newV = minOf(v, PREVIEW_MAX_V)
        }

        val c = newV * newS
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = newV - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val nr = kotlin.math.round((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val ng = kotlin.math.round((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val nb = kotlin.math.round((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    const val ERASER_SCALE = 1.5f
    private const val PREVIEW_MAX_LUM = 0.72f
    private const val PREVIEW_MAX_V = 0.72f
    private const val PREVIEW_MIN_SAT = 0.55f
    private const val PREVIEW_GRAY_SAT = 0.08f
    private const val PREVIEW_GRAY_MAX_V = 0.55f
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
