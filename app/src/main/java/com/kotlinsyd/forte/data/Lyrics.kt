package com.kotlinsyd.forte.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One timestamped lyric line (LRC), times in milliseconds. */
data class LyricLine(val timeMs: Long, val text: String)

/** Lyrics for one song: plain text always when found; [synced] is
 * present only when LRCLIB has timestamped lines for it. */
data class LyricsResult(
    val plain: String,
    val synced: List<LyricLine>? = null,
)

/**
 * Lyrics come from LRCLIB (lrclib.net) — a free, key-less lyrics database.
 * If a song isn't there, the player simply shows no lyrics strip. Nothing is
 * faked or scraped from anywhere shady.
 */
object Lyrics {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ConcurrentHashMap forbids null keys and values. The old cache was
    // ConcurrentHashMap<String, String?> and the fetch stored its result
    // unconditionally — storing a "no lyrics" null threw an NPE out of
    // ConcurrentHashMap.putVal, escaped the coroutine, and killed the
    // app (Sentry FORTE-1, on the open-player-while-loading path).
    // Hits live in [cache]; misses are remembered in [misses] instead,
    // so a null is never put into the map.
    private val cache = ConcurrentHashMap<String, LyricsResult>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    suspend fun fetch(track: Track): String? = fetchResult(track)?.plain

    suspend fun fetchResult(track: Track): LyricsResult? = withContext(Dispatchers.IO) {
        // Fenced end to end: nothing in here — network, parsing, or the
        // cache write — may ever throw out of a lyrics fetch. A failure
        // simply means "no lyrics strip for this song".
        runCatching {
            val key = track.id
            cache[key]?.let { return@runCatching it }
            if (key in misses) return@runCatching null
            val result = runCatching {
                val base = "https://lrclib.net/api/search".toHttpUrlOrNull()!!
                val url = base.newBuilder()
                    .addQueryParameter("track_name", track.title)
                    .addQueryParameter("artist_name", track.artist)
                    .build()
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Forte/1.0.0")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    var best: LyricsResult? = null
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() }
                        val syncedRaw = obj.optString("syncedLyrics").takeIf { it.isNotBlank() }
                        val synced = syncedRaw?.let { parseLrc(it) }
                        val text = plain ?: synced?.joinToString("\n") { it.text }
                        if (text != null) {
                            best = LyricsResult(text, synced)
                            break
                        }
                    }
                    best
                }
            }.getOrNull()
            if (result != null) cache[key] = result else misses += key
            result
        }.getOrNull()
    }

    private val LRC_TAG = Regex("""\[(\d+):(\d+)\.(\d+)]""")

    /** Parse LRC text into timestamped lines (unsorted input is
     * sorted; lines without a valid tag are dropped). */
    private fun parseLrc(synced: String): List<LyricLine>? {
        val out = mutableListOf<LyricLine>()
        for (line in synced.lines()) {
            val m = LRC_TAG.find(line) ?: continue
            val (min, sec, frac) = m.destructured
            val fracMs = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0
            val timeMs = min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs
            val text = line.replace(LRC_TAG, "").trim()
            if (text.isNotBlank()) out += LyricLine(timeMs, text)
        }
        return out.sortedBy { it.timeMs }.takeIf { it.isNotEmpty() }
    }
}
