package com.kotlinsyd.forte.data

import android.content.Context
import com.kotlinsyd.forte.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Anonymous install + activity counting on the owner's own stats
 * server, in FORTE's OWN lane: every ping carries flavor=forte, and
 * the worker records Forte under forte_-prefixed counters only —
 * no other app's install/DAU numbers are ever touched by a Forte ping.
 *
 * What is sent: a random install UUID (generated on the device; it
 * identifies no person), the app version and the flavor — nothing else.
 *   kind=install — once per install (first launch)
 *   kind=session — every launch
 * Failures are silent and never block the app; a failed install ping
 * is retried on a later launch.
 */
object AppStats {
    private const val ENDPOINT = "https://plaviohd.edon.workers.dev/app-ping"
    const val FLAVOR = "forte"
    private const val PREFS = "forte_stats"
    private const val KEY_UUID = "install_uuid"
    private const val KEY_COUNTED = "install_counted"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun uuid(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_UUID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_UUID, it).apply()
        }
    }

    private fun ping(context: Context, kind: String): Boolean {
        return try {
            val query = "uuid=${URLEncoder.encode(uuid(context), "UTF-8")}" +
                "&ver=${URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")}" +
                "&flavor=$FLAVOR" +
                "&kind=$kind"
            val conn = URL("$ENDPOINT?$query").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            // Drain the response so the hit registers server-side.
            conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Launch ping: the one-time install count, plus a session tick. */
    suspend fun trackLaunch(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val p = prefs(appCtx)
        if (!p.getBoolean(KEY_COUNTED, false)) {
            if (ping(appCtx, "install")) {
                p.edit().putBoolean(KEY_COUNTED, true).apply()
            }
        }
        ping(appCtx, "session")
    }
}
