package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.InputRouter
import dev.xiaonuoboard.app.engine.InputRouter.Mode
import dev.xiaonuoboard.app.engine.PointerKind
import dev.xiaonuoboard.app.engine.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pen priority, palm rejection, pinch and stylus-only routing (identity screen→canvas mapping). */
class InputRouterTest {
    private val red = 0xFFE61919.toInt()

    private class Rec : InputRouter.Listener {
        var transforms = 0
        var lastScale = 1f
        var strokeStarts = 0
        var strokeEnds = 0
        var cancelled = 0
        var fills = 0
        override fun onTransform(scale: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) { transforms++; lastScale = scale }
        override fun onStrokeStart(kind: PointerKind, x: Float, y: Float, pressure: Float, time: Long) { strokeStarts++ }
        override fun onStrokeEnd(rect: DirtyRect, x: Float, y: Float, pressure: Float, time: Long, cancelled: Boolean) {
            strokeEnds++; if (cancelled) this.cancelled++
        }
        override fun onFilled(rect: DirtyRect) { fills++ }
    }

    private fun setup(tool: Tool = Tool.CRAYON, stylusOnly: Boolean = false): Triple<ColoringCanvasController, InputRouter, Rec> {
        val (ink, w, h) = TestArt.twoBoxes()
        val c = ColoringCanvasController(w, h, ink, closeRadius = 0)
        c.tool = tool
        c.stylusOnly = stylusOnly
        c.stayInsideLines = false
        c.color = red
        val rec = Rec()
        return Triple(c, InputRouter(c, rec, tapSlop = 4f, palmTouchMajor = 50f), rec)
    }

    @Test
    fun liftingStylusWhilePalmRestsEndsStrokeAndPalmNeverPaints() {
        val (c, r, rec) = setup()
        r.down(0, PointerKind.STYLUS, 30f, 40f)
        r.move(0, 32f, 42f)
        r.down(1, PointerKind.FINGER, 70f, 40f) // palm lands on the right box
        assertEquals(Mode.STROKE, r.mode)
        r.move(1, 72f, 45f)
        r.up(0, 32f, 42f) // pen lifts, palm stays
        assertEquals(1, rec.strokeEnds)
        assertEquals(0, rec.cancelled)
        r.move(1, 75f, 50f)
        r.move(1, 80f, 60f)
        r.up(1, 80f, 60f)
        assertEquals(red, c.paint[42 * c.width + 32])
        assertEquals("palm painted the right box", 0, c.paint[45 * c.width + 72])
        assertEquals(0, c.paint[60 * c.width + 80])
        assertEquals("no zoom while pen + palm", 0, rec.transforms)
        assertEquals(Mode.IDLE, r.mode)
    }

    @Test
    fun palmFirstThenStylusStillDraws() {
        val (c, r, rec) = setup()
        r.down(0, PointerKind.FINGER, 70f, 40f) // palm rests first (finger stroke starts)
        r.move(0, 71f, 41f)
        r.down(1, PointerKind.STYLUS, 25f, 30f) // pen arrives as secondary pointer
        assertEquals(Mode.STROKE, r.mode)
        assertEquals(1, r.activeId)
        assertEquals("finger stroke rolled back", 1, rec.cancelled)
        assertEquals(0, c.paint[41 * c.width + 71])
        r.move(1, 30f, 35f)
        r.move(0, 80f, 50f) // palm drifts: ignored
        r.up(1, 30f, 35f)
        r.up(0, 80f, 50f)
        assertEquals(red, c.paint[35 * c.width + 30])
        assertEquals(0, c.paint[50 * c.width + 80])
        assertEquals(1, c.undoStack.size)
    }

    @Test
    fun palmFirstThenStylusTapFillsInFillMode() {
        val (c, r, rec) = setup(tool = Tool.FILL)
        r.down(0, PointerKind.FINGER, 70f, 40f)
        r.down(1, PointerKind.STYLUS, 30f, 40f)
        r.up(1, 30f, 40f)
        r.up(0, 70f, 40f)
        assertEquals(1, rec.fills)
        assertEquals(red, c.paint[40 * c.width + 30])
        assertEquals("finger tap was superseded by the pen", 0, c.paint[40 * c.width + 70])
    }

