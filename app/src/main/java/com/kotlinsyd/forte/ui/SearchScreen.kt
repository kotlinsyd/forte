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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.data.AlbumRef
import com.kotlinsyd.forte.data.ArtistRef
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.ForteEngine
import kotlinx.coroutines.launch

enum class SearchFilter { SONGS, ALBUMS, PLAYLISTS, ARTISTS }

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
    var submittedQuery by mutableStateOf("")
    var filter by mutableStateOf(SearchFilter.SONGS)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var songs by mutableStateOf<List<Track>>(emptyList())
    /** The engine's search session (continuation for load-more). */
    private var forteSearch: ForteEngine.SearchSession? = null
    var canLoadMore by mutableStateOf(false)
    var albums by mutableStateOf<List<AlbumRef>>(emptyList())
    var playlists by mutableStateOf<List<AlbumRef>>(emptyList())
    var artists by mutableStateOf<List<ArtistRef>>(emptyList())

    fun submit(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        submittedQuery = query
        ForteApp.instance.settings.addRecent(query)
        runSearch()
    }

    fun chooseFilter(f: SearchFilter) {
        filter = f
        if (submittedQuery.isNotBlank()) runSearch()
    }

    private fun runSearch() {
        val q = submittedQuery
        loading = true
        failed = false
        // Last-good for this exact query+filter paints instantly;
        // the fresh search keeps loading behind it and swaps in
        // when it lands (owner's stale-while-revalidate rule).
        val ctx = ForteApp.instance
        val key = "search:${filter.name}:${q.lowercase()}"
        when (filter) {
            SearchFilter.SONGS -> com.kotlinsyd.forte.data.LastGood.getTracks(ctx, key)
                ?.let { songs = it }
            SearchFilter.ALBUMS -> com.kotlinsyd.forte.data.LastGood.getRefs(ctx, key)
                ?.let { albums = it }
            SearchFilter.PLAYLISTS -> com.kotlinsyd.forte.data.LastGood.getRefs(ctx, key)
                ?.let { playlists = it }
            SearchFilter.ARTISTS -> com.kotlinsyd.forte.data.LastGood.getArtists(ctx, key)
                ?.let { artists = it }
        }
        viewModelScope.launch {
            var ok = true
            runCatching {
                when (filter) {
                    // Every domain searches through Forte's own
                    // InnerTube search (the music app's own filter
                    // tokens per type — validated live).
                    SearchFilter.SONGS -> {
                        val (tracks, session) = ForteEngine.searchSongs(q)
                        forteSearch = session
                        songs = tracks
                        canLoadMore = session.continuation != null
                        com.kotlinsyd.forte.data.LastGood.putTracks(ctx, key, tracks)
                    }
                    SearchFilter.ALBUMS -> {
                        albums = ForteEngine.searchAlbums(q)
                        com.kotlinsyd.forte.data.LastGood.putRefs(ctx, key, albums)
                    }
                    SearchFilter.PLAYLISTS -> {
                        playlists = ForteEngine.searchPlaylists(q)
                        com.kotlinsyd.forte.data.LastGood.putRefs(ctx, key, playlists)
                    }
                    SearchFilter.ARTISTS -> {
                        artists = ForteEngine.searchArtists(q)
                        com.kotlinsyd.forte.data.LastGood.putArtists(ctx, key, artists)
                    }
                }
            }.onFailure { ok = false }
            // A failure with last-good on screen is silent — the
            // content the user already has wins over an error note.
            failed = !ok && songs.isEmpty() && albums.isEmpty() &&
                playlists.isEmpty() && artists.isEmpty()
            loading = false
        }
    }

    fun loadMore() {
        val forte = forteSearch ?: return
        if (forte.continuation == null || loading) return
        loading = true
        viewModelScope.launch {
            runCatching {
                val (tracks, session) = ForteEngine.searchMore(forte)
                forteSearch = session
                songs = (songs + tracks).distinctBy { it.id }
                canLoadMore = session.continuation != null
            }
            loading = false
        }
    }
}

private val Categories = listOf(
    "Pop" to "pop hits", "Hip-Hop" to "hip hop hits", "Desi" to "desi hits",
    "Rock" to "rock classics", "Lo-fi" to "lofi beats", "Jazz" to "jazz essentials",
    "Classical" to "classical essentials", "R&B" to "rnb hits",
)

