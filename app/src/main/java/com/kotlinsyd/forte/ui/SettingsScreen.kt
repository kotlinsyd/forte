package com.kotlinsyd.forte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import com.kotlinsyd.forte.BuildConfig
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.R
import com.kotlinsyd.forte.data.AudioQuality
import com.kotlinsyd.forte.data.UpdateChecker
import com.kotlinsyd.forte.data.UpdateInfo
import com.kotlinsyd.forte.data.YtAccount
import com.kotlinsyd.forte.data.toJson
import com.kotlinsyd.forte.data.toTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun qualityLabel(q: AudioQuality): String = when (q) {
    AudioQuality.AUTO -> "Auto"
    AudioQuality.HIGH -> "High · up to 256k"
    AudioQuality.MEDIUM -> "Medium · ~96k"
    AudioQuality.LOW -> "Low · ~50k"
}

@Composable
private fun rememberOnWifi(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun current(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    var onWifi by remember { mutableStateOf(current()) }
    androidx.compose.runtime.DisposableEffect(context) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                onWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            override fun onLost(network: android.net.Network) {
                onWifi = current()
            }
        }
        cm?.registerDefaultNetworkCallback(cb)
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return onWifi
}

/**
 * Settings, laid out account first,
 * the two network quality rows with the live one badged, then
 * category rows that open their own pages — with a search box that
 * flattens every setting onto one list. Forte's rows, Forte's face;
 * every control drives the same behaviour it always did.
 */
