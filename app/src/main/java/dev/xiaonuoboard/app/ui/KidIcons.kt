package dev.xiaonuoboard.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xiaonuoboard.app.data.HomeTabs
import dev.xiaonuoboard.app.i18n.LocalAppStrings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom-drawn, chunky, high-contrast E-Ink vector icons for kids (zero emojis, zero text).
 * Drawn strictly with bold rounded strokes so every tool is unmistakable on 7.8" Kaleido Plus.
 */
object KidIcons {

    @Composable
    fun BackArrow(tint: Color, modifier: Modifier = Modifier.size(34.dp)) {
        val desc = LocalAppStrings.current.cdBack
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.12f
            val p = Path().apply {
                moveTo(s * 0.50f, s * 0.22f)
                lineTo(s * 0.20f, s * 0.50f)
                lineTo(s * 0.50f, s * 0.78f)
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(tint, Offset(s * 0.24f, s * 0.50f), Offset(s * 0.82f, s * 0.50f), sw, StrokeCap.Round)
        }
    }

    /** Classic tilted wax crayon with pointed tip and paper wrapper bands. */
    @Composable
    fun Crayon(tint: Color, modifier: Modifier = Modifier.size(36.dp)) {
        val desc = LocalAppStrings.current.cdCrayon
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            rotate(-42f, pivot = Offset(s * 0.5f, s * 0.5f)) {
                val left = s * 0.34f
                val right = s * 0.66f
                // Pointed wax tip
                val tip = Path().apply {
                    moveTo(left + s * 0.04f, s * 0.28f)
                    lineTo(s * 0.50f, s * 0.08f)
                    lineTo(right - s * 0.04f, s * 0.28f)
                    close()
                }
                drawPath(tip, tint)
                drawPath(tip, tint, style = Stroke(sw, join = StrokeJoin.Round))
                // Crayon body
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(left, s * 0.28f),
                    size = Size(right - left, s * 0.62f),
                    cornerRadius = CornerRadius(s * 0.05f),
                    style = Stroke(sw, join = StrokeJoin.Round),
                )
                // Signature wrapper stripes
                drawLine(tint, Offset(left, s * 0.42f), Offset(right, s * 0.42f), sw)
                drawLine(tint, Offset(left, s * 0.74f), Offset(right, s * 0.74f), sw)
                // Center oval badge on wrapper
                drawOval(
                    color = tint,
                    topLeft = Offset(s * 0.41f, s * 0.50f),
                    size = Size(s * 0.18f, s * 0.16f),
                )
            }
        }
    }

    /** Tilted paint bucket with handle and pouring paint drop. */
    @Composable
    fun PaintBucket(tint: Color, modifier: Modifier = Modifier.size(36.dp)) {
        val desc = LocalAppStrings.current.cdPaintBucket
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            // Tilted bucket body
            val body = Path().apply {
                moveTo(s * 0.20f, s * 0.44f)
                lineTo(s * 0.50f, s * 0.18f)
                lineTo(s * 0.76f, s * 0.48f)
                lineTo(s * 0.46f, s * 0.74f)
                close()
            }
            drawPath(body, tint, style = Stroke(sw, join = StrokeJoin.Round))
            // Paint rim inside tilted top
            drawLine(tint, Offset(s * 0.24f, s * 0.42f), Offset(s * 0.72f, s * 0.48f), sw, StrokeCap.Round)
            // Handle arc
            drawArc(
                color = tint,
                startAngle = -140f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(s * 0.26f, s * 0.08f),
                size = Size(s * 0.34f, s * 0.34f),
                style = Stroke(sw * 0.9f, cap = StrokeCap.Round),
            )
            // Dripping paint drop on right
            val drop = Path().apply {
                moveTo(s * 0.80f, s * 0.56f)
                cubicTo(s * 0.92f, s * 0.72f, s * 0.89f, s * 0.88f, s * 0.80f, s * 0.88f)
                cubicTo(s * 0.71f, s * 0.88f, s * 0.68f, s * 0.72f, s * 0.80f, s * 0.56f)
                close()
            }
            drawPath(drop, tint)
        }
    }

    /** Chunky two-tone diagonal school eraser with paper sleeve and erase line. */
    @Composable
    fun Eraser(tint: Color, modifier: Modifier = Modifier.size(36.dp)) {
        val desc = LocalAppStrings.current.cdEraser
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            // Ground line being erased
            drawLine(tint, Offset(s * 0.14f, s * 0.84f), Offset(s * 0.86f, s * 0.84f), sw, StrokeCap.Round)
            rotate(-35f, pivot = Offset(s * 0.50f, s * 0.48f)) {
                // Rubber tip (filled left half)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.16f, s * 0.30f),
                    size = Size(s * 0.30f, s * 0.34f),
                    cornerRadius = CornerRadius(s * 0.07f),
                )
                // Full eraser outline
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.16f, s * 0.30f),
                    size = Size(s * 0.66f, s * 0.34f),
                    cornerRadius = CornerRadius(s * 0.07f),
                    style = Stroke(sw, join = StrokeJoin.Round),
                )
                // Sleeve divider
                drawLine(tint, Offset(s * 0.46f, s * 0.30f), Offset(s * 0.46f, s * 0.64f), sw)
            }
        }
    }

    /** Stay-inside-lines padlock (locked vs unlocked). */
    @Composable
    fun ClipLock(locked: Boolean, tint: Color, modifier: Modifier = Modifier.size(34.dp)) {
        val desc = if (locked) LocalAppStrings.current.cdClipLocked else LocalAppStrings.current.cdClipUnlocked
        Canvas(modifier.semantics { contentDescription = desc }) {
            drawPadlock(locked, tint)
        }
    }

    private fun DrawScope.drawPadlock(locked: Boolean, tint: Color) {
        val s = size.minDimension
        val sw = s * 0.09f
        // Shackle
        if (locked) {
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.30f, s * 0.14f),
                size = Size(s * 0.40f, s * 0.38f),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            drawLine(tint, Offset(s * 0.30f, s * 0.33f), Offset(s * 0.30f, s * 0.46f), sw)
            drawLine(tint, Offset(s * 0.70f, s * 0.33f), Offset(s * 0.70f, s * 0.46f), sw)
        } else {
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 155f,
                useCenter = false,
                topLeft = Offset(s * 0.36f, s * 0.12f),
                size = Size(s * 0.40f, s * 0.38f),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            drawLine(tint, Offset(s * 0.36f, s * 0.31f), Offset(s * 0.36f, s * 0.46f), sw)
        }
        // Lock body
        drawRoundRect(
            color = tint,
            topLeft = Offset(s * 0.20f, s * 0.46f),
            size = Size(s * 0.60f, s * 0.40f),
            cornerRadius = CornerRadius(s * 0.09f),
            style = Stroke(sw, join = StrokeJoin.Round),
        )
        // Keyhole dot
        drawCircle(tint, radius = s * 0.065f, center = Offset(s * 0.50f, s * 0.64f))
        drawLine(tint, Offset(s * 0.50f, s * 0.67f), Offset(s * 0.50f, s * 0.76f), sw * 0.8f, StrokeCap.Round)
    }

    /** Bold curved undo arrow. */
    @Composable
    fun Undo(tint: Color, modifier: Modifier = Modifier.size(34.dp)) {
        val desc = LocalAppStrings.current.cdUndo
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.105f
            val arrow = Path().apply {
                moveTo(s * 0.38f, s * 0.18f)
                lineTo(s * 0.18f, s * 0.36f)
                lineTo(s * 0.38f, s * 0.54f)
            }
            drawPath(arrow, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val curve = Path().apply {
                moveTo(s * 0.22f, s * 0.36f)
                lineTo(s * 0.58f, s * 0.36f)
                arcTo(Rect(s * 0.38f, s * 0.36f, s * 0.82f, s * 0.80f), -90f, 180f, false)
                lineTo(s * 0.42f, s * 0.80f)
            }
            drawPath(curve, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Crisp 4-point sparkle icon for E-Ink full refresh (清残影). */
    @Composable
    fun SparkleRefresh(tint: Color, modifier: Modifier = Modifier.size(34.dp)) {
        val desc = LocalAppStrings.current.cdClearGhosting
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            fun star(cx: Float, cy: Float, r: Float): Path = Path().apply {
                moveTo(cx, cy - r)
                quadraticTo(cx, cy, cx + r, cy)
                quadraticTo(cx, cy, cx, cy + r)
                quadraticTo(cx, cy, cx - r, cy)
                quadraticTo(cx, cy, cx, cy - r)
                close()
            }
            drawPath(star(s * 0.44f, s * 0.54f, s * 0.34f), tint)
            drawPath(star(s * 0.78f, s * 0.24f, s * 0.15f), tint)
        }
    }

    /** Save / export down-arrow into tray. */
    @Composable
    fun SaveExport(tint: Color, modifier: Modifier = Modifier.size(34.dp)) {
        val desc = LocalAppStrings.current.cdSaveExport
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.10f
            drawLine(tint, Offset(s * 0.50f, s * 0.16f), Offset(s * 0.50f, s * 0.62f), sw, StrokeCap.Round)
            val head = Path().apply {
                moveTo(s * 0.30f, s * 0.44f)
                lineTo(s * 0.50f, s * 0.64f)
                lineTo(s * 0.70f, s * 0.44f)
            }
            drawPath(head, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val tray = Path().apply {
                moveTo(s * 0.18f, s * 0.64f)
                lineTo(s * 0.18f, s * 0.82f)
                lineTo(s * 0.82f, s * 0.82f)
                lineTo(s * 0.82f, s * 0.64f)
            }
            drawPath(tray, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Circular trash / clear-page icon for Reset (重置). */
    @Composable
    fun ResetPage(tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = LocalAppStrings.current.cdResetCanvas
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.10f
            // Lid
            drawLine(tint, Offset(s * 0.20f, s * 0.28f), Offset(s * 0.80f, s * 0.28f), sw, StrokeCap.Round)
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * 0.38f, s * 0.14f),
                size = Size(s * 0.24f, s * 0.14f),
                cornerRadius = CornerRadius(s * 0.04f),
                style = Stroke(sw * 0.85f),
            )
            // Can body
            val can = Path().apply {
                moveTo(s * 0.26f, s * 0.28f)
                lineTo(s * 0.32f, s * 0.84f)
                lineTo(s * 0.68f, s * 0.84f)
                lineTo(s * 0.74f, s * 0.28f)
            }
            drawPath(can, tint, style = Stroke(sw, join = StrokeJoin.Round))
            drawLine(tint, Offset(s * 0.43f, s * 0.44f), Offset(s * 0.45f, s * 0.72f), sw * 0.8f, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.57f, s * 0.44f), Offset(s * 0.55f, s * 0.72f), sw * 0.8f, StrokeCap.Round)
        }
    }

    /** Proportional solid dot representing brush thickness (small / medium / large). */
    @Composable
    fun BrushDot(dotDiameter: Dp, tint: Color, label: String, modifier: Modifier = Modifier.size(32.dp)) {
        Canvas(modifier.semantics { contentDescription = label }) {
            drawCircle(tint, radius = (dotDiameter.toPx() / 2f).coerceAtMost(size.minDimension * 0.45f))
        }
    }

    /** Stylus-only nib vs Finger+Stylus icon. */
    @Composable
    fun StylusMode(stylusOnly: Boolean, tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = if (stylusOnly) LocalAppStrings.current.cdStylusOnly else LocalAppStrings.current.cdFingerAndStylus
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.09f
            // Pen nib
            val nib = Path().apply {
                moveTo(s * 0.22f, s * 0.80f)
                lineTo(s * 0.30f, s * 0.56f)
                lineTo(s * 0.68f, s * 0.18f)
                lineTo(s * 0.82f, s * 0.32f)
                lineTo(s * 0.44f, s * 0.70f)
                close()
            }
            drawPath(nib, tint, style = Stroke(sw, join = StrokeJoin.Round))
            if (!stylusOnly) {
                // Extra touch ripple dot to indicate finger + pen
                drawCircle(tint, radius = s * 0.10f, center = Offset(s * 0.72f, s * 0.74f))
                drawCircle(tint, radius = s * 0.18f, center = Offset(s * 0.72f, s * 0.74f), style = Stroke(sw * 0.7f))
            }
        }
    }

    /** Eye icon for translucent reference ghost underlay. */
    @Composable
    fun Eye(active: Boolean, tint: Color, modifier: Modifier = Modifier.size(26.dp)) {
        val desc = LocalAppStrings.current.cdGhostUnderlay
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.10f
            val eye = Path().apply {
                moveTo(s * 0.10f, s * 0.50f)
                quadraticTo(s * 0.50f, s * 0.12f, s * 0.90f, s * 0.50f)
                quadraticTo(s * 0.50f, s * 0.88f, s * 0.10f, s * 0.50f)
                close()
            }
            drawPath(eye, tint, style = Stroke(sw, join = StrokeJoin.Round))
            drawCircle(tint, radius = if (active) s * 0.16f else s * 0.12f, center = Offset(s * 0.50f, s * 0.50f))
        }
    }

    /** Diagonal expand arrows for PiP. */
    @Composable
    fun Expand(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
        val desc = LocalAppStrings.current.cdExpandRef
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.11f
            drawLine(tint, Offset(s * 0.22f, s * 0.78f), Offset(s * 0.78f, s * 0.22f), sw, StrokeCap.Round)
            val tr = Path().apply {
                moveTo(s * 0.52f, s * 0.22f); lineTo(s * 0.78f, s * 0.22f); lineTo(s * 0.78f, s * 0.48f)
            }
            val bl = Path().apply {
                moveTo(s * 0.48f, s * 0.78f); lineTo(s * 0.22f, s * 0.78f); lineTo(s * 0.22f, s * 0.52f)
            }
            drawPath(tr, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(bl, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Collapse chevron for PiP. */
    @Composable
    fun Collapse(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
        val desc = LocalAppStrings.current.cdCollapseRef
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.12f
            val p = Path().apply {
                moveTo(s * 0.24f, s * 0.36f)
                lineTo(s * 0.50f, s * 0.66f)
                lineTo(s * 0.76f, s * 0.36f)
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Palette / color-dots toggle icon for showing or hiding the full 16-color tray. */
    @Composable
    fun MoreColors(expanded: Boolean, tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = if (expanded) LocalAppStrings.current.cdCollapseMoreColors else LocalAppStrings.current.cdExpandMoreColors
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.095f
            if (expanded) {
                // Down chevron inside rounded box to indicate collapse
                val p = Path().apply {
                    moveTo(s * 0.24f, s * 0.38f)
                    lineTo(s * 0.50f, s * 0.66f)
                    lineTo(s * 0.76f, s * 0.38f)
                }
                drawPath(p, tint, style = Stroke(sw * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                // 4-dot palette grid + plus hint
                val r = s * 0.13f
                drawCircle(tint, r, Offset(s * 0.30f, s * 0.30f))
                drawCircle(tint, r, Offset(s * 0.70f, s * 0.30f), style = Stroke(sw))
                drawCircle(tint, r, Offset(s * 0.30f, s * 0.70f), style = Stroke(sw))
                drawCircle(tint, r, Offset(s * 0.70f, s * 0.70f))
            }
        }
    }

    /** Picture frame icon for collapsed PiP button. */
    @Composable
    fun RefPicture(tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = LocalAppStrings.current.cdShowRef
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.095f
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * 0.14f, s * 0.18f),
                size = Size(s * 0.72f, s * 0.64f),
                cornerRadius = CornerRadius(s * 0.08f),
                style = Stroke(sw),
            )
            drawCircle(tint, radius = s * 0.08f, center = Offset(s * 0.34f, s * 0.38f))
            val mtn = Path().apply {
                moveTo(s * 0.20f, s * 0.76f)
                lineTo(s * 0.44f, s * 0.50f)
                lineTo(s * 0.58f, s * 0.64f)
                lineTo(s * 0.68f, s * 0.54f)
                lineTo(s * 0.80f, s * 0.76f)
            }
            drawPath(mtn, tint, style = Stroke(sw * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    // ───────────────────────── Home screen icons (same chunky ink style, zero emoji) ─────────────────────────

    /** Kaleido-friendly saturated paint colors for the app logo's palette dots. */
    private val LogoRed = Color(0xFFE53935)
    private val LogoYellow = Color(0xFFFFC400)
    private val LogoBlue = Color(0xFF1E66D0)
    private val LogoGreen = Color(0xFF2E9E44)

    /** Four-point sparkle path (shared by the magic / new-page icons). */
    private fun sparkle(cx: Float, cy: Float, r: Float): Path = Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx, cy, cx + r, cy)
        quadraticTo(cx, cy, cx, cy + r)
        quadraticTo(cx, cy, cx - r, cy)
        quadraticTo(cx, cy, cx, cy - r)
        close()
    }

    /** App logo: artist palette with a thumb notch and four colored paint dots (ink-outlined). */
    @Composable
    fun PaletteLogo(tint: Color, modifier: Modifier = Modifier.size(40.dp), colorful: Boolean = true) {
        val desc = LocalAppStrings.current.appName
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            val palette = Path().apply {
                moveTo(s * 0.52f, s * 0.12f)
                cubicTo(s * 0.80f, s * 0.12f, s * 0.92f, s * 0.34f, s * 0.90f, s * 0.52f)
                cubicTo(s * 0.88f, s * 0.66f, s * 0.76f, s * 0.67f, s * 0.68f, s * 0.64f)
                cubicTo(s * 0.58f, s * 0.61f, s * 0.53f, s * 0.71f, s * 0.59f, s * 0.79f)
                cubicTo(s * 0.64f, s * 0.87f, s * 0.57f, s * 0.92f, s * 0.46f, s * 0.90f)
                cubicTo(s * 0.22f, s * 0.86f, s * 0.10f, s * 0.68f, s * 0.10f, s * 0.50f)
                cubicTo(s * 0.10f, s * 0.28f, s * 0.28f, s * 0.12f, s * 0.52f, s * 0.12f)
                close()
            }
            drawPath(palette, tint, style = Stroke(sw, join = StrokeJoin.Round))
            val dots = listOf(
                Offset(s * 0.30f, s * 0.44f) to LogoRed,
                Offset(s * 0.46f, s * 0.28f) to LogoYellow,
                Offset(s * 0.68f, s * 0.34f) to LogoBlue,
                Offset(s * 0.30f, s * 0.66f) to LogoGreen,
            )
            for ((c, color) in dots) {
                drawCircle(if (colorful) color else tint, radius = s * 0.075f, center = c)
                drawCircle(tint, radius = s * 0.075f, center = c, style = Stroke(sw * 0.45f))
            }
        }
    }

    /** Magic wand with a big sparkle (AI 魔法画坊). */
    @Composable
    fun MagicWand(tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = LocalAppStrings.current.cdAiMagic
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.11f
            drawLine(tint, Offset(s * 0.16f, s * 0.86f), Offset(s * 0.56f, s * 0.46f), sw, StrokeCap.Round)
            drawPath(sparkle(s * 0.68f, s * 0.32f, s * 0.24f), tint)
            drawPath(sparkle(s * 0.26f, s * 0.24f, s * 0.11f), tint)
            drawCircle(tint, radius = s * 0.05f, center = Offset(s * 0.86f, s * 0.70f))
        }
    }

    /** Small closed padlock: the button opens a parent-locked area (家长锁). */
    @Composable
    fun ParentLock(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
        val desc = LocalAppStrings.current.cdParentLock
        Canvas(modifier.semantics { contentDescription = desc }) { drawPadlock(locked = true, tint = tint) }
    }

    /** Chunky 8-tooth settings gear (家长设置). */
    @Composable
    fun Gear(tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = LocalAppStrings.current.cdParentSettings
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.10f
            val c = Offset(s * 0.5f, s * 0.5f)
            for (i in 0 until 8) {
                val a = (i * PI / 4).toFloat()
                drawLine(
                    tint,
                    Offset(c.x + cos(a) * s * 0.26f, c.y + sin(a) * s * 0.26f),
                    Offset(c.x + cos(a) * s * 0.40f, c.y + sin(a) * s * 0.40f),
                    sw * 1.35f,
                    StrokeCap.Round,
                )
            }
            drawCircle(tint, radius = s * 0.26f, center = c, style = Stroke(sw))
            drawCircle(tint, radius = s * 0.09f, center = c, style = Stroke(sw * 0.85f))
        }
    }

    /** Bold up-arrow in a ring: a new app version is ready (新版). */
    @Composable
    fun UpdateArrow(tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = LocalAppStrings.current.cdNewVersion
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.10f
            drawCircle(tint, radius = s * 0.40f, center = Offset(s * 0.5f, s * 0.5f), style = Stroke(sw))
            drawLine(tint, Offset(s * 0.50f, s * 0.72f), Offset(s * 0.50f, s * 0.30f), sw, StrokeCap.Round)
            val head = Path().apply {
                moveTo(s * 0.32f, s * 0.46f)
                lineTo(s * 0.50f, s * 0.28f)
                lineTo(s * 0.68f, s * 0.46f)
            }
            drawPath(head, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Six-armed snowflake with V branches (艾莎 / Frozen). */
    @Composable
    fun Snowflake(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
        val desc = LocalAppStrings.current.cdSnowflake
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            val c = Offset(s * 0.5f, s * 0.5f)
            for (i in 0 until 6) {
                rotate(i * 60f, pivot = c) {
                    drawLine(tint, c, Offset(c.x, s * 0.10f), sw, StrokeCap.Round)
                    drawLine(tint, Offset(c.x, s * 0.26f), Offset(c.x - s * 0.11f, s * 0.15f), sw * 0.85f, StrokeCap.Round)
                    drawLine(tint, Offset(c.x, s * 0.26f), Offset(c.x + s * 0.11f, s * 0.15f), sw * 0.85f, StrokeCap.Round)
                }
            }
            drawCircle(tint, radius = s * 0.07f, center = c)
        }
    }

    /** Puppy face with floppy ears and a big nose (布鲁伊 / Bluey). */
    @Composable
    fun Dog(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
        val desc = LocalAppStrings.current.cdDog
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * 0.22f, s * 0.20f),
                size = Size(s * 0.56f, s * 0.66f),
                cornerRadius = CornerRadius(s * 0.26f),
                style = Stroke(sw, join = StrokeJoin.Round),
            )
            // Floppy ears hanging off both sides of the head (solid, so they read at 28dp).
            val earL = Path().apply {
                moveTo(s * 0.32f, s * 0.24f)
                cubicTo(s * 0.12f, s * 0.18f, s * 0.02f, s * 0.46f, s * 0.12f, s * 0.64f)
                cubicTo(s * 0.20f, s * 0.66f, s * 0.26f, s * 0.52f, s * 0.30f, s * 0.42f)
                close()
            }
            val earR = Path().apply {
                moveTo(s * 0.68f, s * 0.24f)
                cubicTo(s * 0.88f, s * 0.18f, s * 0.98f, s * 0.46f, s * 0.88f, s * 0.64f)
                cubicTo(s * 0.80f, s * 0.66f, s * 0.74f, s * 0.52f, s * 0.70f, s * 0.42f)
                close()
            }
            drawPath(earL, tint)
            drawPath(earR, tint)
            drawPath(earL, tint, style = Stroke(sw * 0.8f, join = StrokeJoin.Round))
            drawPath(earR, tint, style = Stroke(sw * 0.8f, join = StrokeJoin.Round))
            drawCircle(tint, radius = s * 0.055f, center = Offset(s * 0.40f, s * 0.46f))
            drawCircle(tint, radius = s * 0.055f, center = Offset(s * 0.60f, s * 0.46f))
            drawOval(tint, topLeft = Offset(s * 0.41f, s * 0.57f), size = Size(s * 0.18f, s * 0.11f))
            drawLine(tint, Offset(s * 0.50f, s * 0.67f), Offset(s * 0.50f, s * 0.74f), sw * 0.8f, StrokeCap.Round)
        }
    }

    /** Round piggy face with a big snout (小猪佩奇 / Peppa Pig). */
    @Composable
    fun Pig(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
        val desc = LocalAppStrings.current.cdPig
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            val earL = Path().apply {
                moveTo(s * 0.20f, s * 0.30f); lineTo(s * 0.22f, s * 0.08f); lineTo(s * 0.40f, s * 0.20f); close()
            }
            val earR = Path().apply {
                moveTo(s * 0.80f, s * 0.30f); lineTo(s * 0.78f, s * 0.08f); lineTo(s * 0.60f, s * 0.20f); close()
            }
            drawPath(earL, tint)
            drawPath(earR, tint)
            drawPath(earL, tint, style = Stroke(sw, join = StrokeJoin.Round))
            drawPath(earR, tint, style = Stroke(sw, join = StrokeJoin.Round))
            drawCircle(tint, radius = s * 0.36f, center = Offset(s * 0.5f, s * 0.54f), style = Stroke(sw))
            drawCircle(tint, radius = s * 0.05f, center = Offset(s * 0.36f, s * 0.42f))
            drawCircle(tint, radius = s * 0.05f, center = Offset(s * 0.64f, s * 0.42f))
            drawOval(
                tint,
                topLeft = Offset(s * 0.33f, s * 0.54f),
                size = Size(s * 0.34f, s * 0.22f),
                style = Stroke(sw, join = StrokeJoin.Round),
            )
            drawCircle(tint, radius = s * 0.04f, center = Offset(s * 0.44f, s * 0.65f))
            drawCircle(tint, radius = s * 0.04f, center = Offset(s * 0.56f, s * 0.65f))
        }
    }

    /** Round bun-hero face: big round nose and cheeks, happy smile (面包超人 / Anpanman). */
    @Composable
    fun BunFace(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
        val desc = LocalAppStrings.current.cdBunFace
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            drawCircle(tint, radius = s * 0.40f, center = Offset(s * 0.5f, s * 0.5f), style = Stroke(sw))
            drawCircle(tint, radius = s * 0.11f, center = Offset(s * 0.27f, s * 0.56f), style = Stroke(sw * 0.8f))
            drawCircle(tint, radius = s * 0.11f, center = Offset(s * 0.73f, s * 0.56f), style = Stroke(sw * 0.8f))
            drawCircle(tint, radius = s * 0.11f, center = Offset(s * 0.50f, s * 0.52f))
            drawCircle(tint, radius = s * 0.045f, center = Offset(s * 0.36f, s * 0.34f))
            drawCircle(tint, radius = s * 0.045f, center = Offset(s * 0.64f, s * 0.34f))
            drawArc(
                color = tint,
                startAngle = 25f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(s * 0.36f, s * 0.52f),
                size = Size(s * 0.28f, s * 0.22f),
                style = Stroke(sw * 0.8f, cap = StrokeCap.Round),
            )
        }
    }

    /** Bold "AB" letters (字母). */
    @Composable
    fun Letters(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
        val desc = LocalAppStrings.current.cdLetters
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.095f
            val style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val a = Path().apply {
                moveTo(s * 0.08f, s * 0.82f); lineTo(s * 0.28f, s * 0.18f); lineTo(s * 0.48f, s * 0.82f)
                moveTo(s * 0.16f, s * 0.60f); lineTo(s * 0.40f, s * 0.60f)
            }
            drawPath(a, tint, style = style)
            val b = Path().apply {
                moveTo(s * 0.60f, s * 0.82f); lineTo(s * 0.60f, s * 0.18f); lineTo(s * 0.74f, s * 0.18f)
                cubicTo(s * 0.92f, s * 0.18f, s * 0.92f, s * 0.48f, s * 0.74f, s * 0.48f)
                lineTo(s * 0.60f, s * 0.48f)
                moveTo(s * 0.74f, s * 0.48f)
                cubicTo(s * 0.96f, s * 0.48f, s * 0.96f, s * 0.82f, s * 0.74f, s * 0.82f)
                lineTo(s * 0.60f, s * 0.82f)
            }
            drawPath(b, tint, style = style)
        }
    }

    /** Framed picture hanging on a nail (我的作品). */
    @Composable
    fun GalleryFrame(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
        val desc = LocalAppStrings.current.cdMyWorks
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.085f
            val string = Path().apply {
                moveTo(s * 0.30f, s * 0.26f); lineTo(s * 0.50f, s * 0.08f); lineTo(s * 0.70f, s * 0.26f)
            }
            drawPath(string, tint, style = Stroke(sw * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(tint, radius = s * 0.05f, center = Offset(s * 0.50f, s * 0.08f))
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * 0.12f, s * 0.26f),
                size = Size(s * 0.76f, s * 0.62f),
                cornerRadius = CornerRadius(s * 0.06f),
                style = Stroke(sw, join = StrokeJoin.Round),
            )
            drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.34f, s * 0.44f))
            val hills = Path().apply {
                moveTo(s * 0.20f, s * 0.80f)
                lineTo(s * 0.42f, s * 0.56f)
                lineTo(s * 0.56f, s * 0.70f)
                lineTo(s * 0.66f, s * 0.60f)
                lineTo(s * 0.80f, s * 0.80f)
            }
            drawPath(hills, tint, style = Stroke(sw * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Glyph of a Home category tab. */
    @Composable
    fun TabGlyph(icon: HomeTabs.Icon, tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
        when (icon) {
            HomeTabs.Icon.SNOWFLAKE -> Snowflake(tint, modifier)
            HomeTabs.Icon.DOG -> Dog(tint, modifier)
            HomeTabs.Icon.PIG -> Pig(tint, modifier)
            HomeTabs.Icon.BUN_FACE -> BunFace(tint, modifier)
            HomeTabs.Icon.LETTERS -> Letters(tint, modifier)
            HomeTabs.Icon.MAGIC -> MagicWand(tint, modifier)
            HomeTabs.Icon.GALLERY -> GalleryFrame(tint, modifier)
        }
    }

    /** Solid five-point star (已涂 / 已完成 badges). */
    @Composable
    fun Star(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
        val desc = LocalAppStrings.current.cdStar
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val cx = s * 0.5f
            val cy = s * 0.54f
            val outer = s * 0.48f
            val inner = s * 0.20f
            val star = Path().apply {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outer else inner
                    val a = (-PI / 2 + i * PI / 5).toFloat()
                    val x = cx + cos(a) * r
                    val y = cy + sin(a) * r
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(star, tint)
            drawPath(star, tint, style = Stroke(s * 0.06f, join = StrokeJoin.Round))
        }
    }

    /** Thick pager chevron (◀ / ▶ replacement). */
    @Composable
    fun Chevron(pointsLeft: Boolean, tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
        val desc = if (pointsLeft) LocalAppStrings.current.cdPrevPage else LocalAppStrings.current.cdNextPage
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.16f
            val p = Path().apply {
                if (pointsLeft) {
                    moveTo(s * 0.64f, s * 0.18f); lineTo(s * 0.32f, s * 0.50f); lineTo(s * 0.64f, s * 0.82f)
                } else {
                    moveTo(s * 0.36f, s * 0.18f); lineTo(s * 0.68f, s * 0.50f); lineTo(s * 0.36f, s * 0.82f)
                }
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Blank page with a folded corner, a big plus and a sparkle (生成新涂色页). */
    @Composable
    fun NewPage(tint: Color, modifier: Modifier = Modifier.size(64.dp)) {
        val desc = LocalAppStrings.current.cdNewColoringPage
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.07f
            val page = Path().apply {
                moveTo(s * 0.18f, s * 0.10f)
                lineTo(s * 0.58f, s * 0.10f)
                lineTo(s * 0.76f, s * 0.28f)
                lineTo(s * 0.76f, s * 0.90f)
                lineTo(s * 0.18f, s * 0.90f)
                close()
            }
            drawPath(page, tint, style = Stroke(sw, join = StrokeJoin.Round))
            val fold = Path().apply {
                moveTo(s * 0.58f, s * 0.10f); lineTo(s * 0.58f, s * 0.28f); lineTo(s * 0.76f, s * 0.28f)
            }
            drawPath(fold, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(tint, Offset(s * 0.47f, s * 0.42f), Offset(s * 0.47f, s * 0.76f), sw * 1.3f, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.30f, s * 0.59f), Offset(s * 0.64f, s * 0.59f), sw * 1.3f, StrokeCap.Round)
            drawPath(sparkle(s * 0.82f, s * 0.14f, s * 0.13f), tint)
        }
    }

    /** Backspace key: arrow-shaped key cap with an ✕ (parent-gate number pad). */
    @Composable
    fun Backspace(tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        val desc = LocalAppStrings.current.cdBackspace
        Canvas(modifier.semantics { contentDescription = desc }) {
            val s = size.minDimension
            val sw = s * 0.09f
            val key = Path().apply {
                moveTo(s * 0.08f, s * 0.50f)
                lineTo(s * 0.32f, s * 0.22f)
                lineTo(s * 0.90f, s * 0.22f)
                lineTo(s * 0.90f, s * 0.78f)
                lineTo(s * 0.32f, s * 0.78f)
                close()
            }
            drawPath(key, tint, style = Stroke(sw, join = StrokeJoin.Round))
            drawLine(tint, Offset(s * 0.46f, s * 0.38f), Offset(s * 0.70f, s * 0.62f), sw, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.70f, s * 0.38f), Offset(s * 0.46f, s * 0.62f), sw, StrokeCap.Round)
        }
    }
}
