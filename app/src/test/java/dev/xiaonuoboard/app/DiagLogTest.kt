package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.data.DiagLogExporter
import dev.xiaonuoboard.app.eink.DiagLog
import dev.xiaonuoboard.app.eink.DiagRingBuffer
import dev.xiaonuoboard.app.eink.StrokeStats
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.PointerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Diagnostic ring buffer, per-stroke summaries and the export file name. */
class DiagLogTest {
    private var now = 1_000L
    private fun ring(capacity: Int) = DiagRingBuffer(capacity) { now }

    @Test
    fun keepsEntriesInOrderBeforeWrapping() {
        val r = ring(4)
        r.log("A", "one"); now += 5
        r.log("B", "two")
        assertEquals(2, r.count)
        assertEquals(0L, r.dropped)
        assertEquals(listOf("one", "two"), r.snapshot().map { it.msg })
        assertEquals(listOf(1_000L, 1_005L), r.snapshot().map { it.timeMs })
    }

    @Test
    fun wrapAroundDropsTheOldestEntries() {
        val r = ring(3)
        for (i in 1..7) { r.log("T", "m$i"); now++ }
        assertEquals(3, r.count)
        assertEquals(7L, r.totalLogged)
        assertEquals(4L, r.dropped)
        assertEquals(listOf("m5", "m6", "m7"), r.snapshot().map { it.msg })
        // Exactly full (no wrap offset) also reads oldest → newest.
        val full = ring(3)
        for (i in 1..3) full.log("T", "x$i")
        assertEquals(listOf("x1", "x2", "x3"), full.snapshot().map { it.msg })
    }

    @Test
    fun formatShowsMonotonicTimeDeltaTagAndMessage() {
        val r = ring(10)
        r.log("RAW", "enable"); now += 12
        r.log("STROKE", "end src=RAW")
        val lines = r.formatEntries().trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].matches(Regex("""\s*1000\s+\+0 RAW\s+enable""")))
        assertTrue(lines[1], lines[1].matches(Regex("""\s*1012\s+\+12 STROKE\s+end src=RAW""")))
        assertEquals("", ring(2).formatEntries())
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val r = ring(3)
        repeat(5) { r.log("T", "m$it") }
        r.clear()
        assertEquals(0, r.count)
        assertEquals(0L, r.dropped)
        assertTrue(r.snapshot().isEmpty())
        r.log("T", "after")
        assertEquals(listOf("after"), r.snapshot().map { it.msg })
    }

    @Test
    fun concurrentWritersNeverCorruptTheBuffer() {
        val r = DiagRingBuffer(500)
        val threads = 8
        val perThread = 2_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                for (i in 0 until perThread) {
                    r.log("T$t", "$t:$i")
                    if (i % 97 == 0) r.snapshot() // readers interleaved with writers
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals((threads * perThread).toLong(), r.totalLogged)
        assertEquals(500, r.count)
        val snap = r.snapshot()
        assertEquals(500, snap.size)
        // Every entry is a consistent (tag, message) pair, and each writer's entries stay in order.
        val lastIndex = IntArray(threads) { -1 }
        for (e in snap) {
            val (t, i) = e.msg.split(':').map { it.toInt() }
            assertEquals("T$t", e.tag)
            assertTrue(i > lastIndex[t])
            lastIndex[t] = i
        }
        // Timestamps are monotonic (non-decreasing) in buffer order.
        assertTrue(snap.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
    }

    @Test
    fun appLogHonoursEnabledAndKeepsState() {
        DiagLog.clear()
        DiagLog.log(DiagLog.RAW, "visible")
        DiagLog.enabled = false
        var built = false
        DiagLog.log(DiagLog.RAW) { built = true; "hidden" }
        DiagLog.enabled = true
        assertFalse("disabled log must not build its message", built)
        assertEquals(1, DiagLog.count)
        assertTrue(DiagLog.formatEntries().contains("visible"))
        DiagLog.setState("canvas", "1200x1600")
        assertEquals("1200x1600", DiagLog.stateSnapshot()["canvas"])
        DiagLog.clear()
        assertEquals(0, DiagLog.count)
        assertEquals(DiagRingBuffer.DEFAULT_CAPACITY, DiagLog.capacity)
    }

    // ---------------- stroke summary ----------------

    @Test
    fun strokeSummaryAggregatesPointsPressureCpuAndFrames() {
        val s = StrokeStats()
        assertNull(s.end(0L, DirtyRect.EMPTY, false, 1f))
        s.begin(StrokeStats.Source.RAW, PointerKind.STYLUS, renderEnabledAtBegin = false, nowMs = 5_000L, eventTimeMs = 4_990L)
        s.point(0.2f, 819f)
        s.point(0.9f, 3686f)
        s.point(Float.NaN, Float.NaN) // lift sample
        s.addCpu(1_500_000L)
        s.addCpu(500_000L)
        s.frame(3, 400_000L, 600_000L)
        val line = s.end(5_120L, DirtyRect(1, 2, 30, 40), cancelled = false, brushRadius = 14.5f)!!
        assertFalse(s.active)
        for (part in listOf(
            "end", "src=RAW", "tool=STYLUS", "pts=3", "pMin=0.200", "pMax=0.900", "pRawMax=3686.000", "r=14.5",
            "dur=120ms", "paintCpu=2.00ms", "renderAtBegin=false", "lag=10ms", "dirty=1,2-30,40",
            "frames=1", "tiles=3", "upload=0.40ms", "draw=0.60ms",
        )) assertTrue("$part in $line", line.contains(part))
        // Updates outside a stroke are ignored.
        s.point(1f, 1f); s.addCpu(1L); s.frame(1, 1L, 1L)
        assertEquals(3, s.pointCount)
    }

    @Test
    fun cancelledStrokeWithoutPressureOrPlausibleTimestamp() {
        val s = StrokeStats()
        s.begin(StrokeStats.Source.MOTION, PointerKind.FINGER, renderEnabledAtBegin = true, nowMs = 100L, eventTimeMs = 0L)
        s.point(Float.NaN, Float.NaN)
        val line = s.end(150L, DirtyRect.EMPTY, cancelled = true, brushRadius = 12f)!!
        for (part in listOf("cancel", "src=MOTION", "tool=FINGER", "pMin=-", "pMax=-", "pRawMax=-", "lag=n/a", "dirty=-")) {
            assertTrue("$part in $line", line.contains(part))
        }
        assertFalse(line.contains("frames="))
    }

    @Test
    fun exportFileNameUsesTheLocalTimestamp() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("xiaonuo-diag-20261122-005854.txt", DiagLogExporter.fileName(1_795_309_134_000L, utc))
        assertTrue(DiagLogExporter.fileName(0L, utc).matches(Regex("""xiaonuo-diag-\d{8}-\d{6}\.txt""")))
    }
}
