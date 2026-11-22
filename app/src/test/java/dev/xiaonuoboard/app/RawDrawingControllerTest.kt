package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.eink.RawDrawingBackend
import dev.xiaonuoboard.app.eink.RawDrawingController
import dev.xiaonuoboard.app.eink.RawPenListener
import dev.xiaonuoboard.app.eink.RawStrokeSink
import dev.xiaonuoboard.app.eink.UiScheduler
import dev.xiaonuoboard.app.engine.DirtyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle, stroke and post-stroke refresh state machine of the BOOX TouchHelper integration. */
class RawDrawingControllerTest {

    private class FakeBackend : RawDrawingBackend {
        val log = mutableListOf<String>()
        var opens = 0
        var openOk = true
        var enableOk = true
        var renderOk = true
        var lastLimit: DirtyRect? = null
        var lastExclude: List<DirtyRect> = emptyList()
        var lastStroke: Pair<Float, Int>? = null
        var lastPressureStyle: Boolean? = null
        var enabled = false
        var render = false
        var open = false

        override fun open(limit: DirtyRect, exclude: List<DirtyRect>, strokeWidth: Float, strokeColor: Int, pressureSensitive: Boolean): Boolean {
            opens++
            log += "open"
            lastLimit = limit; lastExclude = exclude; lastStroke = strokeWidth to strokeColor; lastPressureStyle = pressureSensitive
            open = openOk
            return openOk
        }
        override fun setLimit(limit: DirtyRect, exclude: List<DirtyRect>): Boolean {
            log += "limit"; lastLimit = limit; lastExclude = exclude; return true
        }
        override fun setEnabled(enabled: Boolean): Boolean {
            log += "enabled=$enabled"
            if (!enableOk) return false
            this.enabled = enabled; render = enabled; return true
        }
        override fun setRenderEnabled(enabled: Boolean): Boolean {
            log += "render=$enabled"
            if (!renderOk) return false
            render = enabled; return true
        }
        override fun setStroke(width: Float, color: Int, pressureSensitive: Boolean): Boolean {
            log += "stroke"; lastStroke = width to color; lastPressureStyle = pressureSensitive; return true
        }
        override fun close() { log += "close"; open = false; enabled = false; render = false }
    }

    private class FakeSink : RawStrokeSink {
        val events = mutableListOf<String>()
        val pressures = mutableListOf<Float>()
        val times = mutableListOf<Long>()
        override fun rawStrokeBegin(x: Float, y: Float, pressure: Float, time: Long, erasing: Boolean) {
            events += "begin(${x.toInt()},${y.toInt()}${if (erasing) ",erase" else ""})"
            pressures += pressure; times += time
        }
        override fun rawStrokeMove(x: Float, y: Float, pressure: Float, time: Long) {
            events += "move(${x.toInt()},${y.toInt()})"; pressures += pressure; times += time
        }
        override fun rawStrokeEnd(x: Float, y: Float, pressure: Float, time: Long) {
            events += "end(${x.toInt()},${y.toInt()})"; pressures += pressure; times += time
        }
        override fun rawStrokeAbort() { events += "abort" }
    }

    private class FakeScheduler : UiScheduler {
        val pending = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        override fun postDelayed(task: Runnable, delayMs: Long) { pending += task; delays += delayMs }
        override fun cancel(task: Runnable) { pending.removeAll { it === task } }
        fun runAll() { val copy = pending.toList(); pending.clear(); copy.forEach { it.run() } }
    }

    private val limit = DirtyRect(0, 0, 800, 1000)