    @Test
    fun twoFingersPinchAndCancelFingerStroke() {
        val (c, r, rec) = setup()
        r.down(0, PointerKind.FINGER, 20f, 20f)
        r.move(0, 22f, 22f)
        r.down(1, PointerKind.FINGER, 60f, 60f)
        assertEquals(Mode.PINCH, r.mode)
        assertEquals(1, rec.cancelled)
        r.move(1, 80f, 80f)
        assertTrue(rec.transforms > 0)
        assertTrue(rec.lastScale > 1f)
        r.up(1, 80f, 80f)
        r.up(0, 22f, 22f)
        assertTrue(c.paint.all { it == 0 })
        assertEquals(0, c.undoStack.size)
    }

    @Test
    fun stylusDuringPinchTakesOver() {
        val (c, r, _) = setup()
        r.down(0, PointerKind.FINGER, 20f, 20f)
        r.down(1, PointerKind.FINGER, 60f, 60f)
        r.down(2, PointerKind.STYLUS, 30f, 40f)
        assertEquals(Mode.STROKE, r.mode)
        r.move(0, 10f, 10f) // fingers ignored now
        r.up(2, 30f, 40f)
        assertEquals(red, c.paint[40 * c.width + 30])
    }

    @Test
    fun largeContactIsTreatedAsPalm() {
        val (c, r, rec) = setup()
        r.down(0, PointerKind.FINGER, 30f, 40f, touchMajor = 200f)
        r.move(0, 35f, 45f)
        r.up(0, 35f, 45f)
        assertEquals(0, rec.strokeStarts)
        assertTrue(c.paint.all { it == 0 })
    }

    @Test
    fun stylusOnlyRejectsFingerAndUnknownButFingerPans() {
        val (c, r, rec) = setup(stylusOnly = true)
        assertFalse(c.accepts(PointerKind.UNKNOWN))
        assertFalse(c.accepts(PointerKind.FINGER))
        assertTrue(c.accepts(PointerKind.STYLUS))
        assertTrue(c.accepts(PointerKind.ERASER_TIP))
        r.down(0, PointerKind.UNKNOWN, 30f, 40f)
        r.move(0, 35f, 45f)
        r.up(0, 35f, 45f)
        r.down(0, PointerKind.FINGER, 30f, 40f)
        assertEquals(Mode.PAN, r.mode)
        r.move(0, 40f, 50f)
        r.up(0, 40f, 50f)
        assertEquals(0, rec.strokeStarts)
        assertTrue(rec.transforms > 0)
        assertTrue(c.paint.all { it == 0 })
    }

    @Test
    fun draggedTapDoesNotFill() {
        val (c, r, rec) = setup(tool = Tool.FILL)
        r.down(0, PointerKind.STYLUS, 30f, 40f)
        r.move(0, 40f, 40f)
        r.up(0, 40f, 40f)
        assertEquals(0, rec.fills)
        assertTrue(c.paint.all { it == 0 })
    }

    @Test
    fun cancelRollsBackStroke() {
        val (c, r, rec) = setup()
        r.down(0, PointerKind.STYLUS, 30f, 40f)
        r.move(0, 35f, 45f)
        r.cancel()
        assertEquals(1, rec.cancelled)
        assertTrue(c.paint.all { it == 0 })
        assertEquals(Mode.IDLE, r.mode)
    }

    // ---- BOOX TouchHelper raw pen stream (pointer RAW) alongside the MotionEvent stream ----

    private val RAW = 0x7EAD_0001

    @Test
    fun rawPenStrokePaintsAndDuplicateMotionPenIsIgnored() {
        val (c, r, rec) = setup()
        r.down(RAW, PointerKind.STYLUS, 25f, 30f) // raw stream first
        r.down(0, PointerKind.STYLUS, 25f, 30f) // same physical stroke via MotionEvents
        r.move(RAW, 30f, 35f)
        r.move(0, 75f, 35f) // even a diverging duplicate cannot paint
        r.up(RAW, 30f, 35f)
        // Duplicate pen down must NEVER have entered `pointers`: lifting RAW immediately returns to IDLE (not IGNORE).
        assertEquals(Mode.IDLE, r.mode)
        r.up(0, 75f, 35f)
        assertEquals(1, rec.strokeStarts)
        assertEquals(1, rec.strokeEnds)
        assertEquals(0, rec.cancelled)
        assertEquals(red, c.paint[35 * c.width + 30])
        assertEquals(0, c.paint[35 * c.width + 75])
        assertEquals(Mode.IDLE, r.mode)
    }

