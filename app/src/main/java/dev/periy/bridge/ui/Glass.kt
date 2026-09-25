package dev.periy.bridge.ui

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass for the floating bars (Settings, Appearance, Glass).
 *
 * What the bars float over is recorded into [BackdropState.layer] as it draws. A glass bar
 * draws that recording again, under itself: blurred and lifted, so colour from what is behind
 * shows through; bent near its rounded edge by a lens, the way a thick piece of glass bends
 * what is under its rim, with red and blue separating a little where the bend is steepest; a
 * sheen along the top and a bright rim finish it. The lens needs Android 13, the blur Android
 * 12; older phones get a translucent bar.
 */
class BackdropState(val layer: GraphicsLayer) {
    /** Where the recorded content sits in the window, so glass can find what is under it. */
    var origin by mutableStateOf(Offset.Zero)
}

val LocalBackdrop = staticCompositionLocalOf<BackdropState?> { null }

/** Whether the floating bars are glass (the shared Appearance switch). */
val LocalGlass = staticCompositionLocalOf { false }

@Composable
fun rememberBackdrop(): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropState(layer) }
}

/** Marks what glass shows through: draws the content as usual, and records it. */
fun Modifier.backdropSource(state: BackdropState): Modifier = this
    .onGloballyPositioned { state.origin = it.positionInWindow() }
    .drawWithContent {
        state.layer.record { this@drawWithContent.drawContent() }
        drawLayer(state.layer)
    }

/**
 * A floating bar's surface: glass when that is on (and a backdrop is recorded), else the
 * solid floating surface. [radius] is the corner radius, for the lens.
 */
@Composable
fun Modifier.glassBar(shape: Shape, radius: Dp, depth: Dp = 10.dp): Modifier {
    val backdrop = LocalBackdrop.current
    if (!LocalGlass.current || backdrop == null) return this.floating(shape)
    val dark = Bridge.Dark
    // As Apple's bars: frosted white over light content, a darkened tint of it over dark.
    val tint = if (dark) Color(0x47000000) else Color(0x94EFEFF2)
    val shadowed = this.shadow(if (dark) 12.dp else 22.dp, shape,
        ambientColor = Color(if (dark) 0x80000000 else 0x30000000), spotColor = Color(if (dark) 0x80000000 else 0x30000000))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        // No lens or blur before Android 12: a translucent bar with the same rims.
        return shadowed.clip(shape).background(Bridge.Surface.copy(alpha = 0.9f)).rims(dark, radius)
    }
    return shadowed.liquid(backdrop, shape, radius, depth, tint, dark).rims(dark, radius)
}

@Composable
private fun Modifier.liquid(backdrop: BackdropState, shape: Shape, radius: Dp, depth: Dp, tint: Color, dark: Boolean): Modifier {
    val layer = rememberGraphicsLayer()
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(LENS) else null }
    var at by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { at = it.positionInWindow() }
        .clip(shape)
        .drawBehind {
            // Softly blurred, still recognisable, and colour pushed up, the way glass makes it glow.
            val blur = 5.dp.toPx()
            val glow = android.graphics.RenderEffect.createColorFilterEffect(
                android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
                    setSaturation(1.9f)
                    if (!dark) postConcat(android.graphics.ColorMatrix().apply { setScale(1.06f, 1.06f, 1.06f, 1f) })
                })
            )
            var effect = android.graphics.RenderEffect.createChainEffect(
                glow, android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
            )
            if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r = radius.toPx().coerceAtMost(size.minDimension / 2)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("radius", r)
                shader.setFloatUniform("depth", depth.toPx().coerceAtMost(size.minDimension / 2))
                effect = android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"), effect,
                )
            }
            layer.renderEffect = effect.asComposeRenderEffect()
            val dx = backdrop.origin.x - at.x
            val dy = backdrop.origin.y - at.y
            layer.record { translate(dx, dy) { drawLayer(backdrop.layer) } }
            drawLayer(layer)
            drawRect(tint)
            // A little light under the top edge.
            drawRect(Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.07f else 0.22f),
                0.5f to Color.White.copy(alpha = 0f),
            ))
        }
}

/** The glass's edge: one thin bright line, lit where light enters top-left, glinting bottom-right. */
private fun Modifier.rims(dark: Boolean, radius: Dp): Modifier = this.drawWithContent {
    drawContent()
    val edge = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.42f else 0.95f),
        0.35f to Color.White.copy(alpha = if (dark) 0.12f else 0.55f),
        0.65f to Color.White.copy(alpha = if (dark) 0.10f else 0.50f),
        1f to Color.White.copy(alpha = if (dark) 0.30f else 0.90f),
        start = Offset(0f, 0f), end = Offset(size.width, size.height),
    )
    val r = radius.toPx().coerceAtMost(size.minDimension / 2)
    // Over white, a fine dark hairline outside the bright one keeps the edge crisp.
    if (!dark) {
        val h = 0.6.dp.toPx()
        drawRoundRect(Color(0x1F000000), topLeft = Offset(h / 2, h / 2), size = androidx.compose.ui.geometry.Size(size.width - h, size.height - h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r - h / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(h))
    }
    val w = 1.dp.toPx()
    val o = if (dark) 0f else 0.6.dp.toPx()
    drawRoundRect(edge, topLeft = Offset(o + w / 2, o + w / 2), size = androidx.compose.ui.geometry.Size(size.width - 2 * o - w, size.height - 2 * o - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius((r - o - w / 2).coerceAtLeast(0f)), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
}

/**
 * The lens. Distance to the rounded edge comes from a signed-distance function; within
 * [depth] of the edge each pixel samples from further inside, more steeply nearer the rim, so
 * what is behind curves into the glass. Red and blue bend a little more and a little less than
 * green: the fringe real glass shows.
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;

float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 h = size * 0.5;
    float2 p = coord - h;
    float d = sdRound(p, h, radius);
    float e = 1.0 - clamp(-d / depth, 0.0, 1.0);
    // Only a thin band at the rim bends; the rest is left alone.
    float bend = pow(e, 2.0) * depth * 1.0;
    float2 n = float2(
        sdRound(p + float2(1.0, 0.0), h, radius) - sdRound(p - float2(1.0, 0.0), h, radius),
        sdRound(p + float2(0.0, 1.0), h, radius) - sdRound(p - float2(0.0, 1.0), h, radius));
    float len = length(n);
    n = len > 0.0001 ? n / len : float2(0.0);
    float2 off = -n * bend;
    half4 g = content.eval(coord + off);
    // Red bends more than green, blue less: the coloured fringes at the edge.
    half r = content.eval(coord + off * 1.3).r;
    half b = content.eval(coord + off * 0.7).b;
    half3 c = half3(r, g.g, b);
    // The rim catches a little light.
    c += half3(pow(e, 8.0) * 0.10);
    return half4(c, 1.0);
}
"""
