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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom-drawn, chunky, high-contrast E-Ink vector icons for kids (zero emojis, zero text).
 * Drawn strictly with bold rounded strokes so every tool is unmistakable on 7.8" Kaleido Plus.
 */
object KidIcons {

    @Composable
    fun BackArrow(tint: Color, modifier: Modifier = Modifier.size(34.dp)) {
        Canvas(modifier.semantics { contentDescription = "返回" }) {
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
        Canvas(modifier.semantics { contentDescription = "蜡笔" }) {
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
        Canvas(modifier.semantics { contentDescription = "油漆桶" }) {
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
        Canvas(modifier.semantics { contentDescription = "橡皮" }) {
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
        Canvas(modifier.semantics { contentDescription = if (locked) "已锁定不出线" else "自由涂鸦" }) {
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
    }

    /** Bold curved undo arrow. */
    @Composable
    fun Undo(tint: Color, modifier: Modifier = Modifier.size(34.dp)) {
        Canvas(modifier.semantics { contentDescription = "撤销" }) {
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
        Canvas(modifier.semantics { contentDescription = "清残影" }) {
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
        Canvas(modifier.semantics { contentDescription = "保存导出" }) {
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
        Canvas(modifier.semantics { contentDescription = "重置画布" }) {
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
        Canvas(modifier.semantics { contentDescription = if (stylusOnly) "仅笔作画" else "手笔皆可" }) {
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
        Canvas(modifier.semantics { contentDescription = "参考底稿" }) {
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
        Canvas(modifier.semantics { contentDescription = "放大参考图" }) {
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
        Canvas(modifier.semantics { contentDescription = "收起参考图" }) {
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

    /** Picture frame icon for collapsed PiP button. */
    @Composable
    fun RefPicture(tint: Color, modifier: Modifier = Modifier.size(30.dp)) {
        Canvas(modifier.semantics { contentDescription = "显示参考图" }) {
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
}
