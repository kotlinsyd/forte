package com.kotlinsyd.forte.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Info about a newer release found via the stable pointer. */
data class UpdateInfo(
    val version: String,
    val code: Int,
    val notes: String,
    val apkUrl: String,
)

/**
 * In-app updater: reads the stable pointer (stable.json at the
 * root of the Forte GitLab repo) and offers the new APK in-app when
 * the feed's versionCode exceeds the installed one. Only versions the
 * owner marks stable ever appear there; dev builds stay silent.
 * Silent on any failure.
 */
object UpdateChecker {
    const val STABLE_URL = "https://gitlab.com/KotlinJS5/forte/-/raw/main/stable.json"
    private const val PREFS = "forte_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED = "skipped_code"
    /** Automatic launch checks are throttled — at most one per day. */
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    sealed interface Result {
        data class Update(val info: UpdateInfo) : Result
        data object UpToDate : Result
        data object Offline : Result
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.longVersionCode.toInt()
    }.getOrDefault(0)

    /** Automatic check on launch: daily throttle + dismissed versions. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val last = p.getLong(KEY_LAST_CHECK, 0L)
        if (last > 0 && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
            return@withContext null
        }
        val info = runCatching { fetch(context) }.getOrNull()
        if (info != null) {
            p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (info.code == p.getInt(KEY_SKIPPED, 0)) return@withContext null
        }
        info
    }

    /** Manual check (Settings): ignores the throttle and dismissals. */
    suspend fun checkNow(context: Context): Result = withContext(Dispatchers.IO) {
        runCatching {
            val info = fetch(context)
            prefs(context).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (info != null) Result.Update(info) else Result.UpToDate
        }.getOrElse { Result.Offline }
    }

    /** Remember that the user dismissed this version ("Later"). */
    fun skip(context: Context, code: Int) {
        prefs(context).edit().putInt(KEY_SKIPPED, code).apply()
    }

    /** Returns the feed's release when it is newer than installed. */
    private fun fetch(context: Context): UpdateInfo? {
        val conn = (URL(STABLE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Forte/${installedCode(context)}")
        }
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.length > 65536) return null
            val j = JSONObject(text)
            val code = j.optInt("code", 0)
            val version = j.optString("version").removePrefix("v")
            val apkUrl = j.optString("apkUrl")
            if (code <= 0 || version.isBlank() || apkUrl.isBlank()) return null
            if (code <= installedCode(context)) return null
            return UpdateInfo(version, code, j.optString("notes"), apkUrl)
        } finally {
            conn.disconnect()
        }
    }
}
