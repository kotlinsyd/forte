package com.kotlinsyd.forte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    // Auto-refresh (v1.0.21, reworked v1.0.34-test): a resume
    // tick re-runs both effects on every return, and EVERY run
    // fetches — the cache still paints instantly and the fresh
    // feed swaps in behind it (stale-while-revalidate), but no
    // run trusts the cache and skips the network anymore. The
    // v1.0.21–v1.0.33 freshness gate (trust anything younger
    // than 3 h) meant a phone opened more often than every 3
    // hours effectively never refetched: the feed read as
    // never auto-refreshing, which is exactly what the owner
    // reported. A pull is a run like any other, plus the
    // spinner and the hero stride (see HomePullState).
    var resumeTick by remember { mutableStateOf(0) }
    // The pull's whole state in one holder (v1.0.34-test): the
    // gesture handler, the feed effects and the refresh box all
    // read and write this ONE object, and the pull path is
    // exercised through it by the UI tests — the real handler,
    // not a copy.
    val pull = remember { HomePullState() }
    val resumeGate = remember { ResumeGate() }
    DisposableEffect(context) {
        val activity = generateSequence(context) {
            (it as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
        if (activity == null) {
            onDispose { }
        } else {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    // Registering replays the current state once:
                    // that first ON_RESUME is composition itself,
                    // not a return — ResumeGate swallows it; only
                    // later resumes count as returns.
                    if (resumeGate.onResume()) resumeTick++
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(settings.regionGl, settings.regionChosen, accountTick, resumeTick, pull.forceTick) {
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
        // No freshness gate (v1.0.34-test): every run fetches.
        // What was painted from the cache above stays on screen
        // until the fresh feed lands and swaps in behind it.
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
        // The pull spinner answers to the feed fetch: it clears
        // when the fetch settles, success or failure (a background
        // run landing here with the spinner somehow up clears it
        // too — it is only ever up during a pull).
        pull.settle()
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
    LaunchedEffect(settings.regionGl, accountTick, resumeTick, pull.forceTick) {
        chartsFailed = false
        // No freshness gate here either (v1.0.34-test): charts
        // ride the same triggers as the feed and refetch on
        // every run, for the selected region.
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

    // The picked region, read live from settings (the country
    // label under the wordmark displays it; never hardcoded).
    val pickedRegion = if (settings.regionGl.isBlank()) {
        com.kotlinsyd.forte.data.Regions.deviceDefault()
    } else {
        com.kotlinsyd.forte.data.Regions.byGl(settings.regionGl)
    }

    val scope = rememberCoroutineScope()
    // HERO content rule (v1.0.24-test, owner's order): the hero
    // carries ONLY top hits — the picked region's Top Charts in
    // chart order (YtAccount.charts; chartsLabel names the
    // country the server really selected), top 8. The first
    // hero (v1.0.23-test) sampled the feed's album/playlist
    // cards at random and the owner sent it back: "only top
    // hit songs … most popular ones, not all bad include".
    // Fallback when charts come back empty: the feed's own
    // taste shelf (Mixed for you), then his most-played
    // history. While charts are still loading there is no hero
    // — it joins when they land and never holds the page up.
    // No source, no hero (never a broken hero).
    // Hero window rotation (v1.0.22, stride fixed v1.0.33-test):
    // the hero still carries ONLY chart hits (owner's rule), but
    // the v1.0.22 window advanced ONE chart position per day —
    // seven of eight posters identical day after day over a
    // near-static chart, and a pull never moved it at all, so
    // the hero read as frozen since the update. The window now
    // strides a full hero page per day over the chart's top 20,
    // and a pull strides it again (see heroWindow): every hero
    // track is still a top-20 chart hit in chart order, and the
    // featured set turns over completely each day and on a pull.
    val heroDay = (System.currentTimeMillis() / 86_400_000L).toInt()
    val heroTracks: List<Track> = remember(charts, homeShelves, history, chartsFailed, heroDay, pull.heroShift) {
        heroTracksFor(charts, homeShelves, history, chartsFailed, heroDay, pull.heroShift)
    }
    val heroFromCharts = !charts.isNullOrEmpty() && heroTracks.isNotEmpty()
    val heroOrigin = if (heroFromCharts) "Top charts" else "Made for you"
    // THE LEAD SHELF (v1.0.37-test): Home's layout is BitChord's
    // now (see the list below), so the hero rule wears that
    // design's lead-shelf form — a pager of compact track rows
    // (columns of four), not the v1.0.35 featured card the
    // owner rejected with the rest of that layout. The pager's
    // first columns are the hero window itself (the region's
    // top chart hits, striding by day and by pull — the
    // stride is the pull's visible effect, exactly as it was
    // on the card), and the rest of the chart follows in
    // chart order behind it, so the whole top 20 is on the
    // page. From a fallback well the pager is that well's
    // window, as the hero was. A tap on a row plays it with
    // the FULL chart as the queue — the chart entry's own
    // behaviour, unchanged.
    val chartPagerTracks: List<Track> = if (heroFromCharts) {
        val pool = charts.orEmpty().take(20)
        heroTracks + pool.filter { t -> heroTracks.none { it.id == t.id } }
    } else {
        heroTracks
    }
    val chartPagerQueue = if (heroFromCharts) charts.orEmpty() else chartPagerTracks

    // CARD TAP = PLAY (v1.0.24-test, owner's rule): a card tap
    // never lands on a playlist page — those pages usually hold
    // just the one song anyway. The tap fetches the page behind
    // the scenes and its first track opens in the player with
    // the rest as the queue; the page itself opens only when
    // the fetch returns nothing playable.
    val playCard: (com.kotlinsyd.forte.data.YtAccount.HomeCard) -> Unit = { card ->
        scope.launch {
            val page = runCatching {
                com.kotlinsyd.forte.data.YtAccount.playlistPage(card.browseId)
            }.getOrNull()
            val tracks = page?.tracks.orEmpty()
            if (tracks.isNotEmpty()) {
                actions.play(tracks.first(), tracks, card.title)
            } else {
                onOpenYtPlaylist(card.browseId)
            }
        }
    }

    // Poster prefetch (v1.0.24-test): when Home's data lands,
    // warm the hero posters and the first shelf's covers at the
    // exact ladder sizes their slots will request, so first
    // paint finds them in the image cache, not on the network.
    val prefetchedUrls = remember { mutableSetOf<String>() }
    LaunchedEffect(charts, homeShelves) {
        val loader = coil.Coil.imageLoader(context)
        val want = mutableListOf<Pair<String, com.kotlinsyd.forte.data.ArtSize>>()
        charts.orEmpty().take(8).forEach { t ->
            t.thumbUrl?.let { want += it to com.kotlinsyd.forte.data.ArtSize.HERO }
        }
        homeShelves?.firstOrNull()?.let { shelf ->
            shelf.tracks.take(6).forEach { t ->
                t.thumbUrl?.let { want += it to com.kotlinsyd.forte.data.ArtSize.CARD }
            }
            shelf.cards.take(6).forEach { c ->
                c.thumbUrl?.let { want += it to com.kotlinsyd.forte.data.ArtSize.CARD }
            }
        }
        for ((url, size) in want) {
            val sized = com.kotlinsyd.forte.data.artworkAt(url, size) ?: continue
            if (!prefetchedUrls.add(sized)) continue
            loader.enqueue(
                coil.request.ImageRequest.Builder(context).data(sized).build(),
            )
        }
    }

    // The feed sections (v1.0.37-test): the feed's shelves in
    // feed order, whole — the v1.0.35 arrangement (a pulled-
    // out new-releases shelf, two spotlight doorways) is gone
    // with that layout. Shelves flow the way the reference
    // design's feed flows: one card shelf after another, in
    // the order the feed served them.
    val feedShelves = homeShelves.orEmpty()
        .filter { it.tracks.isNotEmpty() || it.cards.isNotEmpty() }

    // (The now-playing colour wash that used to sit behind
    // the top of Home stays gone: the Listen Now page opens
    // on the plain page background, title first.)
    Box(Modifier.fillMaxSize()) {
    // Pull-to-refresh (v1.0.21, reworked v1.0.34-test): wraps
    // ONLY the scrolling list. A pull runs HomePullState.onPull;
    // the feed effects' re-run is what actually fetches, and
    // its settle clears the spinner. The v1.0.34-test fix put
    // the indicator below the old floating header; the Listen
    // Now page has no floating header, so the indicator rides
    // at the very top of the list, in the open.
    HomeRefreshBox(
        isRefreshing = pull.isRefreshing,
        onRefresh = {
            // The whole pull in one holder call (v1.0.34-test):
            // spinner up, hero window strides a full page, and
            // the feed effects re-run and fetch. The stride is
            // the pull's guaranteed visible effect: a signed-in
            // session's feed and the chart are composed per
            // account and barely move between asks, so the fetch
            // alone can repaint an identical screen — the new
            // featured card cannot. It applies to whichever well
            // feeds the card (charts, the taste shelf, history —
            // see heroTracksFor).
            pull.onPull()
            // A pull must be able to bring a NEW home, not the
            // same feed re-served to the same visitor: rotate
            // the guest visitor BEFORE the forced run starts,
            // so its fetches compose a fresh feed (v1.0.22).
            com.kotlinsyd.forte.data.YtAccount.rotateGuestVisitor()
        },
        indicatorTop = 12.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Invisible end clearance — the list itself scrolls
        // behind the floating dock to the physical screen edge.
        contentPadding = PaddingValues(
            bottom = 16.dp + LocalDockClearance.current,
        ),
    ) {
        // 1 — The header (v1.0.37-test, the reference design's
        // header treatment): the FORTE wordmark stands at the
        // top of the page — that is where the app's mark lives
        // — with the account door at its right (the same door
        // as always: his Google photo when signed in, the plain
        // profile icon when not; a tap opens the account sheet
        // / Settings). No giant page title under it: the page
        // opens on the mark and goes straight to the shelves,
        // the way the reference Home opens. The picked region
        // only DISPLAYS here (v1.0.38-test, owner's order: the
        // selector on Home "looks bad" — changing the region
        // lives ONLY in Settings now), and since v1.0.39-test
        // it displays as small plain text tucked under the
        // wordmark's E end — no capsule, no chip (the .38
        // glass chip is gone on the owner's word: "not country
        // name in capsule, just put under Forte a country
        // small"). The feed effects still key on
        // settings.regionGl exactly as before.
        item {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Wordmark + country, one left block: the
                    // name is right-aligned inside the block,
                    // so its right edge sits exactly under the
                    // wordmark's final E.
                    Column(horizontalAlignment = Alignment.End) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.soniko_wordmark),
                            contentDescription = "SONIKO.",
                            modifier = Modifier.height(26.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            pickedRegion.name,
                            style = ForteType.groupLabel,
                            color = ForteColors.TextDim,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
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
        }
        // 2 — The lead shelf: the region's top chart as a
        // pager of compact rows (the hero rule in the new
        // layout's form — see chartPagerTracks above). Rows
        // carry their true chart rank; a tap plays the song
        // with the full chart as its queue.
        if (chartPagerTracks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                HomeSectionHeader(
                    title = if (heroFromCharts) {
                        "Top Songs — ${chartsLabel ?: pickedRegion.name}"
                    } else {
                        heroOrigin
                    },
                )
                HomeTrackPager(
                    tracks = chartPagerTracks,
                    queue = chartPagerQueue,
                    queueLabel = if (heroFromCharts) "Top charts" else heroOrigin,
                    actions = actions,
                    favoriteIds = favoriteIds,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    rankOf = if (heroFromCharts) {
                        { track -> charts.orEmpty().indexOfFirst { it.id == track.id } + 1 }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
        } else if (charts == null && !chartsFailed) {
            item {
                Spacer(Modifier.height(26.dp))
                Box(Modifier.padding(horizontal = 16.dp)) { SkeletonShelf() }
            }
        } else if (chartsFailed) {
            item {
                Spacer(Modifier.height(26.dp))
                Text(
                    "Charts couldn't load. Check your connection and reopen Soniko.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        // 3 — Recently played: his real listening history
        // (Library.history) as the same pager — the reference
        // design's Recents form. No history yet → the section
        // is absent and the feed shelves below flow up into
        // its place; nothing is faked to fill it.
        if (history.isNotEmpty()) {
            item {
                HomeSectionHeader(
                    title = "Recently played",
                    actionText = "See all",
                    onAction = onOpenHistory,
                )
                HomeTrackPager(
                    tracks = history.take(20),
                    queue = history,
                    queueLabel = "Listen again",
                    actions = actions,
                    favoriteIds = favoriteIds,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    rankOf = null,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        // The feed's loading / failure states: shelf-shaped
        // skeletons while there is no last-good to paint,
        // bounded by the loader's watchdog; a plain line when
        // the load failed outright.
        val shelves = homeShelves
        if (shelves == null && !homeFailed) {
            items(2) {
                Spacer(Modifier.height(26.dp))
                Box(Modifier.padding(horizontal = 16.dp)) { SkeletonShelf() }
            }
        }
        if (shelves == null && homeFailed) {
            item {
                Spacer(Modifier.height(26.dp))
                Text(
                    "Home couldn't load. Charts and your library still work below.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        // 4 — The real region home feed, in feed order:
        // shelves straight from YouTube Music as card shelves
        // (song shelves play in place; card shelves play the
        // page behind the scenes — see playCard).
        if (shelves != null) {
            items(feedShelves) { shelf ->
                HomeSectionHeader(shelf.title)
                ShelfCardRow(
                    shelf = shelf,
                    actions = actions,
                    onPlayCard = playCard,
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // 5 — His own playlists as a card shelf (the same
        // card language as the feed shelves: 150dp covers at
        // the 12dp corner, title under, count under that).
        if (playlists.isNotEmpty()) {
            item {
                HomeSectionHeader("Playlists")
                // FORTE-9: plain scrollable Row, not a LazyRow
                // (nested-lazy focus-search freeze — see the
                // shelf row above).
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    playlists.forEach { playlist ->
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
                                .width(150.dp)
                                .clickable { onOpenPlaylist(playlist.id) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(150.dp)
                                    .clip(RoundedCornerShape(ForteGlass.RadiusArtwork))
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
                                                    model = com.kotlinsyd.forte.data.artworkAt(
                                                        url,
                                                        com.kotlinsyd.forte.data.ArtSize.ROW,
                                                    ),
                                                    placeholder = rememberArtworkPainter(playlist.name),
                                                    error = rememberArtworkPainter(playlist.name),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(75.dp),
                                                )
                                            }
                                        }
                                        Row {
                                            thumbs.drop(2).take(2).forEach { url ->
                                                coil.compose.AsyncImage(
                                                    model = com.kotlinsyd.forte.data.artworkAt(
                                                        url,
                                                        com.kotlinsyd.forte.data.ArtSize.ROW,
                                                    ),
                                                    placeholder = rememberArtworkPainter(playlist.name),
                                                    error = rememberArtworkPainter(playlist.name),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(75.dp),
                                                )
                                            }
                                        }
                                    }
                                    thumbs.isNotEmpty() -> coil.compose.AsyncImage(
                                        model = com.kotlinsyd.forte.data.artworkAt(
                                            thumbs.first(),
                                            com.kotlinsyd.forte.data.ArtSize.CARD,
                                        ),
                                        placeholder = rememberArtworkPainter(playlist.name),
                                        error = rememberArtworkPainter(playlist.name),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    else -> Text("♪", fontSize = 44.sp, color = ForteColors.Red)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                playlist.name, style = ForteType.cardTitle,
                                color = Color.White, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "$count songs", style = ForteType.cardSub,
                                color = ForteColors.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
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
                // The primary button (v1.0.37-test): white with
                // black content — the design's primary/onPrimary
                // pair. The red pill was the old theme's accent
                // wash; red now lives on small marks only.
                .background(Color.White)
                .clickable { onPlay() }
                .padding(horizontal = 26.dp, vertical = 11.dp),
        ) {
            Text("Play", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        Box(
            modifier = Modifier
                .forteGlassChip(RoundedCornerShape(ForteGlass.RadiusCapsule))
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
    val shape = RoundedCornerShape(ForteGlass.RadiusArtwork)
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
            .border(1.dp, ForteGlass.Hairline, shape)
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
                model = com.kotlinsyd.forte.data.artworkAt(
                    artUrl,
                    com.kotlinsyd.forte.data.ArtSize.CARD,
                ),
                placeholder = rememberArtworkPainter(title),
                error = rememberArtworkPainter(title),
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
                    .clip(RoundedCornerShape(ForteGlass.RadiusArtwork)),
            ) {
                coil.compose.AsyncImage(
                    model = com.kotlinsyd.forte.data.artworkAt(
                        heroArt,
                        com.kotlinsyd.forte.data.ArtSize.HERO,
                    ),
                    placeholder = rememberArtworkPainter(title),
                    error = rememberArtworkPainter(title),
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
                            // FORTE-9: plain scrollable Row, not a
                            // LazyRow (nested-lazy freeze — see Home).
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                shelf.cards.forEach { card ->
                                    Column(
                                        modifier = Modifier
                                            .width(156.dp)
                                            .clickable { onOpenYtPlaylist(card.browseId) },
                                    ) {
                                        Artwork(card.thumbUrl, card.title, 156.dp, corner = ForteGlass.RadiusArtwork)
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

// ----------------------------------------------------------------------
// Home's pieces (v1.0.37-test): the shelf header, the compact-
// row pager and its rows, the hero window maths, the pull
// state, and the retired featured card the pull tests compose.
// ----------------------------------------------------------------------

/** The hero page's meta line: the artist, plus the play count
 * when the chart carries one. */
private fun heroMetaLine(track: Track): String =
    listOf(
        track.artist,
        if (track.viewCount > 0) "${formatViews(track.viewCount)} plays" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * A Home shelf header (v1.0.37-test, the reference design's
 * SectionHeader): the shelf's title at the row-title step,
 * an optional subtitle under it in the dim colour, and an
 * optional action at the right in the primary white. The
 * header carries its own vertical padding (10dp), so
 * sections stack header-to-content with no extra spacers —
 * the feed's rhythm comes from these paddings plus the
 * 26dp the section leaves under its content.
 */
@Composable
private fun HomeSectionHeader(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = ForteType.rowTitle,
            color = ForteColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                style = ForteType.cardTitle,
                color = ForteColors.Text,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onAction() }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * A pager of compact track rows (v1.0.37-test): the
 * reference design's Recents form — the tracks chunked
 * into columns of four compact rows, the columns laid in
 * a horizontally scrolling row. A column is 88% of the
 * available width, capped at 400dp, so a phone shows one
 * column and the edge of the next. FORTE-9: a plain
 * scrollable Row, not a LazyRow (nested-lazy freeze).
 */
@Composable
private fun HomeTrackPager(
    tracks: List<Track>,
    queue: List<Track>,
    queueLabel: String,
    actions: TrackActions,
    favoriteIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    rankOf: ((Track) -> Int)?,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = minOf((maxWidth - 32.dp) * 0.88f, 400.dp)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tracks.chunked(4).forEach { column ->
                Column(Modifier.width(columnWidth)) {
                    column.forEach { track ->
                        HomeCompactRow(
                            track = track,
                            rank = rankOf?.invoke(track),
                            actions = actions,
                            isFavorite = track.id in favoriteIds,
                            isCurrent = track.id == currentTrackId,
                            isPlaying = isPlaying,
                            onClick = { actions.play(track, queue, queueLabel) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One compact track row (v1.0.37-test, the reference
 * design's CompactTrackRow): 48dp artwork at a 7dp corner
 * with the hairline edge, the title at the row-title step,
 * the artist under it in the dim colour, and the track's
 * own menu at the right. A chart row carries its rank in
 * the accent red ahead of the artwork — the one red mark
 * the design spends on a list (its rank badge).
 */
@Composable
private fun HomeCompactRow(
    track: Track,
    rank: Int?,
    actions: TrackActions,
    isFavorite: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null && rank > 0) {
            Text(
                rank.toString(),
                style = ForteType.caption,
                color = ForteColors.Red,
                modifier = Modifier.width(22.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(artworkGradient(track.title)))
                .border(1.dp, ForteGlass.Hairline, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote, contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(20.dp),
            )
            val url = rememberEnrichedArtwork(track)
            if (!url.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = com.kotlinsyd.forte.data.artworkAt(
                        url,
                        com.kotlinsyd.forte.data.ArtSize.ROW,
                    ),
                    placeholder = rememberArtworkPainter(track.title),
                    error = rememberArtworkPainter(track.title),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = ForteType.rowTitle,
                color = ForteColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist.ifBlank { "Song" },
                style = ForteType.cardSub,
                color = ForteColors.TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Text(
                text = if (isPlaying) "Playing" else "Paused",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ForteColors.Red,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        TrackMenu(track = track, actions = actions, isFavorite = isFavorite)
    }
}

/**
 * The hero's displayed title (owner's rule, v1.0.25-test):
 * ONLY the main name. The original title is cut at the first
 * bracket suffix — "(feat. …)", "(From "…")" and the like
 * are dropped entirely, never wrapped, never "cleaned" or
 * reordered: "Casa Tupka Anthemo (feat. Priyanshi
 * Srivastava)" shows as "Casa Tupka Anthemo"; "Tu Mil Jaaye
 * (From "KING")" shows as "Tu Mil Jaaye". If what remains is
 * still too long, the Text ellipsizes it. If cutting would
 * leave an empty or near-empty string (the title itself
 * starts with a bracket), the original title stands.
 * DISPLAY ONLY — the track keeps its full original title
 * everywhere else (player, menus, library).
 */
private fun heroTitle(title: String): String {
    val cut = title.indexOf(" (")
    if (cut < 0) return title
    val main = title.substring(0, cut).trim()
    return if (main.length >= 2) main else title
}

/**
 * Which tracks the Home hero carries, derived from the three
 * wells in priority order: the region charts, the feed's own
 * taste shelf (Mixed for you), then his most-played history.
 * Extracted from the Home composition (v1.0.34-test) so the
 * pull-path UI tests derive the hero exactly as the screen
 * does — the same function, not a copy.
 */
internal fun heroTracksFor(
    charts: List<Track>?,
    homeShelves: List<com.kotlinsyd.forte.data.YtAccount.HomeShelf>?,
    history: List<Track>,
    chartsFailed: Boolean,
    heroDay: Int,
    heroShift: Int,
): List<Track> = when {
    !charts.isNullOrEmpty() -> heroWindow(charts.orEmpty().take(20), heroDay, heroShift)
    chartsFailed || charts != null -> {
        val mixed = homeShelves
            ?.firstOrNull { it.title.contains("Mixed", ignoreCase = true) }
            ?.tracks.orEmpty()
        when {
            // The day/pull stride applies to EVERY well
            // (v1.0.34-test): before, the fallback wells ignored
            // heroShift entirely, so on a phone whose hero came
            // from here a pull could never move the hero at all.
            mixed.isNotEmpty() -> heroWindow(mixed.take(20), heroDay, heroShift)
            history.size >= 3 -> heroWindow(history.take(20), heroDay, heroShift)
            else -> emptyList()
        }
    }
    else -> emptyList()
}

/**
 * The hero's window over the chart pool (pure, unit-tested):
 * 8 consecutive chart hits in cyclic chart order, starting a
 * full hero page (8 positions) further into the pool for each
 * day ([heroDay]) and for each pull ([heroShift] grows by 8
 * per pull). The v1.0.22 window started at `day % (size - 7)`
 * — one new position per day, seven of eight posters carried
 * over, and no response to a pull at all. A pool of 8 or
 * fewer is the window whole: nothing to stride.
 */
internal fun heroWindow(pool: List<Track>, heroDay: Int, heroShift: Int): List<Track> {
    if (pool.size <= 8) return pool
    val start = Math.floorMod(heroDay * 8 + heroShift, pool.size)
    return List(8) { i -> pool[(start + i) % pool.size] }
}

/**
 * Home's pull-to-refresh container: the material3 box directly
 * wrapping the scrolling list. Extracted (v1.0.34-test) so the
 * pull-path UI tests exercise the very container the screen
 * uses. The indicator's top offset is a parameter: under the
 * old floating header it had to sit at 84dp, below the header
 * zone, or the header's solid black bar painted over it and
 * no pull ever showed (v1.0.34-test); the Listen Now page
 * (v1.0.35-test) has no floating header, so the screen passes
 * a small offset and the indicator rides at the very top.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorTop: androidx.compose.ui.unit.Dp = 84.dp,
    content: @Composable () -> Unit,
) {
    val state = androidx.compose.material3.pulltorefresh
        .rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            androidx.compose.material3.pulltorefresh
                .PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = indicatorTop),
                )
        },
    ) {
        content()
    }
}

/**
 * The pull-to-refresh state of Home, in one holder
 * (v1.0.34-test): the gesture handler, both feed effects and
 * the refresh box all read and write this single object, so
 * the chain the UI tests exercise is the chain the screen
 * runs — the handler itself, not a re-implementation.
 *
 * [onPull] is everything a pull does to state: the spinner
 * goes up ([isRefreshing]), the hero window strides a full
 * page ([heroShift]) and the feed effects re-run ([forceTick]
 * is their key). The feed effect calls [settle] when its fetch
 * lands, which is what brings the spinner down.
 */
internal class HomePullState {
    var heroShift by mutableStateOf(0)
        private set
    var forceTick by mutableStateOf(0)
        private set
    var isRefreshing by mutableStateOf(false)
        private set

    fun onPull() {
        isRefreshing = true
        heroShift += 8
        forceTick++
    }

    fun settle() {
        isRefreshing = false
    }
}

/**
 * The resume trigger's replay guard: registering a lifecycle
 * observer replays the current state once, and that first
 * ON_RESUME is composition itself, not a return from
 * background. [onResume] returns true only for real returns.
 * Extracted (v1.0.34-test) so the auto-refresh trigger is
 * unit-testable.
 */
internal class ResumeGate {
    private var replayed = false

    fun onResume(): Boolean {
        if (!replayed) {
            replayed = true
            return false
        }
        return true
    }
}

/**
 * The Listen Now featured card (v1.0.35-test; RETIRED from
 * Home in v1.0.37-test — the layout it fronted was rejected
 * whole, and the lead shelf is a compact-row pager now).
 * It stays in the tree because the pull-path UI tests
 * compose it as the hero's face: a pull must visibly turn
 * it over (see HomePullRefreshTest). Nothing in the app
 * navigates to it.
 */
@Composable
internal fun FeaturedChartCard(
    track: Track,
    label: String,
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .forteGlassPanel(RoundedCornerShape(ForteGlass.RadiusPanel))
            .clickable { onClick() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Brush.linearGradient(artworkGradient(track.title))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote, contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(44.dp),
            )
            if (!track.thumbUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = com.kotlinsyd.forte.data.artworkAt(
                        track.thumbUrl,
                        com.kotlinsyd.forte.data.ArtSize.HERO,
                    ),
                    placeholder = rememberArtworkPainter(track.title),
                    error = rememberArtworkPainter(track.title),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = ForteType.groupLabel,
                        color = ForteColors.Red,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        headline,
                        fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, contentDescription = "Play",
                        tint = ForteColors.Bg, modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                heroTitle(track.title),
                style = ForteType.rowTitle,
                color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                track.artist.ifBlank { "Song" },
                style = ForteType.rowSub,
                color = ForteColors.TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One shelf card for a song (the feed shelves' shared
 * card, v1.0.37-test at the reference design's metrics):
 * 150dp artwork at the 12dp corner, a 10dp gap, the title
 * at the card-title step on one line, the artist under it
 * in the dim colour on one line — no reserved blocks, the
 * card is exactly as tall as its text.
 */
@Composable
private fun ShelfTrackCard(
    track: Track,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable { onClick() },
    ) {
        Artwork(track.thumbUrl, track.title, 150.dp, corner = ForteGlass.RadiusArtwork)
        Spacer(Modifier.height(10.dp))
        Text(
            track.title, style = ForteType.cardTitle,
            color = Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist, style = ForteType.cardSub,
            color = ForteColors.TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One Home shelf as a card row: the shelf's songs as cards
 * (a tap plays the song in its shelf queue), then its cards
 * (a tap plays the page behind the scenes — see playCard in
 * HomeScreen). FORTE-9: a plain scrollable Row, not a
 * LazyRow — a lazy list nested inside the Home lazy column
 * makes a TV remote's focus search re-measure both lists and
 * every card between on the main thread for one key press,
 * which froze a low-end TV box into an ANR. Shelves are
 * short, so an eager Row measures once, cheaply, and looks
 * and scrolls the same.
 */
@Composable
private fun ShelfCardRow(
    shelf: com.kotlinsyd.forte.data.YtAccount.HomeShelf,
    actions: TrackActions,
    onPlayCard: (com.kotlinsyd.forte.data.YtAccount.HomeCard) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        shelf.tracks.forEach { track ->
            ShelfTrackCard(
                track = track,
                onClick = { actions.play(track, shelf.tracks, shelf.title) },
            )
        }
        shelf.cards.forEach { card ->
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onPlayCard(card) },
            ) {
                Artwork(card.thumbUrl, card.title, 150.dp, corner = ForteGlass.RadiusArtwork)
                Spacer(Modifier.height(10.dp))
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

@Composable
internal fun HomeHero(
    tracks: List<Track>,
    eyebrow: String,
    isFavorite: (Track) -> Boolean,
    onPlay: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        pageCount = { tracks.size },
    )
    LaunchedEffect(tracks.size) {
        if (tracks.size > 1) {
            while (true) {
                kotlinx.coroutines.delay(6_000)
                if (!pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage(
                        (pagerState.currentPage + 1) % tracks.size,
                    )
                }
            }
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Each poster's TRUE aspect (width / height), learned
    // from the artwork itself — a fit-into-96×96 decode
    // preserves the source's aspect, so the bitmap's own
    // dimensions carry it (the player's own trick). Keyed
    // by track id. The poster zone's height follows the
    // CURRENT page's aspect, so a 16:9 chart frame stands
    // at 16:9 and a square cover stands square; a poster
    // that has not landed yet reads as 16:9, the charts'
    // own shape.
    var aspects by remember { mutableStateOf(mapOf<String, Float>()) }
    LaunchedEffect(tracks) {
        val loader = coil.Coil.imageLoader(context)
        for (track in tracks) {
            if (aspects.containsKey(track.id)) continue
            val url = track.thumbUrl?.let {
                com.kotlinsyd.forte.data.artworkAt(
                    it,
                    com.kotlinsyd.forte.data.ArtSize.HERO,
                )
            } ?: continue
            runCatching {
                val result = loader.execute(
                    coil.request.ImageRequest.Builder(context)
                        .data(url)
                        .size(96, 96)
                        .allowHardware(false)
                        .build(),
                )
                if (result is coil.request.SuccessResult) {
                    val bitmap = (result.drawable as?
                        android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                        aspects = aspects +
                            (track.id to bitmap.width.toFloat() / bitmap.height.toFloat())
                    }
                }
            }
        }
    }
    val currentTrack = tracks.getOrNull(pagerState.currentPage) ?: tracks.first()
    val currentAspect = aspects[currentTrack.id] ?: (16f / 9f)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Full width at the poster's own aspect — the whole
        // picture, uncropped, unstretched.
        val posterHeight = maxWidth / currentAspect
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(posterHeight)) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val track = tracks[page]
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable { onPlay(track) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(artworkGradient(track.title)),
                                ),
                        )
                        if (!track.thumbUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = com.kotlinsyd.forte.data.artworkAt(
                                    track.thumbUrl,
                                    com.kotlinsyd.forte.data.ArtSize.HERO,
                                ),
                                placeholder = rememberArtworkPainter(track.title),
                                error = rememberArtworkPainter(track.title),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // NOTHING over the poster (v1.0.28-test):
                        // the veils and fades the hero used to
                        // paint across the artwork are gone —
                        // the owner's word on the approved
                        // sample was that the full poster stays
                        // visible, with no dark added in it.
                    }
                }
            }
            // The text block, ~8dp under the poster's own
            // bottom edge, on the plain page background. It
            // follows the CURRENT page (the pager above now
            // carries only posters).
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp),
            ) {
                Text(
                    eyebrow,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.75f),
                    letterSpacing = 2.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                // The title is ONE line (v1.0.29-test,
                // owner's order on his v1.0.28-test
                // screenshot: the two-line reserve the
                // v1.0.24/.25-test fixes left behind put a
                // hole under one-line titles — "Guzarish",
                // then a gap before the artist). The rest
                // is cut with an ellipsis ("rest ll be
                // cutted" — his words); the artist line
                // sits directly under the title. The text
                // itself is still the owner's main-name
                // rule (see heroTitle): the bracket suffix
                // never displays. The player's two-line
                // title is untouched.
                Text(
                    heroTitle(currentTrack.title),
                    fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                    lineHeight = 38.sp,
                    color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    heroMetaLine(currentTrack),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(20.dp),
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .clickable { onPlay(currentTrack) }
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = ForteColors.Bg,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Play",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = ForteColors.Bg,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    val fav = isFavorite(currentTrack)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .frostedDarkGlass(CircleShape)
                            .clickable { onToggleFavorite(currentTrack) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (fav) "Liked" else "Like",
                            tint = if (fav) ForteColors.Red else Color.White,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    if (tracks.size > 1) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tracks.indices.forEach { i ->
                                val active = i == pagerState.currentPage
                                Box(
                                    Modifier
                                        .size(if (active) 7.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (active) Color.White
                                            else Color.White.copy(alpha = 0.35f),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