    private inner class Rig(available: Boolean = true) {
        var isAvailable = available
        val backend = FakeBackend()
        val sink = FakeSink()
        val scheduler = FakeScheduler()
        var factoryCalls = 0
        var failure: String? = null
        var flushes = 0
        /** What the canvas does on a pen-up flush: hide the layer, refresh, resume after the frame. */
        var flushLikeCanvas = false
        lateinit var listener: RawPenListener
        val c: RawDrawingController = RawDrawingController(
            available = { isAvailable },
            backendFactory = { l -> factoryCalls++; listener = l; backend },
            sink = sink,
            scheduler = scheduler,
            onFailure = { failure = it },
            onPenUpFlush = {
                flushes++
                if (flushLikeCanvas) {
                    c.hidePenLayer()
                    c.schedulePenLayerResume()
                }
            },
        )

        /** Attached, laid out, focused, visible, crayon tool. */
        fun armed(): Rig {
            c.setStroke(12f, 0xFFFF0000.toInt())
            c.setWindowFocused(true)
            c.setVisible(true)
            c.setToolAllowsRaw(true)
            c.setAttached(true)
            c.setBounds(limit, emptyList(), 0, 100)
            return this
        }

        fun stroke(x0: Float = 10f, y0: Float = 10f) {
            c.onRawBegin(x0, y0, 1f, 0L, false)
            c.onRawMove(x0 + 5, y0 + 5, 1f, 1L, false)
            c.onRawEnd(x0 + 5, y0 + 5, 0f, 2L, false)
        }

        /** A committed hardware stroke as the canvas drives it (raw first, then the router's pen stroke). */
        fun canvasStroke(x0: Float = 10f, y0: Float = 10f) {
            c.onRawBegin(x0, y0, 1f, 0L, false)
            c.onPenStrokeStart()
            c.onRawMove(x0 + 5, y0 + 5, 1f, 1L, false)
            c.onRawEnd(x0 + 5, y0 + 5, 0f, 2L, false)
            c.onPenStrokeEnd(cancelled = false)
            c.schedulePenUpFlush()
        }
    }

    @Test
    fun nonBooxNeverCreatesBackend() {
        val r = Rig(available = false).armed()
        r.stroke()
        assertEquals(0, r.factoryCalls)
        assertFalse(r.c.isOpen)
        assertFalse(r.c.isEnabled)
        assertTrue("callbacks without a backend are ignored", r.sink.events.isEmpty())
    }

    @Test
    fun opensOnlyWhenAttachedAndLaidOutAndEnablesOnlyWhenEverythingAllows() {
        val r = Rig()
        r.c.setWindowFocused(true); r.c.setVisible(true); r.c.setToolAllowsRaw(true)
        r.c.setAttached(true)
        assertFalse("no layout yet", r.c.isOpen)
        r.c.setBounds(limit, emptyList(), 0, 100)
        assertTrue(r.c.isOpen)
        assertTrue(r.c.isEnabled)
        assertEquals(listOf("open", "enabled=true"), r.backend.log)
        assertEquals(limit, r.backend.lastLimit)

        r.c.setWindowFocused(false)
        assertFalse(r.c.isEnabled)
        assertTrue(r.c.isOpen)
        r.c.setWindowFocused(true)
        r.c.setVisible(false)
        assertFalse(r.c.isEnabled)
        r.c.setVisible(true)
        r.c.setHostResumed(false)
        assertFalse(r.c.isEnabled)
        r.c.setHostResumed(true)
        assertTrue(r.c.isEnabled)
    }

    @Test
    fun bucketToolNeverArmsRawDrawing() {
        val r = Rig().armed()
        r.c.setToolAllowsRaw(false) // paint bucket
        assertTrue(r.c.isOpen)
        assertFalse(r.c.isEnabled)
        assertFalse(r.backend.enabled)
        r.stroke()
        assertTrue("bucket taps must reach the MotionEvent path", r.sink.events.isEmpty())
        r.c.setToolAllowsRaw(true) // back to crayon
        assertTrue(r.backend.enabled)
    }

    @Test
    fun overlayOrDialogSuspendsAndAbortsAnActiveStroke() {
        val r = Rig().armed()
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        r.c.onRawMove(20f, 20f, 1f, 1L, false)
        r.c.setSuspended(true) // e.g. reset confirmation / enlarged reference shown
        assertFalse(r.backend.enabled)
        assertEquals(listOf("begin(10,10)", "move(20,20)", "abort"), r.sink.events)
        // Callbacks still queued from before the pause are dropped.
        r.c.onRawMove(30f, 30f, 1f, 2L, false)
        r.c.onRawEnd(30f, 30f, 0f, 3L, false)
        r.c.onRawBegin(40f, 40f, 1f, 4L, false)
        assertEquals(3, r.sink.events.size)
        r.c.setSuspended(false)
        assertTrue(r.backend.enabled)
    }

