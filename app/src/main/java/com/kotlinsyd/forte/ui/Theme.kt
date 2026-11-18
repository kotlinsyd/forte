package com.kotlinsyd.forte.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Forte's one theme (v1.0.37-test): the palette is BitChord's
 * dark design, adopted on the owner's order — pure black
 * canvas, near-black surfaces, iOS-grey dim text and hairline,
 * white as the primary (black content on it). The accent
 * survives only the way BitChord spends its accent:
 * sparingly, on small marks (badges, eyebrows, the liked
 * heart) — never as a general accent wash. Locked — no light
 * theme, no variants.
 *
 * v1.0.40-test: the accent COLOUR is now the app icon's own
 * pink — the core of the logo's gradient (#C25186, sampled
 * from the Vecteezy mark's EPS; stops and source in
 * CREDITS-logo.md) — on the owner's order: "Now we should
 * use our logo color in that all areas where we are using
 * red." Every surface that spent the red spends the pink
 * from the same two tokens below; the usage pattern (small
 * marks only) is unchanged.
 */
object ForteColors {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0D0D0F)
    val SurfaceHi = Color(0xFF1C1C1E)
    val Border = Color(0xFF2C2C2E)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFF8E8E93)
    /** The accent (v1.0.40-test): the logo gradient's core
     * pink, #C25186 — sampled from the app icon's EPS
     * (gradient stops #823A96 → #C25186 → #F16872; see
     * CREDITS-logo.md). Small marks only, as ever. Until
     * this build this token was BitChord's AccentRed
     * (#FA2D48); the owner's logo-colour order recoloured
     * the token, and every accent site follows it. */
    val Red = Color(0xFFC25186)
    // The player's accent points (v1.0.20-test set these to
    // a TRUE red (#FF0000) on the owner's correction "its
    // not true red", superseding the muted rose #E5484D of
    // the day): the seek bar's played portion, the liked
    // thumb-up fill, the disliked thumb-down glyph, the
    // press ring, the filled player button. SCOPE unchanged:
    // the player's small points only — nothing large wears
    // this. v1.0.40-test: on the owner's logo-colour order
    // ("all areas where we are using red") these points now
    // wear the same logo core pink as [Red]; the token keeps
    // its name and its scope, only the hue moved.
    val TrueRed = Color(0xFFC25186)
    val GlassFill = Color(0x14FFFFFF) // white 8%
    val GlassFillHi = Color(0x2EFFFFFF) // white 18%
    val GlassBorder = Color(0x24FFFFFF) // white 14%
}

/**
 * FORTE GLASS (v1.0.36-test, retuned v1.0.37-test to the
 * BitChord design the owner ordered) — Forte's glass design
 * system.
 *
 * Glass is the material on the floating surfaces; in-page
 * surfaces follow BitChord's flat near-black surfaces
 * (#0D0D0F / #1C1C1E), not blur. The radii and spacing below
 * are the design's ladder: shelf artwork at 12dp (BitChord's
 * card corner), the page gutter at 16dp (its PAGE_GUTTER),
 * the section rhythm at 26dp (its shelf spacing). The one
 * value that never moves is the player sheet's 36dp —
 * owner-locked. Where a surface floats over the recorded
 * page (the bottom capsules, the account card, the player's
 * sheet) the tint sits on a REAL backdrop blur instead
 * (Backdrop.kt); the user's Glass effects setting governs
 * both forms — off, a glass surface falls back to the flat
 * surface + border it replaces.
 */
object ForteGlass {
    // -- Radii: the corner ladder ------------------------------
    val RadiusArtworkSm: Dp = 8.dp  // row thumbnails, small art
    val RadiusArtwork: Dp = 12.dp   // shelf / card artwork
    val RadiusCard: Dp = 16.dp      // cards, half cards, mini strip
    val RadiusPanel: Dp = 20.dp     // page panels, settings groups
    val RadiusSheet: Dp = 28.dp     // action sheets, dialogs' kin
    val RadiusPlayerSheet: Dp = 36.dp // the player deck sheet (locked)
    val RadiusCapsule: Dp = 50.dp   // full-round: dock, chips, pills

    // -- Spacing: the scale ------------------------------------
    val SpaceXs: Dp = 4.dp
    val SpaceS: Dp = 8.dp
    val SpaceM: Dp = 12.dp
    val SpaceL: Dp = 16.dp
    val SpaceXl: Dp = 20.dp
    val SpaceXxl: Dp = 24.dp
    val PagePad: Dp = 16.dp    // the page gutter (BitChord's PAGE_GUTTER)
    val SectionGap: Dp = 26.dp // vertical rhythm between sections

