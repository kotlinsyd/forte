package com.kotlinsyd.forte.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The second net for artwork (the owner's chain: YouTube Music cover
 * → iTunes → video frame). When a track's best YouTube artwork is
 * only a video frame (i.ytimg) or missing, iTunes' free, key-less
 * Search API usually has the real album cover; its artworkUrl100
 * rewrites to the 1200×1200 form. Matches are conservative — title
 * and artist must agree after normalising away "(feat. …)", brackets
 * and punctuation — because a wrong cover is worse than a frame.
 * Results (and misses) are cached per video id for the process, so
 * lists stay fast and the API is hit at most once per song.
 */
object ArtworkResolver {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, String>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    // Hero-frame verdicts (see maxresFrame): video id → the
    // frame URL the track should carry — maxres when the video
    // truly has one, its own thumb when it does not. Confirmed
    // answers only; an unconfirmed probe is never stored.
    private val frameCache = ConcurrentHashMap<String, String>()

    // The maxres probe gets its own client with a hard call
    // cap: it reads only the frame's header bytes, never the
    // full body, and a pathological connection must not hold
    // the charts load hostage.
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun needsUpgrade(thumbUrl: String?): Boolean =
        thumbUrl.isNullOrBlank() || thumbUrl.contains("i.ytimg.com")

    /** The iTunes cover for [track], or null when no confident match. */
    suspend fun itunesCover(track: Track): String? = withContext(Dispatchers.IO) {
        runCatching {
            cache[track.id]?.let { return@runCatching it }
            if (track.id in misses) return@runCatching null
            // First attempt: the track as carried. Second attempt,
            // only when the title itself names a different singer
            // than the artist field (a channel/label): the derived
            // candidate, judged by the same strict agreement.
            val found = searchItunes(track)
                ?: derivedFromTitle(track)?.let { searchItunes(it) }
            if (found != null) cache[track.id] = found else misses += track.id
            found
        }.getOrNull()
    }

    /** One iTunes search judged against [candidate]'s title+artist. */
    private fun searchItunes(candidate: Track): String? {
        val term = "${cleanTitle(candidate.title)} ${candidate.artist}".trim()
        if (term.isBlank()) return null
        val base = "https://itunes.apple.com/search".toHttpUrlOrNull()!!
        val url = base.newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "8")
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Forte/1.0")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val results = JSONObject(resp.body?.string() ?: "{}")
                .optJSONArray("results") ?: return@use null
            var best: String? = null
            var bestScore = 0
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val art = r.optString("artworkUrl100").takeIf { it.isNotBlank() }
                    ?: continue
                val score = matchScore(
                    candidate,
                    r.optString("trackName"),
                    r.optString("artistName"),
                )
                if (score > bestScore) {
                    bestScore = score
                    best = art
                }
            }
            // Both title and artist must agree (score 3) — a title
            // alone (2) is not enough to swap artwork.
            if (bestScore >= 3) {
                best?.replace("100x100bb.jpg", "1200x1200bb.jpg")
            } else null
        }
    }

    /**
     * Plain-video titles often carry the real singer themselves —
     * "ARTIST: Song | Movie …" — while the track's artist field
     * holds the uploading channel/label ("T-Series"), which no
     * iTunes row will ever match. Derive the candidate the title
     * itself names. Null when the title has no such shape, or when
     * it merely repeats the carried artist.
     */
    private fun derivedFromTitle(track: Track): Track? {
        val raw = track.title
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val derivedArtist = raw.substring(0, colon).trim()
        val derivedTitle = raw.substring(colon + 1).substringBefore('|').trim()
        if (derivedArtist.isBlank() || derivedTitle.isBlank()) return null
        if (derivedArtist.split(" ").size > 4) return null
        if (norm(derivedArtist) == norm(track.artist)) return null
        return track.copy(title = derivedTitle, artist = derivedArtist)
    }

    private fun cleanTitle(title: String): String =
        title.replace(Regex("""\(.*?\)|\[.*?]"""), " ")
            .replace(Regex("""(?i)\b(feat\.?|ft\.?)\b.*$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun norm(s: String): String =
        s.lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun matchScore(track: Track, candTitle: String, candArtist: String): Int {
        val wantTitle = norm(cleanTitle(track.title))
        val gotTitle = norm(candTitle)
        val titleOk = wantTitle.isNotBlank() &&
            (gotTitle == wantTitle || gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle))
        if (!titleOk) return 0
        val wantArtistTokens = norm(track.artist)
            .replace(Regex("""\b(feat|ft|and|with)\b"""), " ")
            .split(" ").filter { it.length > 1 }.toSet()
        val gotArtist = norm(candArtist)
        val artistOk = wantArtistTokens.isEmpty() ||
            wantArtistTokens.any { gotArtist.contains(it) }
        return if (artistOk) 3 else 2
    }

    /**
     * This track with its i.ytimg frame upgraded to
     * maxresdefault (1280×720) — or null when that is not a
     * confirmed improvement: non-frame art, a video with no
     * maxres, or a probe the network left unconfirmed.
     *
     * The missing-maxres trap: for a video that has no
     * maxres, YouTube answers the maxresdefault request with
     * HTTP 200 and a 120×90 placeholder — success, but not a
     * poster. The probe therefore reads the frame's REAL
     * dimensions (a bounds-only decode of the body's head;
     * the rest of the body is never pulled) and only a
     * genuinely wide frame counts — the real maxres is 1280
     * wide, the placeholder 120, so the bar sits at 640. A
     * non-200 answer (some videos 404 instead of serving the
     * placeholder) falls back the same way. Both confirmed
     * answers are cached per video for the process; a
     * transport failure confirms nothing, is not cached, and
     * a later load may retry it.
     *
     * Callers bake the answer into the track BEFORE display
     * (charts does, at parse), so the chosen URL rides the
     * track into the hero, the poster prefetch and any saved
     * row — no visible card ever swaps its picture late.
     */
    suspend fun maxresFrame(track: Track): Track? = withContext(Dispatchers.IO) {
        val thumb = track.thumbUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        if (!thumb.contains("i.ytimg.com")) return@withContext null
        if (thumb.contains("maxresdefault.jpg")) return@withContext null
        frameCache[track.id]?.let { chosen ->
            return@withContext if (chosen == thumb) null else track.copy(thumbUrl = chosen)
        }
        val maxres = "https://i.ytimg.com/vi/${track.id}/maxresdefault.jpg"
        val hasMaxres: Boolean? = runCatching {
            val req = Request.Builder().url(maxres)
                .header("User-Agent", "Forte/1.0")
                .build()
            probeHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    false
                } else {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    resp.body?.byteStream()?.let { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                    }
                    opts.outWidth >= 640
                }
            }
        }.getOrNull()
        // Unconfirmed (transport failure): cache nothing,
        // change nothing.
        if (hasMaxres == null) return@withContext null
        frameCache[track.id] = if (hasMaxres) maxres else thumb
        if (hasMaxres) track.copy(thumbUrl = maxres) else null
    }
}
