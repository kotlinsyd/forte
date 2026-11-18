package com.kotlinsyd.forte.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.UpdateChecker
import com.kotlinsyd.forte.data.UpdateInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ForteRoot() {
    val app = ForteApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val controller = app.player
    val playState by controller.state.collectAsState()
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    val settings by app.settings.settings.collectAsState()
    val playlists by app.library.playlists.collectAsState(initial = emptyList())

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        updateInfo = UpdateChecker.check(context)
    }

    var playlistPickerTrack by remember { mutableStateOf<Track?>(null) }
    var creatingForTrack by remember { mutableStateOf<Track?>(null) }

    val ytLikedIds by app.ytLikedIds.collectAsState()
    // What the hearts show: this phone's favorites plus, when signed
    // in, the account's YouTube likes.
    val shownFavoriteIds = favoriteIds + ytLikedIds

    // Song-menu sleep timer (TrackActions.openSleepTimer lands here).
    var showSleepTimerRoot by remember { mutableStateOf(false) }
    // The avatar door's account sheet (signed-in tap on Home's
    // profile photo).
    var accountSheetOpen by remember { mutableStateOf(false) }

    // The account card's identity, held as state so a refresh can
    // repaint it (prefs alone don't recompose). Opening the card is
    // a MANUAL identity refresh: the chain runs again, whatever it
    // proves merges into the store, and the card — and, through
    // accountVersion, the Home/Explore headers — repaint from the
    // stored values.
    var accountCardName by remember { mutableStateOf(app.settings.accountName()) }
    var accountCardHandle by remember { mutableStateOf(app.settings.accountHandle()) }
    var accountCardAvatar by remember { mutableStateOf(app.settings.accountAvatar()) }
    // The identity fetch's own account of itself (v1.0.13), shown
    // small on the card while the identity is incomplete — so a
    // screenshot names the failing step instead of just showing
    // the same blank card.
    var accountIdentityNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(accountSheetOpen) {
        accountCardName = app.settings.accountName()
        accountCardHandle = app.settings.accountHandle()
        accountCardAvatar = app.settings.accountAvatar()
        if (accountSheetOpen &&
            com.kotlinsyd.forte.data.YtAccount.isSignedIn()
        ) {
            runCatching { com.kotlinsyd.forte.data.YtAccount.accountInfo() }
                .getOrNull()?.let { info ->
                    app.settings.mergeAccount(info.name, info.handle, info.avatarUrl)
                    accountCardName = app.settings.accountName()
                    accountCardHandle = app.settings.accountHandle()
                    accountCardAvatar = app.settings.accountAvatar()
                    app.accountVersion.value++
                }
            accountIdentityNote =
                com.kotlinsyd.forte.data.YtAccount.lastIdentityCheck
        }
    }

    val actions = TrackActions(
        play = { track, list, origin ->
            val queue = if (list.isEmpty()) listOf(track) else list
            controller.playQueue(
                queue,
                queue.indexOf(track).coerceAtLeast(0),
                origin,
            )
        },
        toggleFavorite = { track ->
            val wasFav = track.id in favoriteIds
            scope.launch { app.library.toggleFavorite(track, wasFav) }
            // Two-way likes: the same tap likes/unlikes on YouTube.
            // A refusal is said out loud and the local state stands —
            // nothing is faked.
            if (com.kotlinsyd.forte.data.YtAccount.isSignedIn()) {
                val nowLiked = !wasFav
                scope.launch {
                    runCatching {
                        com.kotlinsyd.forte.data.YtAccount.setLiked(track.id, nowLiked)
                    }.onSuccess {
                        app.ytLikedIds.value =
                            if (nowLiked) ytLikedIds + track.id else ytLikedIds - track.id
                    }.onFailure {
                        app.showMessage(
                            "Couldn't update your YouTube like — it's saved on this phone only."
                        )
                    }
                }
            }
        },
        download = { track -> app.downloads.start(track) },
        share = { track ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "${track.title} – ${track.artist}\nhttps://www.youtube.com/watch?v=${track.id}"
                )
            }
            context.startActivity(Intent.createChooser(intent, "Share"))
        },
        addToPlaylist = { track -> playlistPickerTrack = track },
        goToArtist = { track ->
            track.artistUrl?.let { nav.navigate("artist/${Uri.encode(it)}") }
        },
        playNext = { track -> controller.enqueueNext(track) },
        addToQueue = { track -> controller.addToQueue(track) },
        startRadio = { track -> controller.startRadio(track) },
    )
    actions.openSleepTimer = { showSleepTimerRoot = true }

    // The expandable player sheet: one sheet, two resting states —
    // the mini strip (collapsed) and the full player (expanded).
    // SMOOTHNESS (v1.0.5): drag progress is consumed ONLY in the
    // sheet layer's graphicsLayer (translation + alpha) and in these
    // two derived booleans — the player content never recomposes or
    // re-lays-out because the finger moved; the layer is rasterised
    // once and simply re-composited at the new offset each frame.
    val sheet = remember { PlayerSheetState(scope) }

    // Window behaviour driven by Settings (both rows must do
    // exactly what they say):
    //  - "Hide status bar in player": while the full player is
    //    open, the status bar steps aside; it returns the moment
    //    the sheet collapses or the row is switched off.
    //  - "High refresh rate": ask the display for its fastest
    //    mode while the row is on; back to the system choice
    //    when it's off.
    val activity = context as? android.app.Activity
    LaunchedEffect(settings.hideStatusInPlayer) {
        val window = activity?.window ?: return@LaunchedEffect
        val controllerCompat =
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (!settings.hideStatusInPlayer) {
            controllerCompat.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            return@LaunchedEffect
        }
        // snapshotFlow, not a composition read: the root must not
        // recompose for every drag frame of the sheet.
        androidx.compose.runtime.snapshotFlow { sheet.progress >= 0.999f }
            .collect { expanded ->
                if (expanded) {
                    controllerCompat.hide(
                        androidx.core.view.WindowInsetsCompat.Type.statusBars(),
                    )
                } else {
                    controllerCompat.show(
                        androidx.core.view.WindowInsetsCompat.Type.statusBars(),
                    )
                }
            }
    }
    LaunchedEffect(settings.highRefreshRate) {
        val window = activity?.window ?: return@LaunchedEffect
        val attrs = window.attributes
        attrs.preferredRefreshRate = if (settings.highRefreshRate) {
            runCatching {
                @Suppress("DEPRECATION")
                activity.display?.supportedModes
                    ?.maxOfOrNull { it.refreshRate } ?: 0f
            }.getOrDefault(0f)
        } else {
            0f
        }
        window.attributes = attrs
    }
    var queueOpen by remember { mutableStateOf(false) }
    val sheetConnection = remember { sheet.nestedScrollConnection() }
    val headerDragState = rememberDraggableState { delta -> sheet.dragBy(delta) }
    val headerDrag = Modifier.draggable(
        state = headerDragState,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> sheet.settle(velocity) },
    )
    val stripDragState = rememberDraggableState { delta -> sheet.dragBy(delta) }
    val sheetActive by remember { derivedStateOf { sheet.progress > 0f } }
    val sheetMostlyOpen by remember { derivedStateOf { sheet.progress > 0.5f } }

    // Frosted-glass backdrop: the page content records itself here;
    // the dock and mini strip blur it live behind themselves.
    val backdrop = rememberBackdropState()

    // Dock shrink (v1.0.38-test — Plavio's "Glass Shrink",
    // ported on the owner's order, its MainActivity shell
    // copied mechanism-for-mechanism): a hysteresis latch on
    // the shell's nested scroll — a clear scroll DOWN (content
    // moving up, available.y < −10) folds the capsule, a clear
    // scroll UP (available.y > 10) unfolds it, and nothing in
    // between moves it, so the capsule never dances. One
    // Animatable on Plavio's own spring (dampingRatio 0.92,
    // stiffness 205) drives the fold CapsuleDock renders:
    // width × (1 − 0.15·fold), height 62 − 15·fold, tab names
    // eaten. v1.0.39-test: the SAME fold value also drives
    // the mini-player strip above the dock (its width
    // fraction + its internal height fold — see MiniPlayer),
    // on the owner's order that the player capsule shrink
    // with the dock capsule, same way; with no song playing
    // there is no strip and the dock fold is unchanged. The connection consumes NOTHING (returns
    // Offset.Zero), so every page scrolls exactly as before;
    // it hangs on the content stage below, so EVERY tab's
    // scroll drives it, the way Plavio's shell connection
    // wraps its whole content box.
    var compactDock by remember { mutableStateOf(false) }
    val dockFoldAnim = remember { Animatable(0f) }
    LaunchedEffect(compactDock) {
        dockFoldAnim.animateTo(
            targetValue = if (compactDock) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 205f),
        )
    }
    val dockScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y < -10) compactDock = true
                else if (available.y > 10) compactDock = false
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(playState.current == null) {
        if (playState.current == null) {
            sheet.reset()
            queueOpen = false
        }
    }
    BackHandler(enabled = queueOpen) { queueOpen = false }
    BackHandler(enabled = !queueOpen && sheetMostlyOpen) { sheet.collapse() }

    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route ?: "home"
    val tabRoute = when {
        route.startsWith("home") -> "home"
        route.startsWith("explore") || route.startsWith("mood") -> "explore"
        route.startsWith("search") || route.startsWith("mix") -> "search"
        route.startsWith("library") || route.startsWith("ytlib") ||
            route.startsWith("ytplaylist") || route.startsWith("playlist") ||
            route.startsWith("favorites") || route.startsWith("playlists") ||
            route.startsWith("history") || route.startsWith("artists") ||
            route.startsWith("artist") || route.startsWith("downloads") -> "library"
        // Settings is not a capsule tab anymore (owner's v1.0.7
        // order): the one settings door is the top-right profile
        // avatar. These routes map to a key no dock tab carries, so
        // while Settings/Account/Login is open no tab highlights —
        // the page sits over the tabs, above the dock.
        route.startsWith("settings") || route.startsWith("account") ||
            route.startsWith("login") -> "settings"
        else -> "home"
    }
    val miniVisible = playState.current != null
    var stageTopY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ForteColors.Bg),
    ) {
      Box(
        modifier = Modifier
            .fillMaxSize()
            // Top inset only. The bottom of the stage is the physical
            // bottom edge of the screen: page content runs all the
            // way down and scrolls BEHIND the floating dock (owner's
            // rule — no solid band behind the glass). Only the dock
            // column itself respects the navigation-bar inset.
            .windowInsetsPadding(WindowInsets.statusBars)
            .onGloballyPositioned { stageTopY = it.positionInWindow().y },
      ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(dockScrollConnection)
                .recordBackdrop(backdrop),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDockClearance provides if (miniVisible) 158.dp else 92.dp,
            ) {
            NavHost(
                navController = nav,
                startDestination = "home",
                // Short, calm fades between pages — no snaps.
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(160)) },
            ) {
                composable("home") {
                    HomeScreen(
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        // The avatar door:
                        // signed OUT, the tap goes straight to
                        // Settings; signed IN, it opens the account
                        // sheet with its Open Settings row.
                        onOpenAccount = {
                            if (com.kotlinsyd.forte.data.YtAccount.isSignedIn()) {
                                accountSheetOpen = true
                            } else {
                                nav.navigate("settings")
                            }
                        },
                        onOpenHistory = { nav.navigate("history") },
                        onOpenPlaylist = { nav.navigate("playlist/user/$it") },
                        onOpenYtPlaylist = { browseId ->
                            nav.navigate(
                                "ytlib/playlist:${Uri.encode(browseId)}/${Uri.encode("Playlist")}"
                            )
                        },
                        onOpenMix = { title, q ->
                            nav.navigate("mix/${Uri.encode(title)}/${Uri.encode(q)}")
                        },
                        onOpenMood = { title, browseId, params ->
                            nav.navigate(
                                "mood/${Uri.encode(title)}/${Uri.encode(browseId)}/${Uri.encode(params ?: "")}"
                            )
                        },
                    )
                }
                composable(
                    "mood/{title}/{browseId}/{params}",
                    arguments = listOf(
                        navArgument("title") { type = NavType.StringType },
                        navArgument("browseId") { type = NavType.StringType },
                        navArgument("params") { type = NavType.StringType },
                    ),
                ) { entry ->
                    MoodScreen(
                        title = entry.arguments!!.getString("title")!!,
                        browseId = entry.arguments!!.getString("browseId")!!,
                        params = entry.arguments!!.getString("params")
                            ?.takeIf { it.isNotBlank() },
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onOpenYtPlaylist = { browseId ->
                            nav.navigate(
                                "ytlib/playlist:${Uri.encode(browseId)}/${Uri.encode("Playlist")}"
                            )
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("explore") {
                    ExploreScreen(
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onOpenAccount = {
                            if (com.kotlinsyd.forte.data.YtAccount.isSignedIn()) {
                                accountSheetOpen = true
                            } else {
                                nav.navigate("settings")
                            }
                        },
                        onOpenYtPlaylist = { browseId ->
                            nav.navigate(
                                "ytlib/playlist:${Uri.encode(browseId)}/${Uri.encode("Playlist")}"
                            )
                        },
                        onOpenMood = { title, browseId, params ->
                            nav.navigate(
                                "mood/${Uri.encode(title)}/${Uri.encode(browseId)}/${Uri.encode(params ?: "")}"
                            )
                        },
                    )
                }
                composable("account") {
                    AccountScreen(
                        onOpenLogin = { nav.navigate("login") },
                        onOpenYtLibrary = { kind, title ->
                            nav.navigate("ytlib/$kind/${Uri.encode(title)}")
                        },
                        onOpenStats = { nav.navigate("stats") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("stats") {
                    StatsScreen(onBack = { nav.popBackStack() })
                }
                composable("search") {
                    SearchScreen(
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onOpenMix = { title, q ->
                            nav.navigate("mix/${Uri.encode(title)}/${Uri.encode(q)}")
                        },
                        onOpenYtPlaylist = { nav.navigate("playlist/yt/${Uri.encode(it)}") },
                        onOpenArtist = { nav.navigate("artist/${Uri.encode(it)}") },
                    )
                }
                composable("library") {
                    LibraryScreen(
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onOpenDownloads = { nav.navigate("downloads") },
                        onOpenArtist = { nav.navigate("artist/${Uri.encode(it)}") },
                        onOpenFavorites = { nav.navigate("favorites") },
                        onOpenPlaylists = { nav.navigate("playlists") },
                        onOpenHistory = { nav.navigate("history") },
                        onOpenArtists = { nav.navigate("artists") },
                        onOpenPlaylist = { nav.navigate("playlist/user/$it") },
                        onOpenYtLibrary = { kind, title ->
                            nav.navigate("ytlib/$kind/${Uri.encode(title)}")
                        },
                        onOpenLogin = { nav.navigate("login") },
                        onOpenStats = { nav.navigate("stats") },
                    )
                }
                composable("downloads") {
                    DownloadsScreen(
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onOpenDownloads = { nav.navigate("downloads") },
                        onBack = { nav.popBackStack() },
                        onOpenLogin = { nav.navigate("login") },
                        onOpenAccount = { nav.navigate("account") },
                        onOpenStats = { nav.navigate("stats") },
                        onOpenHistory = { nav.navigate("history") },
                    )
                }
                composable("login") {
                    LoginScreen(
                        onDone = {
                            app.accountVersion.value++
                            nav.popBackStack()
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "ytlib/{kind}/{title}",
                    arguments = listOf(
                        navArgument("kind") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    ),
                ) { entry ->
                    YtLibraryScreen(
                        kind = entry.arguments!!.getString("kind")!!,
                        title = entry.arguments!!.getString("title")!!,
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onOpenYtPlaylistTracks = { browseId, title ->
                            nav.navigate("ytlib/playlist:${Uri.encode(browseId)}/${Uri.encode(title)}")
                        },
                        onOpenArtist = { nav.navigate("artist/${Uri.encode(it)}") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("favorites") {
                    FavoritesScreen(actions, playState.current?.id, playState.playing) {
                        nav.popBackStack()
                    }
                }
                composable("playlists") {
                    PlaylistsListScreen(
                        onOpenPlaylist = { nav.navigate("playlist/user/$it") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("history") {
                    HistoryScreen(actions, playState.current?.id, playState.playing) {
                        nav.popBackStack()
                    }
                }
                composable("artists") {
                    ArtistsListScreen(
                        onOpenArtist = { nav.navigate("artist/${Uri.encode(it)}") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "playlist/user/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    UserPlaylistScreen(
                        playlistId = entry.arguments!!.getLong("id"),
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onBack = { nav.popBackStack() },
                        onDeleted = { nav.popBackStack() },
                    )
                }
                composable(
                    "playlist/yt/{url}",
                    arguments = listOf(navArgument("url") { type = NavType.StringType }),
                ) { entry ->
                    YtPlaylistScreen(
                        url = entry.arguments!!.getString("url")!!,
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "artist/{url}",
                    arguments = listOf(navArgument("url") { type = NavType.StringType }),
                ) { entry ->
                    ArtistScreen(
                        channelUrl = entry.arguments!!.getString("url")!!,
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onOpenYtPlaylist = { nav.navigate("playlist/yt/${Uri.encode(it)}") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "mix/{title}/{query}",
                    arguments = listOf(
                        navArgument("title") { type = NavType.StringType },
                        navArgument("query") { type = NavType.StringType },
                    ),
                ) { entry ->
                    MixScreen(
                        title = entry.arguments!!.getString("title")!!,
                        query = entry.arguments!!.getString("query")!!,
                        actions = actions,
                        currentTrackId = playState.current?.id,
                        isPlaying = playState.playing,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            }
        }

        Column(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                // The dock floats over the page: it alone lifts
                // itself above the navigation-bar area while content
                // flows behind it to the physical edge.
                .windowInsetsPadding(WindowInsets.navigationBars)
                // Side margins are Plavio's dock seat
                // (v1.0.39-test): 16dp each side, so the two
                // bars span exactly the width Plavio's capsule
                // spans (its MainActivity dock padding).
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (miniVisible) {
                // The collapsed state of the player sheet. It keeps its
                // exact locked spot above the dock; sliding it up (or
                // tapping it) expands the sheet, and it fades as the
                // full player rises over it. Its measured top is the
                // sheet's travel distance, so finger and sheet move
                // the same number of pixels.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            val top = coords.positionInWindow().y - stageTopY
                            if (top > 100f) sheet.travelPx = top
                        }
                        .graphicsLayer { alpha = 1f - sheet.progress }
                        .draggable(
                            state = stripDragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> sheet.settle(velocity) },
                        ),
                    // The strip folds WITH the dock (v1.0.39-test):
                    // centred in its full-width seat so its fold
                    // narrowing (the dock's own × (1 − 0.15·fold))
                    // closes from both sides, like the capsule
                    // below it. The seat itself — this box, its
                    // measured top, the drag — never moves.
                    contentAlignment = Alignment.Center,
                ) {
                    MiniPlayer(
                        state = playState,
                        glass = settings.glassEffects,
                        onTap = { sheet.expand() },
                        onTogglePlay = { controller.togglePlayPause() },
                        onNext = { controller.next(manual = true) },
                        modifier = Modifier.fillMaxWidth(1f - 0.15f * dockFoldAnim.value),
                        backdrop = backdrop,
                        fold = dockFoldAnim.value,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            CapsuleDock(
                selectedRoute = tabRoute,
                onSelect = { target ->
                    // Tab discipline (owner's rule: wherever I am,
                    // pressing a tab takes me to that tab): navigate
                    // UNCONDITIONALLY — the old code compared the
                    // target against the current destination route
                    // and, from deep routes whose tabRoute maps to a
                    // non-dock key (settings/account/login), could
                    // swallow the press entirely. A tab press is a
                    // fresh start: everything stacked above the
                    // start destination is cleared, no state saved
                    // or restored.
                    nav.navigate(target) {
                        popUpTo(nav.graph.startDestinationId) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                },
                glass = settings.glassEffects,
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                fold = dockFoldAnim.value,
            )
        }

        // The expanded state of the sheet: the full player. Its motion
        // is pure graphicsLayer — translation and alpha read the drag
        // progress in the draw phase only, so the player's heavy
        // content (artwork, palette, lyrics) is rasterised once and
        // never recomposes, re-lays-out or repaints because the sheet
        // moved. At rest-expanded it covers the dock and content
        // exactly like the old player page.
        val sheetTrack = playState.current
        if (sheetTrack != null && sheetActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - sheet.progress) * sheet.travelPx
                        alpha = sheet.progress.coerceIn(0f, 1f)
                    },
            ) {
                PlayerScreen(
                    state = playState,
                    actions = actions,
                    onCollapse = { sheet.collapse() },
                    onOpenQueue = { queueOpen = true },
                    headerDrag = headerDrag,
                    sheetScroll = sheetConnection,
                )
            }
        }

        // Queue rides over the sheet (it is opened from the player).
        AnimatedVisibility(
            visible = queueOpen && sheetTrack != null,
            enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(220)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ForteColors.Bg),
            ) {
                QueueScreen(
                    state = playState,
                    actions = actions,
                    onBack = { queueOpen = false },
                )
            }
        }

        // The account door: a floating frosted-glass card, detached
        // from every edge, with the account at the top and Settings
        // as a clean row inside it. It uses the same recorded stage
        // backdrop as the capsule, so the blur behind the card is
        // the real page, not a dim fake.
        AnimatedVisibility(
            visible = accountSheetOpen,
            enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { it / 3 } + fadeOut(tween(180)),
        ) {
            BackHandler(enabled = true) { accountSheetOpen = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable { accountSheetOpen = false },
            ) {
                val name = accountCardName ?: "YouTube account"
                val handle = accountCardHandle
                val avatarUrl = accountCardAvatar
                GlassBox(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        // The card floats clear of the dock: its
                        // bottom edge sits 12dp above the capsule.
                        .padding(
                            start = 18.dp, end = 18.dp,
                            bottom = LocalDockClearance.current + 12.dp,
                        )
                        .clickable { },
                    shape = RoundedCornerShape(28.dp),
                    glass = settings.glassEffects,
                    backdrop = backdrop,
                    dark = true,
                    blurRadius = 44f,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(ForteColors.SurfaceHi)
                                    .size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!avatarUrl.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = "Your account",
                                        modifier = Modifier.fillMaxSize()
                                            .clip(androidx.compose.foundation.shape.CircleShape),
                                    )
                                } else {
                                    Text(
                                        name.trim().firstOrNull()?.uppercase() ?: "?",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 19.sp,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    handle?.takeIf { it.isNotBlank() } ?: "No channel",
                                    color = ForteColors.TextDim, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    name, color = Color.White, fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (avatarUrl.isNullOrBlank() ||
                                    accountCardName == null
                                ) {
                                    accountIdentityNote?.let { note ->
                                        Text(
                                            note,
                                            color = ForteColors.TextDim,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // One continuous menu panel (v1.0.14): the
                        // rows carry no boxes of their own and no
                        // chevrons — hairlines alone separate them.
                        @Composable
                        fun accountCardRow(
                            label: String,
                            icon: androidx.compose.ui.graphics.vector.ImageVector,
                            divider: Boolean,
                            fn: () -> Unit,
                        ) {
                            if (divider) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 48.dp, end = 12.dp)
                                        .height(1.dp)
                                        .background(ForteColors.Border.copy(alpha = 0.6f)),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clickable { fn() }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    icon, contentDescription = label,
                                    tint = Color.White, modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    label, color = Color.White, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        fun cardGo(route: String) {
                            accountSheetOpen = false
                            nav.navigate(route)
                        }
                        accountCardRow("Settings", Icons.Filled.Settings, divider = false) {
                            cardGo("settings")
                        }
                        accountCardRow("Your account & library", Icons.Filled.Person, divider = true) {
                            cardGo("account")
                        }
                        accountCardRow("Sign out", Icons.AutoMirrored.Filled.Logout, divider = true) {
                            accountSheetOpen = false
                            com.kotlinsyd.forte.data.YtAccount.signOut()
                            app.settings.clearAccount()
                            app.accountVersion.value++
                            app.refreshYtLiked()
                        }
                    }
                }
            }
        }
      }

    // Update offer: the stable feed says a newer version exists.
    // The APK downloads INSIDE the app and goes straight to the
    // system installer — no browser (v1.0.5).
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            onDismiss = { updateInfo = null },
            onSkip = {
                UpdateChecker.skip(context, info.code)
                updateInfo = null
            },
        )
    }

    // First open: pick a region before Home loads (device locale
    // preselected). Dismissing keeps the device default.
    var showRegionPicker by remember { mutableStateOf(false) }
    LaunchedEffect(settings.regionChosen) {
        if (!settings.regionChosen) showRegionPicker = true
    }
    if (showRegionPicker) {
        RegionPickerDialog(
            current = app.settings.region(),
            onPick = { region ->
                app.settings.setRegion(region)
                showRegionPicker = false
            },
            onDismiss = {
                app.settings.setRegion(com.kotlinsyd.forte.data.Regions.deviceDefault())
                showRegionPicker = false
            },
        )
    }

    if (showSleepTimerRoot) {
        SleepTimerDialog(onDismiss = { showSleepTimerRoot = false })
    }

    // Honest one-line messages (account actions that YouTube refused,
    // playlist adds, and the like).
    val userMsg by app.userMessage.collectAsState()
    AnimatedVisibility(
        visible = userMsg != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300)),
        modifier = Modifier
            .align(androidx.compose.ui.Alignment.BottomCenter)
            .padding(bottom = 110.dp, start = 24.dp, end = 24.dp),
    ) {
        Box(
            modifier = Modifier
                .forteGlassChip(RoundedCornerShape(ForteGlass.RadiusCapsule))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(userMsg ?: "", color = Color.White, fontSize = 13.sp)
        }
    }

    // Add-to-playlist picker (global: player, rows, menus all land here).
    var creatingYtForTrack by remember { mutableStateOf<Track?>(null) }
    playlistPickerTrack?.let { track ->
        val signedIn = remember(track) {
            com.kotlinsyd.forte.data.YtAccount.isSignedIn()
        }
        var ytPlaylists by remember(track) {
            mutableStateOf<List<com.kotlinsyd.forte.data.YtAccount.AccountPlaylist>?>(null)
        }
        LaunchedEffect(track, signedIn) {
            if (signedIn) {
                ytPlaylists = runCatching {
                    com.kotlinsyd.forte.data.YtAccount.playlists()
                }.getOrNull()
            }
        }
        AlertDialog(
            onDismissRequest = { playlistPickerTrack = null },
            containerColor = ForteColors.SurfaceHi,
            title = { Text("Add to playlist", color = Color.White) },
            text = {
                LazyColumn {
                    items(playlists) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    playlistPickerTrack = null
                                    scope.launch { app.library.addToPlaylist(playlist.id, track) }
                                }
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                        ) {
                            Text(playlist.name, color = Color.White, fontSize = 15.sp)
                        }
                    }
                    item {
                        TextButton(onClick = {
                            creatingForTrack = track
                            playlistPickerTrack = null
                        }) {
                            Text("New playlist", color = ForteColors.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (signedIn) {
                        item {
                            Text(
                                "YOUR YOUTUBE PLAYLISTS",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = ForteColors.Red, letterSpacing = 2.sp,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        val ytp = ytPlaylists
                        if (ytp == null) {
                            item {
                                Text(
                                    "Loading your playlists…",
                                    color = ForteColors.TextDim, fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        } else {
                            items(ytp) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            playlistPickerTrack = null
                                            scope.launch {
                                                runCatching {
                                                    com.kotlinsyd.forte.data.YtAccount
                                                        .addToYtPlaylist(playlist.playlistId, track.id)
                                                }.onSuccess {
                                                    app.showMessage(
                                                        "Added to \"${playlist.title}\" on YouTube"
                                                    )
                                                }.onFailure {
                                                    app.showMessage(
                                                        "YouTube refused that add — nothing was saved there."
                                                    )
                                                }
                                            }
                                        }
                                        .padding(vertical = 12.dp, horizontal = 6.dp),
                                ) {
                                    Text(playlist.title, color = Color.White, fontSize = 15.sp)
                                }
                            }
                            item {
                                TextButton(onClick = {
                                    creatingYtForTrack = track
                                    playlistPickerTrack = null
                                }) {
                                    Text(
                                        "New YouTube playlist",
                                        color = ForteColors.Red, fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { playlistPickerTrack = null }) {
                    Text("Cancel", color = ForteColors.TextDim)
                }
            },
        )
    }

    creatingForTrack?.let { track ->
        PlaylistNameDialog(
            title = "New playlist",
            initial = "",
            onDismiss = { creatingForTrack = null },
            onConfirm = { name ->
                creatingForTrack = null
                scope.launch {
                    val id = app.library.createPlaylist(name)
                    app.library.addToPlaylist(id, track)
                }
            },
        )
    }

    creatingYtForTrack?.let { track ->
        PlaylistNameDialog(
            title = "New YouTube playlist",
            initial = "",
            onDismiss = { creatingYtForTrack = null },
            onConfirm = { name ->
                creatingYtForTrack = null
                scope.launch {
                    runCatching {
                        val id = com.kotlinsyd.forte.data.YtAccount.createYtPlaylist(name)
                        com.kotlinsyd.forte.data.YtAccount.addToYtPlaylist(id, track.id)
                    }.onSuccess {
                        app.showMessage("Created \"$name\" on YouTube and added the song")
                    }.onFailure {
                        app.showMessage("YouTube refused that — no playlist was created.")
                    }
                }
            },
        )
    }
    }
}