    @Test
    fun motionCancelDoesNotKillARawStroke() {
        val (c, r, rec) = setup()
        r.down(1, PointerKind.FINGER, 70f, 40f) // palm resting
        r.down(RAW, PointerKind.STYLUS, 25f, 30f) // pen priority: palm gesture aborted
        r.cancelAllExcept(RAW) // MotionEvent stream sends ACTION_CANCEL
        r.move(RAW, 30f, 35f)
        r.up(RAW, 30f, 35f)
        assertEquals(red, c.paint[35 * c.width + 30])
        assertEquals("only the finger stroke was rolled back", 1, rec.cancelled)
        assertEquals(Mode.IDLE, r.mode)
    }

    @Test
    fun palmFirstThenStylusCancelAllExceptRawReturnsToIdleOnRawLift() {
        val (c, r, rec) = setup()
        r.down(0, PointerKind.FINGER, 70f, 40f) // palm starts finger stroke / pan
        r.move(0, 72f, 42f)
        // dispatchTouchEvent sees stylus ACTION_POINTER_DOWN and cleans up non-raw pointers
        // BEFORE or AFTER onRawBegin arrives; lifting RAW must return straight to IDLE (not IGNORE).
        r.cancelAllExcept(RAW)
        assertEquals(Mode.IDLE, r.mode)
        r.down(RAW, PointerKind.STYLUS, 25f, 30f)
        r.move(RAW, 30f, 35f)
        r.up(RAW, 30f, 35f)
        assertEquals(Mode.IDLE, r.mode)
        assertEquals(red, c.paint[35 * c.width + 30])
        assertEquals("palm stroke rolled back", 0, c.paint[42 * c.width + 72])
        assertEquals(1, rec.cancelled)
    }

    @Test
    fun motionCancelWithoutRawStrokeCancelsEverything() {
        val (c, r, rec) = setup()
        r.down(0, PointerKind.STYLUS, 25f, 30f)
        r.move(0, 30f, 35f)
        r.cancelAllExcept(RAW)
        assertEquals(1, rec.cancelled)
        assertTrue(c.paint.all { it == 0 })
        assertEquals(Mode.IDLE, r.mode)
    }

    @Test
    fun cancelPointerRollsBackOnlyTheStrokeItOwns() {
        val (c, r, rec) = setup()
        r.down(RAW, PointerKind.STYLUS, 25f, 30f)
        r.move(RAW, 30f, 35f)
        r.down(1, PointerKind.FINGER, 70f, 40f) // palm while drawing
        r.cancelPointer(RAW) // raw drawing paused mid-stroke
        assertEquals(1, rec.cancelled)
        assertTrue(c.paint.all { it == 0 })
        assertEquals("palm left over is ignored until lifted", Mode.IGNORE, r.mode)
        r.move(1, 80f, 50f)
        r.up(1, 80f, 50f)
        assertEquals(0, c.paint[50 * c.width + 80])
        assertEquals(Mode.IDLE, r.mode)
        // Late raw events for the dropped pointer are inert.
        r.move(RAW, 40f, 40f)
        r.up(RAW, 40f, 40f)
        assertEquals(1, rec.strokeEnds)
        r.cancelPointer(RAW)
        assertEquals(1, rec.cancelled)
    }

    @Test
    fun cancelPointerOfAnUninvolvedPointerKeepsTheStroke() {
        val (c, r, _) = setup()
        r.down(0, PointerKind.STYLUS, 25f, 30f)
        r.cancelPointer(RAW) // not registered
        r.move(0, 30f, 35f)
        r.up(0, 30f, 35f)
        assertEquals(red, c.paint[35 * c.width + 30])
    }
}