    @Test
    fun detachClosesAndReattachReopensReleaseIsFinal() {
        val r = Rig().armed()
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        r.c.setAttached(false)
        assertFalse(r.c.isOpen)
        assertFalse(r.backend.open)
        assertEquals("abort", r.sink.events.last())
        r.c.setAttached(true)
        assertTrue(r.c.isOpen)
        assertTrue(r.c.isEnabled)
        assertEquals(2, r.backend.opens)
        assertEquals("backend object reused", 1, r.factoryCalls)
        r.c.release()
        assertFalse(r.c.isOpen)
        r.c.setAttached(true)
        r.c.setBounds(limit, emptyList(), 0, 0)
        assertFalse("released controllers never reopen", r.c.isOpen)
        assertEquals(2, r.backend.opens)
    }

    @Test
    fun emptyLimitCloses() {
        val r = Rig().armed()
        r.c.setBounds(DirtyRect.EMPTY, emptyList(), 0, 0)
        assertFalse(r.c.isOpen)
        r.c.setBounds(limit, emptyList(), 0, 100)
        assertTrue(r.c.isEnabled)
    }

    @Test
    fun strokesAreForwardedOrphansDroppedAndPointListOnlyUsedWithoutMoves() {
        val r = Rig().armed()
        r.c.onRawMove(1f, 1f, 1f, 0L, false) // orphan
        r.c.onRawEnd(1f, 1f, 0f, 0L, false) // orphan
        r.stroke(10f, 10f)
        assertEquals(listOf("begin(10,10)", "move(15,15)", "end(15,15)"), r.sink.events)

        r.sink.events.clear()
        r.c.onRawBegin(50f, 50f, 1f, 0L, false)
        r.c.onRawMove(55f, 55f, 1f, 0L, false)
        r.c.onRawPoints(floatArrayOf(50f, 55f), floatArrayOf(50f, 55f), floatArrayOf(1f, 1f), longArrayOf(0, 1), false)
        r.c.onRawEnd(55f, 55f, 0f, 0L, false)
        assertEquals("list ignored when moves arrived", listOf("begin(50,50)", "move(55,55)", "end(55,55)"), r.sink.events)

        r.sink.events.clear()
        r.c.onRawBegin(60f, 60f, 1f, 0L, true)
        r.c.onRawPoints(floatArrayOf(60f, 70f), floatArrayOf(60f, 70f), floatArrayOf(1f, 1f), longArrayOf(0, 1), true)
        r.c.onRawEnd(70f, 70f, 0f, 0L, true)
        assertEquals(listOf("begin(60,60,erase)", "move(60,60)", "move(70,70)", "end(70,70)"), r.sink.events)
    }

    @Test
    fun beginWithoutEndAbortsThePreviousStroke() {
        val r = Rig().armed()
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        r.c.onRawBegin(20f, 20f, 1f, 0L, false)
        assertEquals(listOf("begin(10,10)", "abort", "begin(20,20)"), r.sink.events)
        assertTrue(r.c.isRawStrokeActive)
    }

    @Test
    fun postStrokeHandshakeHidesPenLayerThenResumesAfterRefresh() {
        val r = Rig().armed()
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        r.c.onPenStrokeStart()
        r.c.onRawEnd(10f, 10f, 0f, 0L, false)
        r.c.onPenStrokeEnd(cancelled = false)
        r.backend.log.clear()
        r.c.hidePenLayer()
        assertEquals(listOf("render=false"), r.backend.log)
        assertFalse(r.c.isRenderEnabled)
        assertTrue("input keeps flowing", r.c.isEnabled)
        r.c.schedulePenLayerResume()
        assertEquals(1, r.scheduler.pending.size)
        r.scheduler.runAll()
        assertEquals(listOf("render=false", "render=true"), r.backend.log)
        assertTrue(r.c.isRenderEnabled)
    }

    @Test
    fun nextStrokeBeforeResumeReEnablesPenLayerImmediately() {
        val r = Rig().armed()
        r.c.hidePenLayer()
        r.c.schedulePenLayerResume()
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        assertTrue(r.c.isRenderEnabled)
        assertTrue("scheduled resume cancelled", r.scheduler.pending.isEmpty())
        // A MotionEvent-owned pen stroke does the same.
        r.c.onRawEnd(10f, 10f, 0f, 0L, false)
        r.c.hidePenLayer()
        r.c.onPenStrokeStart()
        assertTrue(r.c.isRenderEnabled)
    }

