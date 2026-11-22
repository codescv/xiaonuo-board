package dev.xiaonuoboard.app.eink

import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.PointerKind
import java.util.Locale

/**
 * Per-stroke accumulator for the diagnostic log (pure, JVM-tested). All per-point updates are
 * plain field writes (no allocation); one summary line is produced at stroke end / cancel.
 */
class StrokeStats {
    enum class Source { RAW, MOTION }

    var active = false
        private set
    private var source = Source.MOTION
    private var kind = PointerKind.UNKNOWN
    private var renderAtBegin = false
    private var startMs = 0L
    private var points = 0
    private var pMin = Float.NaN
    private var pMax = Float.NaN
    private var rawMax = Float.NaN
    private var cpuNanos = 0L
    private var frames = 0
    private var frameTiles = 0
    private var uploadNanos = 0L
    private var drawNanos = 0L
    /** Delay from the first input sample's timestamp to its delivery on the UI thread, or -1. */
    private var deliveryLagMs = -1L

    fun begin(source: Source, kind: PointerKind, renderEnabledAtBegin: Boolean, nowMs: Long, eventTimeMs: Long) {
        active = true
        this.source = source
        this.kind = kind
        renderAtBegin = renderEnabledAtBegin
        startMs = nowMs
        points = 0
        pMin = Float.NaN
        pMax = Float.NaN
        rawMax = Float.NaN
        cpuNanos = 0L
        frames = 0
        frameTiles = 0
        uploadNanos = 0L
        drawNanos = 0L
        val lag = nowMs - eventTimeMs
        deliveryLagMs = if (eventTimeMs > 0 && lag in 0..MAX_PLAUSIBLE_LAG_MS) lag else -1L
    }

    /** One input sample: [normalized] pressure (NaN = none) and the raw device value. */
    fun point(normalized: Float, raw: Float) {
        if (!active) return
        points++
        if (!normalized.isNaN()) {
            if (pMin.isNaN() || normalized < pMin) pMin = normalized
            if (pMax.isNaN() || normalized > pMax) pMax = normalized
        }
        if (!raw.isNaN() && (rawMax.isNaN() || raw > rawMax)) rawMax = raw
    }

    /** UI-thread CPU spent painting this stroke (router → controller → brush). */
    fun addCpu(nanos: Long) {
        if (active) cpuNanos += nanos
    }

    /** A frame drawn during the stroke (software preview): tiles copied, copy and draw time. */
    fun frame(tiles: Int, uploadNanos: Long, drawNanos: Long) {
        if (!active) return
        frames++
        frameTiles += tiles
        this.uploadNanos += uploadNanos
        this.drawNanos += drawNanos
    }

    val pointCount: Int get() = points

    /** Ends the stroke and returns its summary line, or null when no stroke was active. */
    fun end(nowMs: Long, dirty: DirtyRect, cancelled: Boolean, brushRadius: Float): String? {
        if (!active) return null
        active = false
        return String.format(
            Locale.US,
            "%s src=%s tool=%s pts=%d pMin=%s pMax=%s pRawMax=%s r=%.1f dur=%dms paintCpu=%.2fms renderAtBegin=%s lag=%s dirty=%s%s",
            if (cancelled) "cancel" else "end",
            source.name, kind.name, points, fmt(pMin), fmt(pMax), fmt(rawMax), brushRadius,
            nowMs - startMs, cpuNanos / 1_000_000.0, renderAtBegin,
            if (deliveryLagMs >= 0) "${deliveryLagMs}ms" else "n/a",
            if (dirty.isEmpty) "-" else "${dirty.left},${dirty.top}-${dirty.right},${dirty.bottom}",
            if (frames == 0) "" else String.format(
                Locale.US, " frames=%d tiles=%d upload=%.2fms draw=%.2fms",
                frames, frameTiles, uploadNanos / 1_000_000.0, drawNanos / 1_000_000.0,
            ),
        )
    }

    private fun fmt(v: Float) = if (v.isNaN()) "-" else String.format(Locale.US, "%.3f", v)

    private companion object {
        const val MAX_PLAUSIBLE_LAG_MS = 10_000L
    }
}
