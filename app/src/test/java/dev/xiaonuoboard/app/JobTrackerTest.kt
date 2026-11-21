package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.ui.JobTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The recycle-after-join guarantee used by CanvasScreen / AiWorkshopScreen. */
class JobTrackerTest {

    @Test
    fun joinAllWaitsForEveryTrackedJobBeforeRecycle() = runBlocking {
        val tracker = JobTracker()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val finished = AtomicInteger(0)
        tracker.track(launch(Dispatchers.Default) { gate1.await(); finished.incrementAndGet() })
        tracker.track(async(Dispatchers.Default) { gate2.await(); finished.incrementAndGet() })
        assertTrue(tracker.active)
        var recycledAfter = -1
        val recycler = launch(Dispatchers.Default) {
            tracker.joinAll()
            recycledAfter = finished.get()
        }
        gate1.complete(Unit)
        gate2.complete(Unit)
        recycler.join()
        assertEquals("recycle ran only after both readers finished", 2, recycledAfter)
        assertFalse(tracker.active)
    }

    @Test
    fun joinAllOnEmptyTrackerReturnsImmediately() = runBlocking {
        JobTracker().joinAll()
    }
}
