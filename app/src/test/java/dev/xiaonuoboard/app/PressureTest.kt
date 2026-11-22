package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.eink.RawDrawingGeometry
import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.ColoringCanvasController
import dev.xiaonuoboard.app.engine.CrayonBrush
import dev.xiaonuoboard.app.engine.InputRouter
import dev.xiaonuoboard.app.engine.PointerKind
import dev.xiaonuoboard.app.engine.RawPressureNormalizer
import dev.xiaonuoboard.app.engine.RegionMap
import dev.xiaonuoboard.app.engine.RegionMapEngine
import dev.xiaonuoboard.app.engine.StrokePressure
import dev.xiaonuoboard.app.engine.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Pen pressure → tapered crayon width (normalization, curve, interpolation, undo, 不涂出线). */
class PressureTest {
    private val red = 0xFFE61919.toInt()

    // ---------------- normalization ----------------

    @Test
    fun normalizeHandlesRawScaleAndInvalidReadings() {
        assertEquals(0.5f, StrokePressure.normalize(2048f, 4096f), 1e-6f)
        assertEquals(1f, StrokePressure.normalize(5000f, 4096f), 0f) // clamped
        assertEquals(0.25f, StrokePressure.normalize(0.25f, 1f), 0f) // MotionEvent (already normalized)
        assertEquals(1f, StrokePressure.normalize(1.3f, 1f), 0f) // MotionEvent may exceed 1
        assertTrue(StrokePressure.normalize(0f, 4096f).isNaN())
        assertTrue(StrokePressure.normalize(-3f, 4096f).isNaN())
        assertTrue(StrokePressure.normalize(Float.NaN, 4096f).isNaN())
        assertTrue(StrokePressure.normalize(Float.POSITIVE_INFINITY, 1f).isNaN())
        // Unknown / broken max: treat as already normalized.
        assertEquals(0.5f, StrokePressure.normalize(0.5f, 0f), 0f)
        assertEquals(0.5f, StrokePressure.normalize(0.5f, Float.NaN), 0f)
        assertFalse(StrokePressure.isValid(Float.NaN))
        assertFalse(StrokePressure.isValid(0f))
        assertTrue(StrokePressure.isValid(0.01f))
    }

    @Test
    fun rawNormalizerSelfCorrectsAWrongReportedMaximum() {
        val n = RawPressureNormalizer(2048f) // SDK fallback on a 4096-level panel
        assertEquals(0.5f, n.normalize(1024f), 1e-6f)
        assertEquals(1f, n.normalize(4095f), 0f) // raises the scale
        assertEquals(4095f, n.scaleMax, 0f)
        assertEquals(1024f / 4095f, n.normalize(1024f), 1e-6f)
        assertEquals(4095f, n.observedMax, 0f)
        assertTrue(n.normalize(0f).isNaN())
        n.normalize(1e9f) // garbage never becomes the scale
        assertEquals(4095f, n.scaleMax, 0f)
        assertEquals(RawPressureNormalizer.DEFAULT_MAX, RawPressureNormalizer(0f).scaleMax, 0f)
        assertEquals(RawPressureNormalizer.DEFAULT_MAX, RawPressureNormalizer(Float.NaN).scaleMax, 0f)
        assertEquals(RawPressureNormalizer.DEFAULT_MAX, RawPressureNormalizer(-1f).scaleMax, 0f)
        // A firmware reporting an already-normalized [0, 1] scale is honoured…
        val unit = RawPressureNormalizer(1f)
        assertEquals(0.3f, unit.normalize(0.3f), 1e-6f)
        assertEquals(1f, unit.scaleMax, 0f)
        // …and jumps to the Wacom range (not the first raw sample) if raw units arrive after all.
        assertEquals(800f / 4096f, unit.normalize(800f), 1e-6f)
        assertEquals(RawPressureNormalizer.DEFAULT_MAX, unit.scaleMax, 0f)
    }

    // ---------------- curve ----------------

