package com.kotlinsyd.forte.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * TRUE frosted glass without a blur library (none exists in the
 * offline cache): the content a bar floats over is recorded once
 * into a [GraphicsLayer]; a second layer replays that recording
 * through a RenderEffect blur; the bar then draws the blurred
 * copy behind itself, translated so the picture lines up with
 * what's really behind it, clipped to the bar's shape. A
 * translucent tint + light edge (GlassBox) finishes the glass.
 *
 * Recording happens in the draw phase and the layers are
 * re-recorded only when the content invalidates — scrolling
 * repaints it, which is exactly what makes the frosting "live".
 * API 31+ for RenderEffect; below that, callers fall back to
 * the flat tint.
 *
 * WHERE THE BLUR LAYER IS RECORDED IS THE WHOLE GAME
 * (v1.0.25-test — the capsule-frost placement fix).
 *
 * A GraphicsLayer recorded from inside a draw scope takes that
 * scope's size. The blur layer must therefore be recorded from
 * inside the CONTENT's own draw — the full-screen scope — or it
 * comes out the size of whatever small element asked for it,
 * holding only that element-sized patch of the content's
 * top-left corner.
 *
 * That is exactly what v1.0.24-test got wrong. Its two-layer
 * change moved the blur record into the CONSUMER (the ~62dp
 * dock): the blur layer came out dock-sized, holding the page's
 * top-left dock-sized patch; the dock then translated it by
 * -(dock position) — roughly two thousand pixels up — which
 * moved the patch entirely outside the capsule's clip. The
 * capsule drew NO blurred picture at all, only its translucent
 * tint over the live page beneath: page text showed through
 * SHARP, reading as a pale, clear wash. The owner's verdict:
 * "capsules are still clear".
 *
 * The two arrangements that provably blur on the owner's phone
 * never record small:
 *  - Forte v1.0.19 put the BlurEffect on the recorded CONTENT
 *    layer itself (full-screen, recorded by the page) and the
 *    dock drew that layer translated — its dock DID blur.
 *  - Plavio's dock (the owner's reference, verified frosted on
 *    his phone) keeps a dedicated blur layer, but records it
 *    INSIDE the content's own drawWithContent, in the same
 *    full-screen draw pass, immediately after the content
 *    layer is recorded (its MainActivity shell); the dock only
 *    ever translates and draws it.
 *
 * This file now follows Plavio's arrangement exactly:
 * [recordBackdrop] records the content layer, draws the
 * content, then records every registered blur layer from the
 * content layer — all inside the content's draw scope.
 * [glassBackdrop] only registers its (radius, layer) pair and,
 * at draw time, translates and draws its blur layer. The
 * content layer itself never carries an effect.
 *
 * v1.0.30-test adds the CAPSULE layer alongside: the audit
 * for the owner's fourth "still readable" verdict found
 * Forte's dock/mini already passed MORE than Plavio on
 * both paper levers — a 40dp radius converted to ~120px at
 * 3x density (Plavio: 22dp ≈ 66px) under a 0.80 tint
 * (Plavio: 0.50) — yet glyphs survived on his phone while
 * Plavio's read frosted. More radius into the same
 * full-resolution RenderEffect pipeline was never going to
 * fix that. So the two bottom capsules no longer sample a
 * full-size blur layer at all: [recordBackdrop] also
 * records the page DOWNSCALED (~176px wide) with a blur,
 * and [capsuleGlassBackdrop] draws that miniature back up.
 * Text dies in the rasterization itself — see
 * [CapsuleBackdropWidthPx].
 */
class BackdropState internal constructor(
    val layer: GraphicsLayer,
    internal val capsuleLayer: GraphicsLayer,
) {
    var contentOrigin: Offset by mutableStateOf(Offset.Zero)

    /**
     * Blur layers registered by [glassBackdrop] call sites,
     * keyed by the radius each site asked for. They are
     * recorded by [recordBackdrop] — inside the content's own
     * full-screen draw — never by the consumers themselves
     * (see the file header for why the recording site matters).
     */
    internal val blurLayers = mutableStateListOf<Pair<Float, GraphicsLayer>>()

    /**
     * Scale of [capsuleLayer] relative to the full-size
     * content layer (v1.0.30-test): the capsule backdrop is
     * recorded DOWNSCALED — about [CapsuleBackdropWidthPx]
     * pixels wide — and drawn scaled back up by
     * [capsuleGlassBackdrop]. Written by [recordBackdrop] on
     * every record; read by consumers at draw time.
     */
    var capsuleScale: Float by mutableStateOf(1f)
        internal set

    /**
     * Freeze (v1.0.30-test): while true, [recordBackdrop]
     * keeps the last blur recordings and skips re-recording
     * them. The player's deck drag sets this for the whole
     * drag/settle — the recorded scene cannot change under a
     * finger, and re-recording + re-blurring full-screen
     * layers per drag frame was pure frame cost.
     */
    var frozen: Boolean by mutableStateOf(false)

    internal fun registerBlur(radius: Float, blurLayer: GraphicsLayer) {
        if (blurLayers.none { it.second === blurLayer }) {
            blurLayers += radius to blurLayer
        }
    }

    internal fun unregisterBlur(blurLayer: GraphicsLayer) {
        blurLayers.removeAll { it.second === blurLayer }
    }
}

@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    val capsuleLayer = rememberGraphicsLayer()
    return remember(layer, capsuleLayer) { BackdropState(layer, capsuleLayer) }
}

val backdropBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

