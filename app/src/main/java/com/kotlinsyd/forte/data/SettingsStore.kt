package com.kotlinsyd.forte.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ForteSettings(
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    /** Per-network streaming ceilings (Wi-Fi and mobile split). Null =
     * not set separately — [audioQuality] applies, as before. */
    val wifiQuality: AudioQuality? = null,
    val mobileQuality: AudioQuality? = null,
    /** Skip the silent stretches inside songs (player-level). */
    val skipSilence: Boolean = false,
    /** The player's stream-info line (codec · bitrate · engine). */
    val streamInfoLine: Boolean = true,
    /** Split the queue into Previously played / Now playing / Up next. */
    val queueHistory: Boolean = true,
    /** Hide the status bar while the full player is open. */
    val hideStatusInPlayer: Boolean = false,
    /** Let the player artwork run to the physical screen edges. */
    val fullScreenCover: Boolean = false,
    /** Hide the player's "Playing from" origin caption. */
    val hideSongStatus: Boolean = false,
    /** Songs the listener has marked with Dislike on this phone. */
    val dislikedIds: Set<String> = emptySet(),
    /** Calm the app's motion: no colour drift, no shimmer. */
    val reduceAnimation: Boolean = false,
    /** Ask the display for its fastest refresh rate. */
    val highRefreshRate: Boolean = false,
    val downloadQuality: AudioQuality = AudioQuality.HIGH,
    val dataSaver: Boolean = false,
    val backgroundPlay: Boolean = true,
    val wifiOnlyDownloads: Boolean = false,
    val glassEffects: Boolean = true,
    val crashReports: Boolean = true,
    /** Region gl code (see [Regions]); [regionChosen] is false until
     * the owner picks one in the first-open popup (or Settings). */
    val regionGl: String = "",
    val regionChosen: Boolean = false,
    /** Crossfade length in seconds; 0 = off. */
    val crossfadeSec: Int = 0,
    /** Equalizer state: on/off, preset index (-1 = custom/flat),
     * bass boost strength 0..1000 (the audiofx scale). */
    val eqEnabled: Boolean = false,
    val eqPreset: Int = 0,
    val bassBoost: Int = 0,
)