    @Test
    fun handshakeIsNoOpWhenDisabled() {
        val r = Rig().armed()
        r.c.setToolAllowsRaw(false)
        r.backend.log.clear()
        r.c.hidePenLayer()
        r.c.schedulePenLayerResume()
        assertTrue(r.backend.log.isEmpty())
        assertTrue(r.scheduler.pending.isEmpty())
    }

    @Test
    fun boundsChangesUpdateLimitAndMovesReanchorTheReader() {
        val r = Rig().armed()
        r.backend.log.clear()
        r.c.setBounds(limit, emptyList(), 0, 100) // unchanged
        assertTrue(r.backend.log.isEmpty())
        val overlay = DirtyRect(700, 0, 800, 80)
        r.c.setBounds(limit, listOf(overlay), 0, 100)
        assertEquals(listOf("limit"), r.backend.log)
        assertEquals(listOf(overlay), r.backend.lastExclude)

        r.backend.log.clear()
        r.c.setBounds(limit, listOf(overlay), 0, 160) // view moved on screen
        assertEquals(listOf("enabled=false", "enabled=true"), r.backend.log)

        // Mid-stroke moves are deferred to the stroke end.
        r.backend.log.clear()
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        r.c.setBounds(limit, listOf(overlay), 0, 200)
        assertTrue(r.backend.log.isEmpty())
        r.c.onRawEnd(10f, 10f, 0f, 0L, false)
        assertEquals(listOf("enabled=false", "enabled=true"), r.backend.log)
        assertEquals("stroke was not aborted", "end(10,10)", r.sink.events.last())
    }

    @Test
    fun strokeParamsArePassedOnOpenAndDeduped() {
        val r = Rig().armed()
        assertEquals(12f to 0xFFFF0000.toInt(), r.backend.lastStroke)
        r.backend.log.clear()
        r.c.setStroke(12f, 0xFFFF0000.toInt())
        assertTrue(r.backend.log.isEmpty())
        r.c.setStroke(24f, 0xFFFFFFFF.toInt())
        assertEquals(listOf("stroke"), r.backend.log)
        assertEquals(24f to 0xFFFFFFFF.toInt(), r.backend.lastStroke)
    }

    @Test
    fun openFailureDegradesPermanentlyAndReports() {
        val r = Rig()
        r.backend.openOk = false
        r.armed()
        assertFalse(r.c.isOpen)
        assertTrue(r.c.isFailed)
        assertNotNull(r.failure)
        r.c.setAttached(false); r.c.setAttached(true)
        assertEquals("no retry storm", 1, r.backend.opens)
    }

    @Test
    fun enableFailureClosesAndReports() {
        val r = Rig()
        r.backend.enableOk = false
        r.armed()
        assertTrue(r.c.isFailed)
        assertFalse(r.c.isOpen)
        assertTrue(r.backend.log.contains("close"))
    }

    @Test
    fun hidingCancelsAPendingResumeSoAStaleTimerCannotFireUnderANewRefresh() {
        val r = Rig().armed()
        r.c.hidePenLayer() // stroke 1 pen-up
        r.c.schedulePenLayerResume() // resume due at t+32
        assertEquals(1, r.scheduler.pending.size)
        r.c.hidePenLayer() // t+25: undo / clear-ghosting refresh
        assertTrue("stale resume cancelled", r.scheduler.pending.isEmpty())
        assertFalse(r.c.isRenderEnabled)
        r.c.schedulePenLayerResume()
        r.c.schedulePenLayerResume() // duplicate schedules collapse into one
        assertEquals(1, r.scheduler.pending.size)
        r.scheduler.runAll()
        assertTrue(r.c.isRenderEnabled)
    }

