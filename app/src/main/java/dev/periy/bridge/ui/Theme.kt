package dev.periy.bridge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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

/**
 * BlazeIt's look: plain, quick, and at home next to the phone's own apps.
 *
 * Solid surfaces on a quiet background, large confident type, and colour used the way a
 * home screen uses it: small bright tiles that say what a thing is. Light or dark follows the
 * phone unless chosen; dark is graphite, or pure black with OLED black on. With Glass on, the
 * floating bars are frosted glass over the content (see Glass.kt); the cards stay solid.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    /** A well inside a surface: text fields, tracks, secondary buttons. */
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val outline: Color,
    val yellow: Color,
    val onYellow: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val purple: Color,
    val red: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF2F2F5),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEFEFF3),
    text = Color(0xFF0B0B0D),
    muted = Color(0xFF6C6C75),
    faint = Color(0xFFABABB4),
    outline = Color(0x14000000),
    yellow = Color(0xFFFFC21A),
    onYellow = Color(0xFF15120A),
    blue = Color(0xFF1F78FF),
    green = Color(0xFF1FB855),
    orange = Color(0xFFFF7A1A),
    purple = Color(0xFF7B5CFF),
    red = Color(0xFFF23B30),
)

/** Dark as a soft graphite, the phone's own dark mode. */
val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF111113),
    surface = Color(0xFF1C1C1F),
    surface2 = Color(0xFF2A2A2F),
    text = Color(0xFFF5F5F7),
    muted = Color(0xFF9C9CA5),
    faint = Color(0xFF5E5E66),
    outline = Color(0x1FFFFFFF),
    yellow = Color(0xFFFFC933),
    onYellow = Color(0xFF15120A),
    blue = Color(0xFF3D8BFF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF8A2A),
    purple = Color(0xFF9079FF),
    red = Color(0xFFFF453A),
)

/** Dark as pure black, for OLED screens (Settings, Appearance, OLED black). */
val OledPalette = Palette(
    dark = true,
    bg = Color(0xFF000000),
    surface = Color(0xFF121214),
    surface2 = Color(0xFF212125),
    text = Color(0xFFF5F5F7),
    muted = Color(0xFF9C9CA5),
    faint = Color(0xFF5A5A62),
    outline = Color(0x1FFFFFFF),
    yellow = Color(0xFFFFC933),
    onYellow = Color(0xFF15120A),
    blue = Color(0xFF3D8BFF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF8A2A),
    purple = Color(0xFF9079FF),
    red = Color(0xFFFF453A),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Picks the palette from the shared settings: "system", "light" or "dark", and the look. */
@Composable
fun BlazeTheme(theme: String, look: dev.periy.bridge.Look = dev.periy.bridge.Look(), content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val p = when {
        !dark -> LightPalette
        look.oled -> OledPalette
        else -> DarkPalette
    }
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalGlass provides look.glass,
        LocalTextStyle provides TextStyle(color = p.text),
        content = content,
    )
}

/** Shorthand for the current palette's roles, readable at any call site in a composable. */
object Bridge {
    val Dark: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.dark
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Chip: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
    val Bar: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Yellow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.yellow
    val OnYellow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onYellow
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Faint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.faint
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.red
    val Good: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.green
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.blue
    val Orange: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.orange
    val Purple: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.purple
}

val CardShape = RoundedCornerShape(26.dp)
val TileShape = RoundedCornerShape(24.dp)
val ButtonShape = RoundedCornerShape(50)
val IconShape = RoundedCornerShape(13.dp)

// ---------------------------------------------------------------------------- type

val LargeTitleStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
val DisplayStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum")
val TitleStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
val LabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
val BodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
val MonoStyle = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
val NumberStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum")

// ---------------------------------------------------------------------------- surfaces

/** A card: a solid surface with rounded corners. */
@Composable
fun Modifier.card(shape: Shape = CardShape, color: Color = Bridge.Surface): Modifier =
    this.clip(shape).background(color)

/** A card inset from the screen edges, as every card on a screen is. */
@Composable
fun Modifier.panel(): Modifier = this.padding(horizontal = 16.dp).card()

