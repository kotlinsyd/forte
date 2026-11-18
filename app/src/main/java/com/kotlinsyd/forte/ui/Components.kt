package com.kotlinsyd.forte.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.formatDuration
import com.kotlinsyd.forte.playback.PlaybackState
import kotlinx.coroutines.launch

/** Actions every track row/menu can trigger. Wired once at the root. */
class TrackActions(
    val play: (Track, List<Track>, String?) -> Unit,
    val toggleFavorite: (Track) -> Unit,
    val download: (Track) -> Unit,
    val share: (Track) -> Unit,
    val addToPlaylist: (Track) -> Unit,
    val goToArtist: (Track) -> Unit,
    val playNext: (Track) -> Unit,
    val addToQueue: (Track) -> Unit,
    val startRadio: (Track) -> Unit,
) {
    /** Opens the sleep-timer choices (set at the root, where the
     * dialog lives). Song menus offer it too. */
    var openSleepTimer: (() -> Unit)? = null
}

/**
 * How much vertical room the floating dock (mini strip + capsule)
 * needs at the bottom of the stage. The stage itself reserves
 * NOTHING — page content runs to the physical bottom edge and
 * scrolls behind the glass (owner's no-solid-band rule); each
 * scrollable list adds this as invisible end padding so its last
 * row can still scroll clear of the dock.
 */
val LocalDockClearance = androidx.compose.runtime.compositionLocalOf { 92.dp }

// ----------------------------------------------------------------------
// Skeletons — the app's one loading language (owner's rule)
// ----------------------------------------------------------------------

/**
 * Skeleton placeholders, shaped like the content they stand in for.
 * They show ONLY while a load is genuinely in flight and fast: the
 * moment data (or the screen's last-good cache) exists, the real
 * content replaces them, and slow loads fall back to that last-good
 * content instead of holding the user on skeletons. One soft pulse,
 * a few flat boxes — cheap to render on low-memory phones.
 */
@Composable
private fun skeletonAlpha(): Float {
    // "Reduce animation" (Settings → Appearance): a still skeleton
    // at a mid alpha instead of the pulse.
    if (com.kotlinsyd.forte.ForteApp.instance.settings.settings
            .collectAsState().value.reduceAnimation
    ) {
        return 0.6f
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skel")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "skelAlpha",
    )
    return alpha
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val alpha = skeletonAlpha()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(ForteColors.SurfaceHi.copy(alpha = alpha)),
    )
}

/** One track-row skeleton: cover block, two lines, menu dot. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(56.dp), corner = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonBox(Modifier.fillMaxWidth(0.55f).height(13.dp), corner = 6.dp)
            Spacer(Modifier.height(7.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.35f).height(11.dp), corner = 6.dp)
        }
    }
}

@Composable
fun SkeletonRows(count: Int = 5) {
    Column { repeat(count) { SkeletonRow() } }
}

/** One home-shelf skeleton: title line + a row of card blocks. */
@Composable
fun SkeletonShelf() {
    Column {
        SkeletonBox(Modifier.fillMaxWidth(0.4f).height(18.dp), corner = 8.dp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Column(Modifier.width(150.dp)) {
                    SkeletonBox(Modifier.size(150.dp), corner = 12.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.8f).height(12.dp), corner = 6.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.55f).height(11.dp), corner = 6.dp)
                }
            }
        }
    }
}

/**
 * The best artwork URL for a track: its YouTube Music cover when it
 * has one; when the extractor only had a video frame, the iTunes
 * net is asked once (cached) for the real album cover. Returns the
 * frame meanwhile, so nothing ever waits on the second net.
 */
@Composable
fun rememberEnrichedArtwork(track: Track): String? {
    // A blank thumb starts on the frame floor, never on the letter:
    // the video's own frame exists even while the nets are asked.
    val floored = track.thumbUrl?.takeIf { it.isNotBlank() }
        ?: com.kotlinsyd.forte.data.frameFloorUrl(track.id)
    var url by remember(track.id) { mutableStateOf(floored) }
    androidx.compose.runtime.LaunchedEffect(track.id, track.thumbUrl) {
        url = floored
        if (com.kotlinsyd.forte.data.ArtworkResolver.needsUpgrade(track.thumbUrl)) {
            com.kotlinsyd.forte.data.ArtworkResolver.itunesCover(track)?.let { url = it }
        }
    }
    return url
}

/**
 * The artwork gradient as a Coil placeholder/error painter: while
 * a poster is in flight (or after it fails), the slot shows the
 * same per-title gradient the artwork boxes already stand on —
 * never a blank or a broken-image flash (v1.0.24-test, the
 * poster-speed fix).
 */
@Composable
fun rememberArtworkPainter(seed: String): androidx.compose.ui.graphics.painter.Painter {
    val colors = artworkGradient(seed)
    return remember(seed) {
        object : androidx.compose.ui.graphics.painter.Painter() {
            override val intrinsicSize = androidx.compose.ui.geometry.Size.Unspecified
            override fun DrawScope.onDraw() {
                drawRect(Brush.linearGradient(colors))
            }
        }
    }
}