    @Test
    fun fingerGestureHoldsThePenLayerHiddenUntilItEnds() {
        val r = Rig().armed()
        r.backend.log.clear()
        r.c.setFingerGesture(true) // pinch / pan / finger painting starts
        assertEquals(listOf("render=false"), r.backend.log)
        r.c.hidePenLayer(); r.c.schedulePenLayerResume() // per-frame refreshes during the gesture
        assertTrue("no resume while the gesture holds the layer", r.scheduler.pending.isEmpty())
        r.c.setFingerGesture(false)
        assertEquals(1, r.scheduler.pending.size)
        r.scheduler.runAll()
        assertTrue(r.c.isRenderEnabled)
    }

    @Test
    fun staleResumeFiringDuringAFingerGestureIsInert() {
        val r = Rig().armed()
        r.c.hidePenLayer()
        r.c.schedulePenLayerResume()
        val stale = r.scheduler.pending.single()
        r.c.setFingerGesture(true)
        stale.run() // even if a runnable escaped cancellation
        assertFalse(r.c.isRenderEnabled)
    }

    @Test
    fun penBeatsAFingerHoldAndARestingPalmNeverHidesALiveTrace() {
        val r = Rig().armed()
        r.c.setFingerGesture(true) // palm lands first
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        assertTrue(r.c.isRenderEnabled)
        assertFalse(r.c.isFingerGestureActive)
        r.backend.log.clear()
        r.c.setFingerGesture(true) // palm lands during the raw stroke
        assertTrue(r.backend.log.isEmpty())
        assertTrue(r.c.isRenderEnabled)
        // MotionEvent-owned pen stroke also clears the hold.
        r.c.onRawEnd(10f, 10f, 0f, 0L, false)
        r.c.setFingerGesture(true)
        r.c.onPenStrokeStart()
        assertTrue(r.c.isRenderEnabled)
        assertFalse(r.c.isFingerGestureActive)
    }

    @Test
    fun rawBeginDoesNotStartAStrokeWhenResumingThePenLayerFails() {
        val r = Rig().armed()
        r.c.hidePenLayer()
        r.backend.renderOk = false
        r.c.onRawBegin(10f, 10f, 1f, 0L, false)
        assertTrue(r.c.isFailed)
        assertFalse(r.c.isRawStrokeActive)
        assertTrue("no orphan RAW pointer in the router", r.sink.events.isEmpty())
    }

    @Test
    fun motionOnlyStrokesNeverDisableAWorkingTouchHelper() {
        val r = Rig().armed()
        r.stroke() // TouchHelper proved it works
        repeat(RawDrawingController.MAX_MISSES * 3) {
            // e.g. pen-downs the native reader drops at the canvas / exclude edges
            r.c.onPenStrokeStart()
            r.c.onPenStrokeEnd(cancelled = false)
        }
        assertNull(r.failure)
        assertTrue(r.c.isEnabled)
    }

    @Test
    fun touchHelperThatNeverDeliversFallsBackAfterThreeMisses() {
        val r = Rig().armed()
        repeat(RawDrawingController.MAX_MISSES - 1) {
            r.c.onPenStrokeStart() // MotionEvent-only pen stroke
            r.c.onPenStrokeEnd(cancelled = false)
        }
        assertNull(r.failure)
        assertTrue(r.c.isOpen)
        r.c.onPenStrokeStart()
        r.c.onPenStrokeEnd(cancelled = true) // cancelled strokes do not count
        assertNull(r.failure)
        r.c.onPenStrokeStart()
        r.c.onPenStrokeEnd(cancelled = false)
        assertNotNull(r.failure)
        assertFalse(r.c.isOpen)
        assertTrue(r.c.isFailed)
    }

    @Test
    fun rawFirstOrRawDuringMotionStrokeIsNotAMiss() {
        val r = Rig().armed()
        repeat(5) {
            // Raw first: the sink starts the pen stroke while the raw stroke is active.
            r.c.onRawBegin(10f, 10f, 1f, 0L, false)
            r.c.onPenStrokeStart()
            r.c.onRawEnd(10f, 10f, 0f, 0L, false)
            r.c.onPenStrokeEnd(cancelled = false)
            // MotionEvent first, raw begin arrives during the stroke.
            r.c.onPenStrokeStart()
            r.c.onRawBegin(10f, 10f, 1f, 0L, false)
            r.c.onRawEnd(10f, 10f, 0f, 0L, false)
            r.c.onPenStrokeEnd(cancelled = false)
        }
        assertEquals(0, r.c.consecutiveMisses)
        assertNull(r.failure)
    }

