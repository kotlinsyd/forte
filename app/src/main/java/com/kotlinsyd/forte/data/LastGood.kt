package com.kotlinsyd.forte.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last-good content cache — the owner's loading rule made concrete:
 * skeletons only while a load is genuinely in flight and fast; the
 * moment the network is slow, the screen shows the PREVIOUSLY LOADED
 * content and the fresh load keeps running in the background,
 * swapping in when it lands. Every network screen keeps its last
 * good payload here (one small JSON file; whole-file rewrites under
 * a lock — the data is a few hundred KB at most).
 */
object LastGood {
    private const val FILE_NAME = "lastgood.json"
    private val lock = Any()
    private var map: MutableMap<String, String>? = null

    private fun load(context: Context): MutableMap<String, String> {
        map?.let { return it }
        val loaded = mutableMapOf<String, String>()
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.isFile) {
                val root = JSONObject(file.readText())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    loaded[k] = root.optString(k)
                }
            }
        }
        map = loaded
        return loaded
    }

    fun get(context: Context, key: String): String? = synchronized(lock) {
        load(context)[key]?.takeIf { it.isNotBlank() }
    }

    fun put(context: Context, key: String, value: String) {
        synchronized(lock) {
            val m = load(context)
            m[key] = value
            runCatching {
                val root = JSONObject()
                m.forEach { (k, v) -> root.put(k, v) }
                File(context.filesDir, FILE_NAME).writeText(root.toString())
            }
        }
    }

    // ------------------------------------------------------------------
    // Track list payloads (search results, playlist detail, charts)
    // ------------------------------------------------------------------

    fun putTracks(context: Context, key: String, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(it.toJson()) }
        put(context, key, arr.toString())
    }

    fun getTracks(context: Context, key: String): List<Track>? {
        val raw = get(context, key) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<Track>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out += it.toTrack() }
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun putRefs(context: Context, key: String, refs: List<AlbumRef>) {
        val arr = JSONArray()
        refs.forEach { ref ->
            arr.put(
                JSONObject()
                    .put("url", ref.url)
                    .put("title", ref.title)
                    .put("artist", ref.artist ?: JSONObject.NULL)
                    .put("thumbUrl", ref.thumbUrl ?: JSONObject.NULL)
                    .put("detail", ref.detail ?: JSONObject.NULL),
            )
        }
        put(context, key, arr.toString())
    }

    fun getRefs(context: Context, key: String): List<AlbumRef>? {
        val raw = get(context, key) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<AlbumRef>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += AlbumRef(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    artist = o.optString("artist").takeIf { it.isNotBlank() },
                    thumbUrl = o.optString("thumbUrl").takeIf { it.isNotBlank() },
                    detail = o.optString("detail").takeIf { it.isNotBlank() },
                )
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun putShelves(context: Context, key: String, shelves: List<YtAccount.HomeShelf>) {
        val shelfArr = JSONArray()
        shelves.forEach { shelf ->
            val s = JSONObject().put("title", shelf.title)
            val tArr = JSONArray()
            shelf.tracks.forEach { tArr.put(it.toJson()) }
            s.put("tracks", tArr)
            val cArr = JSONArray()
            shelf.cards.forEach { card ->
                cArr.put(
                    JSONObject()
                        .put("browseId", card.browseId)
                        .put("title", card.title)
                        .put("subtitle", card.subtitle ?: JSONObject.NULL)
                        .put("thumbUrl", card.thumbUrl ?: JSONObject.NULL),
                )
            }
            s.put("cards", cArr)
            shelfArr.put(s)
        }
        put(context, key, shelfArr.toString())
    }

    fun getShelves(context: Context, key: String): List<YtAccount.HomeShelf>? {
        val raw = get(context, key) ?: return null
        return runCatching {
            val shelfArr = JSONArray(raw)
            val shelves = mutableListOf<YtAccount.HomeShelf>()
            for (i in 0 until shelfArr.length()) {
                val s = shelfArr.optJSONObject(i) ?: continue
                val tracks = mutableListOf<Track>()
                val tArr = s.optJSONArray("tracks") ?: JSONArray()
                for (j in 0 until tArr.length()) {
                    tArr.optJSONObject(j)?.let { tracks += it.toTrack() }
                }
                val cards = mutableListOf<YtAccount.HomeCard>()
                val cArr = s.optJSONArray("cards") ?: JSONArray()
                for (j in 0 until cArr.length()) {
                    val c = cArr.optJSONObject(j) ?: continue
                    cards += YtAccount.HomeCard(
                        browseId = c.optString("browseId"),
                        title = c.optString("title"),
                        subtitle = c.optString("subtitle").takeIf { it.isNotBlank() },
                        thumbUrl = c.optString("thumbUrl").takeIf { it.isNotBlank() },
                    )
                }
                shelves += YtAccount.HomeShelf(s.optString("title"), tracks, cards)
            }
            shelves.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun putMoodSections(
        context: Context,
        key: String,
        sections: List<YtAccount.MoodSection>,
    ) {
        val root = JSONArray()
        sections.forEach { section ->
            val s = JSONObject().put("title", section.title)
            val items = JSONArray()
            section.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("title", item.title)
                        .put("browseId", item.browseId)
                        .put("params", item.params ?: JSONObject.NULL)
                        .put("colorArgb", item.colorArgb ?: JSONObject.NULL),
                )
            }
            s.put("items", items)
            root.put(s)
        }
        put(context, key, root.toString())
    }

    fun getMoodSections(
        context: Context,
        key: String,
    ): List<YtAccount.MoodSection>? {
        val raw = get(context, key) ?: return null
        return runCatching {
            val root = JSONArray(raw)
            val out = mutableListOf<YtAccount.MoodSection>()
            for (i in 0 until root.length()) {
                val s = root.optJSONObject(i) ?: continue
                val items = mutableListOf<YtAccount.MoodGenre>()
                val arr = s.optJSONArray("items") ?: JSONArray()
                for (j in 0 until arr.length()) {
                    val item = arr.optJSONObject(j) ?: continue
                    items += YtAccount.MoodGenre(
                        title = item.optString("title"),
                        browseId = item.optString("browseId"),
                        params = item.optString("params").takeIf { it.isNotBlank() },
                        colorArgb = if (item.isNull("colorArgb")) null else item.optLong("colorArgb"),
                    )
                }
                if (items.isNotEmpty()) {
                    out += YtAccount.MoodSection(s.optString("title"), items)
                }
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun putArtists(context: Context, key: String, refs: List<ArtistRef>) {
        putRefs(
            context, key,
            refs.map { AlbumRef(it.url, it.name, null, it.thumbUrl, it.detail) },
        )
    }

    fun getArtists(context: Context, key: String): List<ArtistRef>? =
        getRefs(context, key)?.map { ArtistRef(it.url, it.title, it.thumbUrl, it.detail) }
}

/** Home's last-good payload: the full shelf set + charts for one
 * region and sign-in state. A region change reads a different key,
 * so the previous region's content can never pose as the new one. */
object HomeCache {
    data class Cached(
        val shelves: List<YtAccount.HomeShelf>,
        val charts: List<Track>,
        val chartsLabel: String?,
    )

    fun key(gl: String, signedIn: Boolean): String =
        "home:${gl.uppercase()}:${if (signedIn) "in" else "guest"}"

    fun load(context: Context, gl: String, signedIn: Boolean): Cached? {
        val raw = LastGood.get(context, key(gl, signedIn)) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val shelves = mutableListOf<YtAccount.HomeShelf>()
            val shelfArr = root.optJSONArray("shelves") ?: JSONArray()
            for (i in 0 until shelfArr.length()) {
                val s = shelfArr.optJSONObject(i) ?: continue
                val tracks = mutableListOf<Track>()
                val tArr = s.optJSONArray("tracks") ?: JSONArray()
                for (j in 0 until tArr.length()) {
                    tArr.optJSONObject(j)?.let { tracks += it.toTrack() }
                }
                val cards = mutableListOf<YtAccount.HomeCard>()
                val cArr = s.optJSONArray("cards") ?: JSONArray()
                for (j in 0 until cArr.length()) {
                    val c = cArr.optJSONObject(j) ?: continue
                    cards += YtAccount.HomeCard(
                        browseId = c.optString("browseId"),
                        title = c.optString("title"),
                        subtitle = c.optString("subtitle").takeIf { it.isNotBlank() },
                        thumbUrl = c.optString("thumbUrl").takeIf { it.isNotBlank() },
                    )
                }
                shelves += YtAccount.HomeShelf(
                    s.optString("title"), tracks, cards,
                )
            }
            val charts = mutableListOf<Track>()
            val chArr = root.optJSONArray("charts") ?: JSONArray()
            for (i in 0 until chArr.length()) {
                chArr.optJSONObject(i)?.let { charts += it.toTrack() }
            }
            Cached(
                shelves = shelves,
                charts = charts,
                chartsLabel = root.optString("chartsLabel").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    fun save(
        context: Context,
        gl: String,
        signedIn: Boolean,
        shelves: List<YtAccount.HomeShelf>,
        charts: List<Track>,
        chartsLabel: String?,
    ) {
        runCatching {
            val root = JSONObject()
            val shelfArr = JSONArray()
            shelves.forEach { shelf ->
                val s = JSONObject().put("title", shelf.title)
                val tArr = JSONArray()
                shelf.tracks.forEach { tArr.put(it.toJson()) }
                s.put("tracks", tArr)
                val cArr = JSONArray()
                shelf.cards.forEach { card ->
                    cArr.put(
                        JSONObject()
                            .put("browseId", card.browseId)
                            .put("title", card.title)
                            .put("subtitle", card.subtitle ?: JSONObject.NULL)
                            .put("thumbUrl", card.thumbUrl ?: JSONObject.NULL),
                    )
                }
                s.put("cards", cArr)
                shelfArr.put(s)
            }
            root.put("shelves", shelfArr)
            val chArr = JSONArray()
            charts.forEach { chArr.put(it.toJson()) }
            root.put("charts", chArr)
            root.put("chartsLabel", chartsLabel ?: JSONObject.NULL)
            LastGood.put(context, key(gl, signedIn), root.toString())
        }
    }
}

internal fun Track.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("artist", artist)
    .put("artistUrl", artistUrl ?: JSONObject.NULL)
    .put("durationSec", durationSec)
    .put("thumbUrl", thumbUrl ?: JSONObject.NULL)
    .put("viewCount", viewCount)

internal fun JSONObject.toTrack(): Track = Track(
    id = optString("id"),
    title = optString("title"),
    artist = optString("artist"),
    artistUrl = optString("artistUrl").takeIf { it.isNotBlank() },
    durationSec = optLong("durationSec"),
    thumbUrl = optString("thumbUrl").takeIf { it.isNotBlank() },
    viewCount = optLong("viewCount", -1),
)