/** A floating piece: the monitor pill and its sheet. Solid, with a soft shadow. */
@Composable
fun Modifier.floating(shape: Shape = ButtonShape): Modifier =
    this.shadow(if (Bridge.Dark) 0.dp else 14.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
        .clip(shape)
        .background(Bridge.Surface)
        .then(if (Bridge.Dark) Modifier.background(Color(0x0DFFFFFF)) else Modifier)

/** Tappable with the platform ripple, clipped to [shape]. */
@Composable
fun Modifier.tap(shape: Shape? = null, onClick: () -> Unit): Modifier =
    (if (shape != null) this.clip(shape) else this).clickable(onClick = onClick)

// ---------------------------------------------------------------------------- parts

/** A section title above a card: small, bold, out of the way. */
@Composable
fun SectionBar(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    // Title in line with the text inside the cards; anything on the right in line with their edge.
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp + 30.dp).padding(start = 28.dp, end = 18.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = Bridge.Muted,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * An icon on a rounded square of solid colour, like an app icon on the home screen. It
 * says at a glance what a row or a tile is about.
 */
@Composable
fun AppIcon(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(color),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.56f)) }
}

/** The main button: a solid capsule. Lambda last, so `BridgeButton("Go") { }` reads right. */
@Composable
fun BridgeButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Bridge.Yellow,
    textColor: Color = if (color == Bridge.Yellow) Bridge.OnYellow else Color.White,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = 46.dp)
            .clip(ButtonShape)
            .background(if (enabled) color else Bridge.Chip)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) textColor else Bridge.Muted, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = if (enabled) textColor else Bridge.Muted)
    }
}

/** A quiet capsule on the well colour, for everything that is not the main action. */
@Composable
fun SoftButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Bridge.Text,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = 38.dp)
            .clip(ButtonShape)
            .background(Bridge.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = tint)
    }
}

/** A small action beside a section's title: an icon and a word, on the well colour or lit. */
@Composable
fun HeaderAction(
    icon: ImageVector,
    label: String,
    tint: Color = Bridge.Text,
    lit: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .height(32.dp)
            .clip(ButtonShape)
            .background(if (lit) Bridge.Yellow else Bridge.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (lit) Bridge.OnYellow else tint
        Icon(icon, null, tint = c, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = c)
    }
}

/** A round icon button on the well colour. */
@Composable
fun IconChip(icon: ImageVector, description: String, tint: Color = Bridge.Text, bg: Color = Bridge.Chip, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(19.dp)) }
}

/**
 * A home-screen widget: an app icon, a title and a line under it. When [active] the whole
 * tile takes the icon's colour, so what is switched on is obvious from across the room.
 */
@Composable
fun Tile(
    icon: ImageVector,
    color: Color,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) color else Bridge.Surface, tween(160), label = "tile")
    val fg = if (active) (if (color == Bridge.Yellow) Bridge.OnYellow else Color.White) else Bridge.Text
    Column(
        modifier
            .clip(TileShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        if (active) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = fg, modifier = Modifier.size(21.dp)) }
        } else AppIcon(icon, color)
        Spacer(Modifier.height(18.dp))
        Spacer(Modifier.weight(1f))
        Text(title, style = TitleStyle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                detail, style = BodyStyle.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = if (active) fg.copy(alpha = 0.75f) else Bridge.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Body copy inside a card. */
@Composable
fun RowNote(text: String, color: Color = Bridge.Muted, modifier: Modifier = Modifier) {
    Text(text, style = BodyStyle, color = color, modifier = modifier.padding(top = 6.dp))
}

/** A text field: a well inside the card. */
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
            .clip(RoundedCornerShape(16.dp))
            .background(Bridge.Chip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = BodyStyle.copy(fontSize = 15.sp), color = Bridge.Faint)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = mono,
            textStyle = if (mono) MonoStyle.copy(color = text) else BodyStyle.copy(color = text, fontSize = 15.sp),
            cursorBrush = SolidColor(Bridge.Yellow),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A thin progress bar. */
@Composable
fun BlockProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = Bridge.Yellow, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(ButtonShape).background(Bridge.Chip)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(ButtonShape).background(color))
    }
}

