package com.kotlinsyd.forte.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.YtAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3" +
        "&passive=true&continue=https://music.youtube.com/"

/**
 * The cannot-miss identity fallback, run inside the login WebView
 * on the signed-in music.youtube.com page itself. The page renders
 * the account's avatar in its nav bar before any menu opens, and
 * the account menu (opened here with one scripted tap on the
 * avatar/settings button, once per page) renders the name and
 * handle/email as plain text lines. Whatever the API chain could
 * not prove, the page the user is already looking at can.
 */
private const val PAGE_PROFILE_JS = """
(function() {
  try {
    var clean = function(v) { return String(v || '').replace(/\s+/g, ' ').trim(); };
    var navImg = document.querySelector('ytmusic-nav-bar img[src], #avatar-btn img[src], ytmusic-settings-button img[src]');
    var avatar = navImg ? (navImg.currentSrc || navImg.src || '') : '';
    if (!avatar) {
      var imgs = document.querySelectorAll('ytmusic-nav-bar img[src], header img[src], nav img[src]');
      for (var k = 0; k < imgs.length; k++) {
        var s = imgs[k].currentSrc || imgs[k].src || '';
        if (s.indexOf('googleusercontent.com') >= 0 || s.indexOf('ggpht.com') >= 0) { avatar = s; break; }
      }
    }
    var area = document.querySelector('ytmusic-account-info, ytd-active-account-header-renderer, yt-multi-page-menu-header-renderer, ytmusic-multi-page-menu-renderer, tp-yt-iron-dropdown');
    if (!area) {
      var btn = document.querySelector('ytmusic-settings-button button, #avatar-btn, button[aria-label*="Account" i]');
      if (btn && !window.__forteProfileClicked) {
        window.__forteProfileClicked = true;
        btn.click();
      }
      return JSON.stringify({ name: '', handle: '', avatarUrl: avatar.indexOf('data:') === 0 ? '' : avatar });
    }
    var img = area.querySelector('img[src]');
    if (img) avatar = img.currentSrc || img.src || avatar;
    var lines = String(area.innerText || '').split('\n').map(clean).filter(function(s) { return s.length > 0; });
    var handle = '';
    var name = '';
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].charAt(0) === '@') { handle = lines[i]; break; }
    }
    var ignored = /^(account|manage your google account|switch account|sign out|youtube|youtube music|your channel|google account|settings)$/i;
    for (var j = 0; j < lines.length; j++) {
      var l = lines[j];
      if (l !== handle && !ignored.test(l) &&
          !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(l) &&
          !/subscribers/i.test(l)) { name = l; break; }
    }
    return JSON.stringify({ name: name, handle: handle, avatarUrl: avatar.indexOf('data:') === 0 ? '' : avatar });
  } catch (e) {
    return JSON.stringify({ name: '', handle: '', avatarUrl: '' });
  }
})()
"""

/** Probe the signed-in page in [webView] for the identity. */
private suspend fun probePageIdentity(webView: WebView): YtAccount.AccountInfo? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(PAGE_PROFILE_JS) { raw ->
            if (!cont.isActive) return@evaluateJavascript
            val info = runCatching {
                val payload = org.json.JSONArray("[$raw]").optString(0)
                val o = org.json.JSONObject(payload)
                YtAccount.AccountInfo(
                    name = o.optString("name").ifBlank { null },
                    handle = o.optString("handle").ifBlank { null },
                    avatarUrl = YtAccount.biggerAvatar(
                        o.optString("avatarUrl").ifBlank { null },
                    ),
                ).takeIf {
                    it.name != null || it.handle != null || it.avatarUrl != null
                }
            }.getOrNull()
            cont.resume(info) {}
        }
    }