    @Test
    fun curveIsMonotonicBoundedAndKidFriendly() {
        var prev = 0f
        for (i in 1..100) {
            val m = StrokePressure.multiplier(i / 100f)
            assertTrue("monotonic at $i", m >= prev)
            assertTrue(m in StrokePressure.MIN_MULT..StrokePressure.MAX_MULT)
            prev = m
        }
        assertEquals(StrokePressure.MAX_MULT, StrokePressure.multiplier(1f), 1e-5f)
        assertEquals(StrokePressure.MAX_MULT, StrokePressure.multiplier(StrokePressure.FULL_PRESSURE), 1e-5f)
        assertTrue("light touch stays visible & thin", StrokePressure.multiplier(0.05f) in 0.42f..0.58f)
        assertTrue("typical Nova Air C press ≈ chosen size", StrokePressure.multiplier(0.25f) in 0.85f..1.00f)
        assertTrue("firm Nova Air C press reaches bold width", StrokePressure.multiplier(0.50f) > 1.30f)
        assertEquals(StrokePressure.NEUTRAL, StrokePressure.multiplier(Float.NaN), 0f)
        assertEquals(StrokePressure.NEUTRAL, StrokePressure.multiplier(0f), 0f)
        assertEquals(StrokePressure.MAX_MULT, StrokePressure.multiplier(7f), 1e-5f) // clamped
    }

    @Test
    fun smoothingIsAnEmaThatConverges() {
        var m = 0.5f
        repeat(30) { m = StrokePressure.smooth(m, 1.3f) }
        assertEquals(1.3f, m, 1e-3f)
        assertEquals(0.5f + StrokePressure.SMOOTHING * 0.8f, StrokePressure.smooth(0.5f, 1.3f), 1e-6f)
    }

    // ---------------- tapered segment ----------------

    private fun open(w: Int = 200, h: Int = 120): Pair<RegionMap, IntArray> {
        val map = RegionMapEngine.build(TestArt.blank(w, h), w, h, closeRadius = 0)
        return map to IntArray(w * h)
    }

    private fun painted(paint: IntArray, w: Int, x: Int, y: Int) = paint[y * w + x] != 0

    @Test
    fun taperedSegmentInterpolatesRadiusAlongTheSegment() {
        val (map, paint) = open()
        val brush = CrayonBrush(map, paint).apply { color = red }
        val rect = brush.stampSegment(40f, 60f, 4f, 160f, 60f, 16f)
        // Bounds cover both end discs: small cap on the left, big cap (top/bottom/right) on the right.
        assertEquals(36, rect.left)
        assertEquals(44, rect.top)
        assertEquals(177, rect.right)
        assertEquals(77, rect.bottom)
        val w = map.width
        fun columnHalfWidth(x: Int): Float = (0 until map.height).count { painted(paint, w, x, it) } / 2f
        val near = columnHalfWidth(50)
        val mid = columnHalfWidth(100)
        val far = columnHalfWidth(150)
        assertTrue("tapers: $near < $mid < $far", near < mid && mid < far)
        // Linear interpolation: half widths ≈ 4 + 12·t (the hull is within ~1 px of the interpolated disc).
        assertEquals(5f, near, 1f)
        assertEquals(10f, mid, 1f)
        assertEquals(15f, far, 1f)
        // Past the big end only its disc is painted.
        for (y in 0 until map.height) for (x in 160 until w) {
            if (painted(paint, w, x, y)) assertTrue(hypot(x + 0.5f - 160f, y + 0.5f - 60f) <= 16f)
        }
        // Both end caps are complete discs.
        assertTrue(painted(paint, w, 40, 63) && painted(paint, w, 37, 60))
        assertTrue(painted(paint, w, 160, 75) && painted(paint, w, 175, 60))
        assertFalse(painted(paint, w, 40, 66))
    }

    @Test
    fun containedDiscAndZeroLengthSegmentsAreJustTheBiggerDisc() {
        val (map, paint) = open()
        val brush = CrayonBrush(map, paint).apply { color = red }
        brush.stampSegment(100f, 60f, 3f, 102f, 60f, 12f) // small disc inside the big one
        var count = 0
        for (i in paint.indices) if (paint[i] != 0) count++
        val disc = Math.PI * 12 * 12
        assertTrue("≈ one disc ($count vs $disc)", kotlin.math.abs(count - disc) < disc * 0.12)
        val (map2, paint2) = open()
        CrayonBrush(map2, paint2).apply { color = red }.stampSegment(50f, 50f, 6f, 50f, 50f, 10f)
        assertTrue(painted(paint2, map2.width, 50, 59))
        assertFalse(painted(paint2, map2.width, 50, 61))
    }

