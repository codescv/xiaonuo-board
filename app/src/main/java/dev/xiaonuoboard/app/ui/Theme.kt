package dev.xiaonuoboard.app.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** E-Ink design tokens: paper surface, ink borders, zero animation, big touch targets. */
object Eink {
    val Ink = Color(0xFF111111)
    val Paper = Color(0xFFF8F8F5)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF555555)
    val Accent = Color(0xFFFFD600)
    val Border: Dp = 2.dp
    val Touch: Dp = 56.dp
    val Shape = RoundedCornerShape(12.dp)
}

/** Indication that draws nothing: no ripples / press highlights → no E-Ink ghosting or flashes. */
object NoOpIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : androidx.compose.ui.Modifier.Node() {}
    override fun hashCode(): Int = -1
    override fun equals(other: Any?) = other === this
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun XiaonuoTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Eink.Ink, onPrimary = Eink.White,
        secondary = Eink.Ink, onSecondary = Eink.White,
        background = Eink.Paper, onBackground = Eink.Ink,
        surface = Eink.Paper, onSurface = Eink.Ink,
        surfaceVariant = Eink.White, onSurfaceVariant = Eink.Ink,
        outline = Eink.Ink,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography.copy(
            bodyLarge = TextStyle(fontSize = 18.sp, color = Eink.Ink),
            bodyMedium = TextStyle(fontSize = 16.sp, color = Eink.Ink),
            titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Eink.Ink),
        ),
    ) {
        CompositionLocalProvider(
            LocalIndication provides NoOpIndication,
            LocalRippleConfiguration provides null,
            // No overscroll stretch/glow animation on scrollable screens (E-Ink ghosting).
            LocalOverscrollConfiguration provides null,
            content = content,
        )
    }
}

/** Click without any indication (works even if a child overrides LocalIndication). */
@Composable
fun Modifier.einkClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null as Indication?, enabled = enabled, onClick = onClick)
}

/** High-contrast E-Ink icon-only button for kids: 2dp ink border, ≥56dp, inverted when [selected]. */
@Composable
fun EinkIconButton(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = Eink.Touch,
    shape: Shape = Eink.Shape,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
) {
    val bg = when {
        !enabled -> Eink.Paper
        selected -> Eink.Ink
        else -> Eink.White
    }
    val fg = when {
        !enabled -> Eink.Muted
        selected -> Eink.White
        else -> Eink.Ink
    }
    Box(
        modifier
            .defaultMinSize(minWidth = minHeight, minHeight = minHeight)
            .background(bg, shape)
            .border(Eink.Border, if (enabled) Eink.Ink else Eink.Muted, shape)
            .einkClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        icon(fg)
    }
}

/** High-contrast E-Ink button: 2dp ink border, ≥56dp, inverted when [selected]. */
@Composable
fun EinkButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = Eink.Touch,
    fontSize: Int = 17,
    shape: Shape = Eink.Shape,
    onClick: () -> Unit,
) {
    val bg = if (selected) Eink.Ink else Eink.White
    val fg = if (!enabled) Eink.Muted else if (selected) Eink.White else Eink.Ink
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = Eink.Touch, minHeight = minHeight)
            .background(bg, shape)
            .border(Eink.Border, if (enabled) Eink.Ink else Eink.Muted, shape)
            .einkClick(enabled, onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
    }
}

/**
 * High-contrast E-Ink button with custom vector icons around its label (the Home screen's
 * replacement for emoji-prefixed labels): same 2dp ink border / ≥56dp / inverted-when-selected
 * look as [EinkButton]; icons receive the current foreground tint.
 */
@Composable
fun EinkIconTextButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = Eink.Touch,
    fontSize: Int = 17,
    shape: Shape = Eink.Shape,
    leading: (@Composable (tint: Color) -> Unit)? = null,
    trailing: (@Composable (tint: Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val bg = if (selected) Eink.Ink else Eink.White
    val fg = if (!enabled) Eink.Muted else if (selected) Eink.White else Eink.Ink
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = Eink.Touch, minHeight = minHeight)
            .background(bg, shape)
            .border(Eink.Border, if (enabled) Eink.Ink else Eink.Muted, shape)
            .einkClick(enabled, onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(fg)
        if (text.isNotEmpty()) {
            Text(
                text,
                color = fg,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        trailing?.invoke(fg)
    }
}

/** Two-option toggle row ("开 / 关" style). */
@Composable
fun <T> EinkSegmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            EinkButton(label, selected = value == selected, modifier = Modifier.weight(1f)) { onSelect(value) }
        }
    }
}

@Composable
fun EinkCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .background(Eink.White, Eink.Shape)
            .border(Eink.Border, Eink.Ink, Eink.Shape)
            .padding(12.dp),
    ) { content() }
}