    // ---------------- pen-up refresh debounce (stroke latency jitter fix) ----------------

    @Test
    fun rapidStrokesKeepTheHardwarePenLayerLiveAndRefreshOnceAtTheEndOfTheBurst() {
        val r = Rig().armed()
        r.flushLikeCanvas = true
        r.backend.log.clear()
        // A child colouring back and forth: 6 strokes, pen lifted ~100 ms between them.
        repeat(6) { i ->
            r.canvasStroke(10f + i * 20, 10f)
            assertTrue(r.c.isPenUpFlushPending)
            assertEquals("one debounce timer", 1, r.scheduler.pending.size)
            assertEquals(RawDrawingController.PEN_UP_FLUSH_DELAY_MS, r.scheduler.delays.last())
        }
        assertTrue(
            "the firmware pen layer was never paused inside the burst, so every stroke was drawn instantly",
            r.backend.log.isEmpty(),
        )
        assertTrue(r.c.isRenderEnabled)
        assertEquals(0, r.flushes)

        r.scheduler.runAll() // ~450 ms of pen idle
        assertEquals("ONE refresh for the whole burst", 1, r.flushes)
        assertFalse(r.c.isPenUpFlushPending)
        assertEquals(listOf("render=false"), r.backend.log)
        r.scheduler.runAll() // resume after the refresh's frame
        assertEquals(listOf("render=false", "render=true"), r.backend.log)
        assertTrue(r.c.isRenderEnabled)
    }

    @Test
    fun aStrokeStartingBeforeTheIdleTimeoutCancelsItWithoutTouchingTheFirmware() {
        val r = Rig().armed()
        r.canvasStroke()
        r.backend.log.clear()
        r.c.onRawBegin(30f, 30f, 1f, 0L, false)
        assertTrue("debounce timer cancelled by pen-down", r.scheduler.pending.isEmpty())
        assertTrue("burst still pending while the next stroke draws", r.c.isPenUpFlushPending)
        r.c.onPenStrokeStart()
        assertTrue(r.backend.log.isEmpty())
        r.c.onRawEnd(30f, 30f, 0f, 1L, false)
        r.c.onPenStrokeEnd(cancelled = false)
        r.c.schedulePenUpFlush()
        assertEquals(1, r.scheduler.pending.size)
        assertEquals(0, r.flushes)
    }

    @Test
    fun motionEventOwnedPenStrokeAlsoCancelsTheIdleTimeout() {
        val r = Rig().armed()
        r.canvasStroke()
        r.c.onPenStrokeStart() // MotionEvent beat the raw callback
        assertTrue(r.scheduler.pending.isEmpty())
        assertEquals(0, r.flushes)
    }

    @Test
    fun penUpFlushFiresAtOnceWhenNothingKeepsThePenLayerLive() {
        val r = Rig().armed()
        r.c.hidePenLayer() // e.g. layer hidden for an undo refresh
        r.c.schedulePenUpFlush()
        assertEquals(1, r.flushes)
        assertFalse(r.c.isPenUpFlushPending)

        r.c.setToolAllowsRaw(false)
        r.c.schedulePenUpFlush() // MotionEvent-owned stroke ending after raw was disabled
        assertEquals(2, r.flushes)
    }

    @Test
    fun toolColourOrBrushChangeFlushesThePendingBurst() {
        val r = Rig().armed()
        r.canvasStroke()
        r.c.setStroke(12f, 0xFFFF0000.toInt()) // unchanged (recomposition)
        assertEquals(0, r.flushes)
        assertTrue(r.c.isPenUpFlushPending)
        r.c.setStroke(12f, 0xFF00FF00.toInt()) // colour picked from the palette
        assertEquals(1, r.flushes)
        assertFalse(r.c.isPenUpFlushPending)
        assertTrue("debounce timer cancelled", r.scheduler.pending.isEmpty())
        r.c.setStroke(18f, 0xFF00FF00.toInt()) // no burst pending: nothing to flush
        assertEquals(1, r.flushes)
    }