    @Test
    fun equalRadiiKeepTheUniformCapsuleExactly() {
        val (map, a) = open()
        val (_, b) = open()
        CrayonBrush(map, a).apply { color = red; radius = 9f }.stampSegment(20f, 30f, 150f, 90f)
        CrayonBrush(map, b).apply { color = red }.stampSegment(20f, 30f, 9f, 150f, 90f, 9f)
        assertTrue(a.contentEquals(b))
    }

    // ---------------- controller integration ----------------

    private fun controller(ink: BooleanArray = TestArt.blank(300, 200), w: Int = 300, h: Int = 200) =
        ColoringCanvasController(w, h, ink, closeRadius = 0).apply {
            tool = Tool.CRAYON; brushSize = BrushSize.MEDIUM; color = red; stayInsideLines = false
        }

    @Test
    fun stylusPressureModulatesWidthSmoothly() {
        val c = controller()
        assertTrue(c.strokeBegin(20f, 100f, PointerKind.STYLUS, 0.05f))
        val light = c.currentBrushRadius
        assertEquals(BrushSize.MEDIUM.radius * StrokePressure.multiplier(0.05f), light, 1e-4f)
        var prev = light
        for (i in 1..20) {
            c.strokeMove(20f + i * 10, 100f, 1f)
            val r = c.currentBrushRadius
            assertTrue("smooth growth", r >= prev && r - prev <= BrushSize.MEDIUM.radius * 0.35f)
            prev = r
        }
        assertEquals(BrushSize.MEDIUM.radius * StrokePressure.MAX_MULT, prev, 0.05f)
        // An unusable sample (lift / hover) keeps the current width.
        c.strokeMove(230f, 100f, Float.NaN)
        assertEquals(prev, c.currentBrushRadius, 0f)
        c.strokeMove(240f, 100f, 0f)
        assertEquals(prev, c.currentBrushRadius, 0f)
        c.strokeEnd()
    }

    @Test
    fun fingersMiceErasersAndMissingPressureStayNeutral() {
        val c = controller()
        c.strokeBegin(20f, 50f, PointerKind.FINGER, 0.05f)
        c.strokeMove(60f, 50f, 1f)
        assertEquals(BrushSize.MEDIUM.radius, c.currentBrushRadius, 0f)
        c.strokeEnd()
        c.strokeBegin(20f, 80f, PointerKind.MOUSE, 0.9f)
        assertEquals(BrushSize.MEDIUM.radius, c.currentBrushRadius, 0f)
        c.strokeEnd()
        c.strokeBegin(20f, 110f, PointerKind.ERASER_TIP, 0.05f)
        c.strokeMove(60f, 110f, 1f)
        assertEquals(BrushSize.MEDIUM.radius * 1.5f, c.currentBrushRadius, 0f)
        c.strokeEnd()
        c.tool = Tool.ERASER
        c.strokeBegin(20f, 140f, PointerKind.STYLUS, 0.05f)
        assertEquals(BrushSize.MEDIUM.radius * 1.5f, c.currentBrushRadius, 0f)
        c.strokeEnd()
        c.tool = Tool.CRAYON
        // Stylus without any reading (default) is exactly the old constant-width brush.
        c.strokeBegin(20f, 170f)
        c.strokeMove(60f, 170f)
        assertEquals(BrushSize.MEDIUM.radius, c.currentBrushRadius, 0f)
        c.strokeEnd()
    }

    @Test
    fun firstUsableReadingSeedsTheWidthWhenPenDownHadNone() {
        val c = controller()
        c.strokeBegin(20f, 100f, PointerKind.STYLUS, Float.NaN)
        assertEquals(BrushSize.MEDIUM.radius, c.currentBrushRadius, 0f)
        c.strokeMove(30f, 100f, 0.05f)
        assertEquals(BrushSize.MEDIUM.radius * StrokePressure.multiplier(0.05f), c.currentBrushRadius, 1e-4f)
        c.strokeEnd()
    }

