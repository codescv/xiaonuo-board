package dev.xiaonuoboard.app.engine

import kotlin.math.pow

/**
 * Pen pressure → brush radius model (pure, JVM-tested, allocation-free).
 *
 * Pressure travels through the input pipeline NORMALIZED to `[0, 1]`; anything that is not a usable
 * reading (`NaN`, `<= 0`, e.g. a hover / lift sample or a device without a pressure sensor) is
 * reported as [NO_PRESSURE] and leaves the brush at its current width. Fingers, mice and the eraser
 * are never pressure-modulated (see [ColoringCanvasController.strokeBegin]).
 *
 * Kid-friendly curve: `MIN + (MAX - MIN) · p^GAMMA` — a light touch still leaves a clearly visible
 * line (~0.42× the chosen 细/中/粗 radius), a normal press is ≈ the chosen size (~0.95×) and a firm
 * press gets noticeably fatter (1.35×). The concave gamma makes the low end responsive, because
 * young kids rarely press hard.
 */
object StrokePressure {
    /** Marker for "no usable pressure reading". */
    const val NO_PRESSURE = Float.NaN
    /** Width multiplier used when the stroke has no usable pressure at all. */
    const val NEUTRAL = 1f
    const val MIN_MULT = 0.42f
    const val MAX_MULT = 1.35f
    const val GAMMA = 0.7f
    /** EMA weight of the newest sample: light smoothing so the width never jumps between points. */
    const val SMOOTHING = 0.35f
    /** Below this a reading is treated as noise (e.g. the digitizer's touch-down ramp). */
    private const val MIN_VALID = 1e-4f

    /** True when [normalized] is a usable reading (finite and > 0). */
    fun isValid(normalized: Float): Boolean = !normalized.isNaN() && normalized > MIN_VALID

    /**
     * Normalizes a raw reading against [max] (e.g. BOOX `TouchPoint.pressure` in `0..maxTouchPressure`,
     * or `MotionEvent.getPressure()` with `max = 1`). Returns [NO_PRESSURE] for unusable input.
     */
    fun normalize(raw: Float, max: Float): Float {
        if (raw.isNaN() || raw <= 0f || raw.isInfinite()) return NO_PRESSURE
        val m = if (max.isNaN() || max <= 0f || max.isInfinite()) 1f else max
        return (raw / m).coerceIn(0f, 1f)
    }

    /** Radius multiplier for a normalized pressure; [NEUTRAL] for an unusable reading. */
    fun multiplier(normalized: Float): Float {
        if (!isValid(normalized)) return NEUTRAL
        val p = normalized.coerceIn(0f, 1f)
        return MIN_MULT + (MAX_MULT - MIN_MULT) * p.pow(GAMMA)
    }

    /** One EMA step from [previous] towards [target]. */
    fun smooth(previous: Float, target: Float): Float = previous + SMOOTHING * (target - previous)
}

/**
 * Normalizes raw BOOX `TouchPoint.pressure` values (raw digitizer units — `RawInputReader` builds
 * every TouchPoint with `(float) pressureInt`, verified in onyxsdk-pen 1.5.9) against the device's
 * reported maximum. `EpdController.getMaxTouchPressure()` falls back to a hard-coded 2048 (SDM / RK)
 * or 1024 (base) when the framework call fails, which would saturate a 4096-level Wacom panel at
 * half pressure, so the observed maximum raises the scale self-correctingly. Not thread-safe (UI
 * thread only, like the raw pen callbacks).
 */
class RawPressureNormalizer(reportedMax: Float) {
    var scaleMax: Float = if (reportedMax.isNaN() || reportedMax <= 0f || reportedMax.isInfinite()) DEFAULT_MAX else reportedMax
        private set
    /** Largest raw reading seen so far (diagnostics). */
    var observedMax: Float = 0f
        private set

    fun normalize(raw: Float): Float {
        if (!raw.isNaN() && !raw.isInfinite()) {
            if (raw > observedMax) observedMax = raw
            if (raw > scaleMax && raw <= SANITY_MAX) {
                // A "normalized" (≤ 1) reported scale that receives raw digitizer units: jump to at
                // least the common Wacom range instead of saturating at the first sample.
                scaleMax = if (scaleMax <= 1f && raw > 1f) maxOf(raw, DEFAULT_MAX) else raw
            }
        }
        return StrokePressure.normalize(raw, scaleMax)
    }

    companion object {
        /** Wacom EMR panels on BOOX report 4096 levels. */
        const val DEFAULT_MAX = 4096f
        /** Readings above this are garbage, never a new scale. */
        const val SANITY_MAX = 65_536f
    }
}
