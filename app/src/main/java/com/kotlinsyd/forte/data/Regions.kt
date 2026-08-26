package com.kotlinsyd.forte.data

import java.util.Locale

/**
 * Forte's region: the country/language pair YouTube Music localises
 * its home feed, charts and account surfaces by (InnerTube gl/hl).
 * The owner picks it once on first open (device locale preselected)
 * and can change it any time in Settings → Region. The same pick is
 * applied to every InnerTube context the engine builds (since
 * v1.0.6), so search and the charts fallback follow it too — before
 * that, the extractor stayed on its US default and search results
 * ignored the pick.
 */
data class Region(
    /** ISO country code, sent as InnerTube "gl". */
    val gl: String,
    val name: String,
    /** InnerTube "hl" language code. */
    val hl: String,
)

object Regions {
    val all: List<Region> = listOf(
        Region("PK", "Pakistan", "en"),
        Region("IN", "India", "en"),
        Region("US", "United States", "en"),
        Region("GB", "United Kingdom", "en"),
        Region("AE", "UAE", "en"),
        Region("SA", "Saudi Arabia", "ar"),
        Region("BD", "Bangladesh", "en"),
        Region("CA", "Canada", "en"),
        Region("AU", "Australia", "en"),
        Region("DE", "Germany", "de"),
        Region("FR", "France", "fr"),
        Region("ES", "Spain", "es"),
        Region("MX", "Mexico", "es"),
        Region("BR", "Brazil", "pt"),
        Region("IT", "Italy", "it"),
        Region("NL", "Netherlands", "nl"),
        Region("TR", "Türkiye", "tr"),
        Region("EG", "Egypt", "ar"),
        Region("NG", "Nigeria", "en"),
        Region("ZA", "South Africa", "en"),
        Region("ID", "Indonesia", "id"),
        Region("MY", "Malaysia", "ms"),
        Region("PH", "Philippines", "en"),
        Region("TH", "Thailand", "th"),
        Region("VN", "Vietnam", "vi"),
        Region("KR", "South Korea", "ko"),
        Region("JP", "Japan", "ja"),
        Region("RU", "Russia", "ru"),
        Region("PL", "Poland", "pl"),
        Region("SE", "Sweden", "sv"),
        Region("NO", "Norway", "no"),
        Region("DK", "Denmark", "da"),
        Region("FI", "Finland", "fi"),
        Region("IE", "Ireland", "en"),
        Region("NZ", "New Zealand", "en"),
        Region("LK", "Sri Lanka", "en"),
        Region("NP", "Nepal", "en"),
        Region("KE", "Kenya", "en"),
        Region("GH", "Ghana", "en"),
        Region("AR", "Argentina", "es"),
        Region("CO", "Colombia", "es"),
        Region("CL", "Chile", "es"),
        Region("PE", "Peru", "es"),
    ).sortedBy { it.name }

    val DEFAULT = Region("US", "United States", "en")

    fun byGl(gl: String?): Region =
        all.firstOrNull { it.gl.equals(gl, ignoreCase = true) } ?: DEFAULT

    /** Preselect from the device: SIM/network country if the locale
     * carries one, else the locale's language region. */
    fun deviceDefault(): Region {
        val locale = Locale.getDefault()
        val country = locale.country.takeIf { it.isNotBlank() }
        return byGl(country)
    }
}

/**
 * The live region, readable from the data layer (InnerTube context)
 * without dragging a SettingsStore reference everywhere. ForteApp
 * seeds it at startup and every region change; it is the single
 * source the account layer reads.
 */
object RegionState {
    @Volatile
    var current: Region = Regions.DEFAULT
}
