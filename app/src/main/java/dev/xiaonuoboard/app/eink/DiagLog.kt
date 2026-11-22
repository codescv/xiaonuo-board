package dev.xiaonuoboard.app.eink

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded, thread-safe ring buffer of diagnostic lines (pure JVM, unit-tested). The oldest entries
 * are overwritten once [capacity] is reached. Timestamps come from a MONOTONIC millisecond clock
 * (by default `System.nanoTime()`, which on Android is CLOCK_MONOTONIC — the same clock as
 * `SystemClock.uptimeMillis()` / `MotionEvent.getEventTime()`), so entries can be correlated with
 * input event times.
 *
 * Storage is three preallocated parallel arrays; [log] only stores references (no per-entry
 * objects besides the caller's message string).
 */
class DiagRingBuffer(
    val capacity: Int = DEFAULT_CAPACITY,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(capacity > 0) }

    private val lock = Any()
    private val times = LongArray(capacity)
    private val tags = arrayOfNulls<String>(capacity)
    private val msgs = arrayOfNulls<String>(capacity)
    /** Index of the next write. */
    private var next = 0
    private var size = 0
    private var total = 0L

    /** Entries currently held (≤ [capacity]). */
    val count: Int get() = synchronized(lock) { size }
    /** Entries ever logged since the last [clear]. */
    val totalLogged: Long get() = synchronized(lock) { total }
    /** Entries overwritten because the buffer was full. */
    val dropped: Long get() = synchronized(lock) { total - size }

    fun log(tag: String, msg: String) {
        val t = clockMs()
        synchronized(lock) {
            times[next] = t
            tags[next] = tag
            msgs[next] = msg
            next = (next + 1) % capacity
            if (size < capacity) size++
            total++
        }
    }

    fun clear() = synchronized(lock) {
        next = 0
        size = 0
        total = 0
        tags.fill(null)
        msgs.fill(null)
    }

    data class Entry(val timeMs: Long, val tag: String, val msg: String)

    /** Oldest → newest copy of the held entries. */
    fun snapshot(): List<Entry> = synchronized(lock) {
        val start = (next - size + capacity) % capacity
        List(size) { k ->
            val i = (start + k) % capacity
            Entry(times[i], tags[i].orEmpty(), msgs[i].orEmpty())
        }
    }

    /** Plain-text dump: one line per entry, `<monotonic ms> +<delta ms> <TAG> <message>`. */
    fun formatEntries(): String {
        val entries = snapshot()
        val sb = StringBuilder(entries.size * 64 + 64)
        var prev = entries.firstOrNull()?.timeMs ?: 0L
        for (e in entries) {
            sb.append(formatLine(e, e.timeMs - prev)).append('\n')
            prev = e.timeMs
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000

        fun formatLine(e: Entry, deltaMs: Long): String =
            String.format(Locale.US, "%10d %+6d %-8s %s", e.timeMs, deltaMs, e.tag, e.msg)
    }
}

/**
 * App-wide diagnostic log (Settings → 「导出诊断日志」). Records lifecycle transitions and one summary
 * line per stroke / refresh / fill / save — NEVER one line per move point — so it is cheap to keep
 * always on. Callers on hot paths use the lambda overload so nothing is formatted when disabled.
 */
object DiagLog {
    private val buffer = DiagRingBuffer()
    /** Last-known key/value state for the export header (canvas size, view size, raw drawing…). */
    private val state = ConcurrentHashMap<String, String>()

    @Volatile var enabled: Boolean = true

    // Tags (≤ 8 chars, aligned in the dump).
    const val ONYX = "ONYX"
    const val TOUCH = "TOUCH"
    const val RAW = "RAW"
    const val STROKE = "STROKE"
    const val DRAW = "DRAW"
    const val FILL = "FILL"
    const val SAVE = "SAVE"
    const val CANVAS = "CANVAS"

    val count: Int get() = buffer.count
    val dropped: Long get() = buffer.dropped
    val capacity: Int get() = buffer.capacity

    fun log(tag: String, msg: String) {
        if (enabled) buffer.log(tag, msg)
    }

    inline fun log(tag: String, msg: () -> String) {
        if (enabled) log(tag, msg())
    }

    fun setState(key: String, value: String) { state[key] = value }

    fun stateSnapshot(): Map<String, String> = state.toSortedMap()

    fun clear() {
        buffer.clear()
    }

    fun formatEntries(): String = buffer.formatEntries()

    /** Monotonic ms on the same clock as the entries. */
    fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** `1.234` style ms from a nanoTime delta (for per-call CPU timings). */
    fun ms(nanos: Long): String = String.format(Locale.US, "%.2f", nanos / 1_000_000.0)
}
