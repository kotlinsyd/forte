package com.kotlinsyd.forte.data

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Optional YouTube account layer (guest-first: nothing here is needed
 * for search or playback, which run on Forte's own engine,
 * anonymously).
 *
 * Sign-in happens on Google's own page in an in-app WebView (see
 * LoginScreen). Forte never sees or stores a password — it keeps only
 * the session cookies the WebView holds, and turns them into
 * authenticated InnerTube (WEB_REMIX) requests the same way the
 * YouTube Music web app does: Cookie header + SAPISIDHASH
 * Authorization. Cookies live in the WebView's own store, so the
 * session persists across restarts on its own.
 */
object YtAccount {

    const val ORIGIN = "https://music.youtube.com"
    /** Desktop UA for these InnerTube API requests only. Never use it
     * for the sign-in WebView — Google blocks sign-in when the UA
     * doesn't match a real mobile browser. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    // The live WEB_REMIX client version, read from music.youtube.com's
    // own page config (verified 2026-11-03: 1.20261031.09.00). The old
    // build pinned a version from October 2024 — reads kept passing,
    // but writes are the first thing YouTube refuses from a client
    // that old, which is exactly what the owner's failed like hit.
    private const val CLIENT_VERSION = "1.20261031.09.00"
    /** WEB_REMIX's numeric client id, sent as X-YouTube-Client-Name —
     * the header the InnerTube lineage puts on every account call. */
    private const val CLIENT_NAME_ID = "67"
    /** The plain WEB origin (www.youtube.com) — seat of the
     * account-switcher GET, the call youtube.com's own avatar menu
     * makes. */
    private const val WEB_ORIGIN = "https://www.youtube.com"

    /** One InnerTube client identity + the origin it is asked on. */
    private data class ClientSpec(
        val origin: String,
        val apiKey: String,
        val clientName: String,
        val clientVersion: String,
        val clientNameId: String,
    )

    private val REMIX_CLIENT =
        ClientSpec(ORIGIN, API_KEY, "WEB_REMIX", CLIENT_VERSION, CLIENT_NAME_ID)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        // Bounded calls (owner's loading rule): a stalled request on
        // slow data must surface and fall back, not hang a screen.
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /**
     * The session's visitorData, captured from every response and
     * sent back in the context like the web app does. Continuation
     * requests are bound to it: without it the server answers a
     * continuation with an empty page (verified live).
     */
    @Volatile
    private var visitorData: String? = null

    /** True once [visitorData] is the session's own (read from the
     * signed-in shell): a session-bound id outranks the anonymous
     * ones browse responses carry, and those must not replace it. */
    @Volatile
    private var visitorSessionBound: Boolean = false

    /** What the account fetch could prove. Every field is
     * independent: a response that carries the photo but not the
     * name still yields the photo (see [accountInfo]). */
    data class AccountInfo(val name: String?, val handle: String?, val avatarUrl: String?)

    data class AccountPlaylist(
        val browseId: String,
        val title: String,
        val subtitle: String?,
        val thumbUrl: String?,
    ) {
        /** Plain playlist id (browse ids carry a "VL" prefix). */
        val playlistId: String get() = browseId.removePrefix("VL")
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    private fun cookiesFor(url: String): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie(url) ?: return emptyMap()
        return raw.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
    }

    /** The signing cookie, in the order the InnerTube lineage
     * reads it: plain SAPISID first, then the __Secure- forms. A
     * jar carrying only the 1P form is still a session — missing
     * it here would send requests with no Authorization at all,
     * which Google reads as a request from nobody. The entry form
     * also reports WHICH variant matched, by name only — the
     * identity truth line names it, never the value. */
    internal fun sapisidEntry(
        cookies: Map<String, String>,
    ): Pair<String, String>? =
        cookies["SAPISID"]?.let { "SAPISID" to it }
            ?: cookies["__Secure-3PAPISID"]?.let { "3PAPISID" to it }
            ?: cookies["__Secure-1PAPISID"]?.let { "1PAPISID" to it }

    private fun sapisidOf(cookies: Map<String, String>): String? =
        sapisidEntry(cookies)?.second

    /** The session is real when Google has issued a SAPISID cookie. */
    fun isSignedIn(): Boolean {
        val cookies = cookiesFor(ORIGIN) + cookiesFor("https://www.youtube.com")
        return sapisidOf(cookies) != null
    }

    /**
     * The cookie jar for ONE seat, the way a browser builds it:
     * exactly the cookies that would be sent to [origin], read
     * live from the WebView store at call time.
     *
     * v1.0.15: the old sessionCookies() blended three origins'
     * jars into one map — www.youtube.com, then music.youtube.com,
     * then accounts.google.com LAST, so the accounts jar's
     * same-named cookies silently overrode the youtube seat's own.
     * A browser never sends .google.com cookies to
     * music.youtube.com, and the lineage's capture is the cookie
     * string for music.youtube.com alone. Blending handed the
     * music seat a SAPISID it does not own: the SAPISIDHASH stayed
     * consistent with the Cookie header, but both named a cookie
     * of another seat — and the account doors answered 401 while
     * browse/history (which ride the session more loosely) kept
     * working. If the seat's own jar carries no signing cookie at
     * all, the www jar is the fallback (it at least belongs to the
     * same site family); the accounts jar is never blended in.
     */
    private fun seatCookies(origin: String): Map<String, String> {
        val own = cookiesFor(origin)
        if (sapisidEntry(own) != null || origin != ORIGIN) return own
        val www = cookiesFor(WEB_ORIGIN)
        return if (sapisidEntry(www) != null) www else own
    }

    /** Which jar the last identity fetch used, by name only —
     * part of the truth line. "music" = the seat's own jar,
     * "www-fallback" = the seat had no signing cookie and the
     * www jar stood in, "none" = no signing cookie anywhere. */
    @Volatile
    private var lastJarName: String = "none"

    private fun sessionCookies(): Map<String, String> = seatCookies(ORIGIN)

    private fun sapisidHash(sapisid: String, origin: String = ORIGIN): String {
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $origin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$ts" + "_" + digest
    }

