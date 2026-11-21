package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.RefreshCoalescer
import dev.xiaonuoboard.app.engine.DirtyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshCoalescerTest {

    @Test
    fun manySamplesInOneFrameBecomeOneRefresh() {
        val c = RefreshCoalescer()
        var flushesScheduled = 0
        // e.g. 8 historical stylus samples delivered in one MotionEvent batch.
        for (k in 0 until 8) {
            if (c.request(DirtyRect(100 + k * 5, 200, 130 + k * 5, 230), EinkMode.FAST)) flushesScheduled++
        }
        assertEquals(1, flushesScheduled)
        val batch = c.drain()!!
        assertEquals(DirtyRect(100, 200, 165, 230), batch.rect)
        assertEquals(EinkMode.FAST, batch.mode)
        assertNull(c.drain())
        assertFalse(c.hasPending)
    }

    @Test
    fun strongestModeWinsRegardlessOfOrder() {
        val c = RefreshCoalescer()
        assertTrue(c.request(DirtyRect(0, 0, 10, 10), EinkMode.FAST))
        assertFalse(c.request(DirtyRect(5, 5, 20, 20), EinkMode.NORMAL))
        assertFalse(c.request(DirtyRect(0, 0, 1, 1), EinkMode.FAST))
        val b = c.drain()!!
        assertEquals(EinkMode.NORMAL, b.mode)
        assertEquals(DirtyRect(0, 0, 20, 20), b.rect)

        assertTrue(c.request(DirtyRect(0, 0, 10, 10), EinkMode.FULL))
        c.request(DirtyRect(0, 0, 10, 10), EinkMode.FAST)
        c.request(DirtyRect(0, 0, 10, 10), EinkMode.REGAL)
        assertEquals(EinkMode.FULL, c.drain()!!.mode)
    }

    @Test
    fun emptyRectsNeverOpenABatch() {
        val c = RefreshCoalescer()
        assertFalse(c.request(DirtyRect.EMPTY, EinkMode.NORMAL))
        assertFalse(c.request(10, 10, 10, 50, EinkMode.NORMAL))
        assertFalse(c.hasPending)
        assertNull(c.drain())
        // An empty rect does not widen an open batch either.
        assertTrue(c.request(10, 10, 20, 20, EinkMode.FAST))
        assertFalse(c.request(DirtyRect.EMPTY, EinkMode.FULL))
        val b = c.drain()!!
        assertEquals(DirtyRect(10, 10, 20, 20), b.rect)
        assertEquals(EinkMode.FAST, b.mode)
    }

    @Test
    fun drainAndClearStartAFreshBatch() {
        val c = RefreshCoalescer()
        assertTrue(c.request(DirtyRect(0, 0, 5, 5), EinkMode.NORMAL))
        c.drain()
        assertTrue(c.request(DirtyRect(50, 50, 60, 60), EinkMode.FAST))
        assertEquals(DirtyRect(50, 50, 60, 60), c.drain()!!.rect)
        assertTrue(c.request(DirtyRect(0, 0, 5, 5), EinkMode.NORMAL))
        c.clear()
        assertFalse(c.hasPending)
        assertTrue(c.request(DirtyRect(1, 1, 2, 2), EinkMode.FAST))
    }

    @Test
    fun rankOrdering() {
        assertTrue(RefreshCoalescer.rank(EinkMode.FAST) < RefreshCoalescer.rank(EinkMode.NORMAL))
        assertTrue(RefreshCoalescer.rank(EinkMode.NORMAL) < RefreshCoalescer.rank(EinkMode.REGAL))
        assertTrue(RefreshCoalescer.rank(EinkMode.REGAL) < RefreshCoalescer.rank(EinkMode.FULL))
        assertEquals(EinkMode.NORMAL, RefreshCoalescer.stronger(EinkMode.NORMAL, EinkMode.FAST))
        assertEquals(EinkMode.NORMAL, RefreshCoalescer.stronger(EinkMode.FAST, EinkMode.NORMAL))
    }
}