/** Plain SharedPreferences-backed settings. Every value here drives real
 * behavior somewhere in the app — no decorative toggles. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("forte_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ForteSettings> = _settings

    private fun read(): ForteSettings = ForteSettings(
        audioQuality = runCatching {
            AudioQuality.valueOf(prefs.getString("audioQuality", "AUTO")!!)
        }.getOrDefault(AudioQuality.AUTO),
        wifiQuality = prefs.getString("wifiQuality", null)?.let {
            runCatching { AudioQuality.valueOf(it) }.getOrNull()
        },
        mobileQuality = prefs.getString("mobileQuality", null)?.let {
            runCatching { AudioQuality.valueOf(it) }.getOrNull()
        },
        skipSilence = prefs.getBoolean("skipSilence", false),
        streamInfoLine = prefs.getBoolean("streamInfoLine", true),
        queueHistory = prefs.getBoolean("queueHistory", true),
        hideStatusInPlayer = prefs.getBoolean("hideStatusInPlayer", false),
        fullScreenCover = prefs.getBoolean("fullScreenCover", false),
        hideSongStatus = prefs.getBoolean("hideSongStatus", false),
        dislikedIds = prefs.getString("dislikedIds", "")!!
            .split("\n").filter { it.isNotBlank() }.toSet(),
        reduceAnimation = prefs.getBoolean("reduceAnimation", false),
        highRefreshRate = prefs.getBoolean("highRefreshRate", false),
        downloadQuality = runCatching {
            AudioQuality.valueOf(prefs.getString("downloadQuality", "HIGH")!!)
        }.getOrDefault(AudioQuality.HIGH),
        dataSaver = prefs.getBoolean("dataSaver", false),
        backgroundPlay = prefs.getBoolean("backgroundPlay", true),
        wifiOnlyDownloads = prefs.getBoolean("wifiOnlyDownloads", false),
        glassEffects = prefs.getBoolean("glassEffects", true),
        crashReports = prefs.getBoolean("crashReports", true),
        regionGl = prefs.getString("regionGl", "")!!,
        regionChosen = prefs.getBoolean("regionChosen", false),
        crossfadeSec = prefs.getInt("crossfadeSec", 0),
        eqEnabled = prefs.getBoolean("eqEnabled", false),
        eqPreset = prefs.getInt("eqPreset", 0),
        bassBoost = prefs.getInt("bassBoost", 0),
    )

    private fun push() {
        _settings.value = read()
    }

    fun setAudioQuality(q: AudioQuality) {
        prefs.edit().putString("audioQuality", q.name).apply(); push()
    }

    fun setWifiQuality(q: AudioQuality) {
        prefs.edit().putString("wifiQuality", q.name).apply(); push()
    }

    fun setMobileQuality(q: AudioQuality) {
        prefs.edit().putString("mobileQuality", q.name).apply(); push()
    }

    fun setSkipSilence(on: Boolean) {
        prefs.edit().putBoolean("skipSilence", on).apply(); push()
    }

    fun setStreamInfoLine(on: Boolean) {
        prefs.edit().putBoolean("streamInfoLine", on).apply(); push()
    }

    fun setQueueHistory(on: Boolean) {
        prefs.edit().putBoolean("queueHistory", on).apply(); push()
    }

    fun setHideStatusInPlayer(on: Boolean) {
        prefs.edit().putBoolean("hideStatusInPlayer", on).apply(); push()
    }

    fun setFullScreenCover(on: Boolean) {
        prefs.edit().putBoolean("fullScreenCover", on).apply(); push()
    }

    fun setHideSongStatus(on: Boolean) {
        prefs.edit().putBoolean("hideSongStatus", on).apply(); push()
    }

    fun setDislikedIds(ids: Set<String>) {
        prefs.edit().putString("dislikedIds", ids.joinToString("\n")).apply(); push()
    }

    fun setReduceAnimation(on: Boolean) {
        prefs.edit().putBoolean("reduceAnimation", on).apply(); push()
    }

    fun setHighRefreshRate(on: Boolean) {
        prefs.edit().putBoolean("highRefreshRate", on).apply(); push()
    }

    fun setDownloadQuality(q: AudioQuality) {
        prefs.edit().putString("downloadQuality", q.name).apply(); push()
    }

    fun setDataSaver(on: Boolean) {
        prefs.edit().putBoolean("dataSaver", on).apply(); push()
    }

    fun setBackgroundPlay(on: Boolean) {
        prefs.edit().putBoolean("backgroundPlay", on).apply(); push()
    }

    fun setWifiOnlyDownloads(on: Boolean) {
        prefs.edit().putBoolean("wifiOnlyDownloads", on).apply(); push()
    }

    fun setGlassEffects(on: Boolean) {
        prefs.edit().putBoolean("glassEffects", on).apply(); push()
    }

    fun setCrashReports(on: Boolean) {
        prefs.edit().putBoolean("crashReports", on).apply(); push()
    }

    /** The region the owner picked (or the device default before the
     * first-open popup). [RegionState] is kept in step here so the
     * data layer always reads the live value. */
    fun region(): Region =
        if (_settings.value.regionGl.isBlank()) Regions.deviceDefault()
        else Regions.byGl(_settings.value.regionGl)

    fun setRegion(region: Region) {
        RegionState.current = region
        prefs.edit()
            .putString("regionGl", region.gl)
            .putBoolean("regionChosen", true)
            .apply()
        push()
    }

    fun setCrossfadeSec(sec: Int) {
        prefs.edit().putInt("crossfadeSec", sec).apply(); push()
    }

    fun setEq(enabled: Boolean, preset: Int, bassBoost: Int) {
        prefs.edit()
            .putBoolean("eqEnabled", enabled)
            .putInt("eqPreset", preset)
            .putInt("bassBoost", bassBoost)
            .apply()
        push()
    }

    // YouTube account display info (the session itself is the WebView's
    // cookie store; this is only the name/handle shown on the card).
    fun accountName(): String? = prefs.getString("accountName", null)

    fun accountHandle(): String? = prefs.getString("accountHandle", null)

    fun accountAvatar(): String? = prefs.getString("accountAvatar", null)

    fun setAccount(name: String?, handle: String?, avatarUrl: String? = null) {
        prefs.edit()
            .putString("accountName", name)
            .putString("accountHandle", handle)
            .putString("accountAvatar", avatarUrl)
            .apply()
    }

    /** Merge a fresh account fetch into what's stored: only fields
     * the fetch actually proved are written, so a partial fetch
     * (photo without name, name without photo) can never wipe a
     * field an earlier fetch already landed. */
    fun mergeAccount(name: String?, handle: String?, avatarUrl: String?) {
        val e = prefs.edit()
        if (name != null) e.putString("accountName", name)
        if (handle != null) e.putString("accountHandle", handle)
        if (avatarUrl != null) e.putString("accountAvatar", avatarUrl)
        e.apply()
    }

    // Harvested mood-card pictures (category browseId+params → image
    // URL), filled once per category by the Home harvest; the grid
    // itself serves no images, so without this cache every cold
    // start would re-browse two dozen category pages.
    fun moodArt(): Map<String, String> {
        val raw = prefs.getString("moodArt", null) ?: return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val out = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getString(k)
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun putMoodArt(key: String, url: String) {
        val merged = moodArt() + (key to url)
        val obj = org.json.JSONObject()
        merged.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("moodArt", obj.toString()).apply()
    }

    fun clearAccount() {
        prefs.edit().remove("accountName").remove("accountHandle").remove("accountAvatar").apply()
    }

    // Search recents, most recent first, capped at 8.
    fun recents(): List<String> =
        prefs.getString("recents", "")!!.split("\n").filter { it.isNotBlank() }

    fun addRecent(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = (listOf(q) + recents().filter { !it.equals(q, ignoreCase = true) }).take(8)
        prefs.edit().putString("recents", list.joinToString("\n")).apply()
    }

    fun removeRecent(query: String) {
        val list = recents().filter { !it.equals(query, ignoreCase = true) }
        prefs.edit().putString("recents", list.joinToString("\n")).apply()
    }

    fun clearRecents() {
        prefs.edit().remove("recents").apply()
    }
}