    fun signOut() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { }
        cm.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        // The scope belonged to the session that just left; a stale
        // one would scope the next sign-in's calls to a stranger.
        sessionScope = null
        scopeStamp = null
        scopeSettled = false
        visitorData = null
        visitorSessionBound = false
        lastIdentityCheck = null
    }

    /**
     * The session's auth headers (Cookie + SAPISIDHASH), for reuse
     * by Forte's own stream engine so its player calls ride the same
     * signed-in session as the account calls. Empty when signed out
     * or when there are no cookies to send.
     */
    fun authHeaders(): Map<String, String> {
        val cookies = sessionCookies()
        if (cookies.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        out["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val sapisid = sapisidOf(cookies)
        if (sapisid != null) {
            out["Authorization"] = "SAPISIDHASH ${sapisidHash(sapisid)}"
        }
        return out
    }

    // ------------------------------------------------------------------
    // Session scope — which account the cookie acts as (v1.0.13)
    // ------------------------------------------------------------------

    /**
     * Which identity the session cookie acts as, read out of the
     * signed-in music.youtube.com page config — the step four
     * builds skipped, and the reason the account menu kept
     * answering about nobody.
     *
     * A cookie is not an account: one jar can carry several Google
     * logins plus the channels hanging off them, and nothing in
     * the cookie says which one YouTube Music is acting as. The
     * web client resolves that from its own page config and then
     * says so on every request — the account index as
     * X-Goog-AuthUser, the active identity as
     * context.user.onBehalfOfUser. Asking the account menu as a
     * hardcoded "user 0" with no account context is asking about
     * the jar's first entry, whoever that is.
     */
    internal data class SessionScope(
        /** SESSION_INDEX — which login in the jar, as a string. */
        val authUser: String,
        /** The active identity for context.user.onBehalfOfUser;
         * null when the shell didn't prove one. Never guessed: a
         * value Google cannot tie to the session is answered 401
         * on every request. */
        val dataSyncId: String?,
        /** DELEGATED_SESSION_ID — set only while a brand channel
         * is the selected identity; sent as X-Goog-PageId. */
        val pageId: String?,
        /** The shell's live INNERTUBE_CLIENT_VERSION. */
        val clientVersion: String?,
    )

    @Volatile
    private var sessionScope: SessionScope? = null

    /** The SAPISID the current scope (or failed attempt) belongs
     * to; a different one invalidates the scope. Held in memory
     * only, never persisted. */
    @Volatile
    private var scopeStamp: String? = null

    @Volatile
    private var scopeSettled: Boolean = false

    /** Earliest time a failed shell read may be retried. */
    @Volatile
    private var scopeRetryAfterMs: Long = 0L

    private val SHELL_LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
    private val SHELL_DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
    private val SHELL_PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
    private val SHELL_SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)"?""")
    private val SHELL_VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val SHELL_CLIENT_VERSION =
        Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")

    /**
     * The onBehalfOfUser value in a DATASYNC_ID. The id is
     * commonly `account||delegated` and the delegated half is the
     * active identity; a plain account leaves that half empty and
     * falls back to the first.
     */
    internal fun normalizeDataSyncId(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        if (!value.contains("||")) return value
        return value.substringAfter("||").takeIf { it.isNotBlank() }
            ?: value.substringBefore("||").takeIf { it.isNotBlank() }
    }

    /** The scope a music.youtube.com page's config describes. A
     * page that doesn't report itself signed in contributes no
     * identity values — only its client version, which is true
     * either way. */
    internal fun scopeFromShell(html: String): SessionScope {
        // The shell embeds its config as an escaped JSON string,
        // The config can arrive backslash-escaped; unescape
        // every pattern below misses and the scope reads signed-out.
        val text = html.replace("\\\"", "\"")
        fun grab(pattern: Regex): String? =
            pattern.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val version = grab(SHELL_CLIENT_VERSION)
        if (grab(SHELL_LOGGED_IN) != "true") {
            return SessionScope("0", null, null, version)
        }
        val pageId = grab(SHELL_PAGE_ID)
        return SessionScope(
            authUser = grab(SHELL_SESSION_INDEX) ?: "0",
            dataSyncId = pageId
                ?: normalizeDataSyncId(grab(SHELL_DATASYNC_ID)),
            pageId = pageId,
            clientVersion = version,
        )
    }

    /** The session's own visitor id, as the shell config carries it. */
    internal fun shellVisitorData(html: String): String? =
        SHELL_VISITOR_DATA.find(html.replace("\\\"", "\""))
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /**
     * Read the session scope once per session, before calls that
     * depend on being the right account (the common request path
     * invokes it). Blocking on purpose: it rides the same IO
     * threads the requests themselves block on. Fails open — a
     * shell that cannot be fetched leaves requests behaving as
     * they did before scope existed, and the read is retried
     * after a pause rather than before every call.
     */
    private fun ensureSessionScope() {
        val cookies = sessionCookies()
        val sapisid = sapisidOf(cookies) ?: return
        if (scopeStamp == sapisid && scopeSettled) return
        if (scopeStamp != sapisid) {
            sessionScope = null
            scopeSettled = false
            scopeStamp = sapisid
        }
        val now = System.currentTimeMillis()
        if (now < scopeRetryAfterMs) return
        val read = runCatching {
            val cookieHeader =
                cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val req = Request.Builder()
                .url("$ORIGIN/")
                .header("User-Agent", USER_AGENT)
                .header("Cookie", cookieHeader)
                .header(
                    "Authorization",
                    "SAPISIDHASH ${sapisidHash(sapisid, ORIGIN)}",
                )
                .build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Shell request failed (${resp.code})" }
                resp.body?.string().orEmpty()
            }
        }
        read.onSuccess { html ->
            if (html.isNotBlank()) {
                sessionScope = scopeFromShell(html)
                scopeSettled = true
                shellVisitorData(html)?.let {
                    visitorData = it
                    visitorSessionBound = true
                }
            }
        }.onFailure {
            scopeRetryAfterMs = System.currentTimeMillis() + 60_000
        }
    }

    /** The client version to claim for [client]: the shell's live
     * one when the scope provided it, the pinned one otherwise. */
    private fun effectiveVersion(client: ClientSpec): String =
        if (client == REMIX_CLIENT) {
            sessionScope?.clientVersion ?: client.clientVersion
        } else {
            client.clientVersion
        }

    // ------------------------------------------------------------------
    // Authenticated InnerTube calls
    // ------------------------------------------------------------------

    private fun authedRequest(
        path: String,
        client: ClientSpec = REMIX_CLIENT,
        authUserOverride: String? = null,
    ): Request.Builder {
        val cookies = seatCookies(client.origin)
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val sapisid = sapisidOf(cookies)
        // The session scope names the account this request acts
        // as (v1.0.13): the page's own SESSION_INDEX, not a
        // hardcoded 0, and the brand page id when the selected
        // identity is a channel. The identity chain's retry ladder
        // (v1.0.15) may pin the index back to 0 for one attempt —
        // that attempt also drops the page id, which belongs to
        // the scoped shape alone.
        val scope = if (client == REMIX_CLIENT) sessionScope else null
        val builder = Request.Builder()
            .url("${client.origin}/youtubei/v1/$path?key=${client.apiKey}&prettyPrint=false")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Origin", client.origin)
            .header("X-Origin", client.origin)
            .header("Referer", "${client.origin}/")
            .header("X-Goog-AuthUser", authUserOverride ?: scope?.authUser ?: "0")
            // The client-identity headers the web app (and the
            // InnerTube lineage) sends on every call. Write calls
            // are checked against them; reads mostly are not —
            // another reason likes failed while browsing worked.
            .header("X-YouTube-Client-Name", client.clientNameId)
            .header("X-YouTube-Client-Version", effectiveVersion(client))
            .header("X-Goog-Api-Format-Version", "1")
        if (authUserOverride == null) {
            scope?.pageId?.let { builder.header("X-Goog-PageId", it) }
        }
        visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }
        // Only send a Cookie header when there are cookies to send:
        // radio and other guest calls ride this same path signed-out,
        // and an empty Cookie header is not the same as none.
        if (cookieHeader.isNotBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        if (sapisid != null) {
            builder.header(
                "Authorization",
                "SAPISIDHASH ${sapisidHash(sapisid, client.origin)}",
            )
        }
        return builder
    }

    private fun contextJson(
        client: ClientSpec = REMIX_CLIENT,
        withUserContext: Boolean = true,
    ): JSONObject {
        // Region drives localisation: the owner's picked country and
        // its language (Settings → Region, first-open popup). Home,
        // charts, account surfaces and radio all read this one
        // context — nothing may hardcode a country of its own.
        val region = RegionState.current
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", effectiveVersion(client))
            .put("hl", region.hl)
            .put("gl", region.gl)
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        if (client == REMIX_CLIENT && withUserContext) {
            // The account this request acts as (v1.0.13): the web
            // client names it in context.user on every call. The
            // onBehalfOfUser value is only ever one the signed-in
            // shell proved — never a guess.
            val user = JSONObject().put("lockedSafetyMode", false)
            sessionScope?.dataSyncId?.let { user.put("onBehalfOfUser", it) }
            context.put("user", user)
        }
        return JSONObject().put("context", context)
    }

    private fun post(
        path: String,
        body: JSONObject,
        client: ClientSpec = REMIX_CLIENT,
        authUserOverride: String? = null,
    ): JSONObject {
        // The common signed-in path settles the session scope
        // first (a no-op signed out, or once read): every call
        // after it is asked as the page's own identity.
        ensureSessionScope()
        val req = authedRequest(path, client, authUserOverride)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "YouTube account request failed (${resp.code})" }
            // Write endpoints (like, playlist create…) may answer a
            // successful call with an empty body. That is success,
            // not a parse failure — the old code fed the empty string
            // to JSONObject and reported the write as refused.
            if (text.isBlank()) return JSONObject()
            val root = JSONObject(text)
            root.optJSONObject("responseContext")
                ?.optString("visitorData")?.takeIf { it.isNotBlank() }
                ?.let { if (!visitorSessionBound) visitorData = it }
            return root
        }
    }

    private fun browse(browseId: String): JSONObject =
        post("browse", contextJson().put("browseId", browseId))

    /**
     * What the last identity fetch actually got, step by step —
     * shown as a small line on the account card while the identity
     * is incomplete, so a screenshot names the failing step
     * instead of showing the same blank card. Plain words only:
     * each step is ok / name only / photo only / empty / HTTP nnn
     * / failed.
     */
    @Volatile
    var lastIdentityCheck: String? = null
        private set

    /**
     * Who is signed in — shown to the user before they confirm the
     * session, and on the account card / headers afterwards.
     *
     * v1.0.13: the calls are the same ones the lineage makes, but
     * they are now asked AS the identity the signed-in page
     * reported (see [ensureSessionScope]) — that scoping is the
     * whole fix. Four earlier builds asked the same endpoints as a
     * hardcoded "user 0" with no account context in the body, and
     * the menu answered about nobody. The order follows the
     * lineage's own: the account menu first (its
     * activeAccountHeaderRenderer is the identity), then the two
     * routes to the account-switcher list (accounts_list, then
     * the switcher GET) whose accountItem rows carry the personal
     * account's name and photo whether or not a channel exists.
     * Stops as soon as BOTH name and photo are proven; fields
     * merge across sources.
     *
     * If the chain still comes back short, the last resort is the
     * page itself: the signed-in music.youtube.com page in a
     * throwaway WebView renders the avatar, and its account menu
     * renders the name — [captureIdentityFromPage]. (LoginScreen
     * keeps its own in-page probe as well.)
     *
     * Every step's outcome is recorded in [lastIdentityCheck].
     * Guest behaviour is untouched: signed out, this returns null
     * without a single request.
     */
    suspend fun accountInfo(): AccountInfo? = withContext(Dispatchers.IO) {
        if (!isSignedIn()) {
            lastIdentityCheck = "Profile check: signed out"
            return@withContext null
        }
        // Truth line v2 (v1.0.15): name the auth state, never a
        // secret — which cookie variant feeds the SAPISIDHASH (by
        // cookie NAME), and which jar it came from. Cookies are
        // always read live from the WebView store at fetch time;
        // there is no stored snapshot anywhere in Forte.
        val jar = seatCookies(ORIGIN)
        val variant = sapisidEntry(jar)?.first
        lastJarName = when {
            variant == null -> "none"
            sapisidEntry(cookiesFor(ORIGIN)) != null -> "music"
            else -> "www-fallback"
        }
        val authNote = "auth ${variant ?: "none"} · jar $lastJarName"
        ensureSessionScope()
        val steps = mutableListOf<String>()
        var merged: AccountInfo? = null
        fun absorb(info: AccountInfo?) {
            merged = mergeAccountInfo(merged, info)
        }
        fun outcome(info: AccountInfo?): String = when {
            info == null -> "empty"
            info.name != null && info.avatarUrl != null -> "ok"
            info.name != null -> "name only"
            info.avatarUrl != null -> "photo only"
            else -> "handle only"
        }
        fun note(step: String, info: AccountInfo?) {
            steps += "$step: ${outcome(info)}"
        }
        fun httpCode(e: Exception): String? =
            Regex("""\((\d{3})\)""").find(e.message.orEmpty())
                ?.groupValues?.get(1)

        /**
         * One API door, with the v1.0.15 retry ladder: the full
         * scoped shape first (the lineage's shape). If Google
         * refuses its AUTH (401), the same door is retried with
         * the context.user claim dropped, then — when the scope
         * named a non-zero index — with the plain pre-scope shape
         * (index 0, no user context, the shape that used to get
         * 200s). A 401 on the scoped shape must not kill the door:
         * whichever single element the session cannot back, the
         * ladder walks past it, and the truth line records the
         * rung that answered ("menu: 401>ok" = refused scoped,
         * answered plainer).
         */
        fun ask(step: String, path: String) {
            val shapes: List<Pair<Boolean, String?>> =
                identityShapeLadder(
                    hasUserClaim = sessionScope?.dataSyncId != null,
                    authUser = sessionScope?.authUser ?: "0",
                )
            var trail = ""
            for ((index, shape) in shapes.withIndex()) {
                val (withUser, authOverride) = shape
                try {
                    val info = parseIdentity(
                        post(
                            path,
                            contextJson(withUserContext = withUser),
                            authUserOverride = authOverride,
                        ),
                    )
                    steps += "$step: $trail${outcome(info)}"
                    absorb(info)
                    return
                } catch (e: Exception) {
                    val code = httpCode(e)
                    if (code != "401" || index == shapes.lastIndex) {
                        steps += "$step: $trail" +
                            (if (code != null) "HTTP $code" else "failed")
                        return
                    }
                    trail = "$code>"
                }
            }
        }
        fun complete() = merged?.name != null && merged?.avatarUrl != null

        // The page FIRST (v1.0.15): it is the proven well on the
        // owner's seat — the Library's stored name came out of
        // this door (via the Home self-heal run) on a day when
        // every API door refused. It also adopts the page's own
        // ytcfg scope, so the API doors below are scoped by the
        // live page's values, not only the shell regex's.
        val ranBefore = pageCaptureCount
        val paged = runCatching { captureIdentityFromPage() }.getOrNull()
        if (pageCaptureCount != ranBefore) {
            note("page", paged)
            absorb(paged)
        }
        if (!complete()) {
            ask("menu", "account/account_menu")
        }
        if (!complete()) {
            ask("list", "account/accounts_list")
        }
        if (!complete()) {
            try {
                val info = parseIdentity(accountSwitcher())
                note("switcher", info)
                absorb(info)
            } catch (e: Exception) {
                val code = httpCode(e)
                steps += "switcher: " +
                    (if (code != null) "HTTP $code" else "failed")
            }
        }
        if (!complete() && pageCaptureCount == ranBefore) {
            // The page door was throttled out of its slot above;
            // it is the last resort again before giving up.
            val late = runCatching { captureIdentityFromPage() }.getOrNull()
            if (pageCaptureCount != ranBefore) {
                note("page", late)
                absorb(late)
            }
        }
        lastIdentityCheck = "Profile check: $authNote · " +
            steps.joinToString(" · ")
        merged
    }

    /**
     * The identity retry ladder, as pure data so it is testable:
     * the shapes one API door is tried in, as
     * (send context.user claim, X-Goog-AuthUser override) pairs.
     * Full scoped shape first; dropping the user claim next (only
     * when there IS a claim to drop); the plain shape last (only
     * when the scope named a non-zero index — with index 0 and no
     * claim, the first shape already IS the plain one).
     */
    internal fun identityShapeLadder(
        hasUserClaim: Boolean,
        authUser: String,
    ): List<Pair<Boolean, String?>> {
        val shapes = mutableListOf<Pair<Boolean, String?>>()
        shapes += (true to null)
        if (hasUserClaim) shapes += (false to null)
        if (authUser != "0") shapes += (false to "0")
        return shapes
    }

    // ------------------------------------------------------------------
    // Last resort: read the identity off the signed-in page itself
    // ------------------------------------------------------------------

    /** Earliest time the page capture may run again — it opens a
     * WebView, so it is throttled hard; callers that poll (the
     * login screen) must not spin one up per poll. */
    @Volatile
    private var lastPageCaptureMs: Long = 0L

    /** How many page captures have actually run (past the
     * throttle) — the identity chain compares it around a call to
     * know whether its "page" step really happened, so the truth
     * line never reports a door that was throttled shut. */
    @Volatile
    private var pageCaptureCount: Int = 0

    /**
     * A Google avatar URL at a size worth showing. The page hands
     * back whatever the nav rendered (often =s32); the same URL at
     * =s176 is the account photo at card size. Only the size
     * segment is rewritten; any other URL passes through untouched.
     */
    internal fun biggerAvatar(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        return Regex("""=s\d+""").replace(value) { "=s176" }
    }

    /**
     * The identity as the signed-in music.youtube.com page itself
     * shows it: the nav bar renders the account's avatar before
     * any menu opens, and one scripted tap on the avatar/settings
     * button renders the name and handle as plain text. The probe
     * also reads the page's own ytcfg scope (SESSION_INDEX /
     * DATASYNC_ID / DELEGATED_SESSION_ID) and adopts it, so later
     * API calls are scoped even when the shell read failed.
     * Runs in a throwaway WebView that shares the app's cookie
     * store; the WebView is destroyed before returning.
     */
    suspend fun captureIdentityFromPage(): AccountInfo? {
        if (!isSignedIn()) return null
        val now = System.currentTimeMillis()
        if (now - lastPageCaptureMs < 45_000) return null
        lastPageCaptureMs = now
        pageCaptureCount++
        return withTimeoutOrNull(40_000) {
            withContext(Dispatchers.Main) {
                runCatching { probePageForIdentity() }.getOrNull()
            }
        }
    }

    private suspend fun probePageForIdentity(): AccountInfo? {
        val webView = WebView(com.kotlinsyd.forte.ForteApp.instance)
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            CookieManager.getInstance()
                .setAcceptThirdPartyCookies(webView, true)
            suspendCancellableCoroutine<Unit> { cont ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                }
                webView.loadUrl("$ORIGIN/")
            }
            // The page is a heavy single-page app: onPageFinished
            // fires long before the nav bar (and its avatar) has
            // rendered, and the account menu needs a beat after the
            // scripted tap. v1.0.14's two probes inside ~3 s came
            // back empty on the owner's phone while a later run —
            // same door, warmer timing — landed his name. Poll
            // instead: first read after a settle, then keep
            // reading while anything is still missing.
            delay(1800)
            var merged: AccountInfo? = null
            for (attempt in 0 until 6) {
                val info = evalPageIdentity(webView)
                merged = mergeAccountInfo(merged, info)
                if (merged?.name != null && merged?.avatarUrl != null) break
                delay(1600)
            }
            return merged
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    /** One read of the page: ytcfg scope + rendered identity. */
    private suspend fun evalPageIdentity(webView: WebView): AccountInfo? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(PAGE_IDENTITY_JS) { raw ->
                if (!cont.isActive) return@evaluateJavascript
                val info = runCatching {
                    val payload = org.json.JSONArray("[$raw]").optString(0)
                    val o = JSONObject(payload)
                    if (o.optBoolean("loggedIn")) {
                        // The page's own scope, adopted whole: it
                        // is the most specific answer available,
                        // and it scopes every later API call.
                        val pageId = o.optString("pageId")
                            .takeIf { it.isNotBlank() }
                        sessionScope = SessionScope(
                            authUser = o.optString("authUser")
                                .takeIf { it.isNotBlank() } ?: "0",
                            dataSyncId = pageId ?: normalizeDataSyncId(
                                o.optString("dataSyncId")
                                    .takeIf { it.isNotBlank() },
                            ),
                            pageId = pageId,
                            clientVersion = sessionScope?.clientVersion,
                        )
                        scopeStamp = sapisidOf(sessionCookies())
                        scopeSettled = true
                    }
                    AccountInfo(
                        name = o.optString("name").takeIf { it.isNotBlank() },
                        handle = o.optString("handle").takeIf { it.isNotBlank() },
                        avatarUrl = biggerAvatar(
                            o.optString("avatarUrl")
                                .takeIf { it.isNotBlank() },
                        ),
                    ).takeIf {
                        it.name != null || it.handle != null ||
                            it.avatarUrl != null
                    }
                }.getOrNull()
                cont.resume(info) {}
            }
        }

    /**
     * The page probe: the session scope out of the page's own
     * config, and the identity out of what the page renders. The
     * avatar sits in the nav bar from first paint; the name and
     * handle appear once the account menu is open, so when no menu
     * is rendered yet the probe taps the avatar/settings button
     * (once per page) and the NEXT read picks the text up.
     * Returns one JSON object as a string.
     */
    private const val PAGE_IDENTITY_JS = """
(function() {
  var out = { loggedIn: false, authUser: '', dataSyncId: '', pageId: '',
              name: '', handle: '', avatarUrl: '' };
  try {
    if (window.ytcfg && window.ytcfg.get) {
      var g = function(k) {
        var v = window.ytcfg.get(k);
        return (v === undefined || v === null) ? '' : String(v);
      };
      out.loggedIn = !!window.ytcfg.get('LOGGED_IN');
      out.authUser = g('SESSION_INDEX');
      out.dataSyncId = g('DATASYNC_ID');
      out.pageId = g('DELEGATED_SESSION_ID');
    }
    var clean = function(v) {
      return String(v || '').replace(/\s+/g, ' ').trim();
    };
    var navImg = document.querySelector(
      'ytmusic-nav-bar img[src], #avatar-btn img[src], ' +
      'ytmusic-settings-button img[src]');
    var avatar = navImg ? (navImg.currentSrc || navImg.src || '') : '';
    if (!avatar) {
      // The nav avatar is the one image on the page served from
      // Google's photo hosts; if the named elements above are not
      // in this build of the page, take any header/nav image that
      // is — the account photo is the only photo up there.
      var imgs = document.querySelectorAll(
        'ytmusic-nav-bar img[src], header img[src], nav img[src]');
      for (var k = 0; k < imgs.length; k++) {
        var s = imgs[k].currentSrc || imgs[k].src || '';
        if (s.indexOf('googleusercontent.com') >= 0 ||
            s.indexOf('ggpht.com') >= 0) { avatar = s; break; }
      }
    }
    var area = document.querySelector(
      'ytmusic-account-info, ytd-active-account-header-renderer, ' +
      'yt-multi-page-menu-header-renderer, ' +
      'ytmusic-multi-page-menu-renderer, tp-yt-iron-dropdown');
    if (!area) {
      var btn = document.querySelector(
        'ytmusic-settings-button button, #avatar-btn, ' +
        'button[aria-label*="Account" i]');
      if (btn && !window.__forteIdentityClicked) {
        window.__forteIdentityClicked = true;
        btn.click();
      }
    } else {
      var img = area.querySelector('img[src]');
      if (img) avatar = img.currentSrc || img.src || avatar;
      var lines = String(area.innerText || '').split('\n')
        .map(clean).filter(function(s) { return s.length > 0; });
      var handle = '';
      for (var i = 0; i < lines.length; i++) {
        if (lines[i].charAt(0) === '@') { handle = lines[i]; break; }
      }
      var ignored = /^(account|manage your google account|switch account|sign out|youtube|youtube music|your channel|google account|settings)$/i;
      var name = '';
      for (var j = 0; j < lines.length; j++) {
        var l = lines[j];
        if (l !== handle && !ignored.test(l) &&
            !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(l) &&
            !/subscribers/i.test(l)) { name = l; break; }
      }
      out.name = name;
      out.handle = handle;
    }
    if (avatar.indexOf('data:') !== 0) out.avatarUrl = avatar;
  } catch (e) { /* a half-rendered page answers with what it has */ }
  return JSON.stringify(out);
})()
"""

    /** Field-wise merge: a field already proven is never replaced,
     * a missing field takes whatever a later source proved. */
    internal fun mergeAccountInfo(
        into: AccountInfo?,
        add: AccountInfo?,
    ): AccountInfo? = when {
        add == null -> into
        into == null -> add
        else -> AccountInfo(
            name = into.name ?: add.name,
            handle = into.handle ?: add.handle,
            avatarUrl = into.avatarUrl ?: add.avatarUrl,
        )
    }

    /**
     * The youtube.com account-switcher list as a plain signed GET —
     * the same call the youtube.com avatar menu makes. The body is
     * JSON behind Google's XSSI guard (a `)]}'` line), stripped
     * before parsing.
     */
    private fun accountSwitcher(): JSONObject {
        ensureSessionScope()
        // The switcher lives on the www seat: its own jar, hashed
        // over its own origin (v1.0.15 seat rule — no blended jar).
        val cookies = seatCookies(WEB_ORIGIN)
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val sapisid = sapisidOf(cookies)
        val builder = Request.Builder()
            .url("$WEB_ORIGIN/getAccountSwitcherEndpoint")
            .header("User-Agent", USER_AGENT)
            .header("X-Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/")
            .header("X-Goog-AuthUser", sessionScope?.authUser ?: "0")
        if (cookieHeader.isNotBlank()) builder.header("Cookie", cookieHeader)
        if (sapisid != null) {
            builder.header(
                "Authorization",
                "SAPISIDHASH ${sapisidHash(sapisid, WEB_ORIGIN)}",
            )
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "Account switcher request failed (${resp.code})" }
            return JSONObject(text.substringAfter(")]}'").trim())
        }
    }

    /**
     * Read the identity out of any account-shaped response — the
     * account menu, the accounts list or the switcher GET. All
     * three envelopes wrap the
     * same two identity carriers, so one shape-tolerant parse
     * serves the whole chain:
     *  1. the menu header renderers — `activeAccountHeaderRenderer`
     *     (current) and `googleAccountHeaderRenderer` (older);
     *  2. the account items — `accountItem` / `accountItemRenderer`
     *     objects, the SELECTED one first (the switcher list is
     *     where a channel-less account's name + photo live);
     *  3. any other object carrying an `accountPhoto`,
     *     `accountName`, `channelHandle` or `accountEmail` field.
     * Photo, name and handle are INDEPENDENT finds across all of
     * the above: a response that carries the photo on an item and
     * the name on the header yields both. Name falls back
     * name → email. The handle never falls back to the email's
     * local part — a wrong handle is worse than none.
     */
    internal fun parseIdentity(root: JSONObject): AccountInfo? {
        val headers = listOfNotNull(
            findObject(root, "activeAccountHeaderRenderer"),
            findObject(root, "googleAccountHeaderRenderer"),
        )
        val items = mutableListOf<JSONObject>()
        val candidates = mutableListOf<JSONObject>()
        walkObjects(root) { obj ->
            (obj.optJSONObject("accountItem")
                ?: obj.optJSONObject("accountItemRenderer"))?.let { items += it }
            if (obj.has("accountPhoto") || obj.has("accountName") ||
                obj.has("channelHandle") || obj.has("accountEmail")
            ) {
                candidates += obj
            }
        }
        val selected = items.firstOrNull { it.optBoolean("isSelected") }
        val ranked = headers + listOfNotNull(selected) + items + candidates

        fun textOf(o: JSONObject, key: String): String? {
            val v = o.opt(key) ?: return null
            return when (v) {
                is JSONObject -> runsText(v)?.takeIf { it.isNotBlank() }
                is String -> v.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        fun photoOf(o: JSONObject): String? =
            o.optJSONObject("accountPhoto")
                ?.optJSONArray("thumbnails")?.lastUrl()
        fun nameOf(o: JSONObject): String? = textOf(o, "accountName")
        fun handleOf(o: JSONObject): String? = textOf(o, "channelHandle")
        fun emailOf(o: JSONObject): String? =
            textOf(o, "accountEmail")?.takeIf { it.contains("@") }
                ?: textOf(o, "email")?.takeIf { it.contains("@") }

        val avatar = ranked.firstNotNullOfOrNull { photoOf(it) }
        val name = ranked.firstNotNullOfOrNull { nameOf(it) }
            ?: ranked.firstNotNullOfOrNull { emailOf(it) }
        val handle = ranked.firstNotNullOfOrNull { handleOf(it) }
        if (avatar == null && name == null && handle == null) return null
        return AccountInfo(name, handle, avatar)
    }

    // ------------------------------------------------------------------
    // Library surfaces
    // ------------------------------------------------------------------

    /** The account's liked songs (the "LM" auto-playlist). */
    suspend fun likedSongs(): List<Track> = withContext(Dispatchers.IO) {
        collectTracks(browse("VLLM"))
    }

    /** The account's watch history, most recent first. */
    suspend fun history(): List<Track> = withContext(Dispatchers.IO) {
        collectTracks(browse("FEmusic_history"))
    }

    /** Playlists the account saved/liked (Library → Playlists). */
    suspend fun playlists(): List<AccountPlaylist> = withContext(Dispatchers.IO) {
        val root = browse("FEmusic_liked_playlists")
        val out = mutableListOf<AccountPlaylist>()
        walkObjects(root) { obj ->
            val renderer = obj.optJSONObject("musicTwoRowItemRenderer") ?: return@walkObjects
            val browseId = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?.takeIf { it.isNotBlank() } ?: return@walkObjects
            val title = renderer.optJSONObject("title")?.let { runsText(it) }
                ?: return@walkObjects
            val subtitle = renderer.optJSONObject("subtitle")?.let { runsText(it) }
            val thumb = renderer.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.bigUrl()
            out += AccountPlaylist(browseId, title, subtitle, thumb)
        }
        out.distinctBy { it.browseId }
    }

    /** Tracks inside one of the account's playlists (by browse id). */
    suspend fun playlistTracks(browseId: String): List<Track> = withContext(Dispatchers.IO) {
        collectTracks(browse(browseId))
    }

    /** Albums saved/liked on the signed-in account, as cards. */
    suspend fun likedAlbums(): List<HomeCard> = withContext(Dispatchers.IO) {
        browseShelves("FEmusic_liked_albums")
            .flatMap { it.cards }
            .distinctBy { it.browseId }
    }

    // ------------------------------------------------------------------
    // Home feed (the real YouTube Music home, region-localised)
    // ------------------------------------------------------------------

    /** One card on the home feed that isn't a single song: a playlist,
     * album or artist page the feed recommends. Opens by browse id. */
    data class HomeCard(
        val browseId: String,
        val title: String,
        val subtitle: String?,
        val thumbUrl: String?,
    )

    data class HomeShelf(
        val title: String,
        val tracks: List<Track> = emptyList(),
        val cards: List<HomeCard> = emptyList(),
    )

    /**
     * The real YouTube Music home browse feed for the current region
     * (FEmusic_home), followed through its CONTINUATIONS: page one
     * carries only ~2 shelves, the full feed (9–10 shelves) arrives
     * over the section-list continuation chain. Guest calls get the
     * region's public home; with a session the same call returns the
     * owner's personalised home. Shelves arrive as carousel
     * renderers: song shelves carry musicResponsiveListItemRenderer
     * rows, card shelves carry musicTwoRowItemRenderer tiles
     * (playlists/albums/artists open by browse id; a two-row tile
     * with a watch endpoint is a song).
     *
     * [onPage] fires after every page lands with the shelves so far,
     * so Home paints progressively and never waits on the slowest
     * page. Verified live from the build VM as guest: PK 2 → 9
     * shelves, IN 2 → 10, US 2 → 10 across 4 pages each.
     */
    suspend fun homeFeed(
        maxPages: Int = 50,
        onPage: ((List<HomeShelf>) -> Unit)? = null,
    ): List<HomeShelf> = withContext(Dispatchers.IO) {
        val all = mutableListOf<HomeShelf>()
        fun absorb(root: JSONObject) {
            for (shelf in parseShelves(root)) {
                if (all.none { it.title == shelf.title }) all += shelf
            }
            if (all.isNotEmpty()) onPage?.invoke(all.toList())
        }
        var root = browse("FEmusic_home")
        absorb(root)
        var pages = 1
        var dryPages = 0
        val seenTokens = mutableSetOf<String>()
        while (pages < maxPages) {
            val token = sectionContinuation(root) ?: break
            // A repeated token is the server's end-of-feed signal in
            // disguise; following it again would loop forever.
            if (!seenTokens.add(token)) break
            root = post("browse", contextJson().put("continuation", token))
            val before = all.size
            absorb(root)
            dryPages = if (all.size == before) dryPages + 1 else 0
            // Three pages in a row with no new shelf means the chain
            // is exhausted even if a token keeps being offered.
            if (dryPages >= 3) break
            pages++
        }
        all
    }

    /** Shelves of one browse/continuation response, in feed order. */
    private fun parseShelves(root: JSONObject): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        walkObjects(root) { obj ->
            val shelf = obj.optJSONObject("musicCarouselShelfRenderer")
                ?: obj.optJSONObject("musicImmersiveCarouselShelfRenderer")
                ?: return@walkObjects
            val title = shelf.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")?.let { runsText(it) }
                ?.takeIf { it.isNotBlank() } ?: return@walkObjects
            val contents = shelf.optJSONArray("contents") ?: return@walkObjects
            val tracks = mutableListOf<Track>()
            val cards = mutableListOf<HomeCard>()
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                item.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
                    parseTrack(r)?.let { tracks += it }
                }
                item.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
                    parseTwoRow(r, tracks, cards)
                }
            }
            if (tracks.isNotEmpty() || cards.isNotEmpty()) {
                shelves += HomeShelf(
                    title,
                    tracks.distinctBy { it.id },
                    cards.distinctBy { it.browseId },
                )
            }
        }
        return shelves
    }

    /**
     * The section-list continuation token of a home response — the
     * only token that advances the whole feed (per-shelf tokens just
     * extend one carousel). Continuation calls must ride the
     * session's visitorData (see [visitorData]); the response lands
     * under continuationContents.sectionListContinuation.
     */
    private fun sectionContinuation(root: JSONObject): String? {
        var token: String? = null
        walkObjects(root) { obj ->
            if (token != null) return@walkObjects
            val sl = obj.optJSONObject("sectionListRenderer")
                ?: obj.optJSONObject("sectionListContinuation")
                ?: return@walkObjects
            val conts = sl.optJSONArray("continuations") ?: return@walkObjects
            for (i in 0 until conts.length()) {
                token = conts.optJSONObject(i)
                    ?.optJSONObject("nextContinuationData")
                    ?.optString("continuation")?.takeIf { it.isNotBlank() }
                    ?: token
            }
        }
        return token
    }

    data class ChartsResult(val countryLabel: String?, val tracks: List<Track>)

    /**
     * Region-true Top Charts. FEmusic_charts takes the country
     * through the same form the web app's country menu submits
     * (formData.selectedValues) — gl alone never switched it, which
     * is why charts used to be US for everyone. YouTube publishes no
     * chart for some countries (Pakistan among them) and answers
     * Global instead; [ChartsResult.countryLabel] reports the
     * country the server actually selected so the UI stays honest.
     * Verified live: IN → "India" charts, US → "United States",
     * PK → "Global" (no PK chart exists).
     */
    suspend fun charts(): ChartsResult = withContext(Dispatchers.IO) {
        val gl = RegionState.current.gl.uppercase()
        val root = post(
            "browse",
            contextJson()
                .put("browseId", "FEmusic_charts")
                .put(
                    "formData",
                    JSONObject().put("selectedValues", JSONArray().put(gl)),
                ),
        )
        val label = chartCountryLabel(root)
        val direct = collectTracks(root)
        if (direct.size >= 8) return@withContext ChartsResult(label, direct)
        // Charts are mostly playlist cards (Daily Top Music Videos,
        // Trending 20, Top 100…): open the best-ranked one and chart
        // its tracks.
        val cards = mutableListOf<HomeCard>()
        val stray = mutableListOf<Track>()
        walkObjects(root) { obj ->
            obj.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
                parseTwoRow(r, stray, cards)
            }
        }
        val ranked = cards.sortedBy { card ->
            val t = card.title
            when {
                t.contains("Daily Top", ignoreCase = true) -> 0
                t.contains("Trending", ignoreCase = true) -> 1
                t.contains("Top 100", ignoreCase = true) -> 2
                t.contains("Music Videos", ignoreCase = true) -> 3
                else -> 4
            }
        }
        for (card in ranked) {
            val tracks = runCatching { playlistTracks(card.browseId) }
                .getOrDefault(emptyList())
            if (tracks.isNotEmpty()) return@withContext ChartsResult(label, tracks)
        }
        ChartsResult(label, direct)
    }

    /** The country the charts server actually selected, read from
     * the country menu button in the response. */
    private fun chartCountryLabel(root: JSONObject): String? {
        var label: String? = null
        walkObjects(root) { obj ->
            if (label == null) {
                label = obj.optJSONObject("musicSortFilterButtonRenderer")
                    ?.optJSONObject("title")?.let { runsText(it) }
            }
        }
        return label
    }

    private fun parseTwoRow(
        renderer: JSONObject,
        tracks: MutableList<Track>,
        cards: MutableList<HomeCard>,
    ) {
        val title = renderer.optJSONObject("title")?.let { runsText(it) }
            ?.takeIf { it.isNotBlank() } ?: return
        val subtitle = renderer.optJSONObject("subtitle")?.let { runsText(it) }
        val thumb = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.bigUrl()
            ?: renderer.optJSONObject("thumbnailRenderer")
                ?.optJSONArray("thumbnails")?.bigUrl()
        val endpoint = renderer.optJSONObject("navigationEndpoint") ?: return
        endpoint.optJSONObject("watchEndpoint")
            ?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?.let { videoId ->
                tracks += Track(
                    id = videoId, title = title,
                    artist = subtitle?.split(" • ", " · ")?.firstOrNull()?.trim() ?: "",
                    thumbUrl = thumb ?: frameFloorUrl(videoId),
                )
                return
            }
        val browseId = endpoint.optJSONObject("browseEndpoint")
            ?.optString("browseId")?.takeIf { it.isNotBlank() } ?: return
        cards += HomeCard(browseId, title, subtitle, thumb)
    }

    // ------------------------------------------------------------------
    // Account writes (likes, playlists, history) — honest failures
    // ------------------------------------------------------------------

    /**
     * Like or unlike a song on the signed-in YouTube account
     * (like/like / like/removelike). Throws when YouTube refuses —
     * callers must surface that, never pretend it worked.
     */
    suspend fun setLiked(videoId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        check(isSignedIn()) { "Not signed in" }
        val body = contextJson().put(
            "target", JSONObject().put("videoId", videoId),
        )
        post(if (liked) "like/like" else "like/removelike", body)
        Unit
    }

    /**
     * Dislike or clear a dislike on the signed-in YouTube account
     * (like/dislike / like/removedislike). Throws when YouTube
     * refuses — callers surface that and restore the prior state.
     */
    suspend fun setDisliked(videoId: String, disliked: Boolean) =
        withContext(Dispatchers.IO) {
            check(isSignedIn()) { "Not signed in" }
            val body = contextJson().put(
                "target", JSONObject().put("videoId", videoId),
            )
            post(if (disliked) "like/dislike" else "like/removedislike", body)
            Unit
        }

    /** Ids of everything in the account's Liked songs — used to mark
     * like state in the player when signed in. */
    suspend fun likedIds(): Set<String> = withContext(Dispatchers.IO) {
        likedSongs().map { it.id }.toSet()
    }

    /** Create a playlist on the account; returns its plain id. */
    suspend fun createYtPlaylist(title: String): String = withContext(Dispatchers.IO) {
        check(isSignedIn()) { "Not signed in" }
        val root = post(
            "playlist/create",
            contextJson().put("title", title).put("privacyStatus", "PRIVATE"),
        )
        val id = root.optString("playlistId").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("YouTube didn't return a playlist id")
        id
    }

    /** Add a song to one of the account's playlists. */
    suspend fun addToYtPlaylist(playlistId: String, videoId: String) =
        withContext(Dispatchers.IO) {
            check(isSignedIn()) { "Not signed in" }
            val action = JSONObject()
                .put("action", "ACTION_ADD_VIDEO")
                .put("addedVideoId", videoId)
            val body = contextJson()
                .put("playlistId", playlistId.removePrefix("VL"))
                .put("actions", JSONArray().put(action))
            post("browse/edit_playlist", body)
            Unit
        }

    /**
     * The artists/channels the account subscribes to, as cards.
     * The correct shape, read from the live response structure:
     * FEmusic_library_corpus_artists is a shelf of channel cards,
     * NOT a track list — the old code browsed FEmusic_subscriptions
     * and collected track rows from it, found none, and the screen
     * sat empty forever ("Subscriptions never works"). Rows open
     * the artist page by channel id.
     */
    suspend fun subscribedArtists(): List<HomeCard> = withContext(Dispatchers.IO) {
        val root = browse("FEmusic_library_corpus_artists")
        val out = mutableListOf<HomeCard>()
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
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.bigUrl()
            out += HomeCard(browseId, title, subtitle, thumb)
        }
        out.distinctBy { it.browseId }
    }

    /**
     * Register a play in the account's YouTube history: the player
     * response carries a playback-tracking URL; one GET to it with
     * the session cookies is what the web app fires when a song
     * starts. Best-effort — a refusal is logged nowhere and shown
     * nowhere; history write-back must never disturb playback.
     */
    suspend fun registerWatch(videoId: String) = withContext(Dispatchers.IO) {
        runCatching {
            if (!isSignedIn()) return@runCatching
            val root = post(
                "player",
                contextJson().put("videoId", videoId).put("contentCheckOk", true),
            )
            val base = root.optJSONObject("playbackTracking")
                ?.optJSONObject("videostatsPlaybackUrl")
                ?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                ?: return@runCatching
            val cookies = sessionCookies()
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val req = Request.Builder()
                .url(base)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$ORIGIN/")
                .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
                .build()
            http.newCall(req).execute().use { resp -> resp.body?.string() }
        }
        Unit
    }

    // ------------------------------------------------------------------
    // Radio (YouTube Music's endless station for one song)
    // ------------------------------------------------------------------

    data class RadioPage(val tracks: List<Track>, val continuation: String?)

    /**
     * The first page of the radio station seeded by [videoId]: the
     * "RD<videoId>" automix playlist behind the watch-next queue.
     * Rides the same WEB_REMIX InnerTube path as the account calls —
     * signed-in when a session exists (personalised), guest otherwise;
     * both work, verified live: 50 tracks plus a continuation.
     */
    suspend fun radioStart(videoId: String): RadioPage = withContext(Dispatchers.IO) {
        val body = contextJson()
            .put("videoId", videoId)
            .put("playlistId", "RD$videoId")
            .put("isAudioOnly", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        val root = post("next", body)
        parseRadioPanel(findObject(root, "playlistPanelRenderer") ?: JSONObject())
    }

    /** The next page of an open station, via the radio continuation. */
    suspend fun radioMore(videoId: String, continuation: String): RadioPage =
        withContext(Dispatchers.IO) {
            val body = contextJson()
                .put("videoId", videoId)
                .put("playlistId", "RD$videoId")
                .put("continuation", continuation)
            val root = post("next", body)
            val panel = root.optJSONObject("continuationContents")
                ?.optJSONObject("playlistPanelContinuation")
                ?: findObject(root, "playlistPanelContinuation")
                ?: JSONObject()
            parseRadioPanel(panel)
        }

    internal fun parseRadioPanel(panel: JSONObject): RadioPage {
        val out = mutableListOf<Track>()
        val contents = panel.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val renderer = contents.optJSONObject(i)
                    ?.optJSONObject("playlistPanelVideoRenderer") ?: continue
                parseRadioTrack(renderer)?.let { out += it }
            }
        }
        var token: String? = null
        val conts = panel.optJSONArray("continuations")
        if (conts != null) {
            for (i in 0 until conts.length()) {
                val c = conts.optJSONObject(i) ?: continue
                token = c.optJSONObject("nextRadioContinuationData")
                    ?.optString("continuation")?.takeIf { it.isNotBlank() }
                    ?: c.optJSONObject("nextContinuationData")
                        ?.optString("continuation")?.takeIf { it.isNotBlank() }
                    ?: token
            }
        }
        return RadioPage(out.distinctBy { it.id }, token)
    }

    private fun parseRadioTrack(renderer: JSONObject): Track? {
        val id = renderer.optString("videoId").takeIf { it.isNotBlank() }
            ?: renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = renderer.optJSONObject("title")?.let { runsText(it) }
            ?.takeIf { it.isNotBlank() } ?: return null
        // Byline shape is "Artist • views/album • …": the first segment
        // is the artist unless it's a view-count style segment.
        val byline = renderer.optJSONObject("longBylineText")?.let { runsText(it) }.orEmpty()
        val segments = byline.split(" • ", " · ").map { it.trim() }.filter { it.isNotBlank() }
        val artist = segments.firstOrNull()
            ?.takeUnless { it.contains("views") || it.contains("likes") } ?: ""
        val lengthText = renderer.optJSONObject("lengthText")?.let { runsText(it) }
        val durationSec = lengthText?.let { parseDuration(it) } ?: 0
        val thumb = renderer.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")?.bigUrl()
        return Track(
            id = id,
            title = title,
            artist = artist,
            durationSec = durationSec,
            // Frame floor, same rule as parseTrack: panel items
            // whose thumbnail object is absent still carry a frame.
            thumbUrl = thumb ?: frameFloorUrl(id),
        )
    }


    // ------------------------------------------------------------------
    // Playlist & channel pages (browse — our own InnerTube shapes,
    // replacing the extractor's playlist/channel calls)
    // ------------------------------------------------------------------

    /** Any playlist reference the UI can hold — a full watch/
     * playlist URL, a VL/MPRE browse id, or a bare list id — as the
     * browse id the browse endpoint wants. */
    private fun playlistBrowseId(input: String): String {
        val s = input.trim()
        return when {
            s.startsWith("VL") || s.startsWith("MPRE") ||
                s.startsWith("RD") || s.startsWith("OLAK") -> s
            s.contains("list=") -> {
                val list = s.substringAfter("list=")
                    .substringBefore("&").substringBefore("#")
                if (list.startsWith("VL") || list.startsWith("MPRE")) list
                else "VL$list"
            }
            s.startsWith("PL") || s.startsWith("UU") ||
                s.startsWith("OL") -> "VL$s"
            else -> s
        }
    }

    /**
     * One page of a playlist or album by browse. Track rows are the
     * same musicResponsiveListItemRenderer shape as everywhere else;
     * the continuation token (when the list continues) rides in
     * [PlaylistPage.nextPage] as a String.
     */
    suspend fun playlistPage(input: String): PlaylistPage = withContext(Dispatchers.IO) {
        val root = browse(playlistBrowseId(input))
        PlaylistPage(
            title = pageTitle(root) ?: "Playlist",
            uploader = null,
            thumbUrl = pageThumb(root),
            tracks = collectTracks(root),
            nextPage = pageContinuation(root),
        )
    }

    /** The next page of an open playlist page. */
    suspend fun playlistMore(current: PlaylistPage): PlaylistPage =
        withContext(Dispatchers.IO) {
            val token = current.nextPage as? String
                ?: return@withContext current
            val root = post("browse", contextJson().put("continuation", token))
            current.copy(
                tracks = (current.tracks + collectTracks(root))
                    .distinctBy { it.id },
                nextPage = pageContinuation(root),
            )
        }

    data class ChannelPage(
        val name: String,
        val avatarUrl: String?,
        val bannerUrl: String?,
        val subscribers: Long,
        val description: String?,
        val id: String,
    )

    /**
     * An artist/channel page by browse. Shape-tolerant header read:
     * the music browse serves the header as an immersive/detail
     * header renderer whose keys have shifted before, so any of
     * them is accepted and the description is taken from the
     * description shelf when the header doesn't carry one.
     */
    suspend fun channelPage(input: String): ChannelPage = withContext(Dispatchers.IO) {
        val browseId = input.trim()
            .substringAfter("channel/").substringBefore("?").substringBefore("/")
            .takeIf { it.startsWith("UC") } ?: input.trim()
        val root = browse(browseId)
        var name: String? = null
        var avatar: String? = null
        var subs = -1L
        var description: String? = null
        for (key in listOf(
            "musicImmersiveHeaderRenderer",
            "musicDetailHeaderRenderer",
            "musicResponsiveHeaderRenderer",
        )) {
            val h = findObject(root, key) ?: continue
            if (name == null) {
                name = h.optJSONObject("title")?.let { runsText(it) }
            }
            if (avatar == null) {
                avatar = h.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ?.bigUrl()
            }
            if (subs < 0) {
                val text = h.optJSONObject("subscriptionButtonText")
                    ?.let { runsText(it) }
                    ?: h.optJSONObject("subscriberCountText")?.let { runsText(it) }
                subs = text?.let { parseCount(it) } ?: -1L
            }
            if (description == null) {
                description = h.optJSONObject("description")?.let { runsText(it) }
            }
        }
        if (description == null) {
            findObject(root, "musicDescriptionShelfRenderer")
                ?.optJSONObject("description")?.let { runsText(it) }
                ?.let { description = it }
        }
        if (name == null) {
            // Last resort: the first shelf's strapline sometimes
            // names the channel; else the page still opens honestly.
            name = parseShelves(root).firstOrNull()?.title ?: "Artist"
        }
        ChannelPage(
            name = name ?: "Artist",
            avatarUrl = avatar,
            bannerUrl = avatar,
            subscribers = subs,
            description = description,
            id = browseId,
        )
    }

    /** "1.23M subscribers" / "45K" / "12,345" → a count. */
    private fun parseCount(text: String): Long {
        val cleaned = text.replace(",", "").trim()
        val num = cleaned.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull()
            ?: return -1
        return when {
            cleaned.contains("B", ignoreCase = true) -> (num * 1_000_000_000).toLong()
            cleaned.contains("M", ignoreCase = true) -> (num * 1_000_000).toLong()
            cleaned.contains("K", ignoreCase = true) -> (num * 1_000).toLong()
            else -> num.toLong()
        }
    }

    private fun pageTitle(root: JSONObject): String? {
        for (key in listOf(
            "musicImmersiveHeaderRenderer",
            "musicDetailHeaderRenderer",
            "musicResponsiveHeaderRenderer",
        )) {
            findObject(root, key)?.optJSONObject("title")
                ?.let { runsText(it) }?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    private fun pageThumb(root: JSONObject): String? {
        for (key in listOf(
            "musicImmersiveHeaderRenderer",
            "musicDetailHeaderRenderer",
            "musicResponsiveHeaderRenderer",
        )) {
            val thumb = findObject(root, key)?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?.bigUrl()
            if (thumb != null) return thumb
        }
        return null
    }

    private fun pageContinuation(root: JSONObject): String? {
        var token: String? = null
        walkObjects(root) { obj ->
            if (token == null) {
                token = obj.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")?.takeIf { it.isNotBlank() }
                    ?: obj.optJSONObject("nextContinuationData")
                        ?.optString("continuation")?.takeIf { it.isNotBlank() }
            }
        }
        return token
    }

    // ------------------------------------------------------------------
    // Catalog wells (the browse sources the Home feed draws from:
    // the home feed plus its supplements, explore, and the
    // moods & genres grid)
    // ------------------------------------------------------------------

    /** Shelves of any browse id — home supplements
     * (FEmusic_new_releases, FEmusic_explore), FEmusic_mixed_for_you,
     * and mood/genre category pages all serve the same shelf
     * renderers the home parser already reads. */
    suspend fun browseShelves(
        browseId: String,
        params: String? = null,
    ): List<HomeShelf> = withContext(Dispatchers.IO) {
        val body = contextJson().put("browseId", browseId)
        if (params != null) body.put("params", params)
        parseShelves(post("browse", body))
    }

    data class MoodGenre(
        val title: String,
        val browseId: String,
        val params: String?,
        /** The category's own colour (the tile stripe colour the
         * browse response carries) as ARGB — the designed ground a
         * picture card sits on before its photo is harvested. */
        val colorArgb: Long? = null,
    )

    data class MoodSection(val title: String, val items: List<MoodGenre>)

    /** A category-button shelf the Explore feed itself serves. */
    data class CategoryShelf(val title: String, val items: List<MoodGenre>)

    /** The Explore payload: normal shelves plus category shelves. */
    data class ExplorePage(
        val shelves: List<HomeShelf>,
        val categories: List<CategoryShelf>,
    )

    /** Parse one category navigation button, whichever label and
     * colour shape the serving feed uses. */
    private fun parseMoodButton(item: JSONObject): MoodGenre? {
        val button = item.optJSONObject("musicNavigationButtonRenderer")
            ?: return null
        val title = button.optJSONObject("buttonText")?.let { runsText(it) }
            ?: button.optJSONObject("text")?.let { runsText(it) }
            ?: return null
        val endpoint = button.optJSONObject("clickCommand")
            ?.optJSONObject("browseEndpoint")
            ?: button.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
            ?: return null
        val browseId = endpoint.optString("browseId")
            .takeIf { it.isNotBlank() } ?: return null
        val solid = button.optJSONObject("solid")
        val color = solid?.optJSONObject("leftStripeColor")
            ?.optLong("color")?.takeIf { it != 0L }
            ?: solid?.optLong("leftStripeColor")?.takeIf { it != 0L }
        return MoodGenre(
            title = title,
            browseId = browseId,
            params = endpoint.optString("params").takeIf { it.isNotBlank() },
            colorArgb = color,
        )
    }

    /** Category shelves inside an Explore response. The normal shelf
     * parser carries tracks/cards only, so navigation buttons need
     * this second pass over the same response. */
    private fun parseCategoryShelves(root: JSONObject): List<CategoryShelf> {
        val out = mutableListOf<CategoryShelf>()
        walkObjects(root) { obj ->
            val shelf = obj.optJSONObject("musicCarouselShelfRenderer")
                ?: obj.optJSONObject("gridRenderer")
                ?: return@walkObjects
            val title = shelf.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")?.let { runsText(it) }
                ?: shelf.optJSONObject("header")
                    ?.optJSONObject("gridHeaderRenderer")
                    ?.optJSONObject("title")?.let { runsText(it) }
                ?: return@walkObjects
            val contents = shelf.optJSONArray("contents")
                ?: shelf.optJSONArray("items") ?: return@walkObjects
            val items = mutableListOf<MoodGenre>()
            for (i in 0 until contents.length()) {
                contents.optJSONObject(i)?.let { parseMoodButton(it) }
                    ?.let { items += it }
            }
            if (items.isNotEmpty() && out.none { it.title == title }) {
                out += CategoryShelf(title, items.distinctBy { it.browseId })
            }
        }
        return out
    }

    /** Browse the real Explore feed (FEmusic_explore). */
    suspend fun explorePage(): ExplorePage = withContext(Dispatchers.IO) {
        val root = browse("FEmusic_explore")
        ExplorePage(parseShelves(root), parseCategoryShelves(root))
    }

    /**
     * The moods & genres grid (FEmusic_moods_and_genres): sections
     * of navigation-button tiles, each tile a category page
     * (browseId + params) that serves ordinary shelves. Parsed by
     * shape — grid renderers and their button items only.
     */
    suspend fun moodsAndGenres(): List<MoodSection> = withContext(Dispatchers.IO) {
        val root = browse("FEmusic_moods_and_genres")
        val sections = mutableListOf<MoodSection>()
        walkObjects(root) { obj ->
            val grid = obj.optJSONObject("gridRenderer") ?: return@walkObjects
            val title = grid.optJSONObject("header")
                ?.optJSONObject("gridHeaderRenderer")
                ?.optJSONObject("title")?.let { runsText(it) }
                ?.takeIf { it.isNotBlank() } ?: return@walkObjects
            val itemsArr = grid.optJSONArray("items") ?: return@walkObjects
            val items = mutableListOf<MoodGenre>()
            for (i in 0 until itemsArr.length()) {
                val button = itemsArr.optJSONObject(i)
                    ?.optJSONObject("musicNavigationButtonRenderer")
                    ?: continue
                val endpoint = button.optJSONObject("clickCommand")
                    ?.optJSONObject("browseEndpoint")
                    ?: button.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                    ?: continue
                val browseId = endpoint.optString("browseId")
                    .takeIf { it.isNotBlank() } ?: continue
                val label = button.optJSONObject("buttonText")
                    ?.let { runsText(it) }?.takeIf { it.isNotBlank() } ?: continue
                val color = button.optJSONObject("solid")
                    ?.takeIf { it.has("leftStripeColor") }
                    ?.optLong("leftStripeColor")?.takeIf { it != 0L }
                items += MoodGenre(
                    label, browseId,
                    endpoint.optString("params").takeIf { it.isNotBlank() },
                    color,
                )
            }
            if (items.isNotEmpty()) sections += MoodSection(title, items)
        }
        sections
    }

    /**
     * A category's own picture for its Home card. The moods grid
     * response itself carries no image per category (verified live:
     * button text + a stripe colour, nothing else) — the category's
     * own page is full of real artwork, so the card takes the first
     * card image that page serves. Callers cache the result; this
     * runs once per category, ever.
     */
    suspend fun moodArtwork(browseId: String, params: String?): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = contextJson().put("browseId", browseId)
                if (params != null) body.put("params", params)
                val root = post("browse", body)
                var art: String? = null
                walkObjects(root) { obj ->
                    if (art == null) {
                        val r = obj.optJSONObject("musicTwoRowItemRenderer")
                            ?: return@walkObjects
                        art = r.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")?.bigUrl()
                    }
                }
                art
            }.getOrNull()
        }

    /**
     * The best artwork the service itself offers for one video —
     * the player response's own thumbnail set, upgraded by the
     * usual rule. Used to backfill library rows that were saved
     * with no artwork at all (the letter-placeholder rows on Home).
     * Falls back to the largest standard frame when this video has
     * one (probed — maxres is not guaranteed to exist), and to the
     * hq frame under it, which always exists.
     */
    suspend fun trackArtwork(videoId: String): String? =
        withContext(Dispatchers.IO) {
            val fromPlayer = runCatching {
                val root = post(
                    "player",
                    contextJson().put("videoId", videoId)
                        .put("contentCheckOk", true),
                )
                root.optJSONObject("videoDetails")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")?.bigUrl()
            }.getOrNull()
            if (fromPlayer != null) return@withContext fromPlayer
            val maxres = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            val hasMaxres = runCatching {
                val req = okhttp3.Request.Builder().url(maxres).head().build()
                http.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (hasMaxres) maxres else frameFloorUrl(videoId)
        }

    /** One published song-credit line, exactly as YouTube labels it. */
    data class SongCredit(val label: String, val value: String)

    /**
     * The song credits YouTube itself publishes for this recording.
     * They ride the main YouTube watch response, not the Music one:
     * its structured description carries a "Song credits" dialog
     * (Song / Artist / Album / Writers, and producers when YouTube
     * has them). Only lines actually present are returned — Forte
     * never invents a writer or producer to fill the sheet.
     */
    suspend fun trackCredits(videoId: String): List<SongCredit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", "2.20240101.01.00")
                    .put("hl", RegionState.current.hl)
                    .put("gl", RegionState.current.gl)
                val body = JSONObject()
                    .put("context", JSONObject().put("client", client))
                    .put("videoId", videoId)
                val req = Request.Builder()
                    .url(
                        "https://www.youtube.com/youtubei/v1/next" +
                            "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false",
                    )
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val root = http.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "Credits request failed (${resp.code})" }
                    JSONObject(resp.body?.string().orEmpty())
                }
                var dialog: JSONObject? = null
                walkObjects(root) { obj ->
                    if (dialog != null) return@walkObjects
                    val candidate = obj.optJSONObject("confirmDialogRenderer")
                        ?: return@walkObjects
                    val title = candidate.optJSONObject("title")
                        ?.let { runsText(it) }
                    if (title.equals("Song credits", ignoreCase = true)) {
                        dialog = candidate
                    }
                }
                val messages = dialog?.optJSONArray("dialogMessages")
                    ?: return@runCatching emptyList()
                val out = mutableListOf<SongCredit>()
                for (i in 0 until messages.length()) {
                    val text = messages.optJSONObject(i)
                        ?.let { runsText(it) } ?: continue
                    text.split("\n\n").forEach { segment ->
                        val split = segment.indexOf(":")
                        if (split > 0) {
                            val label = segment.substring(0, split).trim()
                            val value = segment.substring(split + 1).trim()
                            if (label.isNotBlank() && value.isNotBlank()) {
                                out += SongCredit(label, value)
                            }
                        }
                    }
                }
                out.distinctBy { it.label to it.value }
            }.getOrDefault(emptyList())
        }

    // ------------------------------------------------------------------
    // Response walking / parsing
    // ------------------------------------------------------------------

    private fun collectTracks(root: JSONObject): List<Track> {
        val out = mutableListOf<Track>()
        walkObjects(root) { obj ->
            val renderer = obj.optJSONObject("musicResponsiveListItemRenderer")
                ?: return@walkObjects
            parseTrack(renderer)?.let { out += it }
        }
        return out.distinctBy { it.id }
    }

    /** Shared with ForteEngine's search (same renderer shape). */
    internal fun parseTrack(renderer: JSONObject): Track? {
        var videoId = renderer.optJSONObject("playlistItemData")
            ?.optString("videoId")?.takeIf { it.isNotBlank() }
        if (videoId == null) {
            walkObjects(renderer) { obj ->
                if (videoId == null) {
                    obj.optJSONObject("watchEndpoint")?.optString("videoId")
                        ?.takeIf { it.isNotBlank() }?.let { videoId = it }
                }
            }
        }
        val id = videoId ?: return null
        val columns = renderer.optJSONArray("flexColumns") ?: return null
        fun columnText(i: Int): String? =
            columns.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.let { runsText(it) }
        val title = columnText(0)?.takeIf { it.isNotBlank() } ?: return null
        val second = columnText(1).orEmpty()
        val parts = second.split(" • ", " · ").map { it.trim() }.filter { it.isNotBlank() }
        val durationPart = parts.lastOrNull { it.matches(Regex("\\d+:\\d{2}(:\\d{2})?")) }
        val artist = parts.firstOrNull { it != durationPart } ?: ""
        val durationSec = durationPart?.let { parseDuration(it) } ?: 0
        val thumb = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.bigUrl()
            // Video-type rows can carry their frames directly under
            // `thumbnail`, with no musicThumbnailRenderer wrap.
            ?: renderer.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")?.bigUrl()
        // The artist's channel id rides in the byline runs'
        // navigation endpoints — it is what "Go to artist" opens.
        var artistBrowseId: String? = null
        val runs = columns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")
        if (runs != null) {
            for (i in 0 until runs.length()) {
                val bid = runs.optJSONObject(i)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")?.optString("browseId")
                if (bid != null && bid.startsWith("UC")) {
                    artistBrowseId = bid
                    break
                }
            }
        }
        return Track(
            id = id,
            title = title,
            artist = artist,
            artistUrl = artistBrowseId,
            durationSec = durationSec,
            // Frame floor: a video-type row whose thumbnails didn't
            // parse still leaves with its video frame, never blank.
            thumbUrl = thumb ?: frameFloorUrl(id),
        )
    }

    private fun parseDuration(text: String): Long {
        val bits = text.split(":").mapNotNull { it.toIntOrNull() }
        return bits.fold(0L) { acc, v -> acc * 60 + v }
    }

    private fun runsText(obj: JSONObject): String? {
        val runs = obj.optJSONArray("runs") ?: return obj.optString("simpleText").ifBlank { null }
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

    /** The largest thumbnail, upgraded to the big artwork form
     * (owner's artwork rule — see [bigArtwork]). */
    private fun JSONArray.bigUrl(): String? = bigArtwork(lastUrl())

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