@Composable
fun Artwork(
    url: String?,
    title: String,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(artworkGradient(title))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().firstOrNull()?.uppercase() ?: "♪",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.Black,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                // Per-slot size ladder (v1.0.24-test): the URL is
                // re-sized for THIS slot instead of every slot
                // fetching the source's largest form — a row's
                // w240 lands in a fraction of the w1080's time on
                // a slow connection, so the poster is simply
                // there at first paint. Rows (≤64dp) fetch the
                // row size; cards fetch the card size; anything
                // bigger keeps the hero size.
                model = com.kotlinsyd.forte.data.artworkAt(
                    url,
                    when {
                        size <= 64.dp -> com.kotlinsyd.forte.data.ArtSize.ROW
                        size <= 220.dp -> com.kotlinsyd.forte.data.ArtSize.CARD
                        else -> com.kotlinsyd.forte.data.ArtSize.HERO
                    },
                ),
                // No Coil placeholder/error here on purpose:
                // this box's own gradient + letter stands behind
                // the image and IS the loading/failed face.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ----------------------------------------------------------------------
// Frosted dark liquid glass — the house capsule material (owner's
// v1.0.7 order). The bottom bars must read DARK and premium: a strong
// dark tint over the backdrop blur, a cut-glass rim light for a
// border, and a soft sheen across the top. The blur mechanics stay
// exactly as built (recorded backdrop + RenderEffect); this is the
// material layered over it, nothing more.
// ----------------------------------------------------------------------

/**
 * Cut-glass rim: bright top-left edge, defined bottom edge.
 * v1.0.20-test: the stops are Plavio's LiquidRimBrush exactly
 * (white 0.50 / 0.14 / 0.05 / 0.26) — the capsule rebuild follows
 * Plavio's construction; Forte's were 0.42 / 0.12 / 0.05 / 0.24.
 */
private val DarkGlassRim: Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.50f),
        Color.White.copy(alpha = 0.14f),
        Color.White.copy(alpha = 0.05f),
        Color.White.copy(alpha = 0.26f),
    ),
)

/**
 * Soft highlight across the top of a dark-glass surface.
 * v1.0.20-test: Plavio's topSheen values — white 0.09 fading over
 * the top 35% (Forte's were 0.07 over 40%).
 */
private fun Modifier.darkGlassSheen(): Modifier = drawWithCache {
    val endY = (size.height * 0.35f).takeIf { it > 0f } ?: 1f
    val brush = Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = 0.09f), Color.Transparent),
        startY = 0f,
        endY = endY,
    )
    onDrawBehind { drawRect(brush) }
}

// ----------------------------------------------------------------------
// FORTE GLASS surfaces (v1.0.36-test) — the in-page form of the
// ForteGlass material (tokens in Theme.kt). Floating surfaces
// that can sample the recorded page (dock, mini strip, account
// card, player sheet) keep the live backdrop-blur stacks below;
// every other surface — page panels, settings/account groups,
// cards, chips, pills — wears this flat form of the SAME
// material: Forte's translucent dark tint, the hairline light
// edge, a soft top sheen, and (for panels) a soft shadow. The
// user's Glass effects setting governs both forms alike: off,
// a glass surface falls back to the flat ONYX surface + border
// it replaces, exactly as GlassBox falls back.
// ----------------------------------------------------------------------

/** ForteGlass's top sheen: a soft light across the top of the
 * glass, gone by 40% of the surface's height. */
private fun Modifier.forteGlassSheen(): Modifier = drawWithCache {
    val endY = (size.height * 0.40f).takeIf { it > 0f } ?: 1f
    val brush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = ForteGlass.SheenAlpha),
            Color.Transparent,
        ),
        startY = 0f,
        endY = endY,
    )
    onDrawBehind { drawRect(brush) }
}

/**
 * A raised ForteGlass panel: shadow (unless [elevated] is
 * false), clip, translucent tint, sheen, hairline edge. The
 * caller adds its own clickable/padding after this, as it did
 * after the flat surface it replaces.
 */