    @Test
    fun disablingRawDrawingFlushesThePendingBurst() {
        for (disable in listOf<(RawDrawingController) -> Unit>(
            { it.setToolAllowsRaw(false) }, // paint bucket
            { it.setSuspended(true) }, // reset confirmation / enlarged reference
            { it.setHostResumed(false) }, // activity paused
            { it.setWindowFocused(false) }, // dialog / notification shade
        )) {
            val r = Rig().armed()
            r.canvasStroke()
            disable(r.c)
            assertFalse(r.c.isEnabled)
            assertEquals(1, r.flushes)
            assertFalse(r.c.isPenUpFlushPending)
            assertTrue(r.scheduler.pending.isEmpty())
        }
    }

    @Test
    fun fingerGestureFlushesTheBurstAndHoldsThePenLayerHidden() {
        val r = Rig().armed()
        r.flushLikeCanvas = true
        r.canvasStroke()
        r.backend.log.clear()
        r.c.setFingerGesture(true) // pan / zoom actually moved the picture
        assertEquals(1, r.flushes)
        assertEquals(listOf("render=false"), r.backend.log)
        assertTrue("held hidden during the gesture", r.scheduler.pending.isEmpty())
        r.c.setFingerGesture(false)
        r.scheduler.runAll()
        assertTrue(r.c.isRenderEnabled)
    }

    @Test
    fun anUnrelatedRefreshFoldsTheBurstInAndCancelsTheTimer() {
        val r = Rig().armed()
        r.canvasStroke()
        r.c.hidePenLayer() // undo / fill refresh issued by the canvas covers the burst
        assertFalse(r.c.isPenUpFlushPending)
        assertTrue(r.scheduler.pending.isEmpty())
        assertEquals("the caller refreshes, no second flush", 0, r.flushes)
        r.canvasStroke()
        r.c.cancelPenUpFlush() // the canvas flush took the burst
        assertFalse(r.c.isPenUpFlushPending)
        assertTrue(r.scheduler.pending.isEmpty())
        assertEquals(0, r.flushes)
    }

    @Test
    fun detachOrReleaseDropsTheBurstWithoutRefreshingADeadView() {
        val r = Rig().armed()
        r.canvasStroke()
        r.c.setAttached(false)
        assertFalse(r.c.isPenUpFlushPending)
        assertTrue(r.scheduler.pending.isEmpty())
        assertEquals(0, r.flushes)
        r.c.setAttached(true)
        r.canvasStroke()
        r.c.release()
        assertEquals(0, r.flushes)
        assertTrue(r.scheduler.pending.isEmpty())
    }

    @Test
    fun failureStillRefreshesTheBurstsHardwareTrace() {
        val r = Rig().armed()
        r.canvasStroke()
        r.backend.enableOk = false
        r.c.setToolAllowsRaw(false) // setRawDrawingEnabled(false) throws → raw drawing shut down
        assertTrue(r.c.isFailed)
        assertEquals(1, r.flushes)
        assertFalse(r.c.isPenUpFlushPending)
    }

    @Test
    fun flushNowIsIdempotentAndAStaleTimerIsInert() {
        val r = Rig().armed()
        r.canvasStroke()
        val timer = r.scheduler.pending.single()
        r.c.flushPenUpNow()
        r.c.flushPenUpNow()
        assertEquals(1, r.flushes)
        timer.run() // even if a runnable escaped cancellation
        assertEquals(1, r.flushes)
    }

    @Test
    fun resumeDelayIsConfigurableForTheFrameDrawnHandshake() {
        val r = Rig().armed()
        r.c.hidePenLayer()
        r.c.schedulePenLayerResume(RawDrawingController.RENDER_RESUME_FALLBACK_MS) // at refresh time
        r.c.schedulePenLayerResume() // refresh frame drawn: replaces the fallback
        assertEquals(1, r.scheduler.pending.size)
        assertEquals(
            listOf(RawDrawingController.RENDER_RESUME_FALLBACK_MS, RawDrawingController.RENDER_RESUME_DELAY_MS),
            r.scheduler.delays,
        )
        r.scheduler.runAll()
        assertTrue(r.c.isRenderEnabled)
    }

