package com.kotlinsyd.forte.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * The colours an album, playlist or artist page paints
 * itself in (v1.0.37-test) — the artwork-palette pattern
 * the owner ordered from the reference design, in Forte's
 * own code (AndroidX Palette + direct pixel reads; nothing
 * lifted).
 *
 * A page derived from its sleeve is not one design tinted
 * five ways: the whole page comes out of the artwork — a
 * page tint, the colour the artwork's own bottom edge
 * averages to (so a page that starts where the picture
 * stops reads as the picture's blur carrying on), a raised
 * glass fill for the chips that sit on the tint, and an
 * accent corrected until it is legible on that tint.
 * Forte is dark-only, so only the dark mapping exists.
 */
@Immutable
data class ForteArtworkPalette(
    /** The page's background wash (release/playlist pages). */
    val background: Color,
    /** The artwork's bottom-edge colour, lifted into a
     * band text still clears. An artist page is this
     * colour, flat, all the way down under its photograph. */
    val wash: Color,
    /** Fill for the glass buttons and chips on the tint. */
    val elevated: Color,
    /** The artwork's own colour, contrast-corrected —
     * the play disc, small marks. */
    val accent: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val divider: Color,
)

/** The theme's own colours, worn until (or unless) a seed lands. */
private fun themePalette(): ForteArtworkPalette = ForteArtworkPalette(
    background = ForteColors.Bg,
    wash = ForteColors.Bg,
    elevated = ForteColors.SurfaceHi,
    accent = Color.White,
    onBackground = ForteColors.Text,
    onBackgroundVariant = ForteColors.TextDim,
    divider = Color.White.copy(alpha = 0.12f),
)

/**
 * Pulls the [ForteArtworkPalette] out of the artwork at
 * [imageUrl]. Artwork read once is tinted on the very first
 * frame, off the seed cache — a page opened twice has
 * nothing to wait for and nothing to fade. Only a sleeve
 * genuinely being seen for the first time starts from the
 * theme's own colours and warms into the artwork's over a
 * short fade, so it never flashes a placeholder tint.
 */
@Composable
fun rememberArtworkPalette(imageUrl: String?): ForteArtworkPalette {
    val context = LocalContext.current
    var seed by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { artworkSeedCache[it] })
    }
    val knownUpFront = remember(imageUrl) { seed != null }

    LaunchedEffect(imageUrl) {
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (seed != null) return@LaunchedEffect
        val bitmap = runCatching {
            val result = coil.Coil.imageLoader(context).execute(
                coil.request.ImageRequest.Builder(context)
                    .data(com.kotlinsyd.forte.data.artworkAt(url, com.kotlinsyd.forte.data.ArtSize.CARD))
                    .size(PALETTE_PX, PALETTE_PX)
                    .allowHardware(false)
                    .build(),
            )
            (result as? coil.request.SuccessResult)
                ?.drawable as? BitmapDrawable
        }.getOrNull()?.bitmap ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) { seedOf(bitmap) }
            ?: return@LaunchedEffect
        artworkSeedCache[url] = found
        seed = found
    }

    val target = seed?.toPalette() ?: themePalette()
    val spec: AnimationSpec<Color> = if (knownUpFront) snap() else tween(TINT_FADE_MS)
    return ForteArtworkPalette(
        background = animateColorAsState(target.background, spec, label = "tintBg").value,
        wash = animateColorAsState(target.wash, spec, label = "tintWash").value,
        elevated = animateColorAsState(target.elevated, spec, label = "tintElevated").value,
        accent = animateColorAsState(target.accent, spec, label = "tintAccent").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "tintOn").value,
        onBackgroundVariant = animateColorAsState(
            target.onBackgroundVariant, spec, label = "tintOnVariant",
        ).value,
        divider = animateColorAsState(target.divider, spec, label = "tintDivider").value,
    )
}

/** Colours already read, keyed by artwork URL — the artwork
 * at a URL is the artwork at that URL, so a re-read can only
 * ever produce the same answer. Main-thread access only. */
private val artworkSeedCache = object : LinkedHashMap<String, Seed>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Seed>) = size > 128
}

private const val PALETTE_PX = 128

/** Short: a surface settling into its colour, not an effect. */
private const val TINT_FADE_MS = 260

/** How much of the artwork's height the edge colour is read from. */
private const val EDGE_BAND = 0.18f

/** The raw artwork colours: what the page is mostly made
 * of, its brightest note, and what its bottom edge
 * averages out to. */
