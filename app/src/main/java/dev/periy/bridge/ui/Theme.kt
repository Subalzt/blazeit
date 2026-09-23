package dev.periy.bridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colours by *role*, not by hue.
 *
 * The classic theme could get away with a handful of named colours because black meant
 * the same thing everywhere. The glass theme breaks that: text turns white, bars turn
 * translucent, but text sitting on the yellow accent has to stay black in both. So every
 * colour here is named for what it is used for, and each theme fills the roles in.
 *
 * One rule survives both themes unchanged: yellow means "act on this", and there is only
 * ever one yellow thing that matters on screen.
 */
@Immutable
data class Palette(
    val glass: Boolean,
    val yellow: Color,
    /** Hero band. Solid in classic; faintly see-through in glass. */
    val heroBg: Color,
    /** Text and glyphs drawn on yellow. Black in both themes. */
    val onYellow: Color,
    val onYellowSoft: Color,
    /** Primary text on panels. */
    val text: Color,
    val muted: Color,
    /** Content panels. */
    val panel: Color,
    /** List rows, a step up from the panel. */
    val row: Color,
    /** Glyph chips, empty progress tracks, unselected options. */
    val chip: Color,
    /** Masthead, section bars, the tab strip. */
    val bar: Color,
    /** Non-yellow text on those bars. */
    val onBar: Color,
    /** Hairline around panels. Transparent in classic, where solid fills need no edge. */
    val outline: Color,
    val danger: Color,
    val good: Color,
)

val ClassicPalette = Palette(
    glass = false,
    yellow = Color(0xFFFFE500),
    heroBg = Color(0xFFFFE500),
    onYellow = Color(0xFF0A0A0A),
    onYellowSoft = Color(0xFF2A2A2A),
    text = Color(0xFF0A0A0A),
    muted = Color(0xFF5C5C5C),
    panel = Color(0xFFFFFFFF),
    row = Color(0xFFF0F0F0),
    chip = Color(0xFFD2D2D2),
    bar = Color(0xFF0A0A0A),
    onBar = Color(0xFFFFFFFF),
    outline = Color.Transparent,
    danger = Color(0xFFC62828),
    good = Color(0xFF1B7F3B),
)

/**
 * Dark frosted glass. Panels are thin washes of white over a glowing backdrop, edged with
 * a hairline so they read as sheets rather than smudges. Still square: the brief was the
 * material, not the rounded corners that usually come with it.
 */
val GlassPalette = Palette(
    glass = true,
    yellow = Color(0xFFFFE500),
    heroBg = Color(0xE6FFE500),
    onYellow = Color(0xFF0A0A0A),
    onYellowSoft = Color(0xFF2A2A2A),
    text = Color(0xFFF7F7F7),
    muted = Color(0xB3FFFFFF),
    panel = Color(0x14FFFFFF),
    row = Color(0x1FFFFFFF),
    chip = Color(0x33FFFFFF),
    bar = Color(0x99000000),
    onBar = Color(0xFFFFFFFF),
    outline = Color(0x2EFFFFFF),
    danger = Color(0xFFFF8A80),
    good = Color(0xFF86EFAC),
)

val LocalPalette = staticCompositionLocalOf { ClassicPalette }

/** Shorthand for the current palette's roles, readable at any call site in a composable. */
object Bridge {
    val Yellow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.yellow
    val HeroBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.heroBg
    val OnYellow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onYellow
    val OnYellowSoft: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onYellowSoft
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Paper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panel
    val RowBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.row
    val Chip: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.chip
    val Bar: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bar
    val OnBar: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onBar
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.danger
    val Good: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.good
}

/** Squared-off corners throughout, in both themes. */
val BlockShape = RoundedCornerShape(2.dp)

/** A content panel: fill plus, in glass, the hairline that makes it read as a sheet. */
@Composable
fun Modifier.panel(): Modifier = this
    .background(Bridge.Paper)
    .border(BorderStroke(1.dp, Bridge.Outline))

// ---------------------------------------------------------------------------- type

val TitleStyle = TextStyle(
    fontSize = 15.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 0.2.sp,
)

val LabelStyle = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 1.4.sp,
)

val BodyStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

val MonoStyle = TextStyle(
    fontSize = 15.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
)

// ---------------------------------------------------------------------------- parts

