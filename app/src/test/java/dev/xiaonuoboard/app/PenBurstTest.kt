package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.PenBurst
import dev.xiaonuoboard.app.eink.RefreshCoalescer
import dev.xiaonuoboard.app.engine.DirtyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-of-burst refresh bookkeeping of the BOOX hardware pen strokes (see ColoringCanvasView). */
class PenBurstTest {

    @Test
    fun emptyBurstWithoutBatchRefreshesNothing() {
        val b = PenBurst()
        assertTrue(b.isEmpty)
        assertNull(b.take(null))
        b.addStroke(DirtyRect.EMPTY)
        b.addTrace(DirtyRect.EMPTY)
        assertTrue(b.isEmpty)
    }

    @Test
    fun strokesAndTracesOfTheWholeBurstAccumulateIntoOneColourRefresh() {
        val b = PenBurst()
        b.addStroke(DirtyRect(10, 10, 50, 50))
        b.addTrace(DirtyRect(100, 100, 150, 150))
        b.addStroke(DirtyRect(40, 40, 90, 70))
        b.addTrace(DirtyRect(120, 90, 200, 140))
        val f = b.take(null)!!
        assertEquals(DirtyRect(10, 10, 90, 70), f.canvasRect)
        assertEquals(DirtyRect(100, 90, 200, 150), f.viewRect)
        assertEquals(EinkMode.NORMAL, f.mode)
        assertTrue("taken bursts are cleared", b.isEmpty)
        assertNull(b.take(null))
    }

    @Test
    fun traceOnlyBurstStillRefreshesTheTrace() {
        // e.g. a stroke fully clipped by 不涂出线 or drawn off the canvas image: nothing painted,
        // but the firmware's trace is on the panel.
        val b = PenBurst()
        b.addTrace(DirtyRect(5, 5, 25, 25))
        val f = b.take(null)!!
        assertTrue(f.canvasRect.isEmpty)
        assertEquals(DirtyRect(5, 5, 25, 25), f.viewRect)
        assertEquals(EinkMode.NORMAL, f.mode)
    }

    @Test
    fun aQueuedBatchIsFoldedInAndPenWorkIsNeverRefreshedWeakerThanNormal() {
        val b = PenBurst()
        b.addStroke(DirtyRect(0, 0, 10, 10))
        val f = b.take(RefreshCoalescer.Batch(DirtyRect(20, 20, 30, 30), EinkMode.FAST))!!
        assertEquals(DirtyRect(0, 0, 30, 30), f.canvasRect)
        assertEquals("DU would leave the hardware trace behind", EinkMode.NORMAL, f.mode)

        b.addTrace(DirtyRect(1, 1, 2, 2))
        assertEquals(EinkMode.REGAL, b.take(RefreshCoalescer.Batch(DirtyRect(0, 0, 5, 5), EinkMode.REGAL))!!.mode)
    }

    @Test
    fun batchWithoutPenWorkKeepsItsOwnMode() {
        val b = PenBurst()
        val f = b.take(RefreshCoalescer.Batch(DirtyRect(0, 0, 5, 5), EinkMode.FAST))!!
        assertEquals(EinkMode.FAST, f.mode)
        assertTrue(f.viewRect.isEmpty)
    }

    @Test
    fun clearDropsEverything() {
        val b = PenBurst()
        b.addStroke(DirtyRect(0, 0, 10, 10))
        b.addTrace(DirtyRect(0, 0, 10, 10))
        b.clear()
        assertTrue(b.isEmpty)
        assertNull(b.take(null))
    }
}