@Composable
fun SearchScreen(
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenMix: (String, String) -> Unit,
    onOpenYtPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val app = ForteApp.instance
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var recents by rememberRecents()

    Column(Modifier.fillMaxSize()) {
        Text(
            "Search",
            style = ForteType.pageTitle, color = Color.White,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp),
        )
        TextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp)),
            placeholder = { Text("Songs, artists, albums…", color = ForteColors.TextDim) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search, contentDescription = null,
                    tint = ForteColors.TextDim, modifier = Modifier.size(22.dp),
                )
            },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = ForteColors.TextDim)
                    }
                }
            },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                vm.submit(vm.query)
                recents = app.settings.recents()
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ForteColors.Surface,
                unfocusedContainerColor = ForteColors.Surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        if (vm.submittedQuery.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current),
            ) {
                if (recents.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        SectionHeader(
                            "Recent searches",
                            actionText = "Clear",
                            onAction = {
                                app.settings.clearRecents()
                                recents = emptyList()
                            },
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    items(recents) { recent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable {
                                    vm.query = recent
                                    vm.submit(recent)
                                    recents = app.settings.recents()
                                }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(ForteColors.SurfaceHi),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.History, contentDescription = null,
                                    tint = ForteColors.TextDim, modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                recent, color = Color.White, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    app.settings.removeRecent(recent)
                                    recents = app.settings.recents()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Clear, contentDescription = "Remove",
                                    tint = ForteColors.TextDim, modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader("Browse", modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Categories.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (label, query) ->
                                    // Same category-card recipe as
                                    // Explore's moods (v1.0.14): colour
                                    // field + top-left name; no harvested
                                    // art exists for these fixed seeds,
                                    // so the gradient field carries them.
                                    MoodCard(
                                        title = label,
                                        colorArgb = null,
                                        artUrl = null,
                                        modifier = Modifier.weight(1f),
                                    ) { onOpenMix(label, query) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                // The four filter chips keep their natural size and
                // the row scrolls sideways on narrow screens — a
                // chip is never clipped by the screen edge.
                modifier = Modifier
                    .padding(vertical = 14.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchFilter.entries.forEach { f ->
                    val selected = vm.filter == f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) ForteColors.Red else ForteColors.Surface)
                            .clickable { vm.chooseFilter(f) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            f.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
            when {
                vm.loading && vm.songs.isEmpty() && vm.albums.isEmpty() &&
                    vm.playlists.isEmpty() && vm.artists.isEmpty() ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        SkeletonRows(6)
                    }
                vm.failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Search failed. Check your connection.", color = ForteColors.TextDim)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalDockClearance.current),
                ) {
                    when (vm.filter) {
                        SearchFilter.SONGS -> {
                            items(vm.songs) { track ->
                                TrackRow(
                                    track = track,
                                    actions = actions,
                                    isFavorite = track.id in favoriteIds,
                                    isCurrent = track.id == currentTrackId,
                                    isPlaying = isPlaying,
                                    onClick = { actions.play(track, vm.songs, "Search results") },
                                )
                            }
                            if (vm.canLoadMore) {
                                item {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(ForteColors.SurfaceHi)
                                                .clickable { vm.loadMore() }
                                                .padding(horizontal = 22.dp, vertical = 10.dp),
                                        ) {
                                            Text(
                                                if (vm.loading) "Loading…" else "Load more",
                                                color = Color.White, fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        SearchFilter.ALBUMS -> items(vm.albums) { album ->
                            AlbumRow(album) { onOpenYtPlaylist(album.url) }
                        }
                        SearchFilter.PLAYLISTS -> items(vm.playlists) { playlist ->
                            AlbumRow(playlist) { onOpenYtPlaylist(playlist.url) }
                        }
                        SearchFilter.ARTISTS -> items(vm.artists) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onOpenArtist(artist.url) }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(artist.thumbUrl, artist.name, 52.dp, corner = 26.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    artist.detail?.let {
                                        Text(it, color = ForteColors.TextDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumRow(album: AlbumRef, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(album.thumbUrl, album.title, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(album.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.artist, album.detail).joinToString(" · "),
                color = ForteColors.TextDim, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberRecents(): androidx.compose.runtime.MutableState<List<String>> {
    return androidx.compose.runtime.remember {
        mutableStateOf(ForteApp.instance.settings.recents())
    }
}
