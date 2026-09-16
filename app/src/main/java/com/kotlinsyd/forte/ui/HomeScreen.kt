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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.R
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.ForteEngine
import com.kotlinsyd.forte.data.formatViews
import kotlinx.coroutines.launch


@Composable
fun HomeScreen(
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenAccount: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenYtPlaylist: (String) -> Unit,
    onOpenMix: (String, String) -> Unit,
    onOpenMood: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    val app = ForteApp.instance
    val history by app.library.history.collectAsState(initial = emptyList())
    val playlists by app.library.playlists.collectAsState(initial = emptyList())
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    val settings by app.settings.settings.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val accountTick by app.accountVersion.collectAsState()
    val signedIn = remember(accountTick) {
        com.kotlinsyd.forte.data.YtAccount.isSignedIn()
    }
    // Account photo self-heal: if the session is live but the
    // saved name/photo are missing (the v1.0.7 parse never landed
    // them), fetch the account identity once and persist it, so
    // the avatar door shows his Google photo without him having
    // to open the Account screen first.
    // The header's account photo, held as state: prefs alone don't
    // recompose the header when the self-heal lands the photo, so
    // the fetch below publishes it here the moment it arrives.
    var headerAvatar by remember { mutableStateOf(app.settings.accountAvatar()) }
    LaunchedEffect(signedIn, accountTick) {
        // Repaint from the store first: another surface (the
        // account card's open-refresh, Settings, sign-in) may have
        // landed the identity since this header last looked.
        headerAvatar = app.settings.accountAvatar()
        if (signedIn &&
            (app.settings.accountAvatar() == null || app.settings.accountName() == null)
        ) {
            runCatching { com.kotlinsyd.forte.data.YtAccount.accountInfo() }
                .getOrNull()?.let { info ->
                    // Merge, never overwrite: a fetch that proved
                    // the photo but not the name still lands the
                    // photo (and the other way round).
                    app.settings.mergeAccount(info.name, info.handle, info.avatarUrl)
                    headerAvatar = app.settings.accountAvatar()
                }
        }
    }
    var charts by remember { mutableStateOf<List<Track>?>(null) }
    var chartsLabel by remember { mutableStateOf<String?>(null) }
    var chartsFailed by remember { mutableStateOf(false) }

    // The REAL YouTube Music home for the picked region, followed
    // through its continuations (personalised when signed in).
    //
    // Loading follows the owner's rule (stale-while-revalidate):
    // the screen's last-good payload for THIS region paints
    // instantly, the fresh feed loads behind it and swaps in page
    // by page as shelves land; skeletons appear only when there is
    // no last-good and the load is still fast, and a 6-second
    // watchdog swaps a still-empty screen to search-backed shelves
    // while the real feed keeps loading in the background. A
    // region change reads a different cache key, so the old
    // region's content can never pose as the new region's.
    var homeShelves by remember {
        mutableStateOf<List<com.kotlinsyd.forte.data.YtAccount.HomeShelf>?>(null)
    }
    var homeFailed by remember { mutableStateOf(false) }
    var loadedKey by remember { mutableStateOf("") }
    LaunchedEffect(settings.regionGl, settings.regionChosen, accountTick) {
        val region = app.settings.region()
        val key = com.kotlinsyd.forte.data.HomeCache.key(region.gl, signedIn)
        if (key != loadedKey) {
            loadedKey = key
            homeShelves = null
            charts = null
            chartsLabel = null
        }
        val cached = com.kotlinsyd.forte.data.HomeCache.load(context, region.gl, signedIn)
        if (cached != null) {
            if (homeShelves == null && cached.shelves.isNotEmpty()) {
                homeShelves = cached.shelves
            }
            if (charts == null && cached.charts.isNotEmpty()) {
                charts = cached.charts
                chartsLabel = cached.chartsLabel
            }
        }
        homeFailed = false
        val watchdog = launch {
            kotlinx.coroutines.delay(6_000)
            if (homeShelves.isNullOrEmpty()) {
                runCatching { ForteEngine.searchSongs("top hits") }.getOrNull()
                    ?.first?.takeIf { it.isNotEmpty() }?.let { hits ->
                        homeShelves = listOf(
                            com.kotlinsyd.forte.data.YtAccount.HomeShelf("Top hits", hits),
                        )
                    }
            }
        }
        runCatching {
            com.kotlinsyd.forte.data.YtAccount.homeFeed { page ->
                homeShelves = page
            }
        }.onSuccess { fresh ->
            if (fresh.isNotEmpty()) {
                homeShelves = fresh
                com.kotlinsyd.forte.data.HomeCache.save(
                    context, region.gl, signedIn, fresh,
                    charts ?: cached?.charts ?: emptyList(),
                    chartsLabel ?: cached?.chartsLabel,
                )
            }
        }.onFailure {
            if (homeShelves.isNullOrEmpty()) homeFailed = true
        }
        watchdog.cancel()
        // Home supplements — the same extra wells the Home feed
        // merges in (new releases, explore, mixed for
        // you): each is browsed in parallel and its shelves join
        // by title as they land, never holding the core feed up.
        for (bid in listOf(
            "FEmusic_new_releases", "FEmusic_explore", "FEmusic_mixed_for_you",
        )) {
            launch {
                val extra = runCatching {
                    com.kotlinsyd.forte.data.YtAccount.browseShelves(bid)
                }.getOrDefault(emptyList())
                if (extra.isNotEmpty()) {
                    val cur = homeShelves.orEmpty()
                    val have = cur.map { it.title }.toSet()
                    val fresh = extra.filter { it.title !in have }
                    if (fresh.isNotEmpty()) homeShelves = cur + fresh
                }
            }
        }
    }
    // Charts reload with the region (the old build computed them
    // once with a US-default search and never again). Region-true
    // charts come from FEmusic_charts with the picked country; the
    // extractor search is only the fallback lane.
    LaunchedEffect(settings.regionGl, accountTick) {
        chartsFailed = false
        runCatching { com.kotlinsyd.forte.data.YtAccount.charts() }
            .onSuccess { res ->
                if (res.tracks.isNotEmpty()) {
                    charts = res.tracks
                    chartsLabel = res.countryLabel
                    val region = app.settings.region()
                    com.kotlinsyd.forte.data.HomeCache.save(
                        context, region.gl, signedIn,
                        homeShelves ?: emptyList(), res.tracks, res.countryLabel,
                    )
                } else if (charts == null) {
                    runCatching { ForteEngine.searchSongs("top hits").first }
                        .onSuccess { charts = it }
                        .onFailure { chartsFailed = true }
                }
            }
            .onFailure {
                if (charts == null) {
                    runCatching { ForteEngine.searchSongs("top hits").first }
                        .onSuccess { charts = it }
                        .onFailure { chartsFailed = true }
                }
            }
    }

    // Home header (owner's pick, Sample C): no greeting — the
    // wordmark carries a small dim line naming the picked region
    // and its language ("Pakistan · English"), always read from
    // the live settings pick, never hardcoded.
    val pickedRegion = if (settings.regionGl.isBlank()) {
        com.kotlinsyd.forte.data.Regions.deviceDefault()
    } else {
        com.kotlinsyd.forte.data.Regions.byGl(settings.regionGl)
    }
    val regionLine = pickedRegion.name + " · " +
        java.util.Locale(pickedRegion.hl)
            .getDisplayLanguage(java.util.Locale.ENGLISH)
            .replaceFirstChar { it.uppercase() }

    // Ambience: the playing song's colour washes the top of Home.
    val rawTint by app.nowPlayingTint.collectAsState()
    val tint by androidx.compose.animation.animateColorAsState(
        rawTint, animationSpec = androidx.compose.animation.core.tween(800),
        label = "homeTint",
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(tint.copy(alpha = 0.30f), ForteColors.Bg),
                    )
                ),
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Invisible end clearance only — the list itself scrolls
        // behind the floating dock to the physical screen edge.
        contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.forte_wordmark),
                        contentDescription = "FORTE.",
                        modifier = Modifier.height(24.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        regionLine,
                        fontSize = 13.sp, color = ForteColors.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Profile (owner's order): his account photo when
                // signed in, a plain profile icon when not. This
                // avatar is the ONE settings door (v1.0.7): signed
                // out it opens Settings, signed in the account sheet.
                val avatar = headerAvatar ?: app.settings.accountAvatar()
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ForteColors.SurfaceHi)
                        .clickable { onOpenAccount() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (signedIn && !avatar.isNullOrBlank()) {
                        coil.compose.AsyncImage(
                            model = avatar,
                            contentDescription = "Your account",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Person, contentDescription = "Account",
                            tint = Color.White, modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item {
                Spacer(Modifier.height(26.dp))
                SectionHeader(
                    "Listen again",
                    actionText = "See all",
                    onAction = onOpenHistory,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                // Recents open Home as list rows, not a card shelf
                // (v1.0.14): one thumb + play per row, four deep —
                // resuming a song is one tap, not a shelf browse.
                Column(Modifier.padding(horizontal = 16.dp)) {
                    history.take(4).forEach { track ->
                        TrackRow(
                            track = track,
                            actions = actions,
                            isFavorite = track.id in favoriteIds,
                            isCurrent = track.id == currentTrackId,
                            isPlaying = isPlaying,
                            onClick = { actions.play(track, history, "Listen again") },
                        )
                    }
                }
            }
        }

        // The real region home feed: shelves straight from YouTube
        // Music (song shelves play in place; card shelves open the
        // playlist/album page they point at). The full set the feed
        // returns — no cap.
        val shelves = homeShelves
        if (shelves == null && !homeFailed) {
            // Fast first load with no last-good to show: shelf-shaped
            // skeletons, bounded by the loader's watchdog — the feed
            // or the fallback replaces them within seconds.
            items(2) {
                Spacer(Modifier.height(26.dp))
                Box(Modifier.padding(horizontal = 20.dp)) { SkeletonShelf() }
            }
        }
        if (shelves == null && homeFailed) {
            item {
                Spacer(Modifier.height(26.dp))
                Text(
                    "Home couldn't load. Charts and your library still work below.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        if (shelves != null) {
            items(shelves) { shelf ->
                Spacer(Modifier.height(26.dp))
                SectionHeader(shelf.title, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(shelf.tracks) { track ->
                        Column(
                            modifier = Modifier
                                .width(156.dp)
                                .clickable { actions.play(track, shelf.tracks, shelf.title) },
                        ) {
                            Artwork(track.thumbUrl, track.title, 156.dp, corner = 14.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                track.title, style = ForteType.cardTitle,
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artist, style = ForteType.cardSub, color = ForteColors.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    items(shelf.cards) { card ->
                        Column(
                            modifier = Modifier
                                .width(156.dp)
                                .clickable { onOpenYtPlaylist(card.browseId) },
                        ) {
                            Artwork(card.thumbUrl, card.title, 156.dp, corner = 14.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                card.title, style = ForteType.cardTitle,
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                card.subtitle ?: "", style = ForteType.cardSub, color = ForteColors.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(26.dp))
            SectionHeader(
                // The label is the country the charts server really
                // selected for the picked region (some regions have
                // no chart of their own and get Global — said so).
                "Top charts" + (chartsLabel?.let { " · $it" } ?: ""),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        when {
            charts == null && !chartsFailed -> item {
                Box(Modifier.padding(horizontal = 16.dp)) { SkeletonRows(5) }
            }
            chartsFailed -> item {
                Text(
                    "Charts couldn't load. Check your connection and reopen Forte.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            else -> itemsIndexed(charts.orEmpty().take(15)) { i, track ->
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForteColors.TextDim,
                        modifier = Modifier.width(22.dp),
                    )
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = track.id in favoriteIds,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { actions.play(track, charts.orEmpty(), "Top charts") },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (track.viewCount > 0) {
                    Text(
                        "${formatViews(track.viewCount)} plays",
                        fontSize = 11.sp, color = ForteColors.TextDim,
                        modifier = Modifier.padding(start = 96.dp, bottom = 2.dp),
                    )
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item {
                Spacer(Modifier.height(26.dp))
                SectionHeader("Made for you", modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(playlists) { playlist ->
                        val count by app.library.playlistCount(playlist.id)
                            .collectAsState(initial = 0)
                        val tracks by app.library.playlistTracks(playlist.id)
                            .collectAsState(initial = emptyList())
                        // Artwork-led (v1.0.14): the playlist's own
                        // covers lead — a 2×2 collage when four or
                        // more songs have art, the first cover when
                        // fewer, the gradient + note only when none.
                        val thumbs = tracks.mapNotNull {
                            it.thumbUrl?.takeIf { u -> u.isNotBlank() }
                        }.distinct().take(4)
                        Column(
                            modifier = Modifier
                                .width(156.dp)
                                .clickable { onOpenPlaylist(playlist.id) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(156.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.linearGradient(artworkGradient(playlist.name)),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    thumbs.size >= 4 -> Column {
                                        Row {
                                            thumbs.take(2).forEach { url ->
                                                coil.compose.AsyncImage(
                                                    model = com.kotlinsyd.forte.data.bigArtwork(url),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(78.dp),
                                                )
                                            }
                                        }
                                        Row {
                                            thumbs.drop(2).take(2).forEach { url ->
                                                coil.compose.AsyncImage(
                                                    model = com.kotlinsyd.forte.data.bigArtwork(url),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(78.dp),
                                                )
                                            }
                                        }
                                    }
                                    thumbs.isNotEmpty() -> coil.compose.AsyncImage(
                                        model = com.kotlinsyd.forte.data.bigArtwork(thumbs.first()),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    else -> Text("♪", fontSize = 44.sp, color = ForteColors.Red)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                playlist.name, style = ForteType.cardTitle,
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "$count songs", style = ForteType.cardSub,
                                color = ForteColors.TextDim,
                            )
                        }
                    }
                }
            }
        }

    }
    }
}

/** A mood/genre landing page: one search, played as a list. */
@Composable
fun MixScreen(
    title: String,
    query: String,
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var tracks by remember(query) { mutableStateOf<List<Track>?>(null) }
    var failed by remember(query) { mutableStateOf(false) }

    LaunchedEffect(query) {
        // Last-good first, fresh behind it (owner's loading rule).
        val key = "mix:${query.lowercase()}"
        if (tracks == null) {
            tracks = com.kotlinsyd.forte.data.LastGood.getTracks(context, key)
        }
        runCatching { ForteEngine.searchSongs(query) }
            .onSuccess {
                tracks = it.first
                com.kotlinsyd.forte.data.LastGood.putTracks(context, key, it.first)
            }
            .onFailure { if (tracks == null) failed = true }
    }

    Column(Modifier.fillMaxSize()) {
        ListHeader(title = title, subtitle = "From YouTube Music", onBack = onBack) {
            val list = tracks.orEmpty()
            if (list.isNotEmpty()) {
                PlayShuffleRow(
                    onPlay = { actions.play(list.first(), list, title) },
                    onShuffle = {
                        val shuffled = list.shuffled()
                        actions.play(shuffled.first(), shuffled, title)
                    },
                )
            }
        }
        when {
            tracks == null && !failed -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                SkeletonRows(6)
            }
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load this mix.", color = ForteColors.TextDim)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    bottom = 16.dp + LocalDockClearance.current,
                ),
            ) {
                items(tracks.orEmpty()) { track ->
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = track.id in favoriteIds,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = isPlaying,
                        onClick = { actions.play(track, tracks.orEmpty(), title) },
                    )
                }
            }
        }
    }
}

@Composable
fun ListHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.padding(start = 8.dp, end = 20.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back", tint = Color.White,
                )
            }
            Column {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 13.sp, color = ForteColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        extra()
    }
}

@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ForteColors.Red)
                .clickable { onPlay() }
                .padding(horizontal = 26.dp, vertical = 11.dp),
        ) {
            Text("Play", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ForteColors.SurfaceHi)
                .clickable { onShuffle() }
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Text("Shuffle", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}


/**
 * A mood/genre category card (owner's order: cards, never written
 * chips). The standard category recipe (v1.0.14): the ground is the
 * category's own colour from the browse response, only lightly
 * deepened toward Forte's background so the colour field leads;
 * the name sits top-left; the category's own photo (harvested from
 * its page) rides as one inset square panel on the right, tilted
 * a few degrees like a record sleeve. No photo → the colour field
 * and the name carry the card alone.
 */
@Composable
fun MoodCard(
    title: String,
    colorArgb: Long?,
    artUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val ground = if (colorArgb != null) {
        val c = Color(colorArgb.toInt())
        androidx.compose.ui.graphics.lerp(c, ForteColors.Bg, 0.25f)
    } else {
        null
    }
    Box(
        modifier = modifier
            .height(104.dp)
            .clip(shape)
            .then(
                if (ground != null) Modifier.background(ground)
                else Modifier.background(
                    Brush.linearGradient(artworkGradient(title)),
                ),
            )
            .clickable { onClick() },
    ) {
        Text(
            title,
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 12.dp, end = 106.dp),
        )
        if (!artUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = com.kotlinsyd.forte.data.bigArtwork(artUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .size(88.dp)
                    .rotate(-4f)
                    .shadow(8.dp, RoundedCornerShape(10.dp), clip = false)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

/** A mood/genre category page: the shelves of one
 * FEmusic_moods_and_genres_category browse — song shelves play in
 * place, card shelves open the page they point at. */
@Composable
fun MoodScreen(
    title: String,
    browseId: String,
    params: String?,
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenYtPlaylist: (String) -> Unit,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var shelves by remember(browseId, params) {
        mutableStateOf<List<com.kotlinsyd.forte.data.YtAccount.HomeShelf>?>(null)
    }
    var failed by remember(browseId, params) { mutableStateOf(false) }
    LaunchedEffect(browseId, params) {
        runCatching {
            com.kotlinsyd.forte.data.YtAccount.browseShelves(browseId, params)
        }.onSuccess { shelves = it }
            .onFailure { failed = true }
    }
    // The category's own picture as the page hero — the same
    // harvested art the Home card wears (shared cache).
    val artKey = browseId + "|" + (params ?: "")
    var heroArt by remember(browseId, params) {
        mutableStateOf(app.settings.moodArt()[artKey])
    }
    LaunchedEffect(browseId, params) {
        if (heroArt == null) {
            runCatching {
                com.kotlinsyd.forte.data.YtAccount.moodArtwork(browseId, params)
            }.getOrNull()?.let { url ->
                app.settings.putMoodArt(artKey, url)
                heroArt = url
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ListHeader(title = title, subtitle = "From YouTube Music", onBack = onBack)
        if (!heroArt.isNullOrBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                coil.compose.AsyncImage(
                    model = com.kotlinsyd.forte.data.bigArtwork(heroArt),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.45f),
                                ),
                            ),
                        ),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        when {
            shelves == null && !failed -> Box(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) { SkeletonRows(6) }
            failed || shelves.isNullOrEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
            ) { Text("Couldn't load this category.", color = ForteColors.TextDim) }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    bottom = 16.dp + LocalDockClearance.current,
                ),
            ) {
                shelves.orEmpty().forEach { shelf ->
                    item {
                        Spacer(Modifier.height(18.dp))
                        SectionHeader(
                            shelf.title,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (shelf.tracks.isNotEmpty()) {
                        items(shelf.tracks.take(10)) { track ->
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                TrackRow(
                                    track = track,
                                    actions = actions,
                                    isFavorite = track.id in favoriteIds,
                                    isCurrent = track.id == currentTrackId,
                                    isPlaying = isPlaying,
                                    onClick = { actions.play(track, shelf.tracks, shelf.title) },
                                )
                            }
                        }
                    }
                    if (shelf.cards.isNotEmpty()) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(shelf.cards) { card ->
                                    Column(
                                        modifier = Modifier
                                            .width(156.dp)
                                            .clickable { onOpenYtPlaylist(card.browseId) },
                                    ) {
                                        Artwork(card.thumbUrl, card.title, 156.dp, corner = 14.dp)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            card.title, style = ForteType.cardTitle,
                                            color = Color.White, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            card.subtitle ?: "", style = ForteType.cardSub,
                                            color = ForteColors.TextDim,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
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
