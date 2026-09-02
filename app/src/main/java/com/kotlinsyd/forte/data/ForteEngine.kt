package com.kotlinsyd.forte.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Forte's OWN engine (v1.0.8) — the only engine. Built on the
 * standard InnerTube client method — per-client contexts, signed
 * requests, visitor-bound sessions — implemented clean-room in
 * Forte's own code and names:
 *
 *  - Client identities per call: a ladder of InnerTube clients
 *    (ANDROID_VR → VISIONOS → ANDROID → IOS, then a cipher rung),
 *    each asking with its own client context and User-Agent.
 *  - visitorData binding: the session's visitor id is minted
 *    deliberately (the youtube.com service-worker bootstrap,
 *    found by shape) and refreshed on refusal — never hoped for.
 *    A player request with no visitor id is treated by Google as
 *    a client with no session: the loud answer is LOGIN_REQUIRED,
 *    the quiet one is a normal-looking response whose stream URLs
 *    refuse every real read.
 *  - Guest only: resolution and search never carry the account
 *    cookies. The signed-in session belongs to the account layer
 *    (library, likes, history); playback identity is the client's.
 *  - Direct-URL clients with SABR off, verdict/bench discipline:
 *    a client that refuses a track is benched for ten minutes.
 *  - Nothing is handed to the player until bytes have been
 *    fetched from its URL: every resolved stream is probed with
 *    the exact request the media fetch will make (headers dressed
 *    from the URL's own client stamp, a bounded range window of
 *    the size that client's fetches use, starting past the 1 MiB
 *    boundary some clients' URLs stop honouring), and 16 KiB of
 *    body must actually arrive. A URL can be dead on arrival; the
 *    probe is what turns "spins forever" into "next client, now".
 *  - Media-fetch dressing ([MediaDress]): googlevideo bakes the
 *    minting client into the URL as c=/cver= and compares it
 *    against the headers of the request that comes back for the
 *    bytes — a mismatch is throttled to a crawl or refused. The
 *    player's datasource (see ChunkedDataSource) therefore wears
 *    the minting client's identity and reads in bounded ranges,
 *    the only shape googlevideo serves at line rate.
 *
 * The v1.0.7 failure this replaces: URLs were handed to ExoPlayer
 * raw. Its datasource asked with a default OkHttp user-agent and
 * an open-ended read — a shape googlevideo refuses (403) for the
 * VR/Android clients — and the resulting load error was retried
 * against the same door, so every song buffered forever.
 */
object ForteEngine {

    private const val WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ANDROID_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    private const val IOS_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
    private const val REMIX_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val REMIX_VERSION = "1.20241016.01.00"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Per-attempt fence: one client may never hold a song hostage. */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** The probe's own client, on a short leash: a yes/no question
     * must not wait out a stall — waiting is indistinguishable from
     * the stall being tested for. */
    private val probeHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Media-fetch dressing — the identity a stream URL was minted for
    // ------------------------------------------------------------------

    object MediaDress {
        private const val VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val ANDROID_UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
        private const val IOS_UA =
            "com.google.ios.youtube/20.10.4 " +
                "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"

        /**
         * The headers a media fetch for [url] must carry to match
         * whoever minted it, read from the URL's own c= stamp.
         * Native app clients send a User-Agent only; browser-shaped
         * clients also send their Origin/Referer — sending one for
         * a native client is as wrong as omitting it for a web one.
         * Unknown stamps fall back to the iOS identity: being
         * approximately right beats a contradicting guess.
         */
        fun headersFor(url: String): Map<String, String> {
            val name = url.toHttpUrlOrNull()?.queryParameter("c")
                ?.uppercase() ?: return mapOf("User-Agent" to IOS_UA)
            return when {
                name == "ANDROID_VR" -> mapOf("User-Agent" to VR_UA)
                name == "VISIONOS" -> mapOf("User-Agent" to BROWSER_UA)
                name == "ANDROID" || name.startsWith("ANDROID") ->
                    mapOf("User-Agent" to ANDROID_UA)
                name.startsWith("IOS") -> mapOf("User-Agent" to IOS_UA)
                name == "WEB_REMIX" -> mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/",
                )
                name.startsWith("WEB") || name == "MWEB" -> mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com/",
                )
                else -> mapOf("User-Agent" to IOS_UA)
            }
        }

        /**
         * The largest single range googlevideo reliably serves for
         * [url]'s client: 512 KiB for ANDROID_VR-class URLs, 1 MiB
         * for the rest. Non-googlevideo URLs have no cap of ours.
         */
        fun rangeBytesFor(url: String): Long {
            val parsed = url.toHttpUrlOrNull() ?: return Long.MAX_VALUE
            if (!parsed.host.endsWith("googlevideo.com")) return Long.MAX_VALUE
            val name = parsed.queryParameter("c")?.uppercase()
            return if (name == "ANDROID_VR" || name?.startsWith("TVHTML5_SIMPLY") == true) {
                512L * 1024
            } else {
                1024L * 1024
            }
        }
    }

    // ------------------------------------------------------------------
    // Client ladder (direct-URL clients first)
    // ------------------------------------------------------------------

    private class ClientSpec(
        val id: String,
        val apiKey: String,
        val userAgent: String,
        val useVisitorData: Boolean,
        val buildClient: (hl: String, gl: String) -> JSONObject,
    )

    private fun clientJson(
        name: String,
        version: String,
        hl: String,
        gl: String,
        extra: JSONObject.() -> Unit = {},
    ): JSONObject = JSONObject()
        .put("clientName", name)
        .put("clientVersion", version)
        .put("hl", hl)
        .put("gl", gl)
        .apply(extra)

    private val ladder: List<ClientSpec> = listOf(
        ClientSpec(
            id = "ANDROID_VR",
            apiKey = ANDROID_KEY,
            userAgent = MediaDress.headersFor(
                "https://x.googlevideo.com/?c=ANDROID_VR",
            ).getValue("User-Agent"),
            useVisitorData = true,
            buildClient = { hl, gl ->
                clientJson("ANDROID_VR", "1.65.10", hl, gl) {
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("osName", "Android")
                    put("osVersion", "12L")
                    put("androidSdkVersion", 32)
                }
            },
        ),
        ClientSpec(
            id = "VISIONOS",
            apiKey = WEB_KEY,
            userAgent = BROWSER_UA,
            useVisitorData = true,
            buildClient = { hl, gl ->
                clientJson("VISIONOS", "1.02", hl, gl) {
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice14,1")
                    put("osName", "visionOS")
                    put("osVersion", "2.0.24K340")
                }
            },
        ),
        ClientSpec(
            id = "ANDROID",
            apiKey = ANDROID_KEY,
            userAgent = MediaDress.headersFor(
                "https://x.googlevideo.com/?c=ANDROID",
            ).getValue("User-Agent"),
            useVisitorData = true,
            buildClient = { hl, gl ->
                clientJson("ANDROID", "20.10.38", hl, gl) {
                    put("androidSdkVersion", 30)
                    put("osName", "Android")
                    put("osVersion", "11")
                }
            },
        ),
        ClientSpec(
            id = "IOS",
            apiKey = IOS_KEY,
            userAgent = MediaDress.headersFor(
                "https://x.googlevideo.com/?c=IOS",
            ).getValue("User-Agent"),
            useVisitorData = false,
            buildClient = { hl, gl ->
                clientJson("IOS", "20.10.4", hl, gl) {
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82")
                }
            },
        ),
    )

    /** A refusing client is benched for ten minutes (the verdict
     * cache discipline) instead of being asked again for every
     * song in the queue. */
    private val benchedUntil = ConcurrentHashMap<String, Long>()
    private const val BENCH_MS = 10 * 60_000L

    private fun isBenched(id: String): Boolean =
        (benchedUntil[id] ?: 0L) > System.currentTimeMillis()

    private fun bench(id: String) {
        benchedUntil[id] = System.currentTimeMillis() + BENCH_MS
    }

    // ------------------------------------------------------------------
    // visitorData — minted deliberately, never hoped for
    // ------------------------------------------------------------------

    @Volatile
    private var visitorData: String? = null

    private fun captureVisitorData(root: JSONObject) {
        root.optJSONObject("responseContext")
            ?.optString("visitorData")?.takeIf { it.isNotBlank() }
            ?.let { visitorData = it }
    }

    /** Protobuf-in-base64; always this shape, and nothing else in
     * the bootstrap payload is. */
    private val VISITOR_SHAPE = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    /**
     * Refresh the session's visitor id. Primary source is the
     * youtube.com service-worker bootstrap (sw.js_data) — a fresh
     * id without needing a page, found by shape rather than by a
     * path that would rot. Fallback: a guest WEB_REMIX browse,
     * whose responseContext carries one. Guest throughout: no
     * account cookies ride either call.
     */
    private fun refreshVisitorData() {
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", BROWSER_UA)
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                VISITOR_SHAPE.find(body)?.value?.let {
                    visitorData = it
                    return
                }
            }
        }
        runCatching {
            val region = RegionState.current
            val client = clientJson("WEB_REMIX", REMIX_VERSION, region.hl, region.gl)
            val body = JSONObject()
                .put("context", JSONObject().put("client", client))
                .put("browseId", "FEmusic_home")
            val root = postJson(
                "https://music.youtube.com/youtubei/v1/browse" +
                    "?key=$REMIX_KEY&prettyPrint=false",
                body,
                BROWSER_UA,
            )
            captureVisitorData(root)
        }
    }

    // ------------------------------------------------------------------
    // HTTP (guest only — no auth headers anywhere in this engine)
    // ------------------------------------------------------------------

    private fun postJson(
        url: String,
        body: JSONObject,
        userAgent: String,
    ): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "InnerTube call failed (${resp.code})" }
            return JSONObject(text)
        }
    }

    private fun playerCall(
        spec: ClientSpec,
        videoId: String,
        signatureTimestamp: Int,
    ): JSONObject {
        val region = RegionState.current
        val client = spec.buildClient(region.hl, region.gl)
        if (spec.useVisitorData) {
            if (visitorData == null) refreshVisitorData()
            visitorData?.let { client.put("visitorData", it) }
        }
        val playback = JSONObject()
            .put("vis", 0)
            .put("splay", false)
            .put("lactMilliseconds", "-1")
            .put("signatureTimestamp", signatureTimestamp)
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
            .put(
                "playbackContext",
                JSONObject().put("contentPlaybackContext", playback),
            )
            .put("racyCheckOk", true)
            .put("contentCheckOk", true)
        val root = postJson(
            "https://www.youtube.com/youtubei/v1/player" +
                "?key=${spec.apiKey}&prettyPrint=false",
            body,
            spec.userAgent,
        )
        captureVisitorData(root)
        return root
    }

    // ------------------------------------------------------------------
    // The probe — a URL is not trusted until bytes arrive from it
    // ------------------------------------------------------------------

    /** Where some clients' URLs stop being honoured (the CDN's
     * post-1 MiB refusal), and how much body must actually arrive. */
    private const val AUTH_BOUNDARY_BYTES = 1024L * 1024
    private const val PROBE_READ_BYTES = 16L * 1024

    /**
     * Fetch one real window from [url] exactly as the media fetch
     * will: headers dressed from the URL's own client stamp, a
     * bounded range of the size that client's fetches use, starting
     * past the 1 MiB boundary when the file is that long (some
     * clients' URLs serve the first megabyte and refuse everything
     * after it, so a probe of the opening would pass a URL that
     * dies a minute into the song). True only when the answer is a
     * success with an audio content type AND [PROBE_READ_BYTES] of
     * body actually arrive — headers alone can precede a body that
     * never comes.
     */
    private fun probe(url: String, contentLength: Long): Boolean {
        val clen = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
            ?: contentLength.takeIf { it > 0 }
        val start = if (clen != null && clen > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) {
            AUTH_BOUNDARY_BYTES
        } else {
            0L
        }
        val window = MediaDress.rangeBytesFor(url)
        val range = when {
            window != Long.MAX_VALUE ->
                "bytes=$start-${minOf(start + window, clen ?: (start + window)) - 1}"
            clen != null -> "bytes=$start-${clen - 1}"
            else -> "bytes=$start-"
        }
        val builder = Request.Builder().url(url).header("Range", range)
        MediaDress.headersFor(url).forEach { (k, v) -> builder.header(k, v) }
        return try {
            probeHttp.newCall(builder.build()).execute().use { resp ->
                val type = resp.header("Content-Type").orEmpty()
                when {
                    resp.code in setOf(403, 404, 410) -> false
                    resp.code !in 200..299 -> false
                    !type.startsWith("audio/") -> false
                    resp.body == null -> false
                    else -> runCatching {
                        resp.body!!.source().request(PROBE_READ_BYTES)
                    }.getOrDefault(false)
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * A stream the probe cleared has been refused while actually
     * playing (the datasource reports it). Forget the URL and bench
     * the client that minted it for this track, so the recovery
     * re-resolve walks to a different rung instead of re-serving
     * the same dead door.
     */
    fun onPlaybackRefused(url: String, responseCode: Int) {
        if (responseCode !in setOf(403, 404, 410)) return
        if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") != true) return
        val dead = resolveCache.entries.filter { it.value.resolved.url == url }
        dead.forEach { (key, cached) ->
            resolveCache.remove(key)
            if (cached.resolved.clientId.isNotBlank()) bench(cached.resolved.clientId)
        }
    }

    // ------------------------------------------------------------------
    // Format selection — the same quality rules as always
    // ------------------------------------------------------------------

    private data class Fmt(
        val url: String?,
        val cipher: String?,
        val mime: String,
        val bitrate: Int,
        val contentLength: Long,
    )

    private fun audioFormats(root: JSONObject): List<Fmt> {
        val sd = root.optJSONObject("streamingData") ?: return emptyList()
        val arr = sd.optJSONArray("adaptiveFormats") ?: return emptyList()
        val out = mutableListOf<Fmt>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio")) continue
            out += Fmt(
                url = f.optString("url").takeIf { it.isNotBlank() },
                cipher = f.optString("signatureCipher").takeIf { it.isNotBlank() },
                mime = mime,
                bitrate = f.optInt("bitrate"),
                contentLength = f.optString("contentLength").toLongOrNull() ?: -1,
            )
        }
        return out
    }

    private fun pickFormat(
        formats: List<Fmt>,
        quality: AudioQuality,
        lowData: Boolean,
    ): Fmt? {
        if (formats.isEmpty()) return null
        val sorted = formats.sortedWith(
            compareByDescending<Fmt> { it.bitrate }
                .thenByDescending { it.mime.contains("opus", ignoreCase = true) },
        )
        val effective = if (lowData) AudioQuality.LOW else quality
        return when (effective) {
            AudioQuality.HIGH -> sorted.first()
            AudioQuality.LOW -> sorted.last()
            AudioQuality.MEDIUM ->
                sorted.minByOrNull { kotlin.math.abs(it.bitrate - 96_000) } ?: sorted.last()
            AudioQuality.AUTO ->
                sorted.firstOrNull { it.bitrate <= 170_000 } ?: sorted.first()
        }
    }

    /** "audio/webm; codecs=opus" → "WebM Opus" for the info line. */
    private fun formatLabel(mime: String): String {
        val type = mime.substringBefore(";").trim()
        val codec = mime.substringAfter("codecs=", "").trim('"', ' ')
            .substringBefore(",").trim()
        val base = when (type) {
            "audio/webm" -> "WebM"
            "audio/mp4" -> "MP4"
            else -> type.substringAfter("/").replaceFirstChar { it.uppercase() }
        }
        val codecLabel = when {
            codec.contains("opus", ignoreCase = true) -> "Opus"
            codec.contains("mp4a", ignoreCase = true) -> "AAC"
            codec.isNotBlank() -> codec.replaceFirstChar { it.uppercase() }
            else -> ""
        }
        return listOf(base, codecLabel).filter { it.isNotBlank() }.joinToString(" ")
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    private data class Cached(
        val resolved: ResolvedAudio,
        val at: Long,
        val ttlMs: Long,
    )

    private val resolveCache = ConcurrentHashMap<String, Cached>()
    private const val MAX_TTL_MS = 30 * 60_000L

    fun invalidate(videoId: String) {
        resolveCache.keys
            .filter { it.startsWith("$videoId|") }
            .forEach { resolveCache.remove(it) }
    }

    /**
     * Resolve [videoId] through the client ladder. Every candidate
     * URL must pass the probe before it is returned. [excludeClients]
     * names rungs already tried for this track (the player's
     * recovery rotation); [refreshVisitor] forces a fresh visitor
     * id before the walk. Throws when every rung refused.
     */
    suspend fun resolve(
        videoId: String,
        quality: AudioQuality,
        lowData: Boolean = false,
        excludeClients: Set<String> = emptySet(),
        refreshVisitor: Boolean = false,
    ): ResolvedAudio = withContext(Dispatchers.IO) {
        val cacheKey = "$videoId|${quality.name}|$lowData"
        resolveCache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.at < it.ttlMs &&
                it.resolved.clientId !in excludeClients
            ) {
                return@withContext it.resolved
            }
        }
        if (refreshVisitor) {
            visitorData = null
            refreshVisitorData()
        }
        var vdRefreshed = refreshVisitor
        for (spec in ladder) {
            if (spec.id in excludeClients || isBenched(spec.id)) continue
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val resolved = runCatching {
                    resolveVia(spec, videoId, quality, lowData)
                }.getOrNull()
                if (resolved != null) {
                    cachePut(cacheKey, resolved.first, resolved.second)
                    return@withContext resolved.first
                }
                // One visitorData refresh + retry per resolve: a
                // stale/missing visitor id is the usual reason a
                // bot-walled client refuses.
                if (spec.useVisitorData && !vdRefreshed) {
                    vdRefreshed = true
                    visitorData = null
                    refreshVisitorData()
                    continue
                }
                break
            }
            bench(spec.id)
        }
        // Last rung: a ciphered client (MWEB), only usable when the
        // current player JS yields its decipher (see SigDecipher).
        if (CIPHER_ID !in excludeClients && !isBenched(CIPHER_ID)) {
            val resolved = runCatching {
                resolveViaCipher(videoId, quality, lowData)
            }.getOrNull()
            if (resolved != null) {
                cachePut(cacheKey, resolved.first, resolved.second)
                return@withContext resolved.first
            }
            bench(CIPHER_ID)
        }
        throw IllegalStateException("Forte engine could not resolve $videoId")
    }

    /** @return the resolved stream and its TTL in ms; null when
     * this rung refused or its URL failed the probe. */
    private fun resolveVia(
        spec: ClientSpec,
        videoId: String,
        quality: AudioQuality,
        lowData: Boolean,
    ): Pair<ResolvedAudio, Long>? {
        val root = playerCall(spec, videoId, SigDecipher.FALLBACK_STS)
        val status = root.optJSONObject("playabilityStatus")
            ?.optString("status")
        if (status != "OK") return null
        val formats = audioFormats(root).filter { it.url != null }
        val picked = pickFormat(formats, quality, lowData) ?: return null
        if (!probe(picked.url!!, picked.contentLength)) return null
        val expiresSec = root.optJSONObject("streamingData")
            ?.optLong("expiresInSeconds", 21_600L) ?: 21_600L
        val ttl = minOf(MAX_TTL_MS, (expiresSec - 120).coerceAtLeast(60) * 1000L)
        return ResolvedAudio(
            url = picked.url!!,
            format = formatLabel(picked.mime),
            bitrateKbps = picked.bitrate / 1000,
            sizeBytes = picked.contentLength,
            engine = "Forte",
            userAgent = spec.userAgent,
            clientId = spec.id,
        ) to ttl
    }

    private const val CIPHER_ID = "MWEB_CIPHER"

    /**
     * The cipher rung: MWEB answers with signatureCipher formats
     * (verified live). Usable only when SigDecipher extracted the
     * current player's transform — when it can't, this returns null
     * and the resolve fails honestly.
     */
    private suspend fun resolveViaCipher(
        videoId: String,
        quality: AudioQuality,
        lowData: Boolean,
    ): Pair<ResolvedAudio, Long>? {
        SigDecipher.ensureLoaded()
        val sts = SigDecipher.signatureTimestamp() ?: return null
        val spec = ClientSpec(
            id = CIPHER_ID,
            apiKey = WEB_KEY,
            userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36",
            useVisitorData = true,
            buildClient = { hl, gl -> clientJson("MWEB", "2.20250501.01.00", hl, gl) },
        )
        val root = playerCall(spec, videoId, sts)
        val status = root.optJSONObject("playabilityStatus")
            ?.optString("status")
        if (status != "OK") return null
        val formats = audioFormats(root).filter { it.cipher != null }
        val picked = pickFormat(formats, quality, lowData) ?: return null
        val parts = parseQuery(picked.cipher!!)
        val s = parts["s"] ?: return null
        val base = parts["url"] ?: return null
        val sp = parts["sp"] ?: "sig"
        val sig = SigDecipher.decipher(s) ?: return null
        val url = base + "&" + sp + "=" + URLEncoder.encode(sig, "UTF-8")
        if (!probe(url, picked.contentLength)) return null
        val expiresSec = root.optJSONObject("streamingData")
            ?.optLong("expiresInSeconds", 21_600L) ?: 21_600L
        val ttl = minOf(MAX_TTL_MS, (expiresSec - 120).coerceAtLeast(60) * 1000L)
        return ResolvedAudio(
            url = url,
            format = formatLabel(picked.mime),
            bitrateKbps = picked.bitrate / 1000,
            sizeBytes = picked.contentLength,
            engine = "Forte",
            userAgent = spec.userAgent,
            clientId = CIPHER_ID,
        ) to ttl
    }

    private fun cachePut(
        key: String,
        resolved: ResolvedAudio,
        ttlMs: Long,
    ) {
        resolveCache[key] = Cached(resolved, System.currentTimeMillis(), ttlMs)
    }

    private fun parseQuery(q: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        q.split("&").forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) {
                out[java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                    java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
        }
        return out
    }

    /** Warm the cache for a likely-next track (queue / radio). */
    suspend fun prefetch(
        videoId: String,
        quality: AudioQuality,
        lowData: Boolean = false,
    ) {
        runCatching { resolve(videoId, quality, lowData) }
    }

    // ------------------------------------------------------------------
    // Search (InnerTube, WEB_REMIX — the music app's own filter
    // tokens, validated live for all four result types)
    // ------------------------------------------------------------------

    private const val PARAMS_SONGS = "EgWKAQIIAWoKEAkQChAFEAMQBA=="
    private const val PARAMS_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA=="
    private const val PARAMS_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA=="
    private const val PARAMS_PLAYLISTS = "EgWKAQIoAWoKEAkQChAFEAMQBA=="

    /** A song search in flight: the query plus its continuation. */
    class SearchSession internal constructor(
        val query: String,
        @Volatile var continuation: String?,
    )

    suspend fun searchSongs(
        query: String,
    ): Pair<List<Track>, SearchSession> = withContext(Dispatchers.IO) {
        val root = searchCall(query, PARAMS_SONGS, null)
        val session = SearchSession(query, searchContinuation(root))
        parseSearchTracks(root) to session
    }

    suspend fun searchMore(
        session: SearchSession,
    ): Pair<List<Track>, SearchSession> = withContext(Dispatchers.IO) {
        val token = session.continuation
            ?: return@withContext emptyList<Track>() to session
        val root = searchCall(session.query, PARAMS_SONGS, token)
        session.continuation = searchContinuation(root)
        parseSearchTracks(root) to session
    }

    /** Album search: two-row items whose browse id (MPREb…) opens
     * the album page. */
    suspend fun searchAlbums(query: String): List<AlbumRef> =
        withContext(Dispatchers.IO) {
            parseTwoRow(searchCall(query, PARAMS_ALBUMS, null)).map { card ->
                AlbumRef(
                    url = card.browseId,
                    title = card.title,
                    artist = card.subtitle
                        ?.split(" • ", " · ")?.firstOrNull()?.trim(),
                    thumbUrl = card.thumbUrl,
                    detail = card.subtitle,
                )
            }
        }

    /** Artist search: two-row items whose browse id is the channel. */
    suspend fun searchArtists(query: String): List<ArtistRef> =
        withContext(Dispatchers.IO) {
            parseTwoRow(searchCall(query, PARAMS_ARTISTS, null)).map { card ->
                ArtistRef(
                    url = card.browseId,
                    name = card.title,
                    thumbUrl = card.thumbUrl,
                    detail = card.subtitle,
                )
            }
        }

    /** Playlist search: two-row items with VL browse ids. */
    suspend fun searchPlaylists(query: String): List<AlbumRef> =
        withContext(Dispatchers.IO) {
            parseTwoRow(searchCall(query, PARAMS_PLAYLISTS, null)).map { card ->
                AlbumRef(
                    url = card.browseId,
                    title = card.title,
                    artist = card.subtitle
                        ?.split(" • ", " · ")?.firstOrNull()?.trim(),
                    thumbUrl = card.thumbUrl,
                    detail = card.subtitle,
                )
            }
        }

    private data class TwoRow(
        val browseId: String,
        val title: String,
        val subtitle: String?,
        val thumbUrl: String?,
    )

    private fun parseTwoRow(root: JSONObject): List<TwoRow> {
        val out = mutableListOf<TwoRow>()
        walkObjects(root) { obj ->
            val r = obj.optJSONObject("musicTwoRowItemRenderer")
                ?: return@walkObjects
            val browseId = r.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?.takeIf { it.isNotBlank() } ?: return@walkObjects
            val title = r.optJSONObject("title")?.let { runsText(it) }
                ?.takeIf { it.isNotBlank() } ?: return@walkObjects
            val subtitle = r.optJSONObject("subtitle")?.let { runsText(it) }
            val thumb = r.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?.lastUrl()
            out += TwoRow(browseId, title, subtitle, bigArtwork(thumb))
        }
        return out.distinctBy { it.browseId }
    }

    private fun searchCall(
        query: String,
        params: String,
        continuation: String?,
    ): JSONObject {
        val region = RegionState.current
        val client = clientJson("WEB_REMIX", REMIX_VERSION, region.hl, region.gl)
        if (visitorData == null) refreshVisitorData()
        visitorData?.let { client.put("visitorData", it) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
        if (continuation != null) {
            body.put("continuation", continuation)
        } else {
            body.put("query", query)
            body.put("params", params)
        }
        val root = postJson(
            "https://music.youtube.com/youtubei/v1/search" +
                "?key=$REMIX_KEY&prettyPrint=false",
            body,
            BROWSER_UA,
        )
        captureVisitorData(root)
        return root
    }

    private fun parseSearchTracks(root: JSONObject): List<Track> {
        val out = mutableListOf<Track>()
        walkObjects(root) { obj ->
            obj.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
                YtAccount.parseTrack(r)?.let { out += it }
            }
        }
        return out.distinctBy { it.id }
    }

    private fun searchContinuation(root: JSONObject): String? {
        var token: String? = null
        walkObjects(root) { obj ->
            if (token == null) {
                obj.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")?.takeIf { it.isNotBlank() }
                    ?.let { token = it }
            }
        }
        return token
    }

    // ------------------------------------------------------------------
    // Related (watch-next relatives of one song, guest)
    // ------------------------------------------------------------------

    /**
     * Songs YouTube pairs with [videoId] — the watch-next panel
     * behind the `next` endpoint. Used as radio's backup lane.
     * Guest WEB_REMIX call; the panel parser is the account layer's
     * radio parser (same renderer shape).
     */
    suspend fun related(videoId: String): List<Track> = withContext(Dispatchers.IO) {
        runCatching {
            val region = RegionState.current
            val client = clientJson("WEB_REMIX", REMIX_VERSION, region.hl, region.gl)
            if (visitorData == null) refreshVisitorData()
            visitorData?.let { client.put("visitorData", it) }
            val body = JSONObject()
                .put("context", JSONObject().put("client", client))
                .put("videoId", videoId)
            val root = postJson(
                "https://music.youtube.com/youtubei/v1/next" +
                    "?key=$REMIX_KEY&prettyPrint=false",
                body,
                BROWSER_UA,
            )
            captureVisitorData(root)
            val panel = findObject(root, "playlistPanelRenderer") ?: return@runCatching emptyList()
            YtAccount.parseRadioPanel(panel).tracks
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // Response walking / parsing helpers
    // ------------------------------------------------------------------

    private fun runsText(obj: JSONObject): String? {
        val runs = obj.optJSONArray("runs")
            ?: return obj.optString("simpleText").ifBlank { null }
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().ifBlank { null }
    }

    private fun JSONArray.lastUrl(): String? {
        if (length() == 0) return null
        return optJSONObject(length() - 1)?.optString("url")?.ifBlank { null }
    }

    private fun findObject(root: Any, key: String): JSONObject? {
        var found: JSONObject? = null
        walkObjects(root) { obj ->
            if (found == null) found = obj.optJSONObject(key)
        }
        return found
    }

    private fun walkObjects(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) walkObjects(node.opt(keys.next()), visit)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) walkObjects(node.opt(i), visit)
            }
        }
    }
}
