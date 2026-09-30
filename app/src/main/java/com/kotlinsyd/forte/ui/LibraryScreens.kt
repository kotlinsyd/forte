package com.kotlinsyd.forte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.YtAccount
import com.kotlinsyd.forte.data.formatDuration
import com.kotlinsyd.forte.data.db.PlaylistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenDownloads: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenYtLibrary: (String, String) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val app = ForteApp.instance
    val favorites by app.library.favorites.collectAsState(initial = emptyList())
    val playlists by app.library.playlists.collectAsState(initial = emptyList())
    val follows by app.library.follows.collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val downloads by app.library.downloads.collectAsState(initial = emptyList())
    val historyRows by app.library.historyRows.collectAsState(initial = emptyList())
    val historyTracks by app.library.history.collectAsState(initial = emptyList())
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var libraryKind by remember { mutableStateOf("All") }
    var downloadedOnly by remember { mutableStateOf(false) }
    val downloadIds = remember(downloads) { downloads.map { it.videoId }.toSet() }
    val downloadTracks = remember(downloads) { downloads.map { it.toTrack() } }
    val librarySongs = remember(favorites, historyTracks, downloadTracks) {
        (favorites + historyTracks + downloadTracks).distinctBy { it.id }
    }
    val visibleSongs = remember(librarySongs, downloadedOnly, downloadIds) {
        if (downloadedOnly) librarySongs.filter { it.id in downloadIds } else librarySongs
    }
    val visibleFollows = remember(follows, downloadedOnly, downloadTracks) {
        if (!downloadedOnly) {
            follows
        } else {
            follows.filter { follow ->
                downloadTracks.any {
                    it.artist.equals(follow.name, ignoreCase = true)
                }
            }
        }
    }
    var downloadedPlaylistIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(playlists, downloadIds) {
        val found = mutableSetOf<Long>()
        playlists.forEach { playlist ->
            val tracks = runCatching {
                app.library.playlistTracks(playlist.id).first()
            }.getOrDefault(emptyList())
            if (tracks.any { it.videoId in downloadIds }) found += playlist.id
        }
        downloadedPlaylistIds = found
    }
    val visiblePlaylists = remember(playlists, downloadedOnly, downloadedPlaylistIds) {
        if (downloadedOnly) {
            playlists.filter { it.id in downloadedPlaylistIds }
        } else {
            playlists
        }
    }
    val accountTick by app.accountVersion.collectAsState()
    val signedIn = remember(accountTick) { YtAccount.isSignedIn() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current),
    ) {
        item {
            Text(
                "Library",
                style = ForteType.pageTitle, color = Color.White,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 14.dp),
            )
            // Personal listening card: real numbers from this
            // phone's own play history, labelled with that scope.
            val totalMinutes = historyRows.sumOf { it.durationSec } / 60
            val topSong = historyRows.groupBy { it.videoId }
                .maxByOrNull { it.value.size }?.value?.firstOrNull()
            val topSongPlays = topSong?.let { song ->
                historyRows.count { it.videoId == song.videoId }
            } ?: 0
            val topArtist = historyRows.groupBy { it.artist }
                .maxByOrNull { it.value.size }?.key
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                ForteColors.Red.copy(alpha = 0.30f),
                                ForteColors.Surface,
                            ),
                        ),
                    )
                    .clickable { onOpenStats() }
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$totalMinutes min",
                            fontSize = 25.sp, fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            "listened · ${historyRows.size} plays",
                            fontSize = 13.sp, color = ForteColors.TextDim,
                        )
                    }
                    Text("♪", fontSize = 28.sp, color = ForteColors.Red)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        topSong != null && topArtist != null ->
                            "Top song · ${topSong.title} ($topSongPlays plays) · Top artist · $topArtist"
                        topSong != null ->
                            "Top song · ${topSong.title} ($topSongPlays plays)"
                        else -> "Play a song and your listening card starts here."
                    },
                    fontSize = 13.sp, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "From your recent plays on this phone.",
                    fontSize = 13.sp, color = ForteColors.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your stats ›",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = ForteColors.Red,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "On this phone",
                style = ForteType.section, color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LibraryCard(
                    "Downloads",
                    if (downloads.isEmpty()) {
                        "Nothing saved yet"
                    } else if (downloads.size == 1) {
                        "1 song saved"
                    } else {
                        "${downloads.size} songs saved"
                    },
                    Icons.Filled.Download,
                    Modifier.weight(1f),
                    onOpenDownloads,
                )
                LibraryCard(
                    "History",
                    if (historyTracks.size == 1) "1 song" else "${historyTracks.size} songs",
                    Icons.Filled.History,
                    Modifier.weight(1f),
                    onOpenHistory,
                )
            }
            Spacer(Modifier.height(18.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf("All", "Songs", "Artists", "Albums", "Playlists")) { kind ->
                    LibraryKindChip(
                        label = kind,
                        selected = libraryKind == kind,
                    ) { libraryKind = kind }
                }
                item {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (downloadedOnly) ForteColors.Red else ForteColors.Surface,
                            )
                            .clickable { downloadedOnly = !downloadedOnly }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (downloadedOnly) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.padding(end = 5.dp),
                                )
                            }
                            Text(
                                "Downloaded",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // No second shortcut grid (v1.0.14): the kind chips and
            // the content sections below already reach everything
            // the grid duplicated (Favorites, Playlists, History
            // again, Artists, Stats) — the first content now lands
            // within about a screen of the top.
        }
        if (downloadedOnly && (libraryKind == "All" || libraryKind == "Albums")) {
            item {
                Text(
                    "Downloaded songs",
                    style = ForteType.section, color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                if (libraryKind == "Albums") {
                    Text(
                        "Albums are grouped on YouTube; this filter shows the songs saved on this phone.",
                        fontSize = 12.sp, color = ForteColors.TextDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            if (downloadTracks.isEmpty()) {
                item {
                    Text(
                        "Nothing downloaded yet.",
                        color = ForteColors.TextDim, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(downloadTracks) { track ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = track.id in favoriteIds,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { actions.play(track, downloadTracks, "Downloads") },
                    )
                }
            }
        } else if (libraryKind == "Songs") {
            item {
                Text(
                    "Songs",
                    style = ForteType.section, color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    "${visibleSongs.size} songs from favorites, history and downloads",
                    fontSize = 12.sp, color = ForteColors.TextDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (visibleSongs.isEmpty()) {
                item {
                    Text(
                        if (downloadedOnly) {
                            "No downloaded songs yet."
                        } else {
                            "No songs yet. Favorites and plays will gather here."
                        },
                        color = ForteColors.TextDim, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(visibleSongs) { track ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = track.id in favoriteIds,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { actions.play(track, visibleSongs, "Songs") },
                    )
                }
            }
        } else if (libraryKind == "Artists") {
            item {
                Text(
                    "Artists",
                    style = ForteType.section, color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (visibleFollows.isEmpty()) {
                item {
                    Text(
                        if (downloadedOnly) {
                            "No followed artist has a downloaded song yet."
                        } else {
                            "No artists followed yet."
                        },
                        color = ForteColors.TextDim, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(visibleFollows) { follow ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenArtist(follow.channelUrl) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(follow.avatarUrl, follow.name, 52.dp, corner = 26.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        follow.name, color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            item {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    LibraryCard(
                        "Subscriptions",
                        "Artists & channels from your YouTube account",
                        Icons.Filled.Person,
                        Modifier.fillMaxWidth(),
                    ) { onOpenYtLibrary("subs", "Subscriptions") }
                }
            }
        } else if (libraryKind == "Albums") {
            item {
                Text(
                    "Albums",
                    style = ForteType.section, color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.padding(horizontal = 20.dp)) {
                    if (signedIn) {
                        LibraryCard(
                            "Liked albums",
                            "Albums saved on your YouTube account",
                            Icons.Filled.List,
                            Modifier.fillMaxWidth(),
                        ) { onOpenYtLibrary("albums", "Liked albums") }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(ForteColors.Surface)
                                .clickable { onOpenLogin() }
                                .padding(16.dp),
                        ) {
                            Text(
                                "Your albums live on YouTube",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            Text(
                                "Sign in to see the albums you saved there.",
                                fontSize = 12.sp, color = ForteColors.TextDim,
                            )
                        }
                    }
                }
            }
        } else if (libraryKind == "Playlists") {
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    if (signedIn) {
                        LibraryCard(
                            "YT playlists",
                            "Playlists saved on your YouTube account",
                            Icons.Filled.List,
                            Modifier.fillMaxWidth(),
                        ) { onOpenYtLibrary("playlists", "Your playlists") }
                    } else {
                        Text(
                            "Your playlists on this phone are below. Sign in to add your YouTube playlists too.",
                            color = ForteColors.TextDim, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        item {
            if (libraryKind == "All" && !downloadedOnly) {
            // YouTube account layer (optional): liked songs, saved
            // playlists and watch history from the signed-in account.
            val accountTick by app.accountVersion.collectAsState()
            val signedIn = remember(accountTick) { YtAccount.isSignedIn() }
            Spacer(Modifier.height(24.dp))
            Text(
                "Your YouTube", style = ForteType.section,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (signedIn) {
                val name = app.settings.accountName()
                if (name != null) {
                    Text(
                        "Signed in as $name",
                        color = ForteColors.TextDim, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LibraryCard("Liked songs", "From your account", Icons.Filled.Favorite, Modifier.weight(1f)) {
                        onOpenYtLibrary("liked", "Liked songs")
                    }
                    LibraryCard("YT playlists", "Saved on YouTube", Icons.Filled.List, Modifier.weight(1f)) {
                        onOpenYtLibrary("playlists", "Your playlists")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LibraryCard("YT history", "Watched on YouTube", Icons.Filled.History, Modifier.weight(1f)) {
                        onOpenYtLibrary("history", "Watch history")
                    }
                    LibraryCard("Subscriptions", "Artists & channels you follow", Icons.Filled.Person, Modifier.weight(1f)) {
                        onOpenYtLibrary("subs", "Subscriptions")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(ForteColors.Surface)
                        .clickable { onOpenLogin() }
                        .padding(16.dp),
                ) {
                    Text(
                        "See your YouTube library here",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    )
                    Text(
                        "Sign in to add your liked songs, playlists and history. Forte works fine without it.",
                        fontSize = 12.sp, color = ForteColors.TextDim,
                    )
                }
            }
                    }
        }
        item {
            if ((libraryKind == "All" && !downloadedOnly) || libraryKind == "Playlists") {
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Your playlists", style = ForteType.section,
                    color = Color.White, modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ForteColors.SurfaceHi)
                        .clickable { showImport = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text("Import", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ForteColors.Red)
                        .clickable { showCreate = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, contentDescription = "New playlist", tint = Color.White)
                        Text("New", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
                    }
        }
        if (visiblePlaylists.isEmpty() && ((libraryKind == "All" && !downloadedOnly) || libraryKind == "Playlists")) {
            item {
                Text(
                    "No playlists yet. Make one — it lives on this phone only.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
        items(
            if ((libraryKind == "All" && !downloadedOnly) || libraryKind == "Playlists") {
                visiblePlaylists
            } else {
                emptyList()
            },
        ) { playlist ->
            PlaylistRow(playlist) { onOpenPlaylist(playlist.id) }
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = "New playlist",
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                scope.launch { app.library.createPlaylist(name) }
            },
        )
    }

    if (showImport) {
        // Import a YouTube playlist by link (re-homed here in
        // v1.0.14 when the shortcut grid went — the feature
        // itself stays): its songs land in a new playlist on
        // this phone.
        AlertDialog(
            onDismissRequest = { if (!importing) showImport = false },
            containerColor = ForteColors.SurfaceHi,
            title = { Text("Import playlist", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Paste a YouTube or YouTube Music playlist link.",
                        color = ForteColors.TextDim, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        singleLine = true,
                        placeholder = { Text("https://music.youtube.com/playlist?list=…") },
                    )
                    if (importing) {
                        Spacer(Modifier.height(10.dp))
                        Text("Importing…", color = ForteColors.TextDim, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importUrl.isNotBlank() && !importing,
                    onClick = {
                        importing = true
                        scope.launch {
                            runCatching {
                                var fetched = YtAccount.playlistPage(importUrl.trim())
                                var guard = 0
                                while (fetched.nextPage != null && guard < 5) {
                                    fetched = YtAccount.playlistMore(fetched)
                                    guard++
                                }
                                fetched
                            }.onSuccess { fetched ->
                                val id = app.library.createPlaylist(fetched.title)
                                fetched.tracks.forEach { app.library.addToPlaylist(id, it) }
                                app.showMessage(
                                    "Imported \"${fetched.title}\" — ${fetched.tracks.size} songs"
                                )
                                showImport = false
                                importUrl = ""
                            }.onFailure {
                                app.showMessage("That link didn't open as a playlist.")
                            }
                            importing = false
                        }
                    },
                ) { Text("Import", color = ForteColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) {
                    Text("Cancel", color = ForteColors.TextDim)
                }
            },
        )
    }
}

@Composable
private fun LibraryKindChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) ForteColors.Red else ForteColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun LibraryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // One card recipe (v1.0.14, fit fixed v1.0.16): 20dp radius,
    // the icon riding in a small red-tinted rounded square. Height
    // is a FLOOR, not a fixed size — a hard 104.dp clipped the
    // subtitle mid-glyph on the owner's phone (icon 36 + title +
    // subtitle + padding outgrew it at his font scale), so the
    // card now grows with its content and never cuts a line.
    Column(
        modifier = modifier
            .heightIn(min = 108.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ForteColors.Surface)
            .clickable { onClick() }
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ForteColors.Red.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = title,
                tint = ForteColors.Red, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle, fontSize = 13.sp, color = ForteColors.TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlaylistRow(playlist: PlaylistEntity, onClick: () -> Unit) {
    val app = ForteApp.instance
    val count by app.library.playlistCount(playlist.id).collectAsState(initial = 0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(null, playlist.name, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(playlist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count songs", color = ForteColors.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
fun PlaylistNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForteColors.SurfaceHi,
        title = { Text(title, color = Color.White) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
            ) { Text("Save", color = ForteColors.Red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = ForteColors.TextDim) }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForteColors.SurfaceHi,
        title = { Text(title, color = Color.White) },
        text = { Text(body, color = ForteColors.TextDim) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = ForteColors.Red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = ForteColors.TextDim) }
        },
    )
}

// ----------------------------------------------------------------------
// Simple list screens
// ----------------------------------------------------------------------

@Composable
private fun SimpleTrackList(
    title: String,
    tracks: List<Track>,
    emptyText: String,
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    Column(Modifier.fillMaxSize()) {
        ListHeader(title, null, onBack)
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, color = ForteColors.TextDim, fontSize = 14.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalDockClearance.current)) {
                items(tracks) { track ->
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = track.id in favoriteIds,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { actions.play(track, tracks, title) },
                    )
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(actions: TrackActions, currentTrackId: String?, isPlaying: Boolean, onBack: () -> Unit) {
    val favorites by ForteApp.instance.library.favorites.collectAsState(initial = emptyList())
    SimpleTrackList("Favorites", favorites, "No favorites yet. Tap the heart on any song.", actions, currentTrackId, isPlaying, onBack)
}

@Composable
fun HistoryScreen(actions: TrackActions, currentTrackId: String?, isPlaying: Boolean, onBack: () -> Unit) {
    val history by ForteApp.instance.library.history.collectAsState(initial = emptyList())
    SimpleTrackList("History", history, "Nothing played yet.", actions, currentTrackId, isPlaying, onBack)
}

@Composable
fun PlaylistsListScreen(onOpenPlaylist: (Long) -> Unit, onBack: () -> Unit) {
    val app = ForteApp.instance
    val playlists by app.library.playlists.collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        ListHeader("Playlists", null, onBack) {
            Row(modifier = Modifier.padding(start = 12.dp, top = 10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ForteColors.Red)
                        .clickable { showCreate = true }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) { Text("New playlist", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ForteColors.SurfaceHi)
                        .clickable { showImport = true }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) { Text("Import link", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp + LocalDockClearance.current, start = 0.dp, end = 0.dp)) {
            items(playlists) { playlist -> PlaylistRow(playlist) { onOpenPlaylist(playlist.id) } }
        }
    }
    if (showCreate) {
        PlaylistNameDialog("New playlist", "", { showCreate = false }) { name ->
            showCreate = false
            scope.launch { app.library.createPlaylist(name) }
        }
    }
    if (showImport) {
        // Import a YouTube playlist by link: its songs land in a
        // new playlist on this phone.
        AlertDialog(
            onDismissRequest = { if (!importing) showImport = false },
            containerColor = ForteColors.SurfaceHi,
            title = { Text("Import playlist", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Paste a YouTube or YouTube Music playlist link.",
                        color = ForteColors.TextDim, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        singleLine = true,
                        placeholder = { Text("https://music.youtube.com/playlist?list=…") },
                    )
                    if (importing) {
                        Spacer(Modifier.height(10.dp))
                        Text("Importing…", color = ForteColors.TextDim, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importUrl.isNotBlank() && !importing,
                    onClick = {
                        importing = true
                        scope.launch {
                            runCatching {
                                var fetched = YtAccount.playlistPage(importUrl.trim())
                                var guard = 0
                                while (fetched.nextPage != null && guard < 5) {
                                    fetched = YtAccount.playlistMore(fetched)
                                    guard++
                                }
                                fetched
                            }.onSuccess { fetched ->
                                val id = app.library.createPlaylist(fetched.title)
                                fetched.tracks.forEach { app.library.addToPlaylist(id, it) }
                                app.showMessage(
                                    "Imported \"${fetched.title}\" — ${fetched.tracks.size} songs"
                                )
                                showImport = false
                                importUrl = ""
                            }.onFailure {
                                app.showMessage("That link didn't open as a playlist.")
                            }
                            importing = false
                        }
                    },
                ) { Text("Import", color = ForteColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) {
                    Text("Cancel", color = ForteColors.TextDim)
                }
            },
        )
    }
}

@Composable
fun ArtistsListScreen(onOpenArtist: (String) -> Unit, onBack: () -> Unit) {
    val follows by ForteApp.instance.library.follows.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        ListHeader("Artists you follow", null, onBack)
        if (follows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No artists followed yet.", color = ForteColors.TextDim)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalDockClearance.current)) {
                items(follows) { follow ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpenArtist(follow.channelUrl) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(follow.avatarUrl, follow.name, 52.dp, corner = 26.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(follow.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Filter box for long lists ("search in this list") — local only,
 * filters what is already loaded, no network involved. */
@Composable
fun ListFilterField(value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 20.dp, top = 8.dp)
            .clip(RoundedCornerShape(50)),
        placeholder = { Text("Search in this list", color = ForteColors.TextDim, fontSize = 13.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = ForteColors.Surface,
            unfocusedContainerColor = ForteColors.Surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

fun List<Track>.filteredBy(query: String): List<Track> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter {
        it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
    }
}

// ----------------------------------------------------------------------
// Playlist detail — the user's own (Room) playlist
// ----------------------------------------------------------------------

@Composable
fun UserPlaylistScreen(
    playlistId: Long,
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val app = ForteApp.instance
    val scope = rememberCoroutineScope()
    val playlists by app.library.playlists.collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val rows by app.library.playlistTracks(playlistId).collectAsState(initial = emptyList())
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        ListHeader(playlist?.name ?: "Playlist", "${rows.size} songs", onBack) {
            Row(modifier = Modifier.padding(start = 12.dp, top = 4.dp)) {
                TextButton(onClick = { showRename = true }) { Text("Rename", color = ForteColors.TextDim) }
                TextButton(onClick = { showDelete = true }) { Text("Delete", color = ForteColors.Red) }
            }
            if (rows.size > 8) {
                ListFilterField(filter) { filter = it }
            }
            if (rows.isNotEmpty()) {
                val tracks = rows.map { it.toTrack() }
                PlayShuffleRow(
                    onPlay = { actions.play(tracks.first(), tracks, playlist?.name ?: "Playlist") },
                    onShuffle = {
                        val shuffled = tracks.shuffled()
                        actions.play(shuffled.first(), shuffled, playlist?.name ?: "Playlist")
                    },
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalDockClearance.current)) {
            items(rows.filter {
                val q = filter.trim()
                q.isEmpty() || it.toTrack().title.contains(q, ignoreCase = true) ||
                    it.toTrack().artist.contains(q, ignoreCase = true)
            }) { row ->
                val track = row.toTrack()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = track.id in favoriteIds,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { actions.play(track, rows.map { it.toTrack() }, playlist?.name ?: "Playlist") },
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.IconButton(
                        onClick = { scope.launch { app.library.removeFromPlaylist(row.rowId) } },
                    ) {
                        Icon(Icons.Filled.Clear, contentDescription = "Remove from playlist", tint = ForteColors.TextDim)
                    }
                }
            }
        }
    }

    if (showRename && playlist != null) {
        PlaylistNameDialog("Rename playlist", playlist.name, { showRename = false }) { name ->
            showRename = false
            scope.launch { app.library.renamePlaylist(playlist, name) }
        }
    }
    if (showDelete && playlist != null) {
        ConfirmDialog(
            "Delete playlist?",
            "\"${playlist.name}\" and its track list will be removed from this phone.",
            "Delete",
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                scope.launch {
                    app.library.deletePlaylist(playlist)
                    onDeleted()
                }
            },
        )
    }
}

// ----------------------------------------------------------------------
// Playlist detail — a YouTube album/playlist
// ----------------------------------------------------------------------

@Composable
fun YtPlaylistScreen(
    url: String,
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var page by remember(url) { mutableStateOf<com.kotlinsyd.forte.data.PlaylistPage?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var loadingMore by remember(url) { mutableStateOf(false) }
    var filter by remember(url) { mutableStateOf("") }

    LaunchedEffect(url) {
        // Last-good first, fresh behind it (owner's loading rule).
        val key = "ytdetail:$url"
        if (page == null) {
            com.kotlinsyd.forte.data.LastGood.getTracks(context, key)?.let { cached ->
                page = com.kotlinsyd.forte.data.PlaylistPage(
                    title = com.kotlinsyd.forte.data.LastGood.get(context, "$key:title")
                        ?: "Playlist",
                    uploader = com.kotlinsyd.forte.data.LastGood.get(context, "$key:by"),
                    thumbUrl = null,
                    tracks = cached,
                    nextPage = null,
                )
            }
        }
        runCatching { YtAccount.playlistPage(url) }
            .onSuccess { fresh ->
                page = fresh
                com.kotlinsyd.forte.data.LastGood.putTracks(context, key, fresh.tracks)
                com.kotlinsyd.forte.data.LastGood.put(context, "$key:title", fresh.title)
                fresh.uploader?.let {
                    com.kotlinsyd.forte.data.LastGood.put(context, "$key:by", it)
                }
            }
            .onFailure { if (page == null) failed = true }
    }

    Column(Modifier.fillMaxSize()) {
        // Artwork header (v1.0.14): a playlist page leads with its
        // cover — 132dp art, title/uploader/count beside it — not
        // a bare text header. While the page loads, the first
        // track's cover stands in.
        Row(Modifier.padding(start = 8.dp, top = 10.dp)) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back", tint = Color.White,
                )
            }
        }
        val tracks = page?.tracks.orEmpty()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                page?.thumbUrl ?: tracks.firstOrNull()?.thumbUrl,
                page?.title ?: "Playlist",
                132.dp, corner = 16.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    page?.title ?: "Playlist",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                page?.uploader?.let { uploader ->
                    Text(
                        uploader, fontSize = 13.sp, color = ForteColors.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tracks.isNotEmpty()) {
                    Text(
                        "${tracks.size} songs · ${formatDuration(tracks.sumOf { it.durationSec })}",
                        fontSize = 13.sp, color = ForteColors.TextDim,
                    )
                }
            }
        }
        if (tracks.size > 8) {
            ListFilterField(filter) { filter = it }
        }
        if (tracks.isNotEmpty()) {
            PlayShuffleRow(
                onPlay = { actions.play(tracks.first(), tracks, page?.title ?: "Playlist") },
                onShuffle = {
                    val shuffled = tracks.shuffled()
                    actions.play(shuffled.first(), shuffled, page?.title ?: "Playlist")
                },
            )
        }
        when {
            page == null && !failed -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                SkeletonRows(6)
            }
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load this playlist.", color = ForteColors.TextDim)
            }
            else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalDockClearance.current)) {
                val visible = page!!.tracks.filteredBy(filter)
                items(visible) { track ->
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = track.id in favoriteIds,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { actions.play(track, visible, page?.title ?: "Playlist") },
                    )
                }
                if (page!!.nextPage != null) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(ForteColors.SurfaceHi)
                                    .clickable {
                                        if (!loadingMore) {
                                            loadingMore = true
                                            kotlinx.coroutines.CoroutineScope(
                                                kotlinx.coroutines.Dispatchers.Main
                                            ).launch {
                                                runCatching { YtAccount.playlistMore(page!!) }
                                                    .onSuccess { page = it }
                                                loadingMore = false
                                            }
                                        }
                                    }
                                    .padding(horizontal = 22.dp, vertical = 10.dp),
                            ) {
                                Text(if (loadingMore) "Loading…" else "Load more", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