/**
 * Google's real sign-in page in an in-app WebView. Forte never renders
 * a password field of its own — the user signs in with Google, picks
 * the channel/profile they want on Google's pages, and confirms here
 * before the WebView closes. Only the session cookies are kept.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var account by remember { mutableStateOf<YtAccount.AccountInfo?>(null) }
    var checking by remember { mutableStateOf(false) }

    // Watch the cookie store: once Google has issued a session, fetch
    // who it belongs to and let the USER confirm before closing.
    // The fetch is the identity CHAIN (YtAccount.accountInfo); while
    // it still lacks the name or the photo, the signed-in page in
    // this WebView is probed as the last resort, and polling keeps
    // going until the identity is complete or the user backs out.
    LaunchedEffect(Unit) {
        while (true) {
            if (YtAccount.isSignedIn() &&
                (account?.name == null || account?.avatarUrl == null)
            ) {
                checking = true
                account = YtAccount.mergeAccountInfo(account, YtAccount.accountInfo())
                if (account?.name == null || account?.avatarUrl == null) {
                    webView?.let { wv ->
                        account = YtAccount.mergeAccountInfo(
                            account, probePageIdentity(wv),
                        )
                    }
                }
                checking = false
                // Cookies can land a moment before the profile does;
                // if nothing resolved, keep polling quietly.
                if (account == null) delay(3000)
            }
            delay(1500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ListHeader("Sign in", "Use your Google account on Google's own page", onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // No user-agent override: Google rejects sign-in
                        // from browsers whose UA doesn't match a real
                        // mobile browser ("This browser or app may not
                        // be secure"). The device's default WebView UA
                        // is a genuine mobile Chrome — leave it alone.
                        // The desktop UA in YtAccount is for the
                        // InnerTube API calls only, never this page.
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(LOGIN_URL)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (account != null || checking) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ForteColors.Surface)
                    .padding(16.dp),
            ) {
                if (checking && account == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = ForteColors.Red, modifier = Modifier.size(18.dp))
                        Text(
                            "  Checking your account…",
                            color = Color.White, fontSize = 14.sp,
                        )
                    }
                } else if (account != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        account!!.avatarUrl?.let { url ->
                            coil.compose.AsyncImage(
                                model = url,
                                contentDescription = "Your account photo",
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(
                                "Signed in as ${account!!.name ?: account!!.handle ?: "your account"}",
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            )
                            account!!.handle?.let {
                                Text(it, color = ForteColors.TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ForteColors.Red)
                                .clickable {
                                    app.settings.mergeAccount(
                                        account!!.name, account!!.handle, account!!.avatarUrl,
                                    )
                                    app.refreshYtLiked()
                                    onDone()
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text("Use this account", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ForteColors.SurfaceHi)
                                .clickable {
                                    scope.launch {
                                        YtAccount.signOut()
                                        app.settings.clearAccount()
                                        account = null
                                        webView?.loadUrl(LOGIN_URL)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text("Not you?", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

@Composable
fun YtLibraryScreen(
    kind: String,
    title: String,
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenYtPlaylistTracks: (String, String) -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var tracks by remember(kind) { mutableStateOf<List<Track>?>(null) }
    var playlists by remember(kind) { mutableStateOf<List<YtAccount.AccountPlaylist>?>(null) }
    var artists by remember(kind) { mutableStateOf<List<YtAccount.HomeCard>?>(null) }
    var albums by remember(kind) { mutableStateOf<List<YtAccount.HomeCard>?>(null) }
    var error by remember(kind) { mutableStateOf<String?>(null) }

    LaunchedEffect(kind) {
        // Last-good first, fresh behind it (owner's loading rule):
        // a slow account call must not hold this screen on skeletons
        // when it already showed this list before.
        val trackKey = "ytlib:$kind"
        if (tracks == null && playlists == null) {
            if (kind == "albums") {
                albums = com.kotlinsyd.forte.data.LastGood
                    .getArtists(context, "ytlib:albums")
                    ?.map { ref ->
                        YtAccount.HomeCard(
                            ref.url, ref.name, ref.detail, ref.thumbUrl,
                        )
                    }
            }
            tracks = com.kotlinsyd.forte.data.LastGood.getTracks(context, trackKey)
            if (kind == "playlists") {
                playlists = com.kotlinsyd.forte.data.LastGood.get(context, "ytlib:playlists")
                    ?.let { raw ->
                        runCatching {
                            val arr = org.json.JSONArray(raw)
                            val out = mutableListOf<YtAccount.AccountPlaylist>()
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                out += YtAccount.AccountPlaylist(
                                    o.optString("browseId"), o.optString("title"),
                                    o.optString("subtitle").takeIf { it.isNotBlank() },
                                    o.optString("thumbUrl").takeIf { it.isNotBlank() },
                                )
                            }
                            out.takeIf { it.isNotEmpty() }
                        }.getOrNull()
                    }
            }
        }
        runCatching {
            when (kind) {
                "liked" -> tracks = YtAccount.likedSongs()
                "history" -> tracks = YtAccount.history()
                // Subscriptions = the artists/channels the account
                // follows (a card shelf, not a track list — the
                // shape that actually exists on the server).
                "subs" -> artists = YtAccount.subscribedArtists()
                "albums" -> albums = YtAccount.likedAlbums()
                "playlists" -> playlists = YtAccount.playlists()
                else -> if (kind.startsWith("playlist:")) {
                    tracks = YtAccount.playlistTracks(kind.removePrefix("playlist:"))
                }
            }
        }.onSuccess {
            albums?.let { list ->
                com.kotlinsyd.forte.data.LastGood.putArtists(
                    context,
                    "ytlib:albums",
                    list.map {
                        com.kotlinsyd.forte.data.ArtistRef(
                            url = it.browseId,
                            name = it.title,
                            thumbUrl = it.thumbUrl,
                            detail = it.subtitle,
                        )
                    },
                )
            }
            tracks?.let { com.kotlinsyd.forte.data.LastGood.putTracks(context, trackKey, it) }
            playlists?.let { list ->
                val arr = org.json.JSONArray()
                list.forEach { p ->
                    arr.put(
                        org.json.JSONObject()
                            .put("browseId", p.browseId)
                            .put("title", p.title)
                            .put("subtitle", p.subtitle ?: org.json.JSONObject.NULL)
                            .put("thumbUrl", p.thumbUrl ?: org.json.JSONObject.NULL),
                    )
                }
                com.kotlinsyd.forte.data.LastGood.put(context, "ytlib:playlists", arr.toString())
            }
        }.onFailure {
            if (tracks == null && playlists == null && artists == null && albums == null) {
                error = "Couldn't load this from your YouTube account."
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ListHeader(title, "From your YouTube account", onBack)
        when {
            error != null -> CenterText(error!!)
            tracks == null && playlists == null && artists == null && albums == null ->
                CenterLoading()
            albums != null -> {
                if (albums!!.isEmpty()) {
                    CenterText("No saved albums found on this account.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current)) {
                        items(albums!!) { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenYtPlaylistTracks(album.browseId, album.title)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(album.thumbUrl, album.title, 52.dp, corner = 12.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        album.title, color = Color.White, fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    album.subtitle?.let {
                                        Text(it, color = ForteColors.TextDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            artists != null -> {
                if (artists!!.isEmpty()) {
                    CenterText("No subscriptions found on this account.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current)) {
                        items(artists!!) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenArtist(artist.browseId) }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(artist.thumbUrl, artist.title, 52.dp, corner = 26.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        artist.title, color = Color.White, fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    artist.subtitle?.let {
                                        Text(it, color = ForteColors.TextDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            playlists != null -> {
                if (playlists!!.isEmpty()) {
                    CenterText("No saved playlists found on this account.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current)) {
                        items(playlists!!) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenYtPlaylistTracks(playlist.browseId, playlist.title)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(playlist.thumbUrl, playlist.title, 52.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        playlist.title, color = Color.White, fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    playlist.subtitle?.let {
                                        Text(it, color = ForteColors.TextDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            tracks != null -> {
                if (tracks!!.isEmpty()) {
                    CenterText("Nothing here yet on this account.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalDockClearance.current)) {
                        items(tracks!!) { track ->
                            TrackRow(
                                track = track,
                                actions = actions,
                                isFavorite = track.id in favoriteIds,
                                isCurrent = track.id == currentTrackId,
                                isPlaying = isPlaying,
                                onClick = { actions.play(track, tracks!!, title) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Account screen behind the top-right profile icon: who is
 * signed in (avatar, name, handle), the account's shelves, and sign
 * out. Signed out, it's the front door to the optional sign-in.
 */
