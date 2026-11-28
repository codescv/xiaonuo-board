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
     * Two Kaleido Plus (`SDMDevice`) hardware-scribble quirks make strokes look invisible or very
     * faint until the pen-up `SurfaceView` refresh:
     *  1. **Near-black (`#111111`)**: Onyx's own `ColorUtils.isGrayColor` treats any `0 < R==G==B < 255`
     *     as gray rather than black, bypassing BOOX's 300 PPI solid-black fast-scribble path. Snap
     *     near-black (and dark neutral grays) to exact `Color.BLACK` (`0xFF000000`), matching BOOX
     *     Notes and `OnyxPenDemo`.
     *  2. **Yellow (`#FFD600`, `#FCF7D4`, `#FCE424`) & bright pastels**: Red + Green make up 88.6%
     *     of perceived luminance, so capping HSV `V <= 0.72` alone still left yellow at `Y = 0.57–0.68`
     *     (with both R & G > 128 — only the blue subpixel darkened on Kaleido's CFA). Scale `V`
     *     directly by perceived luminance so every bright chromatic preview hits `Y <= 0.36`, matching
     *     the contrast of red/blue/magenta; pen-up burst repaint replaces it with the exact true color.
     */
    fun hardwarePreviewColor(argb: Int, eraser: Boolean): Int {
        if (eraser) return 0xFFFFFFFF.toInt()
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f

        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxC = maxOf(rf, gf, bf)
        val minC = minOf(rf, gf, bf)
        val delta = maxC - minC
        val v = maxC
        val s = if (maxC == 0f) 0f else delta / maxC

        if (maxOf(r, g, b) <= PREVIEW_NEAR_BLACK_MAX_CHANNEL ||
            (lum <= PREVIEW_NEAR_BLACK_MAX_LUM && s < PREVIEW_NEAR_BLACK_MAX_SAT)
        ) {
            return 0xFF000000.toInt()
        }

        if (s < PREVIEW_GRAY_SAT) {
            return if (lum <= PREVIEW_DARK_GRAY_LUM) 0xFF000000.toInt() else PREVIEW_DARK_GRAY_ARGB
        }

        if (lum <= PREVIEW_MAX_LUM) return argb or (0xFF shl 24)

        val h = when {
            delta == 0f -> 0f
            maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
            maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val newS = maxOf(s, PREVIEW_MIN_SAT)
        val (ur, ug, ub) = hsvUnitRgb(h, newS)
        val unitLum = 0.299f * ur + 0.587f * ug + 0.114f * ub
        val newV = if (unitLum > 0f) minOf(v, PREVIEW_TARGET_LUM / unitLum) else v

        val nr = kotlin.math.round(ur * newV * 255f).toInt().coerceIn(0, 255)
        val ng = kotlin.math.round(ug * newV * 255f).toInt().coerceIn(0, 255)
        val nb = kotlin.math.round(ub * newV * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /** Normalized RGB components in `[0, 1]` for HSV `(h, s, v = 1)`. */
    private fun hsvUnitRgb(h: Float, s: Float): Triple<Float, Float, Float> {
        val x = s * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = 1f - s
        val (r1, g1, b1) = when {
            h < 60f -> Triple(s, x, 0f)
            h < 120f -> Triple(x, s, 0f)
            h < 180f -> Triple(0f, s, x)
            h < 240f -> Triple(0f, x, s)
            h < 300f -> Triple(x, 0f, s)
            else -> Triple(s, 0f, x)
        }
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    const val ERASER_SCALE = 1.5f
    private const val PREVIEW_NEAR_BLACK_MAX_CHANNEL = 45
    private const val PREVIEW_NEAR_BLACK_MAX_LUM = 0.16f
    private const val PREVIEW_NEAR_BLACK_MAX_SAT = 0.25f
    private const val PREVIEW_GRAY_SAT = 0.08f
    private const val PREVIEW_DARK_GRAY_LUM = 0.25f
    /** Matches OnyxPenDemo's `StrokeColor.DARK_GRAY` (`#404040`). */
    private const val PREVIEW_DARK_GRAY_ARGB = 0xFF404040.toInt()
    private const val PREVIEW_MAX_LUM = 0.48f
    private const val PREVIEW_TARGET_LUM = 0.36f
    private const val PREVIEW_MIN_SAT = 0.55f
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
