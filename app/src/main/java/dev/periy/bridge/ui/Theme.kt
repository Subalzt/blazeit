package dev.periy.bridge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Apple's Liquid Glass, as closely as Android allows.
 *
 * Every surface is a sheet of glass: a glossy fill that is lighter at the top, and a rim
 * that catches light along its upper-left edge and fades across the sheet. Things that
 * float -- the tab bar, the monitor -- also blur whatever scrolls behind them. Controls are
 * capsules. One accent, the brand yellow, for the thing to act on.
 */
@Immutable
data class Palette(
    val yellow: Color,
    val onYellow: Color,
    val onYellowSoft: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val panel: Color,
    val row: Color,
    val chip: Color,
    val bar: Color,
    val onBar: Color,
    val outline: Color,
    val danger: Color,
    val good: Color,
    val blue: Color,
)

val GlassPalette = Palette(
    yellow = Color(0xFFFFD60A),
    onYellow = Color(0xFF0A0A0A),
    onYellowSoft = Color(0xB30A0A0A),
    text = Color(0xF7FFFFFF),
    muted = Color(0xA6EBEBF5),
    faint = Color(0x4DEBEBF5),
    panel = Color(0x1FFFFFFF),
    row = Color(0x1AFFFFFF),
    chip = Color(0x2EFFFFFF),
    bar = Color(0x59101014),
    onBar = Color(0xFFFFFFFF),
    outline = Color(0x26FFFFFF),
    danger = Color(0xFFFF453A),
    good = Color(0xFF30D158),
    blue = Color(0xFF0A84FF),
)

val LocalPalette = staticCompositionLocalOf { GlassPalette }

/** What floating glass blurs: everything drawn behind it. Null where no blur is set up. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** Shorthand for the current palette's roles, readable at any call site in a composable. */
object Bridge {
    val Yellow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.yellow
    val OnYellow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onYellow
    val OnYellowSoft: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onYellowSoft
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Faint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.faint
    val Paper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panel
    val RowBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.row
    val Chip: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.chip
    val Bar: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bar
    val OnBar: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onBar
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.danger
    val Good: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.good
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.blue
}

val CardShape = RoundedCornerShape(28.dp)
val ButtonShape = RoundedCornerShape(50)
val BlockShape = RoundedCornerShape(14.dp)

// ---------------------------------------------------------------------------- glass

/** The light-catching rim: bright at the top-left, clear through the middle, a glint at the bottom-right. */
private val RimBrush = Brush.linearGradient(
    0f to Color(0x99FFFFFF),
    0.35f to Color(0x14FFFFFF),
    0.7f to Color(0x0AFFFFFF),
    1f to Color(0x40FFFFFF),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

/** The glossy fill: a little more light at the top, as if lit from above. */
private val FillBrush = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x12FFFFFF)))

/** Floating glass is clearer than a card: less fill, and a bright specular band along the top. */
private val FloatFillBrush = Brush.verticalGradient(
    0f to Color(0x33FFFFFF),
    0.35f to Color(0x0FFFFFFF),
    1f to Color(0x0AFFFFFF),
)

/** A sheet of glass in [shape]. [strong] for floating glass that sits over content and blurs it. */
@Composable
fun Modifier.glass(shape: Shape = CardShape, strong: Boolean = false): Modifier {
    val haze = LocalHaze.current
    return this
        .then(
            if (strong) Modifier.shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            else Modifier
        )
        .clip(shape)
        .then(
            if (strong && haze != null) Modifier.hazeEffect(state = haze) {
                blurRadius = 22.dp
                backgroundColor = Color(0xFF0B0B12)
                tints = listOf(HazeTint(Color(0x14FFFFFF)))
                noiseFactor = 0.03f
            } else Modifier
        )
        .background(if (strong) FloatFillBrush else FillBrush)
        .border(BorderStroke(if (strong) 1.2.dp else 1.dp, RimBrush), shape)
}

/** A card: glass inset from the screen edges. */
@Composable
fun Modifier.panel(): Modifier = this.padding(horizontal = 16.dp).glass(CardShape)

// ---------------------------------------------------------------------------- type

val LargeTitleStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
val TitleStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
val LabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
val BodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
val MonoStyle = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
val NumberStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

// ---------------------------------------------------------------------------- parts

/**
 * The wallpaper the glass sits on: deep and dark, with large soft pools of colour. Liquid
 * Glass is only as good as what shows through it, so this carries real colour.
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val d = size.maxDimension
        drawRect(Color(0xFF06060A))
        fun glow(color: Color, x: Float, y: Float, r: Float) = drawRect(
            Brush.radialGradient(listOf(color, Color.Transparent), Offset(size.width * x, size.height * y), d * r)
        )
        glow(Color(0xA65E5CE6), 0.00f, 0.05f, 0.62f)
        glow(Color(0x8064D2FF), 1.00f, 0.30f, 0.52f)
        glow(Color(0x66BF5AF2), 0.85f, 0.78f, 0.50f)
        glow(Color(0x59FFD60A), 0.10f, 0.95f, 0.55f)
        glow(Color(0x33FF9F0A), 0.55f, 0.55f, 0.40f)
    }
}

/** A grouped-list section header: small, grey, above its card. */
@Composable
fun SectionBar(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 30.dp, end = 28.dp, top = 24.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
            color = Bridge.Text.copy(alpha = 0.86f),
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** The accent capsule, for the one obvious next step. Lambda last, so `BridgeButton("Go") { }` reads right. */
@Composable
fun BridgeButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(ButtonShape)
            .background(
                if (enabled) Brush.verticalGradient(listOf(Color(0xFFFFE45C), Color(0xFFFFC400)))
                else SolidColor(Bridge.Chip)
            )
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x00FFFFFF)))), ButtonShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = if (enabled) Bridge.OnYellow else Bridge.Muted)
    }
}