private data class Seed(
    val dominant: Color,
    val vibrant: Color,
    val edge: Color,
)

private fun seedOf(bitmap: Bitmap): Seed? {
    val palette = Palette.from(bitmap).generate()
    val dominant = palette.dominantSwatch ?: return null
    // The accent has to earn its place twice over: a colour
    // nobody sees enough of reads as arbitrary, and a grey
    // one isn't an accent at all. Palette's own vibrant
    // swatch scores saturation against population; when it
    // has none, score the swatches the same way by hand
    // (saturation × √population, so a sleeve that is
    // four-fifths black sky does not accent in black).
    val vibrant = palette.vibrantSwatch ?: palette.swatches.maxByOrNull { swatch ->
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(swatch.rgb, hsl)
        hsl[1] * sqrt(swatch.population.toFloat())
    } ?: dominant
    return Seed(
        dominant = Color(dominant.rgb),
        vibrant = Color(vibrant.rgb),
        edge = bitmap.bottomEdgeColor(),
    )
}

/**
 * The mean of the artwork's bottom band — what a blur wide
 * enough to lose the picture leaves behind at that edge. A
 * flat mean rather than a quantised swatch on purpose: a
 * blur has no notion of which colour is *important*, and
 * the page under the artwork has to match what the blur
 * actually produced, not what the picture is about.
 */
private fun Bitmap.bottomEdgeColor(): Color {
    val band = (height * EDGE_BAND).toInt().coerceIn(1, height)
    val pixels = IntArray(width * band)
    getPixels(pixels, 0, width, 0, height - band, width, band)
    var red = 0L
    var green = 0L
    var blue = 0L
    pixels.forEach { pixel ->
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
    }
    val count = pixels.size.coerceAtLeast(1)
    return Color(
        red = (red / count).toInt(),
        green = (green / count).toInt(),
        blue = (blue / count).toInt(),
    )
}

/** The dark mapping: every value derived from the seed. */
private fun Seed.toPalette(): ForteArtworkPalette = ForteArtworkPalette(
    // Deep enough that white body text clears contrast on
    // any sleeve, but not so deep the hue is gone — the
    // page stays recognisably *this* record's colour.
    background = dominant.withHsl(
        saturation = { adaptedSaturation(it, minimum = 0.20f, maximum = 0.62f) },
        lightness = { 0.13f },
    ),
    // Follows the edge's own brightness inside a band that
    // stays clear of white text at the top and of
    // [background] at the bottom.
    wash = edge.withHsl(
        saturation = { adaptedSaturation(it, minimum = 0.18f, maximum = 0.58f) },
        lightness = { it.coerceIn(0.14f, 0.24f) },
    ),
    elevated = dominant.withHsl(
        saturation = { adaptedSaturation(it, minimum = 0.20f, maximum = 0.62f) },
        lightness = { 0.22f },
    ),
    accent = vibrant.withHsl(
        saturation = { adaptedSaturation(it, minimum = 0.55f, maximum = 1f) },
        lightness = { it.coerceIn(0.62f, 0.78f) },
    ),
    onBackground = Color.White,
    // A pale tint of the page's own hue rather than a
    // translucent grey — a blue record's credits read light
    // blue on its blue page. Bright enough to still clear
    // contrast on [background].
    onBackgroundVariant = dominant.withHsl(
        saturation = { adaptedSaturation(it, minimum = 0.22f, maximum = 0.50f) },
        lightness = { 0.80f },
    ),
    divider = Color.White.copy(alpha = 0.12f),
)

/**
 * Keeps neutral artwork neutral instead of inventing a hue
 * for it. HSL represents grey with hue zero; raising that
 * grey to a saturation floor manufactures red, which turns
 * brown/maroon at page lightness. Below the threshold the
 * colour is kept as-is; a genuinely chromatic swatch is
 * strengthened into its band.
 */
private fun adaptedSaturation(source: Float, minimum: Float, maximum: Float): Float {
    val saturation = source.coerceIn(0f, 1f)
    return if (saturation < CHROMATIC_THRESHOLD) {
        saturation
    } else {
        saturation.coerceIn(minimum, maximum)
    }
}

/** Below this, boosting saturation makes quantisation noise visible as a tint. */
private const val CHROMATIC_THRESHOLD = 0.12f

private fun Color.withHsl(
    saturation: (Float) -> Float = { it },
    lightness: (Float) -> Float = { it },
): Color {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = saturation(hsl[1]).coerceIn(0f, 1f)
    hsl[2] = lightness(hsl[2]).coerceIn(0f, 1f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}
