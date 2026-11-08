package com.kotlinsyd.forte.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Forte's one theme: Onyx. Locked — no light theme, no variants. */
object ForteColors {
    val Bg = Color(0xFF0A0A0E)
    val Surface = Color(0xFF141419)
    val SurfaceHi = Color(0xFF1C1C23)
    val Border = Color(0xFF23232B)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFF9C9CA6)
    val Red = Color(0xFFE5484D)
    // TRUE RED (v1.0.20-test, owner's correction: "its not true red").
    // The shipped Red above (#E5484D) is a muted rose — only ~69%
    // saturated — so the small accent points now use this pure, solid
    // red (#FF0000) instead. SCOPE: the player's small points only —
    // the seek bar's played portion, the liked thumb-up fill, the
    // disliked thumb-down glyph, and the pause glyph/buffering ring.
    // Every other surface keeps Red; nothing large wears this.
    val TrueRed = Color(0xFFFF0000)
    val GlassFill = Color(0x14FFFFFF) // white 8%
    val GlassFillHi = Color(0x2EFFFFFF) // white 18%
    val GlassBorder = Color(0x24FFFFFF) // white 14%
}

private val ForteScheme = darkColorScheme(
    primary = ForteColors.Red,
    onPrimary = Color.White,
    background = ForteColors.Bg,
    onBackground = ForteColors.Text,
    surface = ForteColors.Surface,
    onSurface = ForteColors.Text,
    surfaceVariant = ForteColors.SurfaceHi,
    onSurfaceVariant = ForteColors.TextDim,
    outline = ForteColors.Border,
)

/**
 * Forte's one type scale (v1.0.14). Every screen draws its text sizes
 * from here — no ad-hoc sp literals for structural text. Steps follow
 * the standard music-app ladder: page hero 34, section 24, card title
 * 15, list row title 16, body 14, caption 12, group label 11 tracked.
 */
object ForteType {
    val pageTitle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold)
    val section = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
    val cardTitle = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    val cardSub = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    val rowTitle = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val rowSub = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val groupLabel = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
    )
}

private val ForteTypography = Typography(
    displaySmall = ForteType.pageTitle,
    headlineMedium = ForteType.section,
    titleMedium = ForteType.rowTitle,
    titleSmall = ForteType.cardTitle,
    bodyMedium = ForteType.body,
    bodySmall = ForteType.rowSub,
    labelSmall = ForteType.caption,
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