@Composable
fun SettingsScreen(
    onOpenDownloads: () -> Unit,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val app = ForteApp.instance
    val settings by app.settings.settings.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pickingWifi by remember { mutableStateOf(false) }
    var pickingMobile by remember { mutableStateOf(false) }
    var pickingDownload by remember { mutableStateOf(false) }
    var pickingRegion by remember { mutableStateOf(false) }
    var pickingCrossfade by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val accountTick by app.accountVersion.collectAsState()
    val signedIn = remember(accountTick) { YtAccount.isSignedIn() }
    // The account row's identity, held as state: the store's
    // account fields live outside the settings flow, so a fetch
    // landing must publish here for the row to repaint.
    var accountDisplay by remember {
        mutableStateOf(
            Triple(
                app.settings.accountName(),
                app.settings.accountHandle(),
                app.settings.accountAvatar(),
            )
        )
    }
    // Keep the shown name/handle/avatar fresh from the account itself.
    androidx.compose.runtime.LaunchedEffect(accountTick, signedIn) {
        if (signedIn) {
            YtAccount.accountInfo()?.let { info ->
                app.settings.mergeAccount(info.name, info.handle, info.avatarUrl)
            }
            accountDisplay = Triple(
                app.settings.accountName(),
                app.settings.accountHandle(),
                app.settings.accountAvatar(),
            )
            app.refreshYtLiked()
        }
    }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val onWifi = rememberOnWifi()
    val downloadRows by app.library.downloads.collectAsState(initial = emptyList())
    var imageCacheBytes by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(page) {
        if (page == "downloads") {
            imageCacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    java.io.File(context.cacheDir, "image_cache").walkTopDown()
                        .filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(0L)
            }
        }
    }

    fun exportLibrary() {
        scope.launch {
            runCatching {
                val root = org.json.JSONObject()
                val favArr = org.json.JSONArray()
                app.library.favorites.first().forEach {
                    favArr.put(it.toJson())
                }
                root.put("favorites", favArr)
                val plArr = org.json.JSONArray()
                app.library.playlists.first().forEach { pl ->
                    val pObj = org.json.JSONObject().put("name", pl.name)
                    val tArr = org.json.JSONArray()
                    app.library.playlistTracks(pl.id).first()
                        .forEach { tArr.put(it.toTrack().toJson()) }
                    pObj.put("tracks", tArr)
                    plArr.put(pObj)
                }
                root.put("playlists", plArr)
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Forte library backup", root.toString())
                )
                app.showMessage("Library backup copied — keep it somewhere safe.")
            }.onFailure { app.showMessage("Couldn't make the backup.") }
        }
    }

    fun importLibrary() {
        scope.launch {
            runCatching {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                    ?: throw IllegalStateException("empty clipboard")
                val root = org.json.JSONObject(text)
                var added = 0
                val favIds = app.library.favorites.first()
                    .map { it.id }.toSet()
                val favArr = root.optJSONArray("favorites")
                if (favArr != null) {
                    for (i in 0 until favArr.length()) {
                        val t = favArr.optJSONObject(i)?.toTrack() ?: continue
                        if (t.id !in favIds) {
                            app.library.toggleFavorite(t, false)
                            added++
                        }
                    }
                }
                val plArr = root.optJSONArray("playlists")
                if (plArr != null) {
                    for (i in 0 until plArr.length()) {
                        val pObj = plArr.optJSONObject(i) ?: continue
                        val name = pObj.optString("name").ifBlank { "Imported playlist" }
                        val id = app.library.createPlaylist(name)
                        val tArr = pObj.optJSONArray("tracks") ?: continue
                        for (j in 0 until tArr.length()) {
                            val t = tArr.optJSONObject(j)?.toTrack() ?: continue
                            app.library.addToPlaylist(id, t)
                            added++
                        }
                    }
                }
                app.showMessage("Backup restored — $added songs brought in.")
            }.onFailure {
                app.showMessage("No Forte backup found on the clipboard.")
            }
        }
    }

    // ---- Rows (shared by the pages and by search results) ----
    val accountRow: @Composable () -> Unit = {
        if (signedIn) {
            val name = accountDisplay.first ?: "YouTube account"
            val handle = accountDisplay.second
            SettingsRow(
                icon = Icons.Filled.Person,
                title = name,
                subtitle = listOfNotNull(handle, "Liked songs, playlists and history").joinToString(" · "),
                value = null,
                onClick = onOpenAccount,
            )
        } else {
            SettingsRow(
                icon = Icons.Filled.Person,
                title = "Sign in with Google",
                subtitle = "Optional — brings your liked songs, playlists and history into Library",
                value = null,
                onClick = onOpenLogin,
            )
        }
    }
    val signOutRow: @Composable () -> Unit = {
        if (signedIn) {
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "Sign out",
                subtitle = "Forte keeps working without an account",
                value = null,
                onClick = {
                    YtAccount.signOut()
                    app.settings.clearAccount()
                    app.accountVersion.value++
                    app.refreshYtLiked()
                },
            )
        }
    }
    val wifiQualityRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Wifi,
            title = "On Wi-Fi",
            subtitle = "Streaming quality on Wi-Fi",
            value = qualityLabel(settings.wifiQuality ?: settings.audioQuality) +
                if (onWifi) " · In use" else "",
            onClick = { pickingWifi = true },
        )
    }
    val mobileQualityRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.SignalCellularAlt,
            title = "On mobile data",
            subtitle = "Streaming quality on mobile data",
            value = qualityLabel(settings.mobileQuality ?: settings.audioQuality) +
                if (!onWifi) " · In use" else "",
            onClick = { pickingMobile = true },
        )
    }
    val crossfadeRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.SwapHoriz,
            title = "Crossfade",
            subtitle = "Songs fade into each other",
            value = if (settings.crossfadeSec == 0) "Off" else "${settings.crossfadeSec}s",
            onClick = { pickingCrossfade = true },
        )
    }
    val sleepRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Timer,
            title = "Sleep timer",
            subtitle = "Stop the music after a while",
            value = null,
            onClick = { showSleepTimer = true },
        )
    }
    val backgroundRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.MusicNote,
            title = "Background play",
            subtitle = "Keep playing with screen off",
            checked = settings.backgroundPlay,
            onChange = { app.settings.setBackgroundPlay(it) },
        )
    }
    val dataSaverRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.DataUsage,
            title = "Data saver",
            subtitle = "Lower quality on mobile data",
            checked = settings.dataSaver,
            onChange = { app.settings.setDataSaver(it) },
        )
    }
    val skipSilenceRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.FastForward,
            title = "Skip silence",
            subtitle = "Skip the quiet stretches inside songs",
            checked = settings.skipSilence,
            onChange = { app.settings.setSkipSilence(it) },
        )
    }
    val streamInfoRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.Info,
            title = "Stream info line",
            subtitle = "Codec, bitrate and engine under the song title",
            checked = settings.streamInfoLine,
            onChange = { app.settings.setStreamInfoLine(it) },
        )
    }
    val queueHistoryRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.QueueMusic,
            title = "Previously played in queue",
            subtitle = "Show what already played above Now playing",
            checked = settings.queueHistory,
            onChange = { app.settings.setQueueHistory(it) },
        )
    }
    val hideStatusRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.Fullscreen,
            title = "Hide status bar in player",
            subtitle = "Full-screen music — the clock and icons step aside",
            checked = settings.hideStatusInPlayer,
            onChange = { app.settings.setHideStatusInPlayer(it) },
        )
    }
    val fullScreenCoverRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.Image,
            title = "Full-screen cover",
            subtitle = "Player artwork runs to the left and right edges",
            checked = settings.fullScreenCover,
            onChange = { app.settings.setFullScreenCover(it) },
        )
    }
    val hideSongStatusRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.VisibilityOff,
            title = "Hide song status",
            subtitle = "Hide the Playing from line at the top of the player",
            checked = settings.hideSongStatus,
            onChange = { app.settings.setHideSongStatus(it) },
        )
    }
    val reduceAnimRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.Animation,
            title = "Reduce animation",
            subtitle = "Calms the colour drift and loading shimmer",
            checked = settings.reduceAnimation,
            onChange = { app.settings.setReduceAnimation(it) },
        )
    }
    val refreshRateRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.Speed,
            title = "High refresh rate",
            subtitle = "Use the display's fastest rate for smoother scrolling",
            checked = settings.highRefreshRate,
            onChange = { app.settings.setHighRefreshRate(it) },
        )
    }
    val historyRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.History,
            title = "Listening history",
            subtitle = "Everything you played, most recent first",
            value = null,
            onClick = onOpenHistory,
        )
    }
    val systemEqRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.GraphicEq,
            title = "System equalizer",
            subtitle = "Open the device's own sound effects panel",
            value = null,
            onClick = {
                runCatching {
                    context.startActivity(
                        app.player.systemEqIntent()
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    app.showMessage("This device has no system equalizer panel.")
                }
            },
        )
    }
    val downloadQualityRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Download,
            title = "Download quality",
            subtitle = "Saved file quality",
            value = qualityLabel(settings.downloadQuality),
            onClick = { pickingDownload = true },
        )
    }
    val wifiOnlyRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.Wifi,
            title = "Wi-Fi only downloads",
            subtitle = "Never use mobile data",
            checked = settings.wifiOnlyDownloads,
            onChange = { app.settings.setWifiOnlyDownloads(it) },
        )
    }
    val downloadsStorageRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Folder,
            title = "Downloaded songs",
            subtitle = "${downloadRows.size} songs — in Library, under On this phone",
            value = com.kotlinsyd.forte.data.formatBytes(downloadRows.sumOf { it.sizeBytes }),
            onClick = onOpenDownloads,
        )
    }
    val imageCacheRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Image,
            title = "Artwork cache",
            subtitle = "Cover images Forte keeps for fast loading",
            value = imageCacheBytes?.let { com.kotlinsyd.forte.data.formatBytes(it) } ?: "…",
            onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        java.io.File(context.cacheDir, "image_cache").deleteRecursively()
                    }
                    imageCacheBytes = 0L
                }
                app.showMessage("Artwork cache cleared — covers reload as you browse.")
            },
        )
    }
    val glassRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.BlurOn,
            title = "Liquid glass",
            subtitle = "Frosted glass on the dock and player",
            checked = settings.glassEffects,
            onChange = { app.settings.setGlassEffects(it) },
        )
    }
    val regionRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Language,
            title = "Region",
            subtitle = "Home, charts and search come from this region's YouTube Music",
            value = app.settings.region().name,
            onClick = { pickingRegion = true },
        )
    }
    val statsRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.BarChart,
            title = "Your stats",
            subtitle = "Top artists, top songs, listening time",
            value = null,
            onClick = onOpenStats,
        )
    }
    val exportRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.ContentCopy,
            title = "Back up library",
            subtitle = "Copies your favorites and playlists as text you can keep",
            value = null,
            onClick = { exportLibrary() },
        )
    }
    val importRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.ContentPaste,
            title = "Restore backup",
            subtitle = "Reads a Forte backup from the clipboard",
            value = null,
            onClick = { importLibrary() },
        )
    }
    val clearRecentsRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Clear,
            title = "Clear search history",
            subtitle = "Removes the recent searches shown on the Search page",
            value = null,
            onClick = {
                app.settings.clearRecents()
                app.showMessage("Search history cleared.")
            },
        )
    }
    val crashRow: @Composable () -> Unit = {
        SettingsSwitchRow(
            icon = Icons.Filled.BugReport,
            title = "Crash reports",
            subtitle = "Send anonymous crash reports so bugs get fixed",
            checked = settings.crashReports,
            onChange = {
                app.settings.setCrashReports(it)
                com.kotlinsyd.forte.data.CrashReports.apply(context, it)
            },
        )
    }
    val updatesRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Update,
            title = "Check for updates",
            subtitle = updateStatus ?: "See if a newer Forte is out",
            value = null,
            onClick = {
                updateStatus = "Checking…"
                scope.launch {
                    when (val result = UpdateChecker.checkNow(context)) {
                        is UpdateChecker.Result.Update -> {
                            updateStatus = "Forte ${result.info.version} is out"
                            updateInfo = result.info
                        }
                        UpdateChecker.Result.UpToDate ->
                            updateStatus = "You're on the latest version"
                        UpdateChecker.Result.Offline ->
                            updateStatus = "Couldn't check — no connection"
                    }
                }
            },
        )
    }
    val licensesRow: @Composable () -> Unit = {
        SettingsRow(
            icon = Icons.Filled.Description,
            title = "Open-source licenses",
            subtitle = "What Forte is built with",
            value = null,
            onClick = { showLicenses = true },
        )
    }

    // keyword index for the search box (flattened search)
    val entries: List<Pair<String, @Composable () -> Unit>> = listOf(
        "account sign in google youtube profile" to accountRow,
        "sign out log out account" to signOutRow,
        "audio quality wifi streaming" to wifiQualityRow,
        "audio quality mobile data streaming" to mobileQualityRow,
        "crossfade fade playback songs" to crossfadeRow,
        "sleep timer stop music" to sleepRow,
        "background play screen off" to backgroundRow,
        "data saver mobile data lower quality" to dataSaverRow,
        "skip silence quiet parts playback" to skipSilenceRow,
        "stream info line codec bitrate engine stats nerds" to streamInfoRow,
        "queue history previously played up next" to queueHistoryRow,
        "hide status bar player fullscreen immersive" to hideStatusRow,
        "full screen cover artwork edge player" to fullScreenCoverRow,
        "hide song status playing from origin player" to hideSongStatusRow,
        "reduce animation motion calm shimmer" to reduceAnimRow,
        "high refresh rate performance smooth display hz" to refreshRateRow,
        "listening history played recent songs" to historyRow,
        "system equalizer eq sound effects" to systemEqRow,
        "download quality saved file" to downloadQualityRow,
        "wifi only downloads mobile data" to wifiOnlyRow,
        "downloads storage size space songs" to downloadsStorageRow,
        "artwork cache images clear space" to imageCacheRow,
        "liquid glass appearance frosted dock" to glassRow,
        "region country language home charts search" to regionRow,
        "stats your data top artists listening time" to statsRow,
        "backup export library copy favorites playlists" to exportRow,
        "restore import backup library clipboard" to importRow,
        "clear search history recent" to clearRecentsRow,
        "crash reports bugs anonymous" to crashRow,
        "check updates version new" to updatesRow,
        "licenses open source built with" to licensesRow,
    )
    val searching = query.isNotBlank()
    val matches = if (searching) {
        entries.filter { (kw, _) ->
            query.trim().lowercase().split(" ").all { it in kw }
        }
    } else {
        emptyList()
    }

    val pageTitle = when (page) {
        "playback" -> "Playback"
        "downloads" -> "Downloads"
        "appearance" -> "Appearance"
        "data" -> "Your data"
        "about" -> "About"
        else -> "Settings"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForteColors.Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        if (page == null) {
            // Slim head on the root page (v1.0.14): one back arrow
            // and the big title — sub-pages keep the shared header.
            Row(Modifier.padding(start = 8.dp, top = 10.dp)) {
                IconButton(onClick = { if (searching) query = "" else onBack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back", tint = Color.White,
                    )
                }
            }
            Text(
                "Settings", style = ForteType.pageTitle, color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(14.dp))
        } else {
            ListHeader(pageTitle, null, onBack = { page = null })
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (page == null) {
                // Search settings: typing flattens every match from
                // every category onto this page.
                androidx.compose.material3.TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    placeholder = { Text("Search settings", color = ForteColors.TextDim) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search, contentDescription = null,
                            tint = ForteColors.TextDim, modifier = Modifier.size(22.dp),
                        )
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White, fontSize = 16.sp,
                    ),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = ForteColors.Surface,
                        unfocusedContainerColor = ForteColors.Surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            when {
                searching -> {
                    SettingsSection("RESULTS")
                    if (matches.isEmpty()) {
                        Text(
                            "No setting matches that.",
                            color = ForteColors.TextDim, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    SettingsGroup(matches.map { it.second })
                }
                page == "playback" -> {
                    SettingsSection("PLAYBACK")
                    SettingsGroup(
                        listOf(
                            crossfadeRow, sleepRow, backgroundRow, skipSilenceRow,
                            streamInfoRow, queueHistoryRow, hideSongStatusRow,
                            systemEqRow, dataSaverRow,
                        ),
                    )
                }
                page == "downloads" -> {
                    SettingsSection("DOWNLOADS")
                    SettingsGroup(
                        listOf(
                            downloadQualityRow, wifiOnlyRow,
                            downloadsStorageRow, imageCacheRow,
                        ),
                    )
                }
                page == "appearance" -> {
                    SettingsSection("APPEARANCE")
                    SettingsGroup(
                        listOf(
                            glassRow, fullScreenCoverRow, hideStatusRow,
                            reduceAnimRow, refreshRateRow,
                        ),
                    )
                }
                page == "data" -> {
                    SettingsSection("YOUR DATA")
                    SettingsGroup(
                        listOf(
                            statsRow, historyRow, exportRow,
                            importRow, clearRecentsRow,
                        ),
                    )
                }
                page == "about" -> {
                    SettingsSection("ABOUT")
                    SettingsGroup(listOf(crashRow, updatesRow, licensesRow))
                }
                else -> {
                    SettingsSection("ACCOUNT")
                    // The sign-out row only joins the group when it
                    // renders (signed in) — no phantom divider.
                    SettingsGroup(
                        buildList {
                            add(accountRow)
                            if (signedIn) add(signOutRow)
                        },
                    )
                    SettingsSection("AUDIO QUALITY")
                    SettingsGroup(listOf(wifiQualityRow, mobileQualityRow))
                    SettingsSection("SETTINGS")
                    SettingsGroup(
                        listOf<@Composable () -> Unit>(
                            {
                                SettingsRow(
                                    icon = Icons.Filled.PlayArrow,
                                    title = "Playback",
                                    subtitle = "Crossfade, sleep timer, stream info",
                                    value = null,
                                    onClick = { page = "playback" },
                                )
                            },
                            {
                                SettingsRow(
                                    icon = Icons.Filled.Download,
                                    title = "Downloads",
                                    subtitle = "Quality, Wi-Fi only, storage",
                                    value = null,
                                    onClick = { page = "downloads" },
                                )
                            },
                            {
                                SettingsRow(
                                    icon = Icons.Filled.Palette,
                                    title = "Appearance",
                                    subtitle = "Liquid glass, motion, refresh rate",
                                    value = null,
                                    onClick = { page = "appearance" },
                                )
                            },
                            regionRow,
                            {
                                SettingsRow(
                                    icon = Icons.Filled.Folder,
                                    title = "Your data",
                                    subtitle = "Stats, backup, search history",
                                    value = null,
                                    onClick = { page = "data" },
                                )
                            },
                            {
                                SettingsRow(
                                    icon = Icons.Filled.Info,
                                    title = "About",
                                    subtitle = "Version, updates, licenses",
                                    value = null,
                                    onClick = { page = "about" },
                                )
                            },
                        ),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            // Footer (v1.0.14): plain centred text on the page —
            // no card around it.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.forte_wordmark),
                    contentDescription = "FORTE.",
                    modifier = Modifier.height(26.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
                Text(
                    "Made by Kotlinsyd",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ForteColors.Red,
                )
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://gitlab.com/KotlinJS5/forte"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) {
                    Text("GitLab", color = ForteColors.TextDim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Music plays from YouTube. No account needed — signing in is optional.",
                    fontSize = 12.sp, color = ForteColors.TextDim,
                )
            }
            Spacer(Modifier.height(28.dp + LocalDockClearance.current))
        }
    }

    if (pickingWifi) {
        QualityPicker(
            title = "Quality on Wi-Fi",
            current = settings.wifiQuality ?: settings.audioQuality,
            onDismiss = { pickingWifi = false },
            onPick = {
                app.settings.setWifiQuality(it)
                pickingWifi = false
            },
        )
    }
    if (pickingMobile) {
        QualityPicker(
            title = "Quality on mobile data",
            current = settings.mobileQuality ?: settings.audioQuality,
            onDismiss = { pickingMobile = false },
            onPick = {
                app.settings.setMobileQuality(it)
                pickingMobile = false
            },
        )
    }
    if (pickingDownload) {
        QualityPicker(
            title = "Download quality",
            current = settings.downloadQuality,
            onDismiss = { pickingDownload = false },
            onPick = {
                app.settings.setDownloadQuality(it)
                pickingDownload = false
            },
        )
    }
    if (pickingRegion) {
        RegionPickerDialog(
            current = app.settings.region(),
            onPick = {
                app.settings.setRegion(it)
                pickingRegion = false
            },
            onDismiss = { pickingRegion = false },
        )
    }
    if (pickingCrossfade) {
        AlertDialog(
            onDismissRequest = { pickingCrossfade = false },
            containerColor = ForteColors.SurfaceHi,
            title = { Text("Crossfade", color = Color.White) },
            text = {
                Column {
                    listOf(0, 3, 5, 8, 12).forEach { sec ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    app.settings.setCrossfadeSec(sec)
                                    pickingCrossfade = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sec == settings.crossfadeSec,
                                onClick = {
                                    app.settings.setCrossfadeSec(sec)
                                    pickingCrossfade = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = ForteColors.Red,
                                    unselectedColor = ForteColors.TextDim,
                                ),
                            )
                            Text(
                                if (sec == 0) "Off" else "$sec seconds",
                                color = Color.White, fontSize = 14.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingCrossfade = false }) {
                    Text("Cancel", color = ForteColors.TextDim)
                }
            },
        )
    }
    if (showSleepTimer) {
        SleepTimerDialog(onDismiss = { showSleepTimer = false })
    }
    updateInfo?.let { info ->
        // Same in-app download as the launch offer — no browser.
        UpdateDialog(info = info, onDismiss = { updateInfo = null })
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            containerColor = ForteColors.SurfaceHi,
            title = { Text("Built with", color = Color.White) },
            text = {
                Text(
                    "Forte Engine — our own YouTube Music engine\n" +
                        "Media3 / ExoPlayer (Apache 2.0) — playback\n" +
                        "Coil (Apache 2.0) — images\n" +
                        "Room & Jetpack Compose (Apache 2.0) — library and UI\n" +
                        "OkHttp (Apache 2.0) — networking\n" +
                        "Lyrics by LRCLIB, a free community database.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text("Close", color = ForteColors.Red)
                }
            },
        )
    }
}

/** Region picker: the full country list, one tap. Used by the
 * first-open popup and by Settings → Region. */
@Composable
fun RegionPickerDialog(
    current: com.kotlinsyd.forte.data.Region,
    onPick: (com.kotlinsyd.forte.data.Region) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForteColors.SurfaceHi,
        title = { Text("Your region", color = Color.White) },
        text = {
            Column {
                Text(
                    "Forte shows the YouTube Music home for your region.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.height(340.dp),
                ) {
                    items(com.kotlinsyd.forte.data.Regions.all) { region ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(region) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = region.gl == current.gl,
                                onClick = { onPick(region) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = ForteColors.Red,
                                    unselectedColor = ForteColors.TextDim,
                                ),
                            )
                            Text(region.name, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Not now", color = ForteColors.TextDim) }
        },
    )
}

@Composable
private fun SettingsSection(title: String) {
    // Quiet grey group label (v1.0.14) — the red accent lives in
    // the row icons now, not in a shouting header.
    Text(
        title,
        style = ForteType.groupLabel,
        color = ForteColors.TextDim,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/**
 * One settings group: its rows sit together on a single 20dp-radius
 * surface card, split by hairlines, instead of floating loose on
 * the black background (v1.0.14).
 */
@Composable
private fun SettingsGroup(rows: List<@Composable () -> Unit>) {
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ForteColors.Surface)
            .padding(vertical = 4.dp),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 54.dp, end = 16.dp)
                        .height(1.dp)
                        .background(ForteColors.Border),
                )
            }
            row()
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String?,
    onClick: () -> Unit,
) {
    // Grouped row shell (v1.0.14): 56dp minimum, a red-tinted
    // leading icon, the type scale's row sizes, and a real chevron
    // icon (the old build drew a " ›" text character, and only
    // when the row carried a value).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = ForteColors.Red.copy(alpha = 0.9f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ForteType.rowTitle, color = Color.White)
            Text(subtitle, style = ForteType.rowSub, color = ForteColors.TextDim)
        }
        if (value != null) {
            Text(value, fontSize = 13.sp, color = ForteColors.TextDim)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.Filled.KeyboardArrowRight, contentDescription = null,
            tint = ForteColors.TextDim, modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = ForteColors.Red.copy(alpha = 0.9f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ForteType.rowTitle, color = Color.White)
            Text(subtitle, style = ForteType.rowSub, color = ForteColors.TextDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ForteColors.Red,
                uncheckedThumbColor = ForteColors.TextDim,
                uncheckedTrackColor = ForteColors.SurfaceHi,
            ),
        )
    }
}

@Composable
private fun QualityPicker(
    title: String,
    current: AudioQuality,
    onDismiss: () -> Unit,
    onPick: (AudioQuality) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForteColors.SurfaceHi,
        title = { Text(title, color = Color.White) },
        text = {
            Column {
                AudioQuality.entries.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(q) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = q == current,
                            onClick = { onPick(q) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = ForteColors.Red,
                                unselectedColor = ForteColors.TextDim,
                            ),
                        )
                        Text(qualityLabel(q), color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = ForteColors.TextDim) }
        },
    )
}