/** A glass capsule for secondary actions. */
@Composable
fun GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier.glass(ButtonShape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = if (danger) Bridge.Danger else Bridge.Text)
    }
}

/**
 * A tappable row inside a card: an icon in a tinted circle, a title with a line of detail,
 * and a chevron. For actions that are not the one primary thing on screen.
 */
@Composable
fun ActionRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    tint: Color = Bridge.Yellow,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text)
            if (detail != null) Text(detail, style = BodyStyle.copy(fontSize = 13.sp, lineHeight = 17.sp), color = Bridge.Muted)
        }
        Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp))
    }
}

/** A small glass circle with an icon, for quiet actions. */
@Composable
fun IconChip(icon: ImageVector, description: String, tint: Color = Bridge.Text, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).glass(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** Small round glyph. */
@Composable
fun Glyph(symbol: String, bg: Color = Bridge.Chip, fg: Color = Bridge.Text) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(symbol, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** A row as its own sheet of glass. `accent` makes it the yellow one that is waiting on you. */
@Composable
fun BridgeRow(
    title: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    meta: String? = null,
    accent: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .then(
                if (accent) Modifier.clip(CardShape).background(Brush.verticalGradient(listOf(Color(0xFFFFE45C), Color(0xFFFFC400))))
                    .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x00FFFFFF)))), CardShape)
                else Modifier.glass(CardShape)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                Glyph(glyph, bg = if (accent) Bridge.OnYellow else Bridge.Chip, fg = if (accent) Bridge.Yellow else Bridge.Text)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title, style = TitleStyle, color = if (accent) Bridge.OnYellow else Bridge.Text,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (meta != null) {
                Spacer(Modifier.width(8.dp))
                Text(meta, style = BodyStyle, maxLines = 1, color = if (accent) Bridge.OnYellowSoft else Bridge.Muted)
            }
            trailing()
        }
        content()
    }
}

/** Body copy inside a row. */
@Composable
fun RowNote(text: String, color: Color = Bridge.Muted) {
    Text(text, style = BodyStyle, color = color, modifier = Modifier.padding(top = 6.dp))
}

/** A text field: a recessed well in the glass. */
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
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x33000000))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x40FFFFFF)))), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = BodyStyle, color = Bridge.Faint)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = if (mono) MonoStyle.copy(color = text) else BodyStyle.copy(color = text, fontSize = 15.sp),
            cursorBrush = SolidColor(Bridge.Yellow),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A thin capsule progress bar with a glowing fill. */
@Composable
fun BlockProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(Color(0x33000000))) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(shape)
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFC400), Color(0xFFFFE45C))))
        )
    }
}

/** A segmented control: a glass track with a brighter lens over the choice. */
@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(ButtonShape).background(Color(0x33000000)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .then(if (on) Modifier.glass(ButtonShape) else Modifier.clip(ButtonShape))
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = LabelStyle, color = if (on) Bridge.Text else Bridge.Muted) }
        }
    }
}

/** The iOS switch: green when on, a white knob that slides. */
@Composable
fun IosSwitch(on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (on) Bridge.Good else Color(0x52787880), label = "track")
    val x by animateFloatAsState(if (on) 1f else 0f, label = "knob")
    Box(
        modifier
            .width(51.dp)
            .height(31.dp)
            .clip(ButtonShape)
            .background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!on) }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .padding(start = 20.dp * x)
                .size(27.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE9E9EE))))
        )
    }
}

/** Rows of a grouped list sharing one sheet of glass, with hairlines between them. */
@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().panel(), content = content)
}

/** One setting: title, optional detail underneath, and a control or value on the right. */
@Composable
fun SettingRow(
    title: String,
    detail: String? = null,
    first: Boolean = false,
    titleColor: Color = Bridge.Text,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    if (!first) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Bridge.Outline))
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.sp), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = BodyStyle.copy(fontSize = 13.sp, lineHeight = 17.sp), color = Bridge.Muted)
        }
        trailing()
    }
}

/** Four bars for signal strength, filled up to [level] (0-4). */
@Composable
fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    val on = Bridge.Text
    val off = Bridge.Faint
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 4) {
            Box(Modifier.width(3.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i < level) on else off))
        }
    }
}

/**
 * The floating tab bar: a capsule of real, blurred glass, with a brighter lens under the
 * current tab.
 */
@Composable
fun GlassTabBar(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier
            .padding(horizontal = 22.dp)
            .fillMaxWidth()
            .liquidGlass(blur = 6.dp, tint = Color(0x2E08080C))
            .padding(4.dp),
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(ButtonShape)
                    .then(
                        // The current tab sits under a lens: a clearer, brighter drop of glass.
                        if (on) Modifier
                            .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF))))
                            .border(BorderStroke(1.dp, RimBrush), ButtonShape)
                        else Modifier
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, label, tint = if (on) Bridge.Yellow else Bridge.Text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold), color = if (on) Bridge.Yellow else Bridge.Text.copy(alpha = 0.8f))
            }
        }
    }
}
