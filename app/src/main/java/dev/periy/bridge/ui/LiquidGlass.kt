package dev.periy.bridge.ui

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Apple's Liquid Glass, for real rather than frosted.
 *
 * The screen's content is recorded once per frame into [BackdropState.layer]. Anything made
 * of liquid glass draws that recording again, under itself, through a lens: clear in the
 * middle, bending the content inwards near its rounded edge the way a thick drop of glass
 * does, with a faint colour fringe where the bend is strongest. A specular rim and a sheen
 * along the top sit on the surface. Needs Android 13 for the lens; older phones get the
 * blurred glass instead.
 */
class BackdropState(val layer: GraphicsLayer) {
    /** Where the recorded content sits in the window, so glass can find what is under it. */
    var origin by mutableStateOf(Offset.Zero)
}

val LocalBackdrop = staticCompositionLocalOf<BackdropState?> { null }

@Composable
fun rememberBackdrop(): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropState(layer) }
}

/** Marks what floating glass shows through: draws the content as usual, and records it. */
fun Modifier.backdropSource(state: BackdropState): Modifier = this
    .onGloballyPositioned { state.origin = it.positionInWindow() }
    .drawWithContent {
        state.layer.record { this@drawWithContent.drawContent() }
        drawLayer(state.layer)
    }

/** True where the lens can run. */
val liquidGlassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * A floating piece of liquid glass. [cornerRadius] null makes a capsule.
 * [depth] is how far in from the edge the glass bends light; [blur] is kept small on
 * purpose -- the content behind should stay readable, as through real glass.
 */
@Composable
fun Modifier.liquidGlass(
    cornerRadius: Dp? = null,
    depth: Dp = 16.dp,
    blur: Dp = 1.5.dp,
    tint: Color = Color(0x0DFFFFFF),
): Modifier {
    val backdrop = LocalBackdrop.current
    val shape = if (cornerRadius == null) ButtonShape else RoundedCornerShape(cornerRadius)
    if (backdrop == null || !liquidGlassSupported) return this.glass(shape, strong = true)
    return this.lens(backdrop, shape, cornerRadius, depth, blur, tint)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.lens(
    backdrop: BackdropState,
    shape: androidx.compose.ui.graphics.Shape,
    cornerRadius: Dp?,
    depth: Dp,
    blur: Dp,
    tint: Color,
): Modifier {
    val lensLayer = rememberGraphicsLayer()
    val shader = remember { RuntimeShader(LENS) }
    var at by remember { mutableStateOf(Offset.Zero) }
    return this
        .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .onGloballyPositioned { at = it.positionInWindow() }
        .clip(shape)
        .drawBehind {
            val r = cornerRadius?.toPx() ?: (size.minDimension / 2)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("radius", r.coerceAtMost(size.minDimension / 2))
            shader.setFloatUniform("depth", depth.toPx())
            val lensEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            val b = blur.toPx()
            lensLayer.renderEffect = (
                if (b > 0.5f) android.graphics.RenderEffect.createChainEffect(
                    lensEffect,
                    android.graphics.RenderEffect.createBlurEffect(b, b, Shader.TileMode.CLAMP),
                ) else lensEffect
            ).asComposeRenderEffect()
            val dx = backdrop.origin.x - at.x
            val dy = backdrop.origin.y - at.y
            lensLayer.record {
                translate(dx, dy) { drawLayer(backdrop.layer) }
            }
            drawLayer(lensLayer)
            drawRect(tint)
        }
        .background(SheenBrush)
        .border(BorderStroke(1.dp, LensRimBrush), shape)
}

/** Light pooling along the top of the glass, fading before the middle. */
private val SheenBrush = Brush.verticalGradient(
    0f to Color(0x2EFFFFFF),
    0.28f to Color(0x08FFFFFF),
    0.7f to Color(0x00FFFFFF),
    1f to Color(0x0FFFFFFF),
)

/** The rim: bright where light enters top-left, a glint bottom-right, nearly gone between. */
private val LensRimBrush = Brush.linearGradient(
    0f to Color(0xB3FFFFFF),
    0.3f to Color(0x1AFFFFFF),
    0.65f to Color(0x0DFFFFFF),
    1f to Color(0x66FFFFFF),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

/**
 * The lens. Distance to the rounded edge comes from a signed-distance function; within
 * [depth] of the edge, each pixel samples from further inside, more steeply the closer it
 * is to the rim, so the content appears to curve down into the glass. Red and blue bend a
 * little more and a little less than green, which is the fringe real glass shows.
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
    float bend = e * e * e * depth * 0.85;
    float2 n = float2(
        sdRound(p + float2(1.0, 0.0), h, radius) - sdRound(p - float2(1.0, 0.0), h, radius),
        sdRound(p + float2(0.0, 1.0), h, radius) - sdRound(p - float2(0.0, 1.0), h, radius));
    float len = length(n);
    n = len > 0.0001 ? n / len : float2(0.0);
    float2 off = -n * bend;
    half4 g = content.eval(coord + off);
    half r = content.eval(coord + off * 1.15).r;
    half b = content.eval(coord + off * 0.85).b;
    return half4(r, g.g, b, g.a);
}
"""