    @Test
    fun routerForwardsPressureToTheController() {
        val c = controller()
        val router = InputRouter(c, object : InputRouter.Listener {})
        router.down(1, PointerKind.STYLUS, 20f, 100f, pressure = 0.05f)
        assertEquals(BrushSize.MEDIUM.radius * StrokePressure.multiplier(0.05f), c.currentBrushRadius, 1e-4f)
        router.move(1, 40f, 100f, pressure = 1f)
        assertTrue(c.currentBrushRadius > BrushSize.MEDIUM.radius * StrokePressure.multiplier(0.05f))
        router.up(1, 40f, 100f)
    }

    @Test
    fun undoRestoresEveryPixelOfAVariableWidthStroke() {
        val c = controller()
        val before = c.paint.copyOf()
        val dirty = mutableListOf<dev.xiaonuoboard.app.engine.DirtyRect>()
        c.onDirty = { dirty += it }
        c.strokeBegin(30f, 100f, PointerKind.STYLUS, 0.02f)
        for (i in 1..25) c.strokeMove(30f + i * 9, 100f + (i % 5) * 6, if (i < 12) 0.1f + i * 0.08f else 1f)
        val rect = c.strokeEnd()
        // The reported stroke rect contains every painted pixel (incl. the widest, firm-press part).
        for (y in 0 until c.height) for (x in 0 until c.width) {
            if (c.paint[y * c.width + x] != 0) {
                assertTrue("($x,$y) outside $rect", x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom)
                assertTrue(dirty.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom })
            }
        }
        assertTrue(c.paint.any { it != 0 })
        c.undo()
        assertTrue("undo restored the canvas", c.paint.contentEquals(before))
    }

    @Test
    fun stayInsideLinesHoldsWithVariableRadius() {
        val (ink, w, h) = TestArt.twoBoxes()
        val c = ColoringCanvasController(w, h, ink, closeRadius = 0).apply {
            tool = Tool.CRAYON; brushSize = BrushSize.THICK; color = red; stayInsideLines = true
        }
        val map = c.regionMap
        val left = map.regionAt(30, 40)
        // Light pen-down far from the divider, then a firm swing across it, the border and off-canvas.
        assertTrue(c.strokeBegin(30f, 40f, PointerKind.STYLUS, 0.03f))
        c.strokeMove(45f, 40f, 0.4f)
        c.strokeMove(80f, 40f, 1f)
        c.strokeMove(95f, 75f, 1f)
        c.strokeMove(2f, 2f, 0.2f)
        c.strokeMove(-50f, 200f, 1f)
        c.strokeEnd()
        var paintedPx = 0
        for (i in c.paint.indices) {
            if (c.paint[i] == 0) continue
            paintedPx++
            val l = map.labels[i]
            assertTrue("leaked into region $l", l == left || l == RegionMap.BOUNDARY)
        }
        assertTrue(paintedPx > 100)
    }

    @Test
    fun lightPenDownLocksTheSameRegionAsTheBaseRadius() {
        val (ink, w, h) = TestArt.twoBoxes()
        val map = RegionMapEngine.build(ink, w, h, closeRadius = 0)
        // Pen comes down ON the divider line (x 49..50): only a search of the base radius finds a region.
        val light = CrayonBrush(map, IntArray(w * h)).apply { radius = 2f }
        light.begin(50f, 40f, stayInside = true, lockRadius = BrushSize.THICK.radius)
        val base = CrayonBrush(map, IntArray(w * h)).apply { radius = BrushSize.THICK.radius }
        base.begin(50f, 40f, stayInside = true)
        assertEquals(base.lockRegionId, light.lockRegionId)
        assertTrue(light.lockRegionId > 0 && light.lockRegionId != Int.MAX_VALUE)
    }

    // ---------------- BOOX hardware trace width ----------------

    @Test
    fun pressureTraceWidthCoversTheFirmPressDiameter() {
        val r = BrushSize.MEDIUM.radius
        assertEquals(r * 2f * StrokePressure.MAX_MULT * 0.6f, RawDrawingGeometry.traceWidthPx(r, false, 0.6f, pressureSensitive = true), 1e-4f)
        // The eraser never uses the pressure style.
        assertEquals(r * 1.5f * 2f, RawDrawingGeometry.traceWidthPx(r, true, 1f, pressureSensitive = true), 1e-4f)
        assertEquals(r * 2f, RawDrawingGeometry.traceWidthPx(r, false, 1f), 1e-4f)
    }
}