@Composable
fun Modifier.forteGlassPanel(
    shape: androidx.compose.ui.graphics.Shape,
    elevated: Boolean = true,
): Modifier {
    val glassOn = com.kotlinsyd.forte.ForteApp.instance.settings.settings
        .collectAsState().value.glassEffects
    return if (glassOn) {
        this
            .then(
                if (elevated) {
                    Modifier.shadow(ForteGlass.ShadowElevation, shape, clip = false)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(ForteGlass.PanelTint, shape)
            .forteGlassSheen()
            .border(1.dp, ForteGlass.Hairline, shape)
            .clip(shape)
    } else {
        this
            .clip(shape)
            .background(ForteColors.Surface, shape)
            .border(1.dp, ForteColors.Border, shape)
    }
}

/**
 * A small ForteGlass chip/pill: no shadow, the lighter chip
 * fill, the same hairline edge. Selected/accent states keep
 * their Forte red and never come through here.
 */
@Composable
fun Modifier.forteGlassChip(
    shape: androidx.compose.ui.graphics.Shape,
): Modifier {
    val glassOn = com.kotlinsyd.forte.ForteApp.instance.settings.settings
        .collectAsState().value.glassEffects
    return if (glassOn) {
        this
            .clip(shape)
            .background(ForteGlass.ChipFill, shape)
            .border(1.dp, ForteGlass.Hairline, shape)
            .clip(shape)
    } else {
        this
            .clip(shape)
            .background(ForteColors.Surface, shape)
            .border(1.dp, ForteColors.Border, shape)
    }
}

// ----------------------------------------------------------------------
// The capsule glass stack — Plavio's PURE FROST, exactly as its
// code carries it (v1.0.23-test, owner's order: Forte's capsules
// "same color, frosted glass as Plavio — same to same", player
// included). Up to v1.0.22-test this stack followed Plavio's OLD
// liquid-glass construction (fill, wash, sheen, glow, bright rim)
// with Forte's own extra frost on top. Plavio's owner had those
// very layers stripped from Plavio's dock in its v0.14.40 lock,
// and its player capsule + popups followed in v0.14.42. What
// remains there — and here now — is two things only: a real
// recorded-backdrop blur (22dp BlurEffect) under a measured
// black tint, and ONE edge: a 1dp dim silver outline,
// #C7CCD6 at 30%. No wash, no sheen, no glow, no bright rim,
// no shadow. The values below are Plavio's own constants
// (its PlayerComponents.DockFrostOutline / dockFrostTint and
// the MainActivity dock), copied, not approximated — with ONE
// owner-ordered exception (v1.0.25-test): the bottom
// capsules' LIVE tint is 0.65 black, not Plavio's 0.50, on
// the owner's direct order after his phone kept reading the
// capsules as clear (see [capsuleTint]); the LIVE tint
// rose again in v1.0.29-test, to 0.80 (see [DockCapsuleTint]).
// ----------------------------------------------------------------------

/** Plavio's dock frost tint (dark palette): 0.50 black. Kept
 * for the FLAT glass (surfaces over artwork with no recorded
 * backdrop to sample — the hero heart, the mini disc). */
val DockFrostTint: Color = Color.Black.copy(alpha = 0.50f)

/**
 * The LIVE glass tint (v1.0.37-test): BitChord's liquid-glass
 * surface colour — #121212 at 40% — over the recorded,
 * vibrancy-lifted blur (see Backdrop.kt's glassRenderEffect).
 * The v1.0.29/.30 capsules wore 0.80 black over the same
 * frost: text died, but so did the glass — the surface read
 * as a solid black bar, which is the "changed nothing" the
 * owner sent back on v1.0.36-test. At 0.40 the blurred
 * colours of the page glow through the tint and the bar
 * reads as glass; text still cannot survive, because the
 * capsule layer it samples is recorded DOWNSCALED (see
 * CapsuleBackdropWidthPx) — the illegibility lives in the
 * rasterization, not in the tint, so the tint is free to
 * lighten.
 *
 * RETIRED in v1.0.38-test (owner's order: "glass is not
 * finished like Plavio — copy Plavio glass design, same to
 * same"): the shell capsules and the player glass wear
 * Plavio's own tint again ([DockFrostTint], 0.50 black).
 * Kept in the tree as the record of the .37 tuning; nothing
 * reads it now.
 */
val GlassBarTint: Color = Color(0xFF121212).copy(alpha = 0.40f)

/** Plavio's one capsule edge: dim silver #C7CCD6 at 30%. */
val DockFrostOutline: Color = Color(0xFFC7CCD6).copy(alpha = 0.30f)

/**
 * The bottom capsules' LIVE tint (v1.0.40-test, owner's
 * order: "Glass of capsules make thick frosted, thick
 * means thick" — corrected the same round: "Not milky
 * white. Blur frosted dark"). The .38/.39 capsule wore
 * Plavio's 0.50 black over its blur and the owner still
 * read it as thin. Thickness here = blur strength (two
 * chained 44dp passes — see Backdrop.kt) PLUS tint depth:
 * the live tint rises to 0.68 BLACK. Deliberately DARK —
 * no white, no milk: the sheet stays in the Plavio capsule
 * family (dark glass), it just gets deeper, so the blurred
 * page behind reads as soft dark shapes under a solid-
 * feeling sheet. 0.68 is the judged line: at 0.50 the live
 * page's luminance still dominates the sheet; 0.80 (the
 * v1.0.29/.30 tint over the old downscale) read as a flat
 * solid bar and killed the glass. At 0.68 over the heavy
 * blur the rim, the selected pill and the blurred colour
 * glow at the edges still say GLASS, while text and image
 * detail cannot survive the tint + blur pair. The flat
 * fallback (no blur to sample) stays the near-solid 0.92,
 * unchanged. The FLAT capsule stack ([frostedDarkGlass])
 * and the player's glass ([liveCapsuleGlass]) keep
 * Plavio's 0.50 — this thick tint belongs to the two
 * bottom capsules only, via [capsuleTint].
 */
private val DockCapsuleThickTint: Color = Color.Black.copy(alpha = 0.68f)

/**
 * The bottom capsules' tint over a LIVE blur. History:
 * v1.0.25-test set 0.65 on the owner's direct order ("capsules
 * are still clear, make them frosted blur 65 percent"),
 * superseding Plavio's 0.50 for Forte's dock + mini-player.
 * v1.0.29-test (the owner's THIRD raise of the same defect —
 * his screenshot shows shelf titles still legible through
 * both capsules): the audit found both chains mechanically
 * correct — each samples the blurred recorded backdrop,
 * recorded at full-screen scope exactly as Plavio's shell
 * does (the v1.0.25-test arrangement) — but the blur radius
 * was Plavio's 22dp and, over a 0.65 tint, that pass leaves
 * glyphs legible on his phone. Per his outcome-first word
 * ("background words not readable" outranks the 0.65
 * number), BOTH levers moved for these two capsules only:
 * the blur radius rose to 40dp and the live tint rose to
 * 0.80 black. Shapes and colours may still glow through;
 * text must not survive.
 * v1.0.30-test (the FOURTH raise — "blur it frosted, thick,
 * so cant read whats backside"): the 40dp build still read.
 * The audit this time compared the actual numbers against
 * Plavio on the same phone: Forte's 40dp IS converted
 * before use (~120px at 3x) and EXCEEDS Plavio's 22dp
 * (~66px) under a heavier tint (0.80 vs 0.50) — so the
 * full-resolution RenderEffect radius was never the gap.
 * The capsules therefore stopped sampling a full-size blur
 * layer: they now sample the DOWNSCALED capsule backdrop
 * (recorded ~176px wide, blurred, drawn back up — see
 * Backdrop.kt), where text is destroyed by the
 * rasterization itself. The tint stays 0.80 over it. The player's
 * glass keeps Plavio's flat/live 0.50 (see [liveCapsuleGlass]
 * / [frostedDarkGlass]), and sheets/dialogs keep their own.
 * When no blur can run — an older phone, or no recorded
 * scene — the capsule must still read DARK, never the light
 * grey wash a bare tint over bright content becomes (the
 * owner's v1.0.23-test screenshot: page text sharp through a
 * pale capsule). Plavio's own no-blur branch is a near-solid
 * 0.92 black; the flat fallback here matches it.
 */
private val DockCapsuleTint: Color = Color.Black.copy(alpha = 0.80f)

private fun capsuleTint(live: Boolean): Color =
    // v1.0.38-test (owner's order: Plavio's glass, same to
    // same): over a LIVE blur the capsule wore PLAVIO'S tint
    // — 0.50 black ([DockFrostTint], its MainActivity dock
    // constant). The v1.0.37-test BitChord tint (#121212 at
    // 40%) is retired for the shell; the .25/.29 owner-
    // ordered 0.65/0.80 history stands in the comments above.
    // v1.0.40-test (owner: thick frosted, DARK): the live
    // tint rises to [DockCapsuleThickTint] — 0.68 black —
    // over the new two-pass 44dp capsule blur (see
    // Backdrop.kt). NOT milky: the deepening is black, the
    // sheet stays dark glass, only thicker. Without a
    // backdrop the capsule must still read DARK, never the
    // light grey wash a bare tint over bright content
    // becomes, so the flat fallback stays near-solid
    // (Plavio's own no-blur branch is the same 0.92).
    if (live) DockCapsuleThickTint else Color.Black.copy(alpha = 0.92f)

/**
 * The full capsule stack over [tint]: the tint itself and
 * the one edge — PLAVIO'S edge (v1.0.38-test, restoring the
 * v1.0.23-test pure-frost stack): a 1dp dim silver outline,
 * [DockFrostOutline] (#C7CCD6 at 30%), the capsule's ONLY
 * edge in Plavio's dock since its v0.14.40 lock. The
 * v1.0.37-test cut-glass rim ([DarkGlassRim]: bright top
 * edge → defined bottom edge, the BitChord Highlight) is
 * retired from the capsule stack on the owner's order —
 * Plavio's capsule carries no rim light, no sheen, no wash,
 * no shadow; blur + tint + the silver line is the whole
 * material. [DarkGlassRim] still edges the non-capsule dark
 * glass (GlassBox's dark branch). The blurred backdrop
 * itself (GlassBox / glassBackdrop) is drawn before this;
 * where no backdrop can be sampled the same stack is drawn
 * flat (see [frostedDarkGlass]).
 */
private fun Modifier.capsuleGlassStack(
    tint: Color,
    shape: androidx.compose.ui.graphics.Shape,
): Modifier = this
    .background(tint, shape)
    .border(1.dp, DockFrostOutline, shape)

/**
 * The dark tint gradient for one of the bottom bars. Over a live
 * blurred backdrop the alphas stay translucent so the frosting
 * shows through the tint; without a backdrop (older Android) the
 * same colours go near-opaque so the bar still reads as dark
 * glass, never as clear plastic. v1.0.8 (owner: "bit more dark
 * and thick"): the live alphas step up to 0.78 → 0.88 and the
 * dock blur behind them is stronger (see GlassBox blurRadius) —
 * the v1.0.7 tint over a 26px blur read as flat plastic.
 * v1.0.18 (owner: "20% more thick/dark, true frosted", against
 * his reference pill): the live tint steps up ~20% again —
 * 0.78 → 0.94 at the top; the bottom's full +20% would be 1.06,
 * past the alpha range, so it lands at 0.96 and the frost still
 * reads through it. The capsules' backdrop blur rises the same
 * 20% (44px → 53px — see CapsuleDock and MiniPlayer).
 */
private fun darkGlassBrush(live: Boolean): Brush {
    val top = Color(0xFF17171F)
    val bottom = Color(0xFF0B0B10)
    return Brush.verticalGradient(
        if (live) {
            listOf(top.copy(alpha = 0.94f), bottom.copy(alpha = 0.96f))
        } else {
            listOf(top.copy(alpha = 0.94f), bottom.copy(alpha = 0.97f))
        },
    )
}

/**
 * The FLAT variant of the capsule stack: Plavio's pure frost —
 * its 0.50 black tint and dim silver outline — drawn with no
 * backdrop to blur, over whatever is beneath (the player's
 * artwork, the hero's art, the mini-player's play disc). The
 * caller clips/clicks; this only paints the glass.
 */
fun Modifier.frostedDarkGlass(shape: androidx.compose.ui.graphics.Shape): Modifier =
    capsuleGlassStack(DockFrostTint, shape)

/**
 * The LIVE variant of the capsule stack (v1.0.21-test): the
 * recorded [backdrop] drawn through the caller's blur (see
 * [glassBackdrop]), then the 0.50 tint — byte-for-byte the
 * Home capsule's construction (GlassBox with capsule = true),
 * Plavio's pure frost since v1.0.23-test. Without a live
 * backdrop (older Android / no recorded scene) it lands on the
 * same flat 0.50 over the artwork — the player exception to
 * the dock's dark no-blur fallback (see [capsuleTint]). The
 * caller clips and clicks; this only paints the glass.
 */
@Composable
fun Modifier.liveCapsuleGlass(
    backdrop: BackdropState?,
    shape: androidx.compose.ui.graphics.Shape,
    blurRadius: Float,
): Modifier {
    val live = backdrop != null && backdropBlurSupported
    return this
        .then(if (live) Modifier.glassBackdrop(backdrop, shape, blurRadius) else Modifier)
        // v1.0.38-test: over a LIVE backdrop the player wears
        // PLAVIO'S pure frost again — the same 0.50 black tint
        // and dim silver outline as the bottom capsules
        // ([DockFrostTint] via [capsuleGlassStack]), exactly
        // the stack this function carried v1.0.23–v1.0.36.
        // The v1.0.37-test BitChord tint (#121212 at 40%) is
        // retired here too, on the same owner order (Plavio's
        // glass, same to same — its player capsule carries the
        // same DockFrostTint/DockFrostOutline constants). The
        // player exception to the dark fallback stands for the
        // flat path only: over the artwork with nothing to
        // sample, the tint is the flat 0.50 whether or not a
        // backdrop could be sampled — the sheet and discs sit
        // ON the picture, not over page content.
        .capsuleGlassStack(DockFrostTint, shape)
}

/**
 * The press ring (v1.0.21-test, owner's rule): while a
 * glass button is being touched, a solid accent outline
 * ring sits on its edge; on release it is gone. This
 * REPLACES the v1.0.20-test red pause glyph, which the
 * owner rejected ("it looks bad") — the accent now lives
 * on the touch, not on the resting button. Drawn as a 2.dp
 * border over the glass rim; transparent whenever the
 * button is not pressed, so the resting look is untouched.
 * [pressed] comes from the button's own interaction source
 * (collectIsPressedAsState at the site). The ring's colour
 * is [ForteColors.TrueRed] — pure #FF0000 when this shipped
 * (hence the function's name), the logo core pink since
 * v1.0.40-test's logo-colour order, like every accent.
 */
fun Modifier.trueRedPressRing(
    shape: androidx.compose.ui.graphics.Shape,
    pressed: Boolean,
): Modifier = border(
    2.dp,
    if (pressed) ForteColors.TrueRed else Color.Transparent,
    shape,
)

@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    glass: Boolean,
    backdrop: BackdropState? = null,
    dark: Boolean = false,
    blurRadius: Float = 26f,
    // v1.0.20-test: the bottom capsule passes true and gets the
    // Plavio-blueprint capsule stack (and NO shadow — Plavio's
    // capsule casts none). Other dark surfaces (the account card)
    // keep the previous near-opaque dark tint untouched.
    capsule: Boolean = false,
    content: @Composable () -> Unit,
) {
    // With a live backdrop the recorded scene shows through blurred;
    // the fill drops to a light tint so the frosting reads. Without
    // one (old Android, glass off) the flat tint stands as before.
    // [dark] swaps the light tint for the frosted dark liquid
    // glass (bottom bars only — player cards keep the light glass).
    val liveGlass = glass && backdrop != null && backdropBlurSupported
    Box(
        modifier = modifier
            // A weightier presence for the dark dock surfaces: a
            // soft shadow lifts the assembly off the content it
            // floats over (glass v2 — player cards keep no shadow).
            // v1.0.37-test: the capsule casts one too — the
            // reference glass carries a shadow (its
            // Shadow.Default), and a shadowless bar at the new
            // 0.40 tint melts into bright content.
            // v1.0.38-test: the CAPSULE's shadow is gone again —
            // Plavio's capsule casts none (its Surface sits at
            // shadowElevation 0 over the tinted backing), and the
            // owner's order is Plavio's glass same to same. The
            // non-capsule dark surfaces (the account card) keep
            // the shadow they carry today.
            .then(if (dark && glass && !capsule) Modifier.shadow(16.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .then(
                if (liveGlass) {
                    // The capsule (the dock) samples the
                    // full-size THICK-frost capsule backdrop
                    // (two chained 44dp blur passes,
                    // v1.0.40-test — see Backdrop.kt's
                    // capsuleGlassBackdrop); every other
                    // GlassBox surface keeps the full-size
                    // per-radius blur layer.
                    if (capsule) Modifier.capsuleGlassBackdrop(backdrop, shape)
                    else Modifier.glassBackdrop(backdrop, shape, blurRadius)
                } else Modifier,
            )
            .then(
                if (dark && glass && capsule) {
                    Modifier.capsuleGlassStack(capsuleTint(liveGlass), shape)
                } else if (dark && glass) {
                    Modifier
                        .background(darkGlassBrush(liveGlass), shape)
                        .darkGlassSheen()
                } else {
                    Modifier.background(
                        when {
                            liveGlass -> Color.White.copy(alpha = 0.10f)
                            glass -> ForteColors.GlassFill
                            else -> ForteColors.Surface
                        },
                        shape,
                    )
                },
            )
            .then(
                if (dark && glass && capsule) {
                    // The rim is the stack's last layer already.
                    Modifier
                } else if (dark && glass) {
                    Modifier.border(1.dp, DarkGlassRim, shape)
                } else {
                    Modifier.border(
                        1.dp,
                        if (glass) ForteColors.GlassBorder else ForteColors.Border,
                        shape,
                    )
                },
            )
            .clip(shape),
    ) { content() }
}

@Composable
fun SectionHeader(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = ForteType.section,
            color = ForteColors.Text,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ForteColors.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    actions: TrackActions,
    isFavorite: Boolean,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    downloadLabel: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.thumbUrl, track.title, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = ForteType.rowTitle,
                color = ForteColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(track.artist, formatDuration(track.durationSec))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = ForteType.rowSub,
                color = ForteColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Text(
                text = if (isPlaying) "Playing" else "Paused",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ForteColors.Red,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (downloadLabel != null) {
            Text(
                text = downloadLabel,
                fontSize = 11.sp,
                color = ForteColors.TextDim,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        TrackMenu(track, actions, isFavorite)
    }
}

// ----------------------------------------------------------------------
// Action sheets — one menu language app-wide (v1.0.14). A bottom
// sheet with 24dp top corners on the dark surface, an optional song
// header, and plain icon rows replaces the stock dropdown menus and
// the stock dialogs for song/player actions.
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForteActionSheet(
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    // The PLAYER's sheets pass true: on the player, popups carry
    // the frost. They used to wear the bare 0.50 tint
    // (v1.0.23-test) and the owner sent it back (v1.0.24-test):
    // a ModalBottomSheet lives in its own window, so the app's
    // recorded backdrop cannot be sampled behind it — 0.50 over
    // nothing left the player bleeding through and the text
    // unreadable ("this setting should get blur background for
    // proper text visible"). The sheet therefore wears the
    // dark no-blur frost instead: a near-opaque black (0.92 —
    // the capsule's own no-blur fallback value) over the
    // sheet's scrim, which composites to a solid dark glass the
    // eye reads as frost, plus the capsule's dim silver outline
    // traced around the sheet. Every other sheet keeps the flat
    // dark surface.
    frost: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = if (frost) {
            Modifier.border(1.dp, DockFrostOutline, sheetShape)
        } else {
            Modifier
        },
        containerColor = if (frost) {
            Color.Black.copy(alpha = 0.92f)
        } else {
            Color(0xFF17171F)
        },
        contentColor = ForteColors.Text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.25f)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            if (header != null) {
                header()
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

/** One row inside a ForteActionSheet: 52dp tall, 22dp icon, 15sp label. */
@Composable
fun ForteSheetRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color.White,
    labelColor: Color = ForteColors.Text,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            fontSize = 15.sp,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun TrackMenu(
    track: Track,
    actions: TrackActions,
    isFavorite: Boolean,
    // Hoisted open state (v1.0.23-test): a caller that opens the
    // menu from its own gesture (the Listen again card's long
    // press) passes both and no three-dot button is drawn — the
    // sheet alone renders. Default null = the classic self-owned
    // state behind the three-dot IconButton.
    hoistedOpen: Boolean? = null,
    onHoistedOpenChange: ((Boolean) -> Unit)? = null,
) {
    var internalOpen by remember { mutableStateOf(false) }
    val open = hoistedOpen ?: internalOpen
    val setOpen: (Boolean) -> Unit = { value ->
        if (onHoistedOpenChange != null) onHoistedOpenChange(value) else internalOpen = value
    }
    if (hoistedOpen == null) {
        IconButton(onClick = { setOpen(true) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = ForteColors.TextDim)
        }
    }
    if (open) {
        val downloads by com.kotlinsyd.forte.ForteApp.instance.library.downloads
            .collectAsState(initial = emptyList())
        val downloaded = downloads.any { it.videoId == track.id }
        ForteActionSheet(
            onDismiss = { setOpen(false) },
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(rememberEnrichedArtwork(track), track.title, 44.dp, corner = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title, style = ForteType.cardTitle,
                            color = ForteColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist, style = ForteType.cardSub,
                            color = ForteColors.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        ) {
            ForteSheetRow(Icons.Filled.Radio, "Start radio") {
                setOpen(false); actions.startRadio(track)
            }
            ForteSheetRow(Icons.Filled.PlaylistPlay, "Play next") {
                setOpen(false); actions.playNext(track)
            }
            ForteSheetRow(Icons.Filled.QueueMusic, "Add to queue") {
                setOpen(false); actions.addToQueue(track)
            }
            ForteSheetRow(Icons.Filled.PlaylistAdd, "Add to playlist") {
                setOpen(false); actions.addToPlaylist(track)
            }
            ForteSheetRow(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                iconTint = if (isFavorite) ForteColors.Red else Color.White,
            ) {
                setOpen(false); actions.toggleFavorite(track)
            }
            ForteSheetRow(
                if (downloaded) Icons.Filled.Check else Icons.Filled.Download,
                if (downloaded) "Downloaded" else "Download",
                iconTint = if (downloaded) ForteColors.Red else Color.White,
            ) {
                setOpen(false)
                if (!downloaded) actions.download(track)
            }
            if (actions.openSleepTimer != null) {
                ForteSheetRow(Icons.Filled.Timer, "Sleep timer") {
                    setOpen(false); actions.openSleepTimer?.invoke()
                }
            }
            if (!track.artistUrl.isNullOrBlank()) {
                ForteSheetRow(Icons.Filled.Person, "Go to artist") {
                    setOpen(false); actions.goToArtist(track)
                }
            }
            ForteSheetRow(Icons.Filled.Share, "Share") {
                setOpen(false); actions.share(track)
            }
        }
    }
}

// ----------------------------------------------------------------------
// In-app update offer: download inside Forte, straight to installer
// ----------------------------------------------------------------------

@Composable
fun UpdateDialog(
    info: com.kotlinsyd.forte.data.UpdateInfo,
    onDismiss: () -> Unit,
    onSkip: () -> Unit = onDismiss,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val installState by com.kotlinsyd.forte.data.UpdateInstaller.state.collectAsState()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            com.kotlinsyd.forte.data.UpdateInstaller.reset()
            onDismiss()
        },
        containerColor = ForteColors.SurfaceHi,
        title = { Text("Soniko ${info.version} is out", color = Color.White) },
        text = {
            Column {
                Text(
                    info.notes.ifBlank { "A new version of Soniko is ready." },
                    color = ForteColors.TextDim, fontSize = 13.sp,
                )
                when (val s = installState) {
                    is com.kotlinsyd.forte.data.UpdateInstaller.State.Downloading -> {
                        Spacer(Modifier.height(14.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { s.fraction },
                            color = ForteColors.Red,
                            trackColor = ForteColors.Surface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Downloading… ${(s.fraction * 100).toInt()}%",
                            color = Color.White, fontSize = 13.sp,
                        )
                    }
                    is com.kotlinsyd.forte.data.UpdateInstaller.State.Failed -> {
                        Spacer(Modifier.height(10.dp))
                        Text(s.message, color = ForteColors.Red, fontSize = 13.sp)
                    }
                    com.kotlinsyd.forte.data.UpdateInstaller.State.NeedsPermission -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Android needs your OK once so Soniko can install its own updates.",
                            color = Color.White, fontSize = 13.sp,
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    when (installState) {
                        is com.kotlinsyd.forte.data.UpdateInstaller.State.Downloading -> {}
                        com.kotlinsyd.forte.data.UpdateInstaller.State.Ready -> {
                            if (com.kotlinsyd.forte.data.UpdateInstaller.install(context)) {
                                com.kotlinsyd.forte.data.UpdateInstaller.reset()
                                onDismiss()
                            }
                        }
                        com.kotlinsyd.forte.data.UpdateInstaller.State.NeedsPermission -> {
                            com.kotlinsyd.forte.data.UpdateInstaller
                                .openInstallPermissionSettings(context)
                        }
                        else -> scope.launch {
                            com.kotlinsyd.forte.data.UpdateInstaller.download(context, info)
                            if (com.kotlinsyd.forte.data.UpdateInstaller.state.value ==
                                com.kotlinsyd.forte.data.UpdateInstaller.State.Ready
                            ) {
                                if (com.kotlinsyd.forte.data.UpdateInstaller.install(context)) {
                                    com.kotlinsyd.forte.data.UpdateInstaller.reset()
                                    onDismiss()
                                }
                            }
                        }
                    }
                },
            ) {
                Text(
                    when (installState) {
                        is com.kotlinsyd.forte.data.UpdateInstaller.State.Downloading -> "Downloading…"
                        com.kotlinsyd.forte.data.UpdateInstaller.State.Ready -> "Install"
                        com.kotlinsyd.forte.data.UpdateInstaller.State.NeedsPermission -> "Allow installs"
                        else -> "Update"
                    },
                    color = ForteColors.Red, fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                com.kotlinsyd.forte.data.UpdateInstaller.reset()
                onSkip()
            }) {
                Text("Later", color = ForteColors.TextDim)
            }
        },
    )
}

// ----------------------------------------------------------------------
// Bottom shell: mini-player + floating glass capsule dock
// ----------------------------------------------------------------------

data class DockTab(val route: String, val label: String, val icon: ImageVector)

val DockTabs = listOf(
    DockTab("home", "Home", Icons.Filled.Home),
    DockTab("explore", "Explore", Icons.Filled.Explore),
    DockTab("library", "Library", Icons.Filled.LibraryMusic),
    DockTab("search", "Search", Icons.Filled.Search),
)

@Composable
fun CapsuleDock(
    selectedRoute: String,
    onSelect: (String) -> Unit,
    glass: Boolean,
    modifier: Modifier = Modifier,
    backdrop: BackdropState? = null,
    // The scroll fold (v1.0.38-test, Plavio's "Glass Shrink"
    // ported on the owner's order): 0 = open, 1 = shrunk.
    // Driven by ForteRoot's nested-scroll latch + the same
    // spring Plavio runs (see ForteRoot). The capsule
    // shrinks anchored to the bottom edge exactly as
    // Plavio's capsule shrinks in its seat: width × (1 −
    // 0.15·fold), height 62 − 15·fold, and the tab names
    // eaten (their slot collapses, Plavio's DockItem
    // mechanism). Icons stay; no tab ever folds
    // away. v1.0.39-test: the mini-player above folds WITH
    // this capsule (same fold value — see MiniPlayer).
    // v1.0.40-test (owner's defect from his .39 test:
    // "when shrink both, there big gap come inside both
    // player and buttons capsule"): the .38/.39 capsule
    // shrank inside a FIXED 62dp slot, bottom-anchored, so
    // at full fold a 15dp dead hole opened at the slot's
    // top — between the mini-player and the pill. The slot
    // now folds WITH the capsule: its height is the same
    // (62 − 15·fold) the pill wears, driven by the same
    // fold value in the same frame, so the pill fills its
    // slot at EVERY point of the spring and the spacing
    // between the two capsules stays the ForteRoot spacer's
    // constant 10dp, open or folded. The stack's total
    // height now truly compacts by the fold travel.
    fold: Float = 0f,
) {
    val shape = RoundedCornerShape(ForteGlass.RadiusCapsule)
    // The capsule's backdrop is the full-size THICK-frost
    // layer (two chained 44dp blur passes, v1.0.40-test —
    // GlassBox's capsule branch samples it; .39 ran Plavio's
    // single 22dp pass, and the v1.0.30 downscaled miniature
    // retired in .39 — see Backdrop.kt's header). Over it
    // sits the capsule stack: the thick DARK tint (0.68
    // black over a live blur — [capsuleTint]) and the dim
    // silver outline, no shadow.
    BoxWithConstraints(
        modifier = modifier.height((62f - 15f * fold).dp),
    ) {
    // Per-tab width in the OPEN tray drives the label size —
    // Plavio's fitted-label rule (v1.0.39-test, its
    // MainActivity dock verbatim): narrower tray → slightly
    // smaller labels, drawn through the density so the system
    // font-size setting cannot stretch or clip them.
    val perTabDp = (maxWidth / DockTabs.size).value
    val labelSizeDp = when {
        perTabDp >= 76f -> 10.5f
        perTabDp >= 70f -> 10f
        perTabDp >= 64f -> 9.5f
        perTabDp >= 58f -> 9f
        else -> 8.5f
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
    GlassBox(
        modifier = Modifier
            .fillMaxWidth(1f - 0.15f * fold)
            .height((62f - 15f * fold).dp),
        shape = shape, glass = glass,
        backdrop = backdrop, dark = true,
        capsule = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockTabs.forEach { tab ->
                val selected = tab.route == selectedRoute
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .then(
                            if (selected) {
                                // Plavio's selected pill: a lighter
                                // glass oval — white gradient 0.22 →
                                // 0.13 with a 1dp white 0.16 border.
                                Modifier
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.22f),
                                                Color.White.copy(alpha = 0.13f),
                                            )
                                        ),
                                        RoundedCornerShape(50),
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.16f),
                                        RoundedCornerShape(50),
                                    )
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(tab.route) }
                        .padding(horizontal = 3.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            // Plavio's tab colours (v1.0.39-test,
                            // its DockItem verbatim): pure white
                            // on the selected pill, soft white
                            // 0.82 for the resting tabs — Forte's
                            // BitChord TextDim grey read visibly
                            // dimmer and greyer against the
                            // owner's Plavio screenshot.
                            tint = if (selected) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.82f)
                            },
                            modifier = Modifier.size(22.dp),
                        )
                        // The name slot, eaten as the capsule
                        // shrinks (Plavio's DockItem mechanism,
                        // v1.0.38-test): the slot's height
                        // collapses with the fold and the text
                        // fades and is cropped by the closing
                        // slot, until only the icon remains.
                        Box(
                            modifier = Modifier
                                .height((15 * (1f - fold)).dp)
                                .clipToBounds()
                                .graphicsLayer { alpha = 1f - fold },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tab.label,
                                // Plavio's label (v1.0.39-test):
                                // its labelSmall at the fitted
                                // size (see labelSizeDp above),
                                // converted back through the
                                // density — one weight for both
                                // states, colour does the talking.
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = with(LocalDensity.current) {
                                        labelSizeDp.dp.toSp()
                                    },
                                ),
                                color = if (selected) {
                                    Color.White
                                } else {
                                    Color.White.copy(alpha = 0.82f)
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
fun MiniPlayer(
    state: PlaybackState,
    glass: Boolean,
    onTap: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: BackdropState? = null,
    // The scroll fold (v1.0.39-test, owner's order: "when
    // shrink capsule it should shrink player capsule as well,
    // same way"): the SAME fold value the dock capsule reads
    // (ForteRoot's one latch + spring), so the two bars fold
    // as one assembly — the caller narrows this strip's width
    // by the dock's own fraction (× (1 − 0.15·fold)) while the
    // strip's height folds inside: artwork, transport and
    // padding step down together (64dp open → 49dp folded,
    // the dock's own 15dp travel). The title/artist column
    // never scales — at full fold it is the strip's floor.
    fold: Float = 0f,
) {
    val track = state.current ?: return
    val shape = RoundedCornerShape(ForteGlass.RadiusCard)
    val liveGlass = glass && backdrop != null && backdropBlurSupported
    Box(
        modifier = modifier
            // No shadow: the capsule assembly casts none (Plavio
            // blueprint; the dock below it is shadowless too).
            .clip(shape)
            // The full-size THICK-frost capsule backdrop,
            // same as the dock below it (two chained 44dp
            // blur passes, v1.0.40-test — see Backdrop.kt):
            // the two bars are one assembly and share one
            // frost.
            .then(if (liveGlass) Modifier.capsuleGlassBackdrop(backdrop, shape) else Modifier)
            // The capsule stack, the same pure frost as the dock
            // below it (the two bars are one assembly): the
            // capsule tint (see [capsuleTint] / [DockCapsuleTint])
            // and the dim silver outline, nothing else — the
            // song-colour wash the strip used to breathe with
            // is retired with the rest of the old layers.
            .then(
                if (glass) {
                    Modifier.capsuleGlassStack(capsuleTint(liveGlass), shape)
                } else {
                    Modifier.background(ForteColors.SurfaceHi, shape)
                },
            )
            .then(
                if (glass) Modifier
                else Modifier.border(1.dp, ForteColors.Border, shape),
            )
            .clip(shape)
            .clickable { onTap() },
    ) {
        Row(
            modifier = Modifier.padding(
                start = 10.dp, end = 6.dp,
                top = (8f - 3.5f * fold).dp, bottom = (8f - 3.5f * fold).dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                rememberEnrichedArtwork(track), track.title,
                (48f - 13f * fold).dp, corner = 12.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title, style = ForteType.rowTitle,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist, style = ForteType.rowSub, color = ForteColors.TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Transport only (v1.0.14): favorite lives in the full
            // player and the song menus; the strip carries next +
            // play/pause as plain glass controls, no red disc, no
            // progress line.
            IconButton(
                onClick = onNext,
                modifier = Modifier.size((36f - 6f * fold).dp),
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size((24f - 2f * fold).dp),
                )
            }
            // The mini disc shares the player's glass-button
            // material (frostedDarkGlass), so it follows the
            // player's true-red press ring too (v1.0.21-test).
            val playPressSource = remember { MutableInteractionSource() }
            val playPressed by playPressSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .size((40f - 8f * fold).dp)
                    .clip(CircleShape)
                    // The flat capsule glass, like the player's
                    // controls (v1.0.20-test) — one material for
                    // every glass control in the assembly.
                    .frostedDarkGlass(CircleShape)
                    .trueRedPressRing(CircleShape, playPressed)
                    .clickable(interactionSource = playPressSource, indication = LocalIndication.current) { onTogglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                // Buffering honesty (owner's v1.0.7 note): while
                // a track is loading the mini strip shows the
                // spinner, exactly as the full player does — a
                // static play button made a trying track look
                // dead.
                if (state.buffering) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
