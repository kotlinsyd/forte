package com.kotlinsyd.forte.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Signature decipher for the cipher rung of Forte's own engine.
 *
 * Written clean-room from the well-known shape of the problem:
 * YouTube's player JS scrambles stream signatures with a short
 * sequence of character operations — reverse the string, drop the
 * first N characters (splice), swap the first character with the
 * one at position N. This file fetches the current player JS,
 * extracts that operation sequence plus the signatureTimestamp the
 * player expects, and interprets the operations itself. No code
 * from any other extractor is used.
 *
 * Honest coverage note (verified 2026-11-03): against the player
 * build live at the time (4efb8b2c), the classic static structure
 * this extractor looks for is NOT present in either the main or
 * the embed base.js — YouTube has restructured that code — so
 * [decipher] returns null there and the cipher rung simply never
 * fires; the battery-proven plain-URL ladder carries playback.
 * The extractor stays because player builds vary by surface and
 * over time: when a build does carry the structure, the rung
 * works; when it doesn't, nothing breaks.
 */
object SigDecipher {

    /** Placeholder used by the plain-URL ladder, whose responses do
     * not validate it (battery-proven). The cipher rung always
     * re-reads the live value from the player JS instead. */
    const val FALLBACK_STS = 20754

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private sealed interface Op {
        object Reverse : Op
        data class Splice(val n: Int) : Op
        data class Swap(val n: Int) : Op
    }

    @Volatile
    private var loaded = false

    @Volatile
    private var sts: Int? = null

    @Volatile
    private var ops: List<Op>? = null

    fun signatureTimestamp(): Int? = sts

    /** Fetch the current player JS once per process and extract
     * what the cipher rung needs. Never throws. */
    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            runCatching {
                val api = get("https://www.youtube.com/iframe_api")
                val id = Regex("""s/player/([\w-]+)/""")
                    .find(api)?.groupValues?.get(1)
                    ?: return@runCatching
                val js = get(
                    "https://www.youtube.com/s/player/$id" +
                        "/player_ias.vflset/en_US/base.js",
                )
                sts = Regex("""signatureTimestamp\s*[:=]\s*(\d+)""")
                    .find(js)?.groupValues?.get(1)?.toIntOrNull()
                ops = extractOps(js)
            }
            loaded = true
        }
    }

    /** Apply the extracted operation sequence to a scrambled
     * signature. Null when no sequence was extracted. */
    fun decipher(scrambled: String): String? {
        val sequence = ops ?: return null
        if (sequence.isEmpty()) return null
        val chars = scrambled.toMutableList()
        for (op in sequence) {
            when (op) {
                Op.Reverse -> chars.reverse()
                is Op.Splice -> repeat(op.n.coerceIn(0, chars.size)) {
                    if (chars.isNotEmpty()) chars.removeAt(0)
                }
                is Op.Swap -> {
                    if (chars.isNotEmpty()) {
                        val idx = op.n % chars.size
                        val tmp = chars[0]
                        chars[0] = chars[idx]
                        chars[idx] = tmp
                    }
                }
            }
        }
        return chars.joinToString("")
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER_UA)
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "GET $url failed (${resp.code})" }
            return resp.body?.string().orEmpty()
        }
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    /**
     * Find the signature function and read its operation calls.
     * Several historical forms are tried; the helper object behind
     * the calls is then classified method by method (a method whose
     * body reverses, splices or swaps). Any step failing yields
     * null — the rung stays silent rather than guessing.
     */
    private fun extractOps(js: String): List<Op>? {
        val sigPatterns = listOf(
            Regex("""([A-Za-z_$][\w$]*)\s*=\s*function\(([A-Za-z_$][\w$]*)\)\{\2\s*=\s*\2\.split\(""\)"""),
            Regex("""function\s+([A-Za-z_$][\w$]*)\(([A-Za-z_$][\w$]*)\)\{\2\s*=\s*\2\.split\(""\)"""),
            Regex("""var\s+([A-Za-z_$][\w$]*)\s*=\s*function\(([A-Za-z_$][\w$]*)\)\{\2\s*=\s*\2\.split\(""\)"""),
        )
        val callPattern =
            Regex("""([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\(\s*[A-Za-z_$][\w$]*\s*(?:,\s*(-?\d+))?\s*\)""")
        for (pattern in sigPatterns) {
            for (match in pattern.findAll(js)) {
                val window = js.substring(
                    match.range.first,
                    minOf(js.length, match.range.first + 1200),
                )
                val joinAt = window.indexOf(".join(\"\")")
                if (joinAt < 0) continue
                val body = window.substring(0, joinAt)
                val calls = callPattern.findAll(body).toList()
                if (calls.isEmpty()) continue
                val helperName = calls.first().groupValues[1]
                val kinds = classifyHelper(js, helperName) ?: continue
                val sequence = mutableListOf<Op>()
                var ok = true
                for (call in calls) {
                    val method = call.groupValues[2]
                    val arg = call.groupValues[3].toIntOrNull() ?: 0
                    when (kinds[method]) {
                        "rev" -> sequence += Op.Reverse
                        "splice" -> sequence += Op.Splice(arg)
                        "swap" -> sequence += Op.Swap(arg)
                        else -> {
                            ok = false
                            break
                        }
                    }
                }
                if (ok && sequence.isNotEmpty()) return sequence
            }
        }
        return null
    }

    /** Classify one helper object's methods by what their bodies
     * do: contains reverse() → "rev", splice(0, → "splice", a
     * modulo-length swap → "swap". */
    private fun classifyHelper(js: String, name: String): Map<String, String>? {
        val start = Regex("""(?:var\s+)?${Regex.escape(name)}\s*=\s*\{""")
            .find(js)?.range?.last ?: return null
        // Brace-match the object literal from its opening brace.
        var depth = 0
        var end = -1
        var i = start
        while (i < js.length) {
            when (js[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
            i++
        }
        if (end < 0) return null
        val literal = js.substring(start, end + 1)
        val methodPattern =
            Regex("""([A-Za-z_$][\w$]*)\s*:\s*function\([^)]*\)\{([^{}]*)\}""")
        val out = mutableMapOf<String, String>()
        for (m in methodPattern.findAll(literal)) {
            val body = m.groupValues[2]
            out[m.groupValues[1]] = when {
                body.contains("reverse") -> "rev"
                body.contains("splice") -> "splice"
                body.contains("%") || body.contains("length") -> "swap"
                else -> "?"
            }
        }
        return out.ifEmpty { null }
    }
}
