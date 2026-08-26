package com.kotlinsyd.forte.data

/** One playable song. [id] is the YouTube video id. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artistUrl: String? = null,
    val durationSec: Long = 0,
    val thumbUrl: String? = null,
    val viewCount: Long = -1,
)

data class AlbumRef(
    val url: String,
    val title: String,
    val artist: String? = null,
    val thumbUrl: String? = null,
    val detail: String? = null,
)

data class ArtistRef(
    val url: String,
    val name: String,
    val thumbUrl: String? = null,
    val detail: String? = null,
)

data class PlaylistPage(
    val title: String,
    val uploader: String?,
    val thumbUrl: String?,
    val tracks: List<Track>,
    val nextPage: Any?,
)

enum class AudioQuality { AUTO, HIGH, MEDIUM, LOW }

/**
 * One resolved audio stream, served by Forte's own engine
 * (ForteEngine). Carries the identity of the client that minted
 * [url]: googlevideo bakes that client into the URL and compares
 * it against the headers of the request that comes back for the
 * bytes, so playback and downloads must dress their media fetches
 * as [clientId] with [userAgent] — the client-identity discipline
 * every media fetch in this app follows.
 */
data class ResolvedAudio(
    val url: String,
    val format: String,
    val bitrateKbps: Int,
    val sizeBytes: Long,
    /** Which engine served this stream — shown on the player's
     * stream-info line. There is one engine: Forte's own. */
    val engine: String = "Forte",
    /** The serving client's User-Agent, for the media fetch. */
    val userAgent: String = "",
    /** The serving client's InnerTube name (e.g. "VISIONOS"). */
    val clientId: String = "",
)

/**
 * The largest artwork a source offers for a URL (owner's quality
 * rule): googleusercontent/ggpht URLs carry a size suffix — strip
 * it and ask for the 1080 form; i.ytimg video frames step up to
 * the largest standard frame (hqdefault — maxres is not guaranteed
 * to exist, hq always is). Account avatars are exempt (callers
 * don't pass them here): a face at its natural size is correct.
 * Anything else passes through untouched.
 */
fun bigArtwork(url: String?): String? {
    val u = url?.takeIf { it.isNotBlank() } ?: return null
    return when {
        u.contains("googleusercontent.com") || u.contains("ggpht.com") -> {
            val base = u.substringBefore("=")
            "$base=w1080-h1080-l90-rj"
        }
        u.contains("i.ytimg.com") -> u
            .replace("default.jpg", "hqdefault.jpg")
            .replace("mqdefault.jpg", "hqdefault.jpg")
        else -> u
    }
}

/**
 * The frame floor: the one picture that exists for EVERY video —
 * its hq frame on i.ytimg. No track may reach a list, the queue
 * or the player with no picture at all while this exists; richer
 * art (the service's own thumbnail set, an iTunes cover) upgrades
 * over the floor, never replaces the guarantee.
 */
fun frameFloorUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/** This track with the frame floor under it when it carries no art. */
fun Track.withArtworkFloor(): Track =
    if (thumbUrl.isNullOrBlank()) copy(thumbUrl = frameFloorUrl(id)) else this

fun formatDuration(totalSec: Long): String {
    if (totalSec <= 0) return ""
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatViews(views: Long): String = when {
    views >= 1_000_000_000 -> trimNum(views / 1e9) + "B"
    views >= 1_000_000 -> trimNum(views / 1e6) + "M"
    views >= 1_000 -> trimNum(views / 1e3) + "K"
    views > 0 -> views.toString()
    else -> ""
}

private fun trimNum(v: Double): String =
    if (v >= 100) v.toInt().toString()
    else "%.1f".format(v).removeSuffix(".0")

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1e3)
    else -> "$bytes B"
}
