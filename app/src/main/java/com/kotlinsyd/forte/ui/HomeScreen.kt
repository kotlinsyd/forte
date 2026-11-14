package com.kotlinsyd.forte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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


/** Home's freshness window (v1.0.21): a saved catalogue younger
 * than this is trusted as-is when Home resumes — no refetch. A
 * pull-to-refresh always fetches, stamp or no stamp. */
private const val STALE_MS = 3 * 3600 * 1000L

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
    // Auto-refresh (v1.0.21): Home's effects used to re-run only
    // when the region or account changed, so a process that stayed
    // alive served the first catalogue it ever loaded — for days.
    // A resume tick now re-runs both effects on every return; each
    // run trusts a cache younger than STALE_MS and skips the
    // network, and a pull (forceTick) always fetches. The handled
    // markers remember which force each effect already served, so
    // a plain resume never mistakes an old pull for a new one.
    var resumeTick by remember { mutableStateOf(0) }
    var forceTick by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var handledHomeForce by remember { mutableStateOf(0) }
    var handledChartsForce by remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val activity = generateSequence(context) {
            (it as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
        if (activity == null) {
            onDispose { }
        } else {
            var replayed = false
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    // Registering replays the current state once:
                    // that first ON_RESUME is composition itself,
                    // not a return — only later resumes count.
                    if (replayed) resumeTick++ else replayed = true
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(settings.regionGl, settings.regionChosen, accountTick, resumeTick, forceTick) {
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
        // Freshness gate (v1.0.21): a run that finds this key's
        // cache younger than STALE_MS trusts what it just painted
        // and skips the network entirely. A forced run (a pull)
        // always fetches; its marker is recorded as the fetch
        // starts, so the run itself is the one that serves it.
        val forcedHome = forceTick != handledHomeForce
        if (!forcedHome && cached != null && cached.savedAt > 0 &&
            System.currentTimeMillis() - cached.savedAt < STALE_MS
        ) {
            isRefreshing = false
            return@LaunchedEffect
        }
        if (forcedHome) handledHomeForce = forceTick
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
        isRefreshing = false
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
    LaunchedEffect(settings.regionGl, accountTick, resumeTick, forceTick) {
        chartsFailed = false
        // The same freshness gate as the feed effect (v1.0.21):
        // charts ride the same HomeCache payload, so the same
        // stamp decides. A forced run always fetches.
        val forcedCharts = forceTick != handledChartsForce
        if (!forcedCharts) {
            val gateCached = com.kotlinsyd.forte.data.HomeCache.load(
                context, app.settings.region().gl, signedIn,
            )
            if (gateCached != null && gateCached.savedAt > 0 &&
                System.currentTimeMillis() - gateCached.savedAt < STALE_MS
            ) {
                return@LaunchedEffect
            }
        }
        if (forcedCharts) handledChartsForce = forceTick
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
    // Hero window rotation (v1.0.22): the hero still carries
    // ONLY chart hits (owner's rule), but a chart's top 8 barely
    // moves day to day, so the hero looked frozen even when Home
    // truly refreshed. The window now walks the chart's top 20
    // by day — every hero track is still a top-20 chart hit in
    // chart order, and the featured set turns over daily and
    // whenever the chart itself moves.
    val heroDay = (System.currentTimeMillis() / 86_400_000L).toInt()
    val heroTracks: List<Track> = remember(charts, homeShelves, history, chartsFailed, heroDay) {
        when {
            !charts.isNullOrEmpty() -> {
                val pool = charts.orEmpty().take(20)
                if (pool.size > 8) {
                    val start = heroDay % (pool.size - 7)
                    pool.drop(start).take(8)
                } else {
                    pool
                }
            }
            chartsFailed || charts != null -> {
                val mixed = homeShelves
                    ?.firstOrNull { it.title.contains("Mixed", ignoreCase = true) }
                    ?.tracks.orEmpty()
                when {
                    mixed.isNotEmpty() -> mixed.take(8)
                    history.size >= 3 -> history.take(8)
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }
    val heroFromCharts = !charts.isNullOrEmpty() && heroTracks.isNotEmpty()
    val heroEyebrow = when {
        heroFromCharts ->
            "TOP CHARTS" + (chartsLabel?.let { " · ${it.uppercase()}" } ?: "")
        heroTracks.isNotEmpty() -> "MADE FOR YOU"
        else -> ""
    }
    val heroOrigin = if (heroFromCharts) "Top charts" else "Made for you"

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

    // (The now-playing colour wash that used to sit behind
    // the top of Home is gone in v1.0.28-test: the owner's
    // approved hero design puts the header zone on PURE
    // black, and a tinted wash behind it would colour that
    // black.)
    Box(Modifier.fillMaxSize()) {
    // Pull-to-refresh (v1.0.21): wraps ONLY the scrolling list —
    // the fixed header below stays outside, floating as it always
    // has. A pull bumps forceTick; the feed effect's forced run
    // is what actually fetches, and its settle clears the spinner.
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            // A pull must be able to bring a NEW home, not the
            // same feed re-served to the same visitor: rotate
            // the guest visitor BEFORE the forced run starts,
            // so its fetches compose a fresh feed (v1.0.22).
            com.kotlinsyd.forte.data.YtAccount.rotateGuestVisitor()
            forceTick++
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Invisible end clearance — the list itself scrolls
        // behind the floating dock to the physical screen edge.
        // Top: the header zone (v1.0.28-test) — the black bar
        // (66dp) plus its fade strip (14dp) — is exactly 80dp,
        // hero or no hero, so the hero poster's top edge lands
        // precisely where the black has finished fading and
        // the poster itself starts clean.
        contentPadding = PaddingValues(
            top = 80.dp,
            bottom = 16.dp + LocalDockClearance.current,
        ),
    ) {
        if (heroTracks.isNotEmpty()) {
            item {
                HomeHero(
                    tracks = heroTracks,
                    eyebrow = heroEyebrow,
                    isFavorite = { track -> track.id in favoriteIds },
                    onPlay = { track ->
                        actions.play(track, heroTracks, heroOrigin)
                    },
                    onToggleFavorite = { track -> actions.toggleFavorite(track) },
                )
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
                // SAMPLE A (v1.0.23-test, owner's pick): Listen
                // again is a card row now — big square artwork
                // cards, title under each, artist under that. A
                // tap plays the song in its history queue, as the
                // rows did; the rows' three-dot menu rides a long
                // press on the card. A plain scrollable Row, not
                // a LazyRow (FORTE-9's nested-lazy lesson — the
                // shelves below follow the same rule).
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    history.forEach { track ->
                        ListenAgainCard(
                            track = track,
                            actions = actions,
                            isFavorite = track.id in favoriteIds,
                            isCurrent = track.id == currentTrackId,
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
                // FORTE-9: a plain scrollable Row, not a LazyRow — a
                // lazy list nested inside the Home lazy column makes a
                // TV remote's focus search re-measure both lists and
                // every card between on the main thread for one key
                // press, which froze a low-end TV box into an ANR.
                // Shelves are short, so an eager Row measures once,
                // cheaply, and looks and scrolls the same.
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    shelf.tracks.forEach { track ->
                        Column(
                            modifier = Modifier
                                .width(156.dp)
                                .clickable { actions.play(track, shelf.tracks, shelf.title) },
                        ) {
                            Artwork(track.thumbUrl, track.title, 156.dp, corner = 14.dp)
                            Spacer(Modifier.height(8.dp))
                            // Fitted titles (v1.0.24-test, the
                            // YouTube Music rule the owner
                            // ordered): the title may take TWO
                            // lines and its block is RESERVED at
                            // two lines (38dp at cardTitle's 19sp
                            // line height) whether the title
                            // needs them or not, and the subtitle
                            // line is always reserved too — so
                            // every card in the row stands the
                            // same height and nothing runs long
                            // or jumps.
                            Text(
                                track.title, style = ForteType.cardTitle,
                                color = Color.White, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(38.dp),
                            )
                            Text(
                                track.artist, style = ForteType.cardSub,
                                color = ForteColors.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(17.dp),
                            )
                        }
                    }
                    shelf.cards.forEach { card ->
                        Column(
                            modifier = Modifier
                                .width(156.dp)
                                // Card tap = play (v1.0.24-test):
                                // never the playlist page.
                                .clickable { playCard(card) },
                        ) {
                            Artwork(card.thumbUrl, card.title, 156.dp, corner = 14.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                card.title, style = ForteType.cardTitle,
                                color = Color.White, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(38.dp),
                            )
                            Text(
                                card.subtitle ?: "", style = ForteType.cardSub,
                                color = ForteColors.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(17.dp),
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
                // FORTE-9: plain scrollable Row, not a LazyRow
                // (nested-lazy focus-search freeze — see the shelf
                // row above).
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
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
                                                    model = com.kotlinsyd.forte.data.artworkAt(
                                                        url,
                                                        com.kotlinsyd.forte.data.ArtSize.ROW,
                                                    ),
                                                    placeholder = rememberArtworkPainter(playlist.name),
                                                    error = rememberArtworkPainter(playlist.name),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(78.dp),
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
                                                    modifier = Modifier.size(78.dp),
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
                            Spacer(Modifier.height(8.dp))
                            Text(
                                playlist.name, style = ForteType.cardTitle,
                                color = Color.White, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(38.dp),
                            )
                            Text(
                                "$count songs", style = ForteType.cardSub,
                                color = ForteColors.TextDim,
                                modifier = Modifier.height(17.dp),
                            )
                        }
                    }
                }
            }
        }

    }
    }
        // The header zone (owner-approved hero design,
        // v1.0.28-test): the wordmark + region line + account
        // avatar sit on PURE BLACK — a solid black bar with a
        // short black fade under it. The bar floats over the
        // page top as the header always has; what changed is
        // that it no longer floats over the hero's artwork —
        // the list's top clearance (80dp) is exactly this bar
        // plus this fade, so the hero poster begins below the
        // black, fully clean (see HomeHero).
        Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.forte_wordmark),
                    contentDescription = "FORTE.",
                    modifier = Modifier.height(24.dp),
                )
                Spacer(Modifier.height(5.dp))
                // The line still goes full white when a hero
                // is up (owner's rule, v1.0.24-test: over the
                // hero it must READ at full strength, never
                // dim). It needs no shadow trick anymore:
                // since v1.0.28-test the header sits on its
                // own pure-black bar, never over the artwork.
                // On plain-background pages it stays the dim
                // line it has always been.
                Text(
                    regionLine,
                    fontSize = 13.sp,
                    color = if (heroTracks.isNotEmpty()) {
                        Color.White
                    } else {
                        ForteColors.TextDim
                    },
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
        // The fade out of the black: a short melt from the
        // bar's black to transparent. It completes exactly
        // where the hero poster's top edge sits — the list's
        // 80dp top clearance is this bar plus this strip — so
        // the poster itself carries no dark at all.
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black, Color.Transparent),
                    ),
                ),
        )
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
                    .clip(RoundedCornerShape(16.dp)),
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

// ----------------------------------------------------------------------
// Sample A + Sample 1 pieces (v1.0.23-test): the Listen again
// card and the Home hero.
// ----------------------------------------------------------------------

/** The hero page's meta line: the artist, plus the play count
 * when the chart carries one. */
private fun heroMetaLine(track: Track): String =
    listOf(
        track.artist,
        if (track.viewCount > 0) "${formatViews(track.viewCount)} plays" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * One Listen again card (Sample A): 148dp square artwork, the
 * title under it (one line, trimmed), the artist under that in
 * grey. An item without artwork stands on a rich per-title
 * gradient with a music-note glyph — never the bare-letter tile
 * (that cheap look is what this replaces). Tap plays; a long
 * press opens the track's menu (the row menu's sheet, hoisted).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ListenAgainCard(
    track: Track,
    actions: TrackActions,
    isFavorite: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    var menuOpen by remember(track.id) { mutableStateOf(false) }
    val url = rememberEnrichedArtwork(track)
    Column(Modifier.width(148.dp)) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(artworkGradient(track.title)))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote, contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(38.dp),
            )
            if (!url.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = com.kotlinsyd.forte.data.artworkAt(
                        url,
                        com.kotlinsyd.forte.data.ArtSize.CARD,
                    ),
                    placeholder = rememberArtworkPainter(track.title),
                    error = rememberArtworkPainter(track.title),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        // Fitted titles, same rule as the shelf cards
        // (v1.0.24-test): unified to the cardTitle step (15sp —
        // this card used to run its own 14/12sp pair), two
        // lines with the 38dp block reserved, the artist line
        // always reserved under it.
        Text(
            track.title,
            style = ForteType.cardTitle,
            color = if (isCurrent) ForteColors.Red else Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(38.dp),
        )
        Text(
            track.artist.ifBlank { "Song" },
            style = ForteType.cardSub, color = ForteColors.TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(17.dp),
        )
        TrackMenu(
            track = track, actions = actions, isFavorite = isFavorite,
            hoistedOpen = menuOpen,
            onHoistedOpenChange = { menuOpen = it },
        )
    }
}

/**
 * The Home hero (owner-approved design, v1.0.28-test —
 * the sample forte-hero-bleed-sample-v2.png, his verdict
 * "Perfect"): the poster WHOLE at its original aspect,
 * full width, uncropped, no stretch — and NOTHING drawn
 * over it: no veil, no fade. The header zone above it is
 * pure black and its fade completes above the poster's
 * top edge (see the header in HomeScreen), so the poster
 * starts clean and stays clean. ~8dp under the poster, on
 * the plain page background: the eyebrow, the big title,
 * the meta line, and the Play row — a solid white Play
 * pill, the pure-frost favourite heart, and the carousel
 * dots riding the row's right end. Tight — no blank band
 * anywhere — and Listen again flows straight in below.
 *
 * The CONTENT is tracks (v1.0.24-test): the region's Top
 * Charts in chart order (see the heroTracks rule in
 * HomeScreen). Tapping the poster PLAYS — the same rule
 * as every card on Home — it never opens a page. The
 * favourite heart is the track's own like, the add Forte
 * has for a song. A slow carousel (6 s) that also swipes.
 */
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

@Composable
private fun HomeHero(
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
