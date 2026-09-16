package com.kotlinsyd.forte.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * TRUE frosted glass without a blur library (none exists in the
 * offline cache): the content a bar floats over is recorded once
 * into a [GraphicsLayer]; the bar then draws that layer back through
 * a RenderEffect blur, translated so the picture lines up with
 * what's really behind it, clipped to the bar's shape. A translucent
 * tint + light edge (GlassBox) finishes the glass.
 *
 * Recording happens in the draw phase and the layer is re-recorded
 * only when the content invalidates — scrolling repaints it, which
 * is exactly what makes the frosting "live". API 31+ for
 * RenderEffect; below that, callers fall back to the flat tint.
 */
class BackdropState internal constructor(val layer: GraphicsLayer) {
    var contentOrigin: Offset by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropState(layer) }
}

val backdropBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

/** Record this content into [state]'s layer as it draws. */
fun Modifier.recordBackdrop(state: BackdropState): Modifier = this
    .onGloballyPositioned { state.contentOrigin = it.positionInRoot() }
    .drawWithContent {
        state.layer.record {
            this@drawWithContent.drawContent()
        }
        drawContent()
    }

/**
 * Draw [state]'s recorded content, blurred, behind this element's
 * own content. Must be applied before the element's background/tint
 * and after its clip. No-op when blur is unsupported — the caller's
 * flat tint stands.
 */
@Composable
fun Modifier.glassBackdrop(
    state: BackdropState?,
    shape: Shape,
    blurRadius: Float = 26f,
): Modifier {
    if (state == null || !backdropBlurSupported) return this
    var pos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .clip(shape)
        .drawWithContent {
            state.layer.renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Clamp)
            translate(
                left = -(pos.x - state.contentOrigin.x),
                top = -(pos.y - state.contentOrigin.y),
            ) {
                drawLayer(state.layer)
            }
            drawContent()
        }
}