    // -- The glass material -----------------------------------
    /** In-page panel tint: BitChord's surface, a breath of
     * translucency kept so a panel over artwork still reads
     * as one material with the floating glass. */
    val PanelTint: Color = Color(0xFF0D0D0F).copy(alpha = 0.88f)
    /** Chip fill: a breath of white over the page — chips and
     * small pills that should read as glass, not as blocks. */
    val ChipFill: Color = Color.White.copy(alpha = 0.07f)
    /** Text-field fill: BitChord's surfaceVariant — fields
     * wear the raised surface so typed text stays legible. */
    val FieldFill: Color = Color(0xFF1C1C1E)
    /** The hairline light edge every glass surface carries. */
    val Hairline: Color = Color.White.copy(alpha = 0.10f)
    /** Top sheen strength (a soft light lying across the top
     * of the glass, fading down its first stretch). */
    val SheenAlpha: Float = 0.05f
    /** Soft shadow under raised glass panels. */
    val ShadowElevation: Dp = 10.dp
}

private val ForteScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = ForteColors.Bg,
    onBackground = ForteColors.Text,
    surface = ForteColors.Surface,
    onSurface = ForteColors.Text,
    surfaceVariant = ForteColors.SurfaceHi,
    onSurfaceVariant = ForteColors.TextDim,
    outline = ForteColors.Border,
)

/**
 * Forte's one type scale (v1.0.14, re-based v1.0.37-test on
 * BitChord's ladder, per the owner's order). BitChord sets its
 * face one weight lighter than the step it names — its titles
 * draw Semibold, its metadata Medium — so this scale names the
 * DRAWN weight directly, in Forte's existing font: page and
 * headline titles Bold/Semibold, row and card titles Medium,
 * body Regular, small labels Medium. Letter spacing follows
 * the same source: small text opens up (+0.25…+0.3), titles
 * close slightly (−0.2…−0.5). Every screen draws its text
 * sizes from here — no ad-hoc sp literals for structural text.
 */
object ForteType {
    val pageTitle = TextStyle(
        fontSize = 34.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
    )
    val headline = TextStyle(
        fontSize = 30.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
    )
    val section = TextStyle(
        fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp,
    )
    val titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
    )
    val cardTitle = TextStyle(
        fontSize = 16.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.05.sp,
    )
    val cardSub = TextStyle(
        fontSize = 14.sp, lineHeight = 19.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp,
    )
    val rowTitle = TextStyle(
        fontSize = 16.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.05.sp,
    )
    val rowSub = TextStyle(
        fontSize = 14.sp, lineHeight = 19.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp,
    )
    val body = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp,
    )
    val bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp,
    )
    val caption = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.25.sp,
    )
    val groupLabel = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
    )
}

private val ForteTypography = Typography(
    displayLarge = ForteType.pageTitle,
    displaySmall = ForteType.pageTitle,
    headlineLarge = ForteType.headline,
    headlineMedium = ForteType.section,
    titleLarge = ForteType.titleLarge,
    titleMedium = ForteType.rowTitle,
    titleSmall = ForteType.cardTitle,
    bodyLarge = ForteType.bodyLarge,
    bodyMedium = ForteType.body,
    bodySmall = ForteType.rowSub,
    labelMedium = ForteType.caption,
    labelSmall = ForteType.groupLabel,
)

@Composable
fun ForteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ForteScheme,
        typography = ForteTypography,
        content = content,
    )
}

/** Deterministic artwork gradient for tracks without (or before) a thumbnail,
 * the way the design samples do it: a deep two-tone wash + the initial. */
fun artworkGradient(seed: String): List<Color> {
    val palettes = listOf(
        listOf(Color(0xFF4A2A6D), Color(0xFF1D1030)),
        listOf(Color(0xFF0E4A56), Color(0xFF08222A)),
        listOf(Color(0xFF6E2434), Color(0xFF2A0E16)),
        listOf(Color(0xFF274A78), Color(0xFF101E33)),
        listOf(Color(0xFF5C4514), Color(0xFF241B08)),
        listOf(Color(0xFF1F5C3D), Color(0xFF0C2618)),
        listOf(Color(0xFF63306E), Color(0xFF28122E)),
        listOf(Color(0xFF704838), Color(0xFF2C1C14)),
    )
    val idx = (seed.hashCode().mod(palettes.size) + palettes.size) % palettes.size
    return palettes[idx]
}
