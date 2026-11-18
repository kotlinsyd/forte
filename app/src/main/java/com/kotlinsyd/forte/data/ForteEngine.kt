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
 *  - Direct-URL clients with SABR off, verdict/bench discipline
 *    (reworked v1.0.24-test, extended v1.0.27-test): a client-wide
 *    refusal (the LOGIN_REQUIRED / bot-wall class) benches a
 *    client, for ten minutes. The old build benched a client for
 *    ten minutes on ANY failure — one unplayable song or one
 *    slow-network probe timeout benched healthy routes, and then
 *    every song failed in bursts. A per-track refusal never
 *    benches; a network failure benches for at most a minute.
 *    Rung-health memory adds two evidence-based benches at the
 *    same ten minutes: a rung whose URL dies mid-play, and a rung
 *    that proves probe-dead during a recovery walk — both are the
 *    boundary-dead class made visible, so later songs in the
 *    session skip a rung that has shown itself unhealthy instead
 *    of re-paying its cost song after song.
 *  - Nothing is handed to the player until bytes have been
 *    fetched from its URL — but a FRESH resolve (v1.0.27-test,
 *    on the probe harness's measurements) gates on the first
 *    window only: headers plus 8 KiB at offset 0, fetched with
 *    the exact request the media fetch will make. The full probe
 *    — the same window PAST the 1 MiB boundary some clients'
 *    URLs stop honouring, 16 KiB of body — belongs to RECOVERY
 *    resolves and to the background check that runs behind a
 *    fresh-resolved song. v1.0.25-test demanded the boundary
 *    proof up front and paid ~20 s of its 25 s budget re-proving
 *    rungs whose URLs were boundary-dead (the fresh probe never
 *    benched them, so every song re-paid it), while v1.0.19's
 *    harsh bench had quietly acted as rung-health memory and
 *    skipped the dead rungs from the second song on.
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

    /** Bench discipline (v1.0.24-test): the long bench is ONLY
     * for a client-wide refusal — the client itself is walled
     * (LOGIN_REQUIRED / "confirm you're not a bot"), so asking it
     * again for every song in the queue is pointless. A per-track
     * refusal (UNPLAYABLE, age gate, region) says nothing about
     * the client's health and NEVER benches; a failure that died
     * on the network earns at most the short bench. Rung-health
     * memory (v1.0.27-test) adds two further long benches, both
     * on direct evidence about the RUNG rather than the track:
     * a rung whose URL dies mid-play ([onPlaybackRefused]) and
     * a rung that returns probe-dead during a recovery walk.
     * Second-chance rule (v1.0.31-test): a bench DEFERS a rung
     * for later songs — it no longer vetoes it. One song's
     * recovery cascade could bench the whole ladder, after
     * which every later song's resolve skipped every rung and
     * threw without a single attempt (the same "couldn't play"
     * down the whole queue). A resolve now walks benched rungs
     * as a second chance when nothing un-benched served (see
     * [walkPlan]); only the calling track's own exclusions are
     * absolute. */
    private val benchedUntil = ConcurrentHashMap<String, Long>()
    private const val BENCH_MS = 10 * 60_000L
    private const val BENCH_SHORT_MS = 60_000L

    private fun isBenched(id: String): Boolean =
        (benchedUntil[id] ?: 0L) > System.currentTimeMillis()

    private fun bench(id: String, ms: Long = BENCH_MS) {
        benchedUntil[id] = System.currentTimeMillis() + ms
    }

    /**
     * The order one resolve walks the ladder (v1.0.31-test):
     * rungs neither excluded nor benched first, in ladder
     * order; then the benched-but-not-excluded rungs, also in
     * ladder order, as a second chance. Excluded rungs — the
     * calling track's own tried set — never appear in either
     * phase. Pure and internal so the per-track isolation
     * rule is unit-testable: a bench one song earned may
     * reorder another song's walk, but it can never empty it.
     */
    internal fun walkPlan(
        rungIds: List<String>,
        excluded: Set<String>,
        benched: Set<String>,
    ): List<String> =
        rungIds.filter { it !in excluded && it !in benched } +
            rungIds.filter { it !in excluded && it in benched }

    /** How one ladder rung's attempt ended — resolve() benches,
     * retries or moves on by this class, never by "it failed". */
    private sealed interface RungResult {
        data class Ok(val value: Pair<ResolvedAudio, Long>) : RungResult

        /** The client itself refused (login-required / bot wall):
         * the long bench applies. */
        data object ClientRefused : RungResult

        /** This track refused (unplayable / age / region): the
         * client is healthy; never benches. [neverReason] is the
         * plain user-facing reason when the refusal can never
         * play as a guest, else null. */
        data class TrackRefused(val neverReason: String?) : RungResult

        /** The player call died on the network: short bench. */
        data object NetworkTrouble : RungResult

        /** The response was fine but every candidate URL failed
         * the probe (dead URL / unresponsive body). On a fresh
         * walk this is per-track and never benches; during a
         * RECOVERY walk the full probe makes it rung evidence —
         * the rung is benched (rung-health memory, v1.0.27-test). */
        data object UrlDead : RungResult
    }

    /** Thrown when a track can NEVER play on the guest engine;
     * [userReason] is the plain reason ("needs sign-in", …) the
     * player surfaces instead of a generic error. */
    class RefusalException(val userReason: String) :
        Exception("Track refused: $userReason")

    /** The plain reason for a refusal that can never play as a
     * guest, read from the playability reason text; null when the
     * refusal is an ordinary per-track "no". */
    private fun neverPlayReason(reason: String): String? = when {
        reason.contains("not a bot", ignoreCase = true) -> null
        reason.contains("sign in", ignoreCase = true) -> "needs sign-in"
        reason.contains("country", ignoreCase = true) ||
            reason.contains("region", ignoreCase = true) ->
            "isn't available in your region"
        else -> null
    }

    /** True when the refusal is the client-wide bot wall: the
     * loud LOGIN_REQUIRED, or the quiet "confirm you're not a
     * bot" served as a per-track-looking UNPLAYABLE. */
    private fun isClientWall(status: String?, reason: String): Boolean =
        status == "LOGIN_REQUIRED" ||
            reason.contains("not a bot", ignoreCase = true)

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

    /** First-pass read: headers plus this much body at offset 0
     * — all a fresh resolve gates on (see [probeFresh]); the
     * full probe's first half for a recovery (see [probe]). */
    private const val PROBE_FIRST_BYTES = 8L * 1024

    /**
     * Pass 1, standalone (v1.0.27-test): headers plus the first
     * bytes at offset 0, on the probe's own 6 s leash. This is
     * ALL a fresh resolve gates on. The measurements behind the
     * change: v1.0.25-test's fresh walk spent ~20 s of its 25 s
     * budget re-probing the boundary window of rungs whose URLs
     * were boundary-dead, and probe-dead outcomes never benched,
     * so every song re-paid the same ~15 s; on a jittery link the
     * walk could cross the budget and the song errored before it
     * ever played. A pass-1-only walk resolves in ~4.4 s. The
     * boundary risk pass 1 cannot see is carried instead by
     * rung-health memory (a rung whose URL dies mid-play is
     * benched for the session) and by the player's background
     * boundary check, which pre-resolves a proven fallback
     * while the song plays (see PlayerController).
     */
    private fun probeFresh(url: String): Boolean =
        probeWindow(url, 0L, PROBE_FIRST_BYTES)

    /**
     * The FULL probe — what a RECOVERY resolve gates on
     * (v1.0.25-test, kept by v1.0.27-test for recovery only).
     * Pass 1 first; then, for any file longer than the
     * boundary, the past-the-boundary window MUST also serve
     * before the URL is trusted. Some clients' URLs serve the
     * first megabyte and refuse everything after it (the CDN's
     * post-1 MiB refusal); v1.0.19's probe knew this and
     * demanded 16 KiB PAST the boundary before trusting any
     * URL, and a recovery — the listener is already waiting on
     * a song that died once — is where that proof belongs.
     * Short files (and unknown lengths, where no boundary can
     * be computed) stand on pass 1, as before.
     */
    private fun probe(url: String, contentLength: Long): Boolean {
        if (!probeWindow(url, 0L, PROBE_FIRST_BYTES)) return false
        val clen = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
            ?: contentLength.takeIf { it > 0 }
        if (clen != null && clen > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) {
            return probeWindow(url, AUTH_BOUNDARY_BYTES, PROBE_READ_BYTES)
        }
        return true
    }

    /** The probe a resolve gates on (v1.0.27-test): pass 1 only
     * for a fresh resolve, the full boundary-proving probe for
     * a recovery — see [probeFresh] and [probe]. */
    private fun serves(url: String, contentLength: Long, recovery: Boolean): Boolean =
        if (recovery) probe(url, contentLength) else probeFresh(url)

    /**
     * The background boundary check (v1.0.27-test): does [url]'s
     * past-the-boundary window serve? True outright for short
     * or unknown-length files (no boundary can be crossed). The
     * player runs this once behind a song that started on a
     * fresh (pass-1-only) resolve; a refusal means the URL will
     * die about a minute in, so a proven fallback is resolved
     * before it can.
     */
    suspend fun boundaryServes(url: String, contentLength: Long): Boolean =
        withContext(Dispatchers.IO) {
            val clen = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
                ?: contentLength.takeIf { it > 0 }
            if (clen == null || clen <= AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) {
                true
            } else {
                probeWindow(url, AUTH_BOUNDARY_BYTES, PROBE_READ_BYTES)
            }
        }

    /**
     * Fetch one real window from [url] exactly as the media fetch
     * will: headers dressed from the URL's own client stamp, a
     * bounded range of the size that client's fetches use,
     * starting at [start]. True only when the answer is a success
     * with an audio content type AND [readBytes] of body actually
     * arrive — headers alone can precede a body that never comes.
     */
    private fun probeWindow(url: String, start: Long, readBytes: Long): Boolean {
        val clen = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
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
                        resp.body!!.source().request(readBytes)
                    }.getOrDefault(false)
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * A stream the probe cleared has been refused while actually
     * playing (the datasource reports it). Forget the URL, so the
     * recovery re-resolve mints a fresh one instead of re-serving
     * the same dead door — and bench the minting rung for ten
     * minutes (v1.0.27-test, rung-health memory): a URL that dies
     * mid-play is exactly the boundary-dead class the fresh probe
     * no longer tests for, so the rung that minted it is treated
     * as unhealthy for the session and later songs skip it — the
     * skipping v1.0.19's harsh any-failure bench used to arrange
     * by accident. (v1.0.24-test's no-bench rule stands for
     * per-track REFUSALS at resolve time; a mid-play death is
     * rung evidence, not a track refusal.) The rung is read from
     * the cached resolve; if that entry is already gone, from
     * the URL's own client stamp.
     */
    fun onPlaybackRefused(url: String, responseCode: Int) {
        if (responseCode !in setOf(403, 404, 410)) return
        if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") != true) return
        val dead = resolveCache.entries.filter { it.value.resolved.url == url }
        val minters = dead.map { it.value.resolved.clientId }.toMutableSet()
        if (minters.isEmpty()) {
            rungIdForStamp(url)?.let { minters += it }
        }
        dead.forEach { (key, _) -> resolveCache.remove(key) }
        minters.forEach { bench(it) }
    }

    /** The ladder rung a stream URL was minted by, read from its
     * c= stamp — the fallback for [onPlaybackRefused] when the
     * resolve that minted the URL has already left the cache.
     * The cipher rung's stamp is its client name (MWEB), not its
     * rung id. Null when the stamp names no rung of ours. */
    private fun rungIdForStamp(url: String): String? {
        val stamp = url.toHttpUrlOrNull()?.queryParameter("c")
            ?.uppercase() ?: return null
        if (stamp == "MWEB") return CIPHER_ID
        return stamp.takeIf { id -> ladder.any { it.id == id } }
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

    /**
     * The rung's formats in preference order for the quality
     * rule — the pick first, then the fallbacks (v1.0.24-test).
     * The old code picked exactly ONE format per response: when
     * that URL failed the probe, the whole rung was abandoned
     * even though the same response carried other working
     * formats. Callers now walk this list until one passes.
     */
    private fun formatCandidates(
        formats: List<Fmt>,
        quality: AudioQuality,
        lowData: Boolean,
    ): List<Fmt> {
        if (formats.isEmpty()) return emptyList()
        val sorted = formats.sortedWith(
            compareByDescending<Fmt> { it.bitrate }
                .thenByDescending { it.mime.contains("opus", ignoreCase = true) },
        )
        val effective = if (lowData) AudioQuality.LOW else quality
        return when (effective) {
            AudioQuality.HIGH -> sorted
            AudioQuality.LOW -> sorted.reversed()
            AudioQuality.MEDIUM ->
                sorted.sortedBy { kotlin.math.abs(it.bitrate - 96_000) }
            AudioQuality.AUTO ->
                sorted.filter { it.bitrate <= 170_000 } +
                    sorted.filter { it.bitrate > 170_000 }
        }
    }

    private fun pickFormat(
        formats: List<Fmt>,
        quality: AudioQuality,
        lowData: Boolean,
    ): Fmt? = formatCandidates(formats, quality, lowData).firstOrNull()

    /** How many formats of one response get probed before the
     * rung is given up — bounds a rung's worst-case time. */
    private const val MAX_FORMAT_TRIES = 3

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
     * URL must pass the probe before it is returned — pass 1 only
     * on a fresh resolve, the full boundary-proving probe on a
     * recovery (v1.0.27-test). [excludeClients]
     * names rungs already tried for this track (the player's
     * recovery rotation); [refreshVisitor] forces a fresh visitor
     * id before the walk. [recovery] marks a recovery re-resolve:
     * those are EXEMPT from the fresh-resolve budget below and
     * get the much larger recovery budget instead — on a slow
     * link v1.0.19 ground through recoveries that the
     * v1.0.24-test 25 s cap now times out (v1.0.25-test).
     * Throws when every rung refused.
     */
    suspend fun resolve(
        videoId: String,
        quality: AudioQuality,
        lowData: Boolean = false,
        excludeClients: Set<String> = emptySet(),
        refreshVisitor: Boolean = false,
        recovery: Boolean = false,
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
        // One FRESH resolve never holds a song hostage
        // (v1.0.24-test): the ladder walk is capped, then the
        // caller fails over to the next track instead of
        // spinning silently. A RECOVERY re-resolve is exempt
        // from that cap (v1.0.25-test): it runs on the
        // recovery budget, so a slow link gets the same
        // grinding patience v1.0.19 had.
        val deadlineMs = System.currentTimeMillis() +
            if (recovery) RECOVERY_BUDGET_MS else RESOLVE_BUDGET_MS
        var neverReason: String? = null

        // One rung, with the bench discipline applied by outcome
        // class. Returns the stream on success, null to move on.
        suspend fun walk(
            id: String,
            useVisitorData: Boolean,
            attempt: suspend (Long) -> RungResult,
        ): ResolvedAudio? {
            var tried = 0
            while (tried < 2) {
                tried++
                if (System.currentTimeMillis() > deadlineMs) return null
                val result = runCatching { attempt(deadlineMs) }
                    .getOrElse { RungResult.NetworkTrouble }
                when (result) {
                    is RungResult.Ok -> {
                        // A rung that just served is healthy —
                        // lift any bench it carried (only a
                        // second-chance walk can meet one).
                        benchedUntil.remove(id)
                        cachePut(cacheKey, result.value.first, result.value.second)
                        return result.value.first
                    }
                    is RungResult.TrackRefused -> {
                        if (neverReason == null) {
                            neverReason = result.neverReason
                        }
                        return null
                    }
                    RungResult.UrlDead -> {
                        // Rung-health memory (v1.0.27-test): a
                        // rung that proves probe-dead during a
                        // RECOVERY walk — where the full probe
                        // makes the outcome rung evidence — is
                        // benched for the session, so later songs
                        // skip it. A fresh walk never benches on
                        // UrlDead: its pass-1-only probe says
                        // nothing about the rung's boundary health.
                        if (recovery) bench(id)
                        return null
                    }
                    RungResult.NetworkTrouble -> {
                        bench(id, BENCH_SHORT_MS)
                        return null
                    }
                    RungResult.ClientRefused -> {
                        // One visitorData refresh + retry per
                        // resolve: a stale/missing visitor id is
                        // the usual reason a bot-walled client
                        // refuses. Only if it still refuses is the
                        // client itself walled — the long bench.
                        if (useVisitorData && !vdRefreshed) {
                            vdRefreshed = true
                            visitorData = null
                            refreshVisitorData()
                            continue
                        }
                        bench(id)
                        return null
                    }
                }
            }
            return null
        }

        // The walk plan (v1.0.31-test): un-benched rungs first,
        // then — only when they produced nothing — the benched
        // ones as a second chance. Benches steer the ladder;
        // they may never EMPTY it. One song's recovery cascade
        // can bench every rung (each verdict is rung evidence
        // earned against that song's URLs), and the old loop
        // then skipped every rung of every later song's fresh
        // resolve without a single network attempt: each later
        // song threw instantly and surfaced the same "couldn't
        // play" until the benches lapsed. Exclusions are not
        // benches: they are THIS track's own tried rungs and
        // stay out in both phases (see walkPlan).
        val specsById = ladder.associateBy { it.id }
        val plan = walkPlan(
            ladder.map { it.id },
            excludeClients,
            ladder.filter { isBenched(it.id) }.map { it.id }.toSet(),
        )
        for (id in plan) {
            val spec = specsById.getValue(id)
            walk(spec.id, spec.useVisitorData) { dl ->
                resolveVia(spec, videoId, quality, lowData, dl, recovery)
            }?.let { return@withContext it }
            if (System.currentTimeMillis() > deadlineMs) break
        }
        // Last rung: a ciphered client (MWEB), only usable when the
        // current player JS yields its decipher (see SigDecipher).
        // This point is reached only when no ladder rung served,
        // so a bench on the cipher rung cannot veto it either
        // (v1.0.31-test) — only this track's own exclusion can.
        if (CIPHER_ID !in excludeClients &&
            System.currentTimeMillis() <= deadlineMs
        ) {
            walk(CIPHER_ID, useVisitorData = true) { dl ->
                resolveViaCipher(videoId, quality, lowData, dl, recovery)
            }?.let { return@withContext it }
        }
        // A refusal that can never play as a guest keeps its plain
        // reason, so the player can say it instead of a generic
        // "couldn't play".
        neverReason?.let { throw RefusalException(it) }
        throw IllegalStateException("Forte engine could not resolve $videoId")
    }

    /** The whole ladder walk for one FRESH resolve is capped
     * at this — then the caller fails over to the next track
     * (v1.0.24-test). Recovery re-resolves are exempt and use
     * [RECOVERY_BUDGET_MS] instead (v1.0.25-test). */
    private const val RESOLVE_BUDGET_MS = 25_000L

    /** A recovery re-resolve's budget: the rotation has
     * already spent the listener's patience once; what it
     * needs now is v1.0.19's grinding room on a slow link,
     * not the fresh-resolve cap. */
    private const val RECOVERY_BUDGET_MS = 120_000L

    /**
     * One rung's attempt, classified for the bench discipline
     * (v1.0.24-test): a refused status is read for WHAT refused
     * (the client wall vs this track), and the probe walks the
     * response's format candidates instead of gambling on one.
     * Network deaths inside the player call propagate as
     * exceptions — resolve() classes them NetworkTrouble.
     * [recovery] picks the probe (v1.0.27-test): pass 1 only on
     * a fresh resolve, the full boundary-proving probe on a
     * recovery.
     */
    private fun resolveVia(
        spec: ClientSpec,
        videoId: String,
        quality: AudioQuality,
        lowData: Boolean,
        deadlineMs: Long,
        recovery: Boolean,
    ): RungResult {
        val root = playerCall(spec, videoId, SigDecipher.FALLBACK_STS)
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status")
        if (status != "OK") {
            val reason = playability?.optString("reason").orEmpty()
            return if (isClientWall(status, reason)) {
                RungResult.ClientRefused
            } else {
                RungResult.TrackRefused(neverPlayReason(reason))
            }
        }
        val formats = audioFormats(root).filter { it.url != null }
        val expiresSec = root.optJSONObject("streamingData")
            ?.optLong("expiresInSeconds", 21_600L) ?: 21_600L
        val ttl = minOf(MAX_TTL_MS, (expiresSec - 120).coerceAtLeast(60) * 1000L)
        for (candidate in formatCandidates(formats, quality, lowData)
            .take(MAX_FORMAT_TRIES)) {
            if (System.currentTimeMillis() > deadlineMs) break
            if (!serves(candidate.url!!, candidate.contentLength, recovery)) continue
            return RungResult.Ok(
                ResolvedAudio(
                    url = candidate.url!!,
                    format = formatLabel(candidate.mime),
                    bitrateKbps = candidate.bitrate / 1000,
                    sizeBytes = candidate.contentLength,
                    engine = "Soniko",
                    userAgent = spec.userAgent,
                    clientId = spec.id,
                ) to ttl,
            )
        }
        return RungResult.UrlDead
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
        deadlineMs: Long,
        recovery: Boolean,
    ): RungResult {
        SigDecipher.ensureLoaded()
        val sts = SigDecipher.signatureTimestamp()
            ?: return RungResult.NetworkTrouble
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
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status")
        if (status != "OK") {
            val reason = playability?.optString("reason").orEmpty()
            return if (isClientWall(status, reason)) {
                RungResult.ClientRefused
            } else {
                RungResult.TrackRefused(neverPlayReason(reason))
            }
        }
        val formats = audioFormats(root).filter { it.cipher != null }
        val expiresSec = root.optJSONObject("streamingData")
            ?.optLong("expiresInSeconds", 21_600L) ?: 21_600L
        val ttl = minOf(MAX_TTL_MS, (expiresSec - 120).coerceAtLeast(60) * 1000L)
        // Same candidate walk as the direct rungs (v1.0.24-test):
        // decipher + probe each candidate until one serves.
        for (candidate in formatCandidates(formats, quality, lowData)
            .take(MAX_FORMAT_TRIES)) {
            if (System.currentTimeMillis() > deadlineMs) break
            val parts = parseQuery(candidate.cipher!!)
            val s = parts["s"] ?: continue
            val base = parts["url"] ?: continue
            val sp = parts["sp"] ?: "sig"
            val sig = SigDecipher.decipher(s) ?: continue
            val url = base + "&" + sp + "=" + URLEncoder.encode(sig, "UTF-8")
            if (!serves(url, candidate.contentLength, recovery)) continue
            return RungResult.Ok(
                ResolvedAudio(
                    url = url,
                    format = formatLabel(candidate.mime),
                    bitrateKbps = candidate.bitrate / 1000,
                    sizeBytes = candidate.contentLength,
                    engine = "Soniko",
                    userAgent = spec.userAgent,
                    clientId = CIPHER_ID,
                ) to ttl,
            )
        }
        return RungResult.UrlDead
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