    @Test
    fun debounceCoversBurstPenLiftsWithoutFeelingSluggish() {
        assertTrue(RawDrawingController.PEN_UP_FLUSH_DELAY_MS in 500L..1000L)
        assertTrue(RawDrawingController.RENDER_RESUME_FALLBACK_MS > RawDrawingController.RENDER_RESUME_DELAY_MS)
    }
    // ---------------- pressure propagation / pressure pen style ----------------

    @Test
    fun rawPressureAndTimestampsReachTheSinkUnchanged() {
        val r = Rig().armed()
        r.c.onRawBegin(10f, 10f, 812f, 100L, false)
        r.c.onRawMove(12f, 12f, 2048f, 101L, false)
        r.c.onRawMove(14f, 14f, 4095f, 102L, false)
        r.c.onRawEnd(14f, 14f, 0f, 103L, false)
        assertEquals(listOf(812f, 2048f, 4095f, 0f), r.sink.pressures)
        assertEquals(listOf(100L, 101L, 102L, 103L), r.sink.times)
    }

    @Test
    fun pointListPressuresAreForwardedWhenNoMovesArrived() {
        val r = Rig().armed()
        r.c.onRawBegin(10f, 10f, 500f, 0L, false)
        r.c.onRawPoints(floatArrayOf(11f, 12f), floatArrayOf(11f, 12f), floatArrayOf(1000f, 3000f), longArrayOf(1L, 2L), false)
        r.c.onRawEnd(12f, 12f, 0f, 3L, false)
        assertEquals(listOf(500f, 1000f, 3000f, 0f), r.sink.pressures)
        // A mismatched (shorter) pressure array is truncated, never read out of bounds.
        r.sink.pressures.clear()
        r.c.onRawBegin(10f, 10f, 1f, 4L, false)
        r.c.onRawPoints(floatArrayOf(11f, 12f, 13f), floatArrayOf(11f, 12f, 13f), floatArrayOf(7f), longArrayOf(5L, 6L, 7L), false)
        assertEquals(listOf(1f, 7f), r.sink.pressures)
    }

    @Test
    fun pressureStyleIsPassedToOpenAndStrokeChangesAndFlushesTheBurst() {
        val r = Rig()
        r.c.setStroke(16f, 0xFFFF0000.toInt(), pressureSensitive = true)
        r.armed() // armed() re-sets (12, red) without the flag → the eraser-like constant style
        assertEquals(false, r.backend.lastPressureStyle)
        r.c.setStroke(16f, 0xFFFF0000.toInt(), pressureSensitive = true)
        assertEquals(true, r.backend.lastPressureStyle)
        assertEquals(16f to 0xFFFF0000.toInt(), r.backend.lastStroke)
        // Only the style changes (crayon → same-size eraser colour stays): still pushed + burst flushed.
        r.canvasStroke()
        r.backend.log.clear()
        r.c.setStroke(16f, 0xFFFF0000.toInt(), pressureSensitive = false)
        assertEquals(listOf("stroke"), r.backend.log.filter { it == "stroke" })
        assertEquals(false, r.backend.lastPressureStyle)
        assertEquals(1, r.flushes)
        // Re-opening after a close keeps the current style.
        r.c.setStroke(16f, 0xFFFF0000.toInt(), pressureSensitive = true)
        r.c.setAttached(false)
        r.c.setAttached(true)
        assertEquals(true, r.backend.lastPressureStyle)
        assertEquals(2, r.backend.opens)
    }

    @Test
    fun failedFirstOpenPublishesTheFailedStateForTheDiagnosticHeader() {
        dev.xiaonuoboard.app.eink.DiagLog.setState("rawDrawing", "stale")
        val r = Rig()
        r.backend.openOk = false
        r.armed()
        assertTrue(r.c.isFailed)
        assertTrue(dev.xiaonuoboard.app.eink.DiagLog.stateSnapshot()["rawDrawing"]!!.contains("failed=true"))
    }

    @Test
    fun beginRecordsWhetherThePenLayerWasAlreadyLive() {
        val r = Rig().armed()
        r.stroke()
        assertTrue(r.c.lastBeginRenderWasEnabled)
        r.c.hidePenLayer()
        r.stroke()
        assertFalse("layer was hidden at pen-down", r.c.lastBeginRenderWasEnabled)
        assertTrue("…and is resumed by the pen-down", r.c.isRenderEnabled)
    }
}
