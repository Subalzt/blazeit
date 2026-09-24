package dev.periy.bridge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The spring every lens moves on: quick, with a little overshoot, like liquid settling. */
private val LensSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 420f)

/** A drop of glass: brighter at the top, with the light-catching rim. */
private val LensFill = Brush.verticalGradient(listOf(Color(0x47FFFFFF), Color(0x17FFFFFF)))
private val LensRim = Brush.linearGradient(
    0f to Color(0xCCFFFFFF), 0.35f to Color(0x26FFFFFF), 0.7f to Color(0x0DFFFFFF), 1f to Color(0x66FFFFFF),
)

/**
 * A row of choices with a glass lens under the current one, the way iOS 26 does it: tap and
 * the lens slides across on a spring; or put a finger down and drag, and the lens follows
 * the finger, swelling a little while it is held, and whatever it rests on when you let go
 * is chosen. [bulge] lets the lens stand proud of the track, top and bottom.
 */
@Composable
fun LiquidBar(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bulge: Dp = 0.dp,
    item: @Composable (index: Int, lit: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(0) }
    val pos = remember { Animatable(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var lastLit by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) { if (!dragging) pos.animateTo(selected.toFloat(), LensSpring) }
    val swell by animateFloatAsState(if (dragging) 1.1f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), label = "swell")
    val lit = if (dragging) pos.value.roundToInt().coerceIn(0, count - 1) else selected
    if (dragging && lit != lastLit) { lastLit = lit; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val density = LocalDensity.current

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .pointerInput(count) {
                fun unit() = (width / count.toFloat()).coerceAtLeast(1f)
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        scope.launch { pos.animateTo((o.x / unit() - 0.5f).coerceIn(0f, count - 1f), spring(stiffness = 900f)) }
                    },
                    onDragEnd = {
                        val target = pos.value.roundToInt().coerceIn(0, count - 1)
                        dragging = false
                        onSelect(target)
                        scope.launch { pos.animateTo(target.toFloat(), LensSpring) }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { pos.animateTo(selected.toFloat(), LensSpring) }
                    },
                ) { change, dx ->
                    change.consume()
                    scope.launch { pos.snapTo((pos.value + dx / unit()).coerceIn(0f, count - 1f)) }
                }
            },
    ) {
        if (width > 0) {
            val itemW = width / count.toFloat()
            val extra = with(density) { bulge.roundToPx() }
            Box(
                Modifier
                    .matchParentSize()
            ) {
                Box(
                    Modifier
                        .offset { IntOffset((pos.value * itemW).roundToInt(), 0) }
                        .width(with(density) { itemW.toDp() })
                        .fillMaxHeight()
                        // Stand proud of the track by [bulge] at top and bottom.
                        .layout { m, c ->
                            val p = m.measure(c.copy(minHeight = c.maxHeight + 2 * extra, maxHeight = c.maxHeight + 2 * extra))
                            layout(p.width, c.maxHeight) { p.place(0, -extra) }
                        }
                        .graphicsLayer { scaleX = swell; scaleY = swell }
                        .shadow(if (bulge > 0.dp) 8.dp else 0.dp, ButtonShape, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(ButtonShape)
                        .background(LensFill)
                        .border(BorderStroke(1.dp, LensRim), ButtonShape),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            repeat(count) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { item(i, i == lit) }
            }
        }
    }
}

/**
 * A switch like the ones in iOS 26 settings: a capsule with its label, and a glass knob --
 * larger than the track, so it bulges out of it -- carrying an icon. Tap to flip, or drag
 * the knob across.
 */
@Composable
fun LiquidSwitch(
    on: Boolean,
    labelOff: String,
    labelOn: String,
    iconOff: ImageVector,
    iconOn: ImageVector,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val trackW = 176.dp
    val trackH = 44.dp
    val knob = 54.dp
    val density = LocalDensity.current
    val travel = with(density) { (trackW - knob + 8.dp).toPx() }
    val x = remember { Animatable(if (on) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(on) { if (!dragging) x.animateTo(if (on) 1f else 0f, LensSpring) }
    val swell by animateFloatAsState(if (dragging) 1.08f else 1f, label = "knob")

    Box(
        modifier
            .width(trackW)
            .height(trackH)
            .background(Color(0x40000000), ButtonShape)
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x40FFFFFF)))), ButtonShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!on) }
            .pointerInput(on) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        val now = x.value > 0.5f
                        scope.launch { x.animateTo(if (now) 1f else 0f, LensSpring) }
                        if (now != on) onChange(now)
                    },
                    onDragCancel = { dragging = false; scope.launch { x.animateTo(if (on) 1f else 0f, LensSpring) } },
                ) { change, dx ->
                    change.consume()
                    scope.launch { x.snapTo((x.value + dx / travel).coerceIn(0f, 1f)) }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The label sits on the side the knob is not.
        Text(
            if (x.value > 0.5f) labelOn else labelOff,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = Bridge.Text,
            modifier = Modifier.align(if (x.value > 0.5f) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 20.dp),
        )
        Box(
            Modifier
                .offset { IntOffset((-4.dp.roundToPx() + x.value * travel).roundToInt(), 0) }
                .size(knob)
                .graphicsLayer { scaleX = swell; scaleY = swell }
                .shadow(14.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x26FFFFFF))))
                .border(BorderStroke(1.2.dp, LensRim), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (x.value > 0.5f) iconOn else iconOff, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}