@Composable
fun AccountScreen(
    onOpenLogin: () -> Unit,
    onOpenYtLibrary: (String, String) -> Unit,
    onOpenStats: () -> Unit,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val accountTick by app.accountVersion.collectAsState()
    val signedIn = remember(accountTick) { YtAccount.isSignedIn() }
    var info by remember(accountTick) { mutableStateOf<YtAccount.AccountInfo?>(null) }
    LaunchedEffect(accountTick, signedIn) {
        if (signedIn) {
            info = YtAccount.accountInfo()
            info?.let { app.settings.mergeAccount(it.name, it.handle, it.avatarUrl) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ListHeader("Account", null, onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            if (signedIn) {
                // Identity card (v1.0.14): left-aligned, with the
                // sign-out as a ghost pill at the card's right —
                // the page reads as a profile, not a prototype.
                val avatar = info?.avatarUrl ?: app.settings.accountAvatar()
                val name = info?.name ?: app.settings.accountName() ?: "YouTube account"
                val handle = info?.handle ?: app.settings.accountHandle()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(ForteColors.Surface)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!avatar.isNullOrBlank()) {
                        coil.compose.AsyncImage(
                            model = avatar,
                            contentDescription = "Account photo",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(ForteColors.SurfaceHi),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name.trim().firstOrNull()?.uppercase() ?: "F",
                                color = Color.White, fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name, color = Color.White, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        handle?.let {
                            Text(
                                it, color = ForteColors.TextDim, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, ForteColors.Border, RoundedCornerShape(50))
                            .clickable {
                                YtAccount.signOut()
                                app.settings.clearAccount()
                                app.ytLikedIds.value = emptySet()
                                app.accountVersion.value++
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Sign out", color = ForteColors.Red,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                AccountGroup(
                    {
                        AccountLink(Icons.Filled.Favorite, "Liked songs", "From your account") {
                            onOpenYtLibrary("liked", "Liked songs")
                        }
                    },
                    {
                        AccountLink(Icons.Filled.List, "Your playlists", "Saved on YouTube") {
                            onOpenYtLibrary("playlists", "Your playlists")
                        }
                    },
                    {
                        AccountLink(Icons.Filled.Person, "Subscriptions", "Artists & channels you follow") {
                            onOpenYtLibrary("subs", "Subscriptions")
                        }
                    },
                    {
                        AccountLink(Icons.Filled.History, "Watch history", "Watched on YouTube") {
                            onOpenYtLibrary("history", "Watch history")
                        }
                    },
                    {
                        AccountLink(Icons.Filled.BarChart, "Your Forte stats", "Top artists, songs and time") {
                            onOpenStats()
                        }
                    },
                )
            } else {
                Text(
                    "You're not signed in",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Forte works fully without an account. Sign in only if you want your YouTube likes, playlists, subscriptions and history here.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ForteColors.Red)
                        .clickable { onOpenLogin() }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text("Sign in with Google", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                AccountGroup(
                    {
                        AccountLink(Icons.Filled.BarChart, "Your Forte stats", "Top artists, songs and time") {
                            onOpenStats()
                        }
                    },
                )
            }
        }
    }
}

/** The account page's links as one grouped card (v1.0.14): a
 * 20dp-radius surface, hairline dividers, plain rows inside. */
@Composable
private fun AccountGroup(vararg rows: @Composable () -> Unit) {
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
private fun AccountLink(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
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
        Icon(
            Icons.Filled.KeyboardArrowRight, contentDescription = null,
            tint = ForteColors.TextDim, modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CenterLoading() {
    // Skeleton rows, not a lone spinner — the app's one loading
    // language (owner's rule).
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SkeletonRows(6)
    }
}

@Composable
private fun CenterText(text: String) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ForteColors.TextDim, fontSize = 14.sp)
    }
}
