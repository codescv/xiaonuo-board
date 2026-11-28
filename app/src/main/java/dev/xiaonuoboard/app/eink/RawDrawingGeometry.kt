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
     * Color sent to the BOOX firmware hardware scribble layer (`TouchHelper.setStrokeColor`).
     *
     * Verified verbatim against BOOX built-in Notes' complete 16-color hardware palette on BOOX
     * Nova Air C (Kaleido Plus, Android 11, `SDMDevice`):
     *  - Neutrals: `#000000` (black), `#404040` (dark gray), `#808080` (mid gray),
     *    `#C0C0C0` (light gray), `#FFFFFF` (white).
     *  - Chromatics: `#FF6163` (red), `#FF4400` (orange-red), `#FFAA00` (gold orange),
     *    `#F0FF00` (lemon yellow), `#00B036` (green), `#008000` (dark green),
     *    `#00F0FF` (aqua), `#00AAFF` (sky blue), `#000084` (navy blue),
     *    `#9338BE` (purple), `#EE00FF` (bright violet).
     *  - Every crayon color maps onto the perceptually closest entry of these exact 16 hardware
     *    colors (and all 16 are exact fixed points), guaranteeing bold zero-latency real-color
     *    live ink while avoiding washed-out mid-tones and false blue tints on near-whites or
     *    dark charcoals.
     */
    fun hardwarePreviewColor(argb: Int, eraser: Boolean): Int {
        if (eraser) return HW_WHITE
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        if (r >= 245 && g >= 245 && b >= 245) return HW_WHITE

        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxC = maxOf(rf, gf, bf)
        val minC = minOf(rf, gf, bf)
        val delta = maxC - minC
        val v = maxC
        val s = if (maxC == 0f) 0f else delta / maxC
        val lum = 0.299f * rf + 0.587f * gf + 0.114f * bf

        // Near-black, neutral grays, and very pale off-whites (e.g. Bluey #D4E9F9 -> #C0C0C0,
        // warm gray #8D99AE -> #808080, dark gray #404040 -> #404040): keep neutral instead of
        // over-saturating faint blue tints into neon aqua.
        if (v <= 0.16f) return HW_BLACK
        if (s < 0.18f || (s < 0.21f && v < 0.85f)) {
            return when {
                lum < 0.18f -> HW_BLACK
                lum < 0.42f -> HW_DARK_GRAY
                lum < 0.72f -> HW_MID_GRAY
                else -> HW_LIGHT_GRAY
            }
        }

        // Dark low-chroma charcoal/slate tones (e.g. Bluey #3B3E63 / #3C3C6C / #24243C):
        // on Kaleido Plus these read as black/charcoal on the swatch, so keep them dark neutral
        // rather than vivid navy blue (true saturated blues have s > 0.50, e.g. #000084 / #283878).
        if (v <= 0.45f && s <= 0.50f) {
            return if (lum < 0.28f) HW_BLACK else HW_DARK_GRAY
        }

        val hDeg = when {
            delta == 0f -> 0f
            maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
            maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        // Very dark high-saturation chromatic colors.
        if (v < 0.30f) {
            return when {
                hDeg in 205f..275f && s > 0.50f -> HW_NAVY_BLUE
                hDeg in 85f..172f && s > 0.50f -> HW_DARK_GREEN
                else -> HW_BLACK
            }
        }

        return when {
            hDeg < 8f || hDeg >= 342f -> if (hDeg >= 342f && b > g + 15) HW_VIOLET else HW_RED
            hDeg < 35f -> HW_ORANGE_RED
            hDeg < 54f -> HW_GOLD_YELLOW
            hDeg < 75f -> if (v >= 0.85f && s >= 0.55f) HW_LEMON_YELLOW else HW_GOLD_YELLOW
            hDeg < 172f -> if (v < 0.56f) HW_DARK_GREEN else HW_GREEN
            hDeg < 194f -> HW_AQUA
            hDeg < 222f -> if (v < 0.85f || s > 0.35f) HW_SKY_BLUE else HW_AQUA
            hDeg < 268f -> HW_NAVY_BLUE
            hDeg < 292f -> HW_PURPLE
            else -> HW_VIOLET
        }
    }

    const val ERASER_SCALE = 1.5f

    /** Complete 16-color BOOX built-in Notes native hardware palette (verified on Nova Air C). */
    const val HW_BLACK = 0xFF000000.toInt()
    const val HW_DARK_GRAY = 0xFF404040.toInt()
    const val HW_MID_GRAY = 0xFF808080.toInt()
    const val HW_LIGHT_GRAY = 0xFFC0C0C0.toInt()
    const val HW_WHITE = 0xFFFFFFFF.toInt()
    const val HW_RED = 0xFFFF6163.toInt()
    const val HW_ORANGE_RED = 0xFFFF4400.toInt()
    const val HW_GOLD_YELLOW = 0xFFFFAA00.toInt()
    const val HW_LEMON_YELLOW = 0xFFF0FF00.toInt()
    const val HW_GREEN = 0xFF00B036.toInt()
    const val HW_DARK_GREEN = 0xFF008000.toInt()
    const val HW_AQUA = 0xFF00F0FF.toInt()
    const val HW_SKY_BLUE = 0xFF00AAFF.toInt()
    const val HW_NAVY_BLUE = 0xFF000084.toInt()
    const val HW_PURPLE = 0xFF9338BE.toInt()
    const val HW_VIOLET = 0xFFEE00FF.toInt()

    val BOOX_HARDWARE_PALETTE: Set<Int> = setOf(
        HW_BLACK,
        HW_DARK_GRAY,
        HW_MID_GRAY,
        HW_LIGHT_GRAY,
        HW_WHITE,
        HW_RED,
        HW_ORANGE_RED,
        HW_GOLD_YELLOW,
        HW_LEMON_YELLOW,
        HW_GREEN,
        HW_DARK_GREEN,
        HW_AQUA,
        HW_SKY_BLUE,
        HW_NAVY_BLUE,
        HW_PURPLE,
        HW_VIOLET,
    )
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