/**
 * The capsule backdrop's recorded width (v1.0.30-test).
 * On a 1080px-wide phone the stage is recorded at ~176px
 * wide — a ~6.1x downscale — so the sharpest page text
 * (14sp ≈ 42px tall at 3x density) lands in the capsule
 * layer about 7px tall, is blurred there, and is drawn
 * back up 6.1x. No glyph survives that round trip, on any
 * device blur implementation — the illegibility is a
 * property of the rasterization, not of the blur filter.
 */
private const val CapsuleBackdropWidthPx = 176f

/**
 * The blur the capsule layer would need at FULL resolution
 * to match the small layer's blur (v1.0.30-test): the small
 * layer's actual radius is this times [BackdropState.capsuleScale]
 * (~18px on the 176px-wide layer at 3x density, ≡ ~110px
 * at full size — and the 6.1x downscale-upscale mushes
 * what's left on top of it).
 */
private const val CapsuleBackdropFullRadiusPx = 110f

/** Record this content into [state]'s layer as it draws — and,
 * in the same draw pass, record every registered blur layer
 * from it (Plavio's arrangement; see the file header). */
fun Modifier.recordBackdrop(state: BackdropState): Modifier = this
    .onGloballyPositioned { state.contentOrigin = it.positionInRoot() }
    .drawWithContent {
        state.layer.record {
            this@drawWithContent.drawContent()
        }
        drawContent()
        if (backdropBlurSupported && !state.frozen) {
            // Still inside the content's own (full-screen) draw
            // scope: each blur layer is recorded at the
            // content's size, holding the WHOLE page, with its
            // BlurEffect on the blur layer itself. Consumers
            // later draw the slice behind them by translation.
            state.blurLayers.forEach { (radius, blurLayer) ->
                blurLayer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                blurLayer.record {
                    drawLayer(state.layer)
                }
            }
            // The capsule backdrop (v1.0.30-test): the same
            // page recorded a second time, DOWNSCALED to
            // ~[CapsuleBackdropWidthPx] wide with its own
            // blur, for the two bottom capsules (dock +
            // mini-player) — see [CapsuleBackdropWidthPx]
            // for why the frost now comes from scale, not
            // from ever-bigger radii. One tiny recording
            // replaces the two full-screen blur layers those
            // capsules used to register, so this is cheaper
            // than what it replaces, not costlier.
            val fullW = size.width
            val fullH = size.height
            if (fullW > 0f && fullH > 0f) {
                val downScale = (CapsuleBackdropWidthPx / fullW).coerceIn(0.05f, 0.30f)
                val smallSize = IntSize(
                    (fullW * downScale).roundToInt().coerceAtLeast(1),
                    (fullH * downScale).roundToInt().coerceAtLeast(1),
                )
                // The effective scale after integer rounding —
                // recorder and consumers share this one number,
                // so the drawn picture stays aligned.
                val effectiveScale = smallSize.width / fullW
                state.capsuleScale = effectiveScale
                val smallRadius = CapsuleBackdropFullRadiusPx * effectiveScale
                state.capsuleLayer.renderEffect =
                    BlurEffect(smallRadius, smallRadius, TileMode.Clamp)
                state.capsuleLayer.record(
                    drawContext.density, layoutDirection, smallSize,
                ) {
                    scale(effectiveScale, effectiveScale, Offset.Zero) {
                        drawLayer(state.layer)
                    }
                }
            }
        }
    }

/**
 * Draw [state]'s blurred copy of the recorded content behind
 * this element's own content. Must be applied before the
 * element's background/tint and after its clip. No-op when
 * blur is unsupported — the caller's flat tint stands.
 *
 * This call site NEVER records its blur layer (v1.0.25-test):
 * recording happens in [recordBackdrop], inside the content's
 * full-screen draw, exactly as Plavio's shell does it. Here
 * the layer is only registered (so the recorder knows this
 * radius is wanted) and drawn, translated so the picture
 * lines up with what's really behind the element.
 */
@Composable
fun Modifier.glassBackdrop(
    state: BackdropState?,
    shape: Shape,
    blurRadius: Float = 26f,
): Modifier {
    if (state == null || !backdropBlurSupported) return this
    val blurLayer = rememberGraphicsLayer()
    DisposableEffect(state, blurLayer, blurRadius) {
        state.registerBlur(blurRadius, blurLayer)
        onDispose { state.unregisterBlur(blurLayer) }
    }
    var pos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .clip(shape)
        .drawWithContent {
            translate(
                left = -(pos.x - state.contentOrigin.x),
                top = -(pos.y - state.contentOrigin.y),
            ) {
                drawLayer(blurLayer)
            }
            drawContent()
        }
}

/**
 * The CAPSULE variant of [glassBackdrop] (v1.0.30-test):
 * draws [BackdropState.capsuleLayer] — the downscaled,
 * blurred recording — behind this element, scaled back up
 * by 1/[BackdropState.capsuleScale] and translated so the
 * picture lines up with what's really behind the element,
 * exactly as [glassBackdrop] aligns its full-size layer.
 * Only the two bottom capsules (the dock in GlassBox's
 * capsule branch, and the mini-player) sample this layer:
 * they are the surfaces the owner demands be unreadable-
 * through ("blur it frosted, thick, so cant read whats
 * backside"). Every other glass surface keeps the
 * full-size [glassBackdrop] path untouched.
 */
@Composable
fun Modifier.capsuleGlassBackdrop(
    state: BackdropState?,
    shape: Shape,
): Modifier {
    if (state == null || !backdropBlurSupported) return this
    var pos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .clip(shape)
        .drawWithContent {
            val s = state.capsuleScale
            translate(
                left = -(pos.x - state.contentOrigin.x),
                top = -(pos.y - state.contentOrigin.y),
            ) {
                scale(1f / s, 1f / s, Offset.Zero) {
                    drawLayer(state.capsuleLayer)
                }
            }
            drawContent()
        }
}