/** A segmented control: a well with the chosen segment raised on it. */
@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val sel = selectedIndex.coerceIn(-1, options.lastIndex)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(40.dp).clip(ButtonShape).background(Bridge.Chip).padding(3.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * sel.coerceAtLeast(0), tween(180), label = "seg")
        if (sel >= 0) {
            Box(
                Modifier.offset(x = x).width(w).fillMaxHeight()
                    .shadow(if (Bridge.Dark) 0.dp else 2.dp, ButtonShape)
                    .clip(ButtonShape)
                    .background(if (Bridge.Dark) Color(0xFF3A3A40) else Color.White)
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(ButtonShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = TextStyle(fontSize = 13.5.sp, fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (i == sel) Bridge.Text else Bridge.Muted,
                    )
                }
            }
        }
    }
}

/** An on/off switch in the accent colour. */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier, color: Color = Bridge.Good, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (on) color else Bridge.Faint.copy(alpha = 0.45f), tween(160), label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, tween(160), label = "knob")
    Box(
        modifier
            .width(52.dp)
            .height(32.dp)
            .clip(ButtonShape)
            .background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(bounded = false, radius = 26.dp)) { onChange(!on) }
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = x).size(26.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** Rows sharing one card, with hairlines between them. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().panel(), content = content)
}

/** One setting: optional icon, title, a line of detail, and a control on the right. */
@Composable
fun SettingRow(
    title: String,
    detail: String? = null,
    first: Boolean = false,
    titleColor: Color = Bridge.Text,
    icon: ImageVector? = null,
    iconColor: Color = Bridge.Blue,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    if (!first) Box(Modifier.fillMaxWidth().padding(start = if (icon != null) 66.dp else 18.dp).height(0.5.dp).background(Bridge.Outline))
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, iconColor, size = 34.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** How much of the bottom a floating glass tab bar covers, above the navigation inset. */
val GlassTabBarSpace = 64.dp + 12.dp

/**
 * The tab bar. Plain: flat, on the surface colour, on the bottom edge like the phone's own
 * navigation, with a pill behind the current tab's icon. Glass: a floating capsule over the
 * content, the current tab lit inside it.
 */
@Composable
fun TabBar(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    if (LocalGlass.current) {
        GlassTabBar(items, selected, bottomInset, modifier, onSelect)
        return
    }
    Column(modifier.fillMaxWidth().background(Bridge.Surface)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Bridge.Outline))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp + bottomInset, start = 8.dp, end = 8.dp)) {
            items.forEachIndexed { i, (label, icon) ->
                val lit = i == selected
                val pill by animateColorAsState(if (lit) Bridge.Yellow.copy(alpha = if (Bridge.Dark) 0.22f else 0.32f) else Color.Transparent, tween(160), label = "pill")
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.width(58.dp).height(32.dp).clip(ButtonShape).background(pill),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, label, tint = if (lit) Bridge.Text else Bridge.Muted, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        style = TextStyle(fontSize = 11.5.sp, fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (lit) Bridge.Text else Bridge.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassTabBar(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    bottomInset: Dp,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 12.dp)
            .height(64.dp)
            .glassBar(shape, 32.dp)
            .padding(5.dp),
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val lit = i == selected
            // The tab you are on is a bubble of glass inside the bar, with its own lit rim.
            val cell by animateColorAsState(
                if (lit) (if (Bridge.Dark) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.06f)) else Color.Transparent,
                tween(180), label = "cell",
            )
            val edge by animateColorAsState(
                if (lit) Color.White.copy(alpha = if (Bridge.Dark) 0.14f else 0.6f) else Color.Transparent,
                tween(180), label = "edge",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(27.dp))
                    .background(cell)
                    .border(1.dp, edge, RoundedCornerShape(27.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, label, tint = if (lit) Bridge.Text else Bridge.Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    style = TextStyle(fontSize = 11.sp, fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (lit) Bridge.Text else Bridge.Muted,
                )
            }
        }
    }
}

/** A row as its own card: used for transfers and one-off notes. */
@Composable
fun BridgeRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = Bridge.Blue,
    meta: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .card()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                AppIcon(icon, iconColor, size = 36.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = TitleStyle.copy(fontSize = 15.5.sp), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (meta != null) {
                Spacer(Modifier.width(8.dp))
                Text(meta, style = LabelStyle, maxLines = 1, color = Bridge.Muted)
            }
            trailing()
        }
        content()
    }
}
