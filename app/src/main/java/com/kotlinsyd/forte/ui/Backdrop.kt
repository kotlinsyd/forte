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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * v1.0.30-test added the CAPSULE layer alongside: after the
 * owner's fourth "still readable" verdict, the two bottom
 * capsules stopped sampling a full-size blur layer and instead
 * sampled the page recorded DOWNSCALED (~176px wide) with a
 * blur, drawn back up — text died in the rasterization itself.
 *
 * v1.0.39-test retires the downscale (owner's order, against
 * his own screenshot of Plavio's capsule: "make liquid glass
 * same to same like ss capsule" — the screenshot wins over
 * every constant Forte carried). The audit this time found
 * the units bug the earlier rounds missed: every full-size
 * radius Forte ever fed its blur pipeline was a RAW PIXEL
 * float handed straight to createBlurEffect (26f default,
 * 44f at the widest ≈ 9–15dp at 3x density), while Plavio's
 * 22dp is converted through the density (≈66px at 3x) before
 * it reaches its BlurEffect. Forte's capsules never actually
 * ran Plavio-strength blur at full resolution. The capsule
 * layer is now Plavio's arrangement verbatim: the page
 * recorded at FULL size inside the content's own draw, one
 * dedicated layer carrying a pure BlurEffect of
 * [CapsuleBlurRadiusDp] converted in the draw scope, no
 * saturation chain, no scale — [capsuleGlassBackdrop] only
 * translates and draws it, exactly as Plavio's dock does.
 *
 * v1.0.40-test thickens that frost (owner's order: "Glass of
 * capsules make thick frosted, thick means thick" —
 * corrected the same round: "Not milky white. Blur frosted
 * dark"). The owner still read the .39 capsule as thin, so
 * the material — not the arrangement — changes: the capsule
 * layer now carries TWO chained BlurEffect passes of 44dp
 * each (double Plavio's 22dp per pass; Gaussian passes add
 * in quadrature, ≈62dp effective) so backdrop detail fully
 * dissolves — blurred shapes only, no readable text or image
 * edges survive, even over bright artwork. The tint half of
 * the thickness lives in Components.kt's capsuleTint (0.50 →
 * 0.68 black, a deeper DARK sheet — no whitening anywhere).
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

    /*
     * The capsule blur layer ([capsuleLayer]): the page
     * recorded at FULL size with the thick-frost effect —
     * two chained 44dp BlurEffect passes (v1.0.40-test; .39
     * carried Plavio's single 22dp pass — see the file
     * header). Recorded by [recordBackdrop] inside the
     * content's own draw; the two bottom capsules only ever
     * translate and draw it ([capsuleGlassBackdrop]).
     */

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
 * The capsule layer's blur radius (v1.0.40-test value,
 * kept as the reference): 44dp — double Plavio's 22dp —
 * converted through the density in the draw scope before
 * it reaches the effect (≈132px at 3x). v1.0.40-test
 * applied it as TWO chained passes at full size, ≈62dp
 * effective; v1.0.41-test derives its single half-size
 * pass from this same constant (44/√2 ≈ 31dp in layer
 * space, presented at 2× — see [recordBackdrop]) so the
 * frost the owner approved is unchanged, only cheaper.
 * v1.0.39-test ran a single 22dp pass (Plavio's own value,
 * its MainActivity does `22.dp.toPx()` on its blur layer);
 * the owner still read that capsule as thin and ordered
 * the frost THICK — "thick means thick" — so the radius
 * doubled and the pass doubled with it. Every radius
 * Forte fed this pipeline before .39 was a raw pixel
 * float (26f/44f ≈ 9–15dp), a third of Plavio's at best;
 * see the file header for the full audit.
 */
private const val CapsuleBlurRadiusDp = 44f

/**
 * The vibrancy of the glass pipeline (v1.0.37-test): the
 * sampled backdrop's saturation, lifted before the blur.
 * BitChord's liquid glass runs its colour controls at
 * saturation 1 + 0.5 × vibrancy(1) = 1.5 — the colours
 * behind a bar arrive richer than the page itself, which
 * is most of what makes the surface read as GLASS rather
 * than as a dark smudge (the v1.0.36-test build kept the
 * blur but not the vibrancy, and its tint was heavy enough
 * to read flat). Same value here, Forte's own chain.
 */
private const val GlassSaturation = 1.5f

/**
 * One recorded-backdrop effect: saturation first, then
 * blur, chained in the platform effect language and handed
 * back to Compose (API 31+ — every caller is already behind
 * [backdropBlurSupported]). Saturation-then-blur matches
 * the reference pipeline's order (colour controls, then
 * blur): the blur mixes already-lifted colours.
 */