/**
 * What sits behind everything.
 *
 * Classic: plain white. Glass: near-black with large, soft glows of the brand yellow and
 * a warm amber, plus one cool patch for depth. This is what makes the glass read as glass
 * without a backdrop blur -- translucent panels over a smooth, glowing field look frosted,
 * because there is no detail behind them for a blur to soften anyway.
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    if (!p.glass) {
        Box(modifier.fillMaxSize().background(p.panel))
        return
    }
    Canvas(modifier.fillMaxSize()) {
        val d = size.maxDimension
        drawRect(Color(0xFF07070A))
        fun glow(color: Color, x: Float, y: Float, r: Float) = drawRect(
            Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = Offset(size.width * x, size.height * y),
                radius = d * r,
            )
        )
        glow(Color(0x8CFFE500), 0.10f, 0.06f, 0.62f)
        glow(Color(0x66FF9F1C), 0.98f, 0.42f, 0.55f)
        glow(Color(0x403D5AFE), 0.85f, 1.00f, 0.55f)
        glow(Color(0x1FFFF4C2), 0.05f, 0.92f, 0.45f)
    }
}

/**
 * The diagonal hazard band. Drawn rather than tiled so it scales to any width without
 * seams, and the stripe pitch stays proportional to its height.
 */
@Composable
fun HazardStripe(height: Dp = 10.dp, modifier: Modifier = Modifier) {
    val yellow = Bridge.Yellow
    val dark = Bridge.OnYellow
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(yellow)
        val h = size.height
        val pitch = h * 1.7f
        var x = -h * 2
        while (x < size.width + h * 2) {
            val p = Path().apply {
                moveTo(x, h)
                lineTo(x + h, 0f)
                lineTo(x + h + pitch / 2f, 0f)
                lineTo(x + pitch / 2f, h)
                close()
            }
            drawPath(p, dark)
            x += pitch
        }
    }
}

/** Dark bar with a shouted label. The structural divider of every screen. */
@Composable
fun SectionBar(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Bridge.Bar)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = LabelStyle,
            color = Bridge.Yellow,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * Yellow call to action. Used only where there is a single obvious next step.
 *
 * `onClick` is last so the trailing-lambda form reads naturally. With a Boolean in that
 * position instead, `BridgeButton("Go") { ... }` silently binds the lambda to the flag.
 */
@Composable
fun BridgeButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (enabled) Bridge.Yellow else Bridge.Chip, BlockShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = LabelStyle,
            color = if (enabled) Bridge.OnYellow else Bridge.Muted,
        )
    }
}

/** Outlined secondary action. Same lambda-last shape as [BridgeButton]. */
@Composable
fun GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = if (danger) Bridge.Danger else Bridge.Text
    Box(
        modifier
            .background(Bridge.Paper, BlockShape)
            .border(BorderStroke(2.dp, fg), BlockShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = LabelStyle, color = fg)
    }
}

/** Small square glyph chip. */
@Composable
fun Glyph(symbol: String, bg: Color = Bridge.Chip, fg: Color = Bridge.Text) {
    Box(
        Modifier.size(34.dp).background(bg, BlockShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = fg)
    }
}

/**
 * A list row: glyph, bold title, optional meta on the right.
 * `accent` paints the row yellow, for the one row that needs attention.
 */
@Composable
fun BridgeRow(
    title: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    meta: String? = null,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(if (accent) Bridge.Yellow else Bridge.RowBg)
            .border(BorderStroke(1.dp, if (accent) Color.Transparent else Bridge.Outline))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                Glyph(
                    glyph,
                    bg = if (accent) Bridge.OnYellow else Bridge.Chip,
                    fg = if (accent) Bridge.Yellow else Bridge.Text,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                title,
                style = TitleStyle,
                color = if (accent) Bridge.OnYellow else Bridge.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (meta != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    meta, style = BodyStyle, maxLines = 1,
                    color = if (accent) Bridge.OnYellowSoft else Bridge.Muted,
                )
            }
        }
        content()
    }
}

/** Body copy inside a row. */
@Composable
fun RowNote(text: String, color: Color = Bridge.Muted) {
    Text(text, style = BodyStyle, color = color, modifier = Modifier.padding(top = 6.dp))
}

/**
 * Text input drawn by hand rather than with a Material text field, so it follows the
 * palette and the square shape exactly.
 */
@Composable
fun BridgeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: Dp = 96.dp,
    mono: Boolean = false,
) {
    val text = Bridge.Text
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(Bridge.Paper, BlockShape)
            .border(BorderStroke(2.dp, text), BlockShape)
            .padding(10.dp)
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, style = BodyStyle, color = Bridge.Muted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = if (mono) MonoStyle.copy(color = text)
            else BodyStyle.copy(color = text, fontSize = 14.sp),
            cursorBrush = SolidColor(text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Flat progress track, square, no animation flourish. */
@Composable
fun BlockProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Bridge.Chip)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(Bridge.Text)
        )
    }
}

/** Segmented control: the active segment is yellow, the rest sit on the bar. */
@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().background(Bridge.Bar),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Bridge.Yellow else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(),
                    style = LabelStyle,
                    color = if (on) Bridge.OnYellow else Bridge.OnBar,
                )
            }
        }
    }
}