private fun glassRenderEffect(radius: Float): androidx.compose.ui.graphics.RenderEffect {
    val saturation = android.graphics.RenderEffect.createColorFilterEffect(
        android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setSaturation(GlassSaturation) },
        ),
    )
    val blur = android.graphics.RenderEffect.createBlurEffect(
        radius, radius, android.graphics.Shader.TileMode.CLAMP,
    )
    return android.graphics.RenderEffect
        .createChainEffect(blur, saturation)
        .asComposeRenderEffect()
}

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
                blurLayer.renderEffect = glassRenderEffect(radius)
                blurLayer.record {
                    drawLayer(state.layer)
                }
            }
            // The capsule backdrop (v1.0.41-test): the same
            // page recorded a second time into its own layer,
            // carrying the THICK frost — but recorded at HALF
            // size and blurred in ONE pass, where v1.0.40-test
            // recorded FULL size and chained TWO 44dp passes.
            // The owner read .40's scroll as flickery/unsmooth:
            // the capsule layer re-records from the content
            // layer on every content invalidation (i.e. every
            // scroll frame), and .40's full-res record + two
            // chained full-res blur passes was the per-frame
            // cost. At this frost's strength the backdrop
            // behind the blur holds no detail finer than the
            // blur itself, so half resolution is visually
            // indistinguishable: the layer records at
            // (w/2 × h/2) with the content drawn scaled 0.5,
            // one BlurEffect pass of 44/√2 ≈ 31dp IN LAYER
            // SPACE — which the consumer's 2× upscale presents
            // as the same ≈62dp effective frost .40's chain
            // produced (two chained 44dp passes add in
            // quadrature to ≈62dp). Tint, rim and pill are
            // untouched — only the price changed: a quarter of
            // the pixels, half the passes. The two bottom
            // capsules (dock + mini-player) sample this layer
            // and nothing else; see the file header for why
            // the v1.0.30 downscale retired (that one scaled
            // the DRAW of a full-size layer and shrank the
            // picture with it — this scales the RECORDING, and
            // the consumer scales the layer back up, so the
            // geometry is identical).
            val capsuleRadiusPx = (CapsuleBlurRadiusDp / sqrt(2f)).dp.toPx()
            val capsuleBlur = android.graphics.RenderEffect.createBlurEffect(
                capsuleRadiusPx,
                capsuleRadiusPx,
                android.graphics.Shader.TileMode.CLAMP,
            )
            state.capsuleLayer.renderEffect = capsuleBlur.asComposeRenderEffect()
            val contentSize = state.layer.size
            if (contentSize.width > 0 && contentSize.height > 0) {
                state.capsuleLayer.record(
                    IntSize(
                        (contentSize.width / 2f).roundToInt(),
                        (contentSize.height / 2f).roundToInt(),
                    ),
                ) {
                    scale(0.5f, 0.5f, Offset.Zero) {
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
 * The CAPSULE variant of [glassBackdrop]: draws
 * [BackdropState.capsuleLayer] — the THICK-frost recording
 * of the page (v1.0.41-test: recorded at half size with a
 * single ≈31dp layer-space blur pass and drawn scaled 2×,
 * presenting the same ≈62dp effective frost as
 * v1.0.40-test's full-size two-pass chain) — behind this
 * element, translated so the picture lines up with what's
 * really behind the element, exactly as [glassBackdrop]
 * aligns its full-size layer and exactly as Plavio's dock
 * draws its blur layer. Only the two bottom capsules (the
 * dock in GlassBox's capsule branch, and the mini-player)
 * sample this layer. (v1.0.30–v1.0.38 this drew a
 * DOWNSCALED miniature of the page instead; the owner's
 * same-to-same order against his Plavio screenshot retired
 * it — see the file header. v1.0.41's half-size RECORDING
 * is a different mechanism: the layer is drawn back at
 * full size, so nothing about the picture changes.) Every
 * other glass surface keeps the full-size [glassBackdrop]
 * path untouched.
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
            translate(
                left = -(pos.x - state.contentOrigin.x),
                top = -(pos.y - state.contentOrigin.y),
            ) {
                // v1.0.41-test: the capsule layer is recorded at
                // HALF size (see recordBackdrop); scale it back
                // up inside the same translation. A layer point
                // q = p/2 lands at 2q − (pos − origin) =
                // p − (pos − origin) — the same alignment the
                // full-size layer had in v1.0.40-test.
                scale(2f, 2f, Offset.Zero) {
                    drawLayer(state.capsuleLayer)
                }
            }
            drawContent()
        }
}
