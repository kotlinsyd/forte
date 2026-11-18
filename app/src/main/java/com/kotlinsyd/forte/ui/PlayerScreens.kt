package com.kotlinsyd.forte.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.data.AudioQuality
import com.kotlinsyd.forte.data.Lyrics
import com.kotlinsyd.forte.data.YtAccount
import com.kotlinsyd.forte.data.formatDuration
import com.kotlinsyd.forte.playback.PlaybackState
import com.kotlinsyd.forte.playback.RepeatMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PlayerScreen(
    state: PlaybackState,
    actions: TrackActions,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    headerDrag: Modifier,
    sheetScroll: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
) {
    val app = ForteApp.instance
    val controller = app.player
    val track = state.current
    val forteSettings by app.settings.settings.collectAsState()
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    val ytLikedIds by app.ytLikedIds.collectAsState()
    val downloads by app.library.downloads.collectAsState(initial = emptyList())
    val dlStates by app.downloads.states.collectAsState()
    var lyricsResult by remember(track?.id) {
        mutableStateOf<com.kotlinsyd.forte.data.LyricsResult?>(null)
    }
    var showFullLyrics by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var credits by remember(track?.id) {
        mutableStateOf<List<YtAccount.SongCredit>?>(null)
    }
    val scope = rememberCoroutineScope()
    /** Per-song lyrics timing shift (±5 s), for lyrics that run
     * early or late against this exact recording. */
    var lyricsOffsetMs by remember(track?.id) { mutableStateOf(0L) }
    val lyrics = lyricsResult?.plain

    LaunchedEffect(track?.id) {
        lyricsResult = null
        if (track != null) lyricsResult = Lyrics.fetchResult(track)
    }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(4000)
            controller.clearError()
        }
    }

    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing playing", color = ForteColors.TextDim)
        }
        return
    }

    LaunchedEffect(showCredits, track.id) {
        if (showCredits && credits == null) {
            credits = YtAccount.trackCredits(track.id)
        }
    }

    // Like state reflects the account too when signed in.
    val isFavorite = track.id in favoriteIds || track.id in ytLikedIds
    val isDisliked = track.id in forteSettings.dislikedIds
    val toggleDislike: () -> Unit = {
        val target = !isDisliked
        val previous = forteSettings.dislikedIds
        app.settings.setDislikedIds(
            if (target) previous + track.id else previous - track.id,
        )
        if (target && isFavorite) {
            when {
                track.id in favoriteIds -> actions.toggleFavorite(track)
                track.id in ytLikedIds -> scope.launch {
                    runCatching { YtAccount.setLiked(track.id, false) }
                        .onSuccess { app.ytLikedIds.value = ytLikedIds - track.id }
                        .onFailure {
                            app.showMessage(
                                "Couldn't remove your YouTube like for this song.",
                            )
                        }
                }
            }
        }
        if (YtAccount.isSignedIn()) {
            scope.launch {
                runCatching { YtAccount.setDisliked(track.id, target) }
                    .onFailure {
                        app.settings.setDislikedIds(previous)
                        app.showMessage(
                            "Couldn't update your YouTube dislike — the change was undone.",
                        )
                    }
            }
        }
        // Thumb down (owner's order, v1.0.18): a NEW dislike also
        // changes the song at once — the listener has said they
        // don't want this one. Removing a dislike never skips.
        if (target) controller.next(manual = true)
    }
    val isDownloaded = downloads.any { it.videoId == track.id }
    val dlProgress = dlStates[track.id]
    // Posters: YTM cover first; when only a video frame exists, the
    // iTunes net supplies the real album cover (see ArtworkResolver).
    val heroUrl = rememberEnrichedArtwork(track)

    // Artwork-derived colour: the cover's own dominant colour,
    // darkened into the Onyx background. It carries the page
    // while the sharp full-screen artwork (Sample B) loads and
    // feeds the app-wide ambience tint.
    val context = LocalContext.current
    var artColor by remember(track.id) { mutableStateOf(ForteColors.Bg) }
    // (v1.0.28-test: the poster is full-bleed again — cover-fit
    // fills any screen — so its aspect no longer drives any
    // layout and is not tracked here; the palette load below
    // only reads the colour now.)
    LaunchedEffect(track.id, heroUrl) {
        val url = heroUrl
        if (!url.isNullOrBlank()) {
            runCatching {
                val loader = context.imageLoader
                val result = loader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(96, 96)
                        .allowHardware(false)
                        .build()
                )
                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        // Palette runs off the main thread, on a private
                        // copy: the cached bitmap belongs to Coil and
                        // must never be read while it can be recycled.
                        val swatch = kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.Default
                        ) {
                            runCatching {
                                val copy = bitmap.copy(
                                    android.graphics.Bitmap.Config.ARGB_8888, false
                                )
                                val palette =
                                    androidx.palette.graphics.Palette.from(copy).generate()
                                palette.dominantSwatch ?: palette.vibrantSwatch
                                    ?: palette.mutedSwatch
                            }.getOrNull()
                        }
                        if (swatch != null) {
                            // Richer ambience (v1.0.5): the cover's
                            // colour breathes through the page instead
                            // of the old near-black wash.
                            artColor = lerp(Color(swatch.rgb), ForteColors.Bg, 0.30f)
                            // Publish for the app-wide ambience (mini
                            // strip, Home wash).
                            ForteApp.instance.nowPlayingTint.value = artColor
                        }
                    }
                }
            }
        }
    }
    // (No animated palette wash behind the page: artColor
    // still feeds the app-wide ambience tint published
    // above; the page itself is the full-bleed poster under
    // the frost sheet, v1.0.28-test.)
    // The player's own recorded backdrop (v1.0.21-test): the page
    // records its background into this state and the glass sheet
    // + control discs blur it behind themselves — the same
    // machinery the Home stage uses for the dock (ForteRoot),
    // wired for the player page. The glass blur is Plavio's own
    // 22dp since the v1.0.23-test pure-frost change.
    val playerBackdrop = rememberBackdropState()
    val capsuleBlurPx = with(LocalDensity.current) { 22.dp.toPx() }
    // Sleep timer state, visible while armed (as before) —
    // hoisted above the layout in v1.0.28-test: the frost
    // sheet's height makes room for the label (and for the
    // error pill) when either is showing, so both are known
    // before the sheet is measured.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.sleepEndsAtMs) {
        while (state.sleepEndsAtMs > 0) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val sleepLabel = when {
        state.sleepAtTrackEnd -> "Sleep: stops at the end of this song"
        state.sleepEndsAtMs > nowTick ->
            "Sleep in " + formatDuration((state.sleepEndsAtMs - nowTick) / 1000)
        else -> null
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(headerDrag)
            .nestedScroll(sheetScroll),
    ) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // OWNER-LOCKED LAYOUT (v1.0.28-test, Design 2):
        // Sample B with the owner's own modification — his
        // words on his v1.0.23-test player screenshot: "Do
        // this but make button down, and smaller this card
        // to down, so poster can show." The FULL-BLEED
        // poster is the page background again (cover-fit,
        // edge to edge), and Sample B's frost sheet —
        // 36dp rounded top corners, drag handle — returns
        // SMALLER AND LOWER: its top edge sits at ~64% of
        // the screen height, so the poster shows over
        // roughly the top two-thirds. The sheet is
        // bottom-anchored and its frost is the player's
        // live capsule glass over the recorded poster
        // (blurred poster, Plavio's 0.50 tint, the dim
        // silver outline — the arrangement fixed in
        // v1.0.25-test). Inside the sheet the deck rides
        // LOW: a flexible spacer under the handle pools
        // the slack, so the buttons land at the same place
        // for every song (the .26 constant-position rule,
        // kept), with tight spacing — gaps were trimmed
        // before any control size was touched. The sheet
        // grows upward a little while the sleep label or
        // the error pill is showing, so nothing ever
        // clips. The whole surface is still the sheet's
        // drag handle — sliding down anywhere minimizes
        // to the mini-player. (Supersedes the .25/.26
        // centred-poster layout and the un-built
        // full-bleed-no-card sample.)
        //
        // v1.0.29-test — THE SHEET MOVES (owner, on his
        // .28 phone test: "Liked it, it should be movable,
        // at default its should be same like this, but
        // swipe up show more options like what need for a
        // music player"). The resting layout above is
        // untouched: the sheet ALSO drags up to ~82% of
        // the screen (its drag state is [deckSheet] below;
        // tapping the handle toggles), the poster stays
        // full-bleed behind it, and the deck stays
        // anchored at the sheet's bottom in both states.
        // An expanded-only panel — thumbs Like it /
        // Don't like and the quick actions — reveals
        // above the actions row as the sheet grows. One
        // discipline survives from before: a downward
        // drag AT REST is never claimed by the sheet's
        // own drag, so it still minimizes the player
        // exactly as it always did.
        //
        // v1.0.30-test — the owner's two verdicts on .29:
        // (1) in the expanded state the actions row sat at
        // the physical screen edge, half-clipped — heights
        // are now computed against the inset-adjusted
        // screen and the nav inset rides inside the sheet
        // as bottom padding (both states), with the panel
        // capped so it absorbs any overflow, never the
        // deck; (2) the swipe animation was "not smooth at
        // all" — the fraction is now consumed ONLY in
        // layout/draw phases (sheet + panel heights are
        // layout lambdas, the panel is pre-composed, the
        // poster's backdrop recordings freeze for the
        // gesture), so a drag frame costs a measure of
        // this subtree plus texture draws, never a
        // recomposition of the screen.
        val sheetShape = RoundedCornerShape(
            topStart = ForteGlass.RadiusPlayerSheet,
            topEnd = ForteGlass.RadiusPlayerSheet,
        )
        val sheetExtra =
            (if (state.error != null) 50.dp else 0.dp) +
                (if (sleepLabel != null) 28.dp else 0.dp)
        // v1.0.30-test (owner's expanded-state screenshot:
        // the Like / Up Next / Share row sat at the very
        // screen edge, half-clipped): the sheet's heights
        // are now computed against the INSET-ADJUSTED screen
        // — the area above the system navigation bar — and
        // the nav inset itself is carried as bottom padding
        // inside the sheet (see the Column below), in BOTH
        // resting states. The deck's seat relative to the
        // usable screen is unchanged; the sheet simply stops
        // pretending the nav-bar strip is usable height.
        val density = LocalDensity.current
        val navInsetBottom = with(density) {
            WindowInsets.navigationBars.getBottom(density).toDp()
        }
        val usableHeight = maxHeight - navInsetBottom
        val defaultSheetHeight =
            maxOf(usableHeight * 0.36f, 300.dp) + sheetExtra + navInsetBottom
        val expandedSheetHeight =
            maxOf(usableHeight * 0.82f + navInsetBottom, defaultSheetHeight)
        // Per-track sheet state: a new song always meets
        // the sheet at rest (fraction 0).
        val deckSheet = remember(track.id) { PlayerDeckSheetState(scope) }
        // While the deck drags or settles, the player's
        // backdrop recordings freeze (v1.0.30-test): the
        // recorded poster can't change mid-gesture, and the
        // re-record/re-blur per drag frame was frame cost
        // for nothing. The last recording keeps serving.
        LaunchedEffect(deckSheet) {
            snapshotFlow { deckSheet.motionActive }.collect { active ->
                playerBackdrop.frozen = active
            }
        }
        // The expanded panel's height cap (v1.0.30-test):
        // the deck's fixed content — everything in the sheet
        // except the weight spacer and the panel itself —
        // measured at its WORST combination (2-line title,
        // stream-info line on, sleep label AND error pill
        // showing), summed from the layout constants below
        // (line heights ≈ 1.25em): handle zone 18 + eyebrow
        // 14 + gap 4 + title block 82 + stream info 18 +
        // gap 10 + seek 28 + times 15 + gap 8 + transport 68
        // + gap 10 + divider 1 + gap 6 + actions row 37 +
        // sleep 21 + error 44 = 384dp. Whatever of the
        // expanded sheet the deck doesn't need, the panel
        // may use — and if the deck needs MORE than the
        // expanded sheet leaves, the PANEL absorbs the
        // difference (it compresses/clips first), never the
        // deck: on a small screen the panel lands short of
        // its full 122dp and the Like / Up Next / Share row
        // still sits whole above the nav inset.
        val deckFixedWorst = 384.dp
        val panelCapHeight = (
            expandedSheetHeight - navInsetBottom - 16.dp - deckFixedWorst
            ).coerceAtLeast(0.dp)
        val sheetRangePx = with(LocalDensity.current) {
            (expandedSheetHeight - defaultSheetHeight).toPx()
        }
        val sheetRangePxState = rememberUpdatedState(sheetRangePx)
        // The recorded page: the full-bleed poster over
        // the artwork gradient, with a light veil that
        // deepens toward the sheet. The sheet and the top
        // discs are drawn after it, outside the recorded
        // subtree, and sample it through the player
        // backdrop — the same wiring the discs already use.
        Box(
            Modifier
                .fillMaxSize()
                .background(ForteColors.Bg)
                .recordBackdrop(playerBackdrop),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(artworkGradient(track.title)),
                    ),
            )
            if (!heroUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    // The player keeps the largest form
                    // (the HERO rung of the ladder —
                    // w1080 / 1200bb), with the artwork
                    // gradient standing in while it loads
                    // or if it fails.
                    model = com.kotlinsyd.forte.data.artworkAt(
                        heroUrl,
                        com.kotlinsyd.forte.data.ArtSize.HERO,
                    ),
                    placeholder = rememberArtworkPainter(track.title),
                    error = rememberArtworkPainter(track.title),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            0.78f to ForteColors.Bg.copy(alpha = 0.38f),
                            1f to ForteColors.Bg.copy(alpha = 0.55f),
                        ),
                    ),
            )
        }
        // The frost sheet, bottom-anchored. Its own
        // vertical drag (v1.0.29-test) grows/shrinks it
        // between the two resting heights; the detector is
        // hand-rolled (not detectVerticalDragGestures) so
        // consumption stays under this sheet's control:
        // a change is consumed only once the drag is
        // claimed — an upward pull, or any pull while the
        // sheet stands part-way up. A downward drag at
        // rest is left unconsumed on purpose and falls
        // through to the player sheet's header drag,
        // which minimizes to the mini-player as before.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // The sheet's height is read from the drag
                // fraction HERE, in the layout phase only
                // (v1.0.30-test): v1.0.29-test read it during
                // composition, so every drag frame recomposed
                // the whole player screen — artwork, palette
                // state, the lot — and then re-laid-out anyway.
                // Now a fraction change re-measures this
                // sheet's subtree and redraws; the screen
                // never recomposes for motion, and the glass
                // behind the sheet is a fixed recorded texture
                // whose clip window simply grows (the backdrop
                // recordings are also frozen for the gesture —
                // see BackdropState.frozen).
                .layout { measurable, constraints ->
                    val targetHeight = defaultSheetHeight +
                        (expandedSheetHeight - defaultSheetHeight) *
                        deckSheet.fraction
                    val hPx = targetHeight.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(minHeight = hPx, maxHeight = hPx),
                    )
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                }
                .liveCapsuleGlass(playerBackdrop, sheetShape, capsuleBlurPx)
                .pointerInput(track.id) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(
                            down.uptimeMillis, down.position,
                        )
                        var claimed = false
                        var undecidedDy = 0f
                        var lastY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes
                                .firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            if (!claimed) {
                                undecidedDy += dy
                                if (abs(undecidedDy) > viewConfiguration.touchSlop) {
                                    claimed = deckSheet.fraction > 0f ||
                                        undecidedDy < 0f
                                    if (claimed) {
                                        // The whole travel so
                                        // far counts — no dead
                                        // zone past the slop.
                                        deckSheet.dragBy(
                                            undecidedDy,
                                            sheetRangePxState.value,
                                        )
                                        change.consume()
                                        velocityTracker.addPosition(
                                            change.uptimeMillis, change.position,
                                        )
                                    }
                                }
                            } else {
                                change.consume()
                                velocityTracker.addPosition(
                                    change.uptimeMillis, change.position,
                                )
                                deckSheet.dragBy(dy, sheetRangePxState.value)
                            }
                        }
                        if (claimed) {
                            deckSheet.settle(velocityTracker.calculateVelocity().y)
                        }
                    }
                },
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                // The nav inset as bottom padding inside the
                // sheet, both states (v1.0.30-test): the deck's
                // last row always lands above the system bar,
                // never under it or clipped by the screen edge.
                .padding(bottom = 16.dp + navInsetBottom),
        ) {
            // The sheet's drag handle (Sample B). Tapping
            // it toggles the sheet between its two resting
            // heights (v1.0.29-test); dragging it — or
            // anywhere on the sheet — moves it by finger.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { deckSheet.toggle() }
                    .padding(top = 9.dp, bottom = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.28f)),
                )
            }
            // The deck rides LOW (owner: "make button
            // down"): the slack pools here, under the
            // handle — the buttons never wander with the
            // title's length or the poster's shape.
            Spacer(Modifier.weight(1f))
            // Eyebrow: the old header's status line, unchanged.
            Text(
                if (forteSettings.hideSongStatus) {
                    "NOW PLAYING"
                } else if (state.radio) {
                    "PLAYING FROM RADIO"
                } else {
                    "PLAYING FROM " + (state.origin ?: "QUEUE").uppercase()
                },
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = ForteColors.TextDim, letterSpacing = 2.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Title block (tap opens Credits, as before).
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { showCredits = true },
            ) {
                Text(
                    // Two lines now (v1.0.24-test, fitted
                    // titles): a long song title wraps onto a
                    // second line and trims with an ellipsis
                    // instead of being cut at one line.
                    track.title, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist, fontSize = 16.sp, color = ForteColors.TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // What is actually streaming (codec · bitrate), or
            // the saved-file note for downloads — the Settings
            // "Stream info line" row owns whether this shows.
            if (forteSettings.streamInfoLine) {
                state.streamInfo?.let { info ->
                    Spacer(Modifier.height(4.dp))
                    Text(info, fontSize = 11.sp, color = ForteColors.TextDim)
                }
            }

            // Seek — the old deck's plumbing unchanged; the
            // times row now shows the total on the right, as
            // Sample B does (it showed "-remaining" before).
            var scrubbing by remember { mutableStateOf<Float?>(null) }
            val duration = state.durationMs.coerceAtLeast(1)
            val shownPos = (scrubbing?.let { (it * duration).toLong() } ?: state.positionMs)
                .coerceIn(0L, duration)
            Spacer(Modifier.height(10.dp))
            ForteProgressBar(
                fraction = (shownPos.toFloat() / duration).coerceIn(0f, 1f),
                scrubbing = scrubbing != null,
                onScrub = { scrubbing = it },
                onSeekFraction = { fraction ->
                    controller.seekTo((fraction * duration).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(shownPos / 1000), fontSize = 12.sp, color = ForteColors.TextDim)
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(duration / 1000),
                    fontSize = 12.sp, color = ForteColors.TextDim,
                )
            }
            Spacer(Modifier.height(8.dp))

            // Transport (Sample B): plain glyphs on the glass —
            // shuffle · prev · big play · next · repeat. The
            // play control is a solid WHITE disc with a dark
            // glyph; shuffle/repeat state still reads in the red
            // tint; the true-red press ring survives on the play
            // disc (the owner's red-on-touch rule).
            val pausePress = remember { MutableInteractionSource() }
            val pausePressed by pausePress.collectIsPressedAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { controller.setShuffle(!state.shuffle) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Shuffle, contentDescription = "Shuffle",
                        tint = if (state.shuffle) ForteColors.Red else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(21.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable { controller.previous() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious, contentDescription = "Previous",
                        tint = Color.White, modifier = Modifier.size(32.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White, CircleShape)
                        .trueRedPressRing(CircleShape, pausePressed)
                        .clickable(interactionSource = pausePress, indication = LocalIndication.current) {
                            controller.togglePlayPause()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.buffering) {
                        CircularProgressIndicator(
                            color = ForteColors.Bg, modifier = Modifier.size(32.dp),
                        )
                    } else {
                        Icon(
                            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) "Pause" else "Play",
                            tint = ForteColors.Bg, modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable { controller.next(manual = true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SkipNext, contentDescription = "Next",
                        tint = Color.White, modifier = Modifier.size(32.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { controller.cycleRepeat() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.repeat != RepeatMode.OFF) ForteColors.Red else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            Spacer(Modifier.height(6.dp))

            // The EXPANDED PANEL (v1.0.29-test): exists
            // only while the sheet stands grown — its
            // height and alpha ride the sheet's fraction,
            // so it fades/slides in as the sheet rises and
            // is fully gone at rest. No duplicates of the
            // face: the thumbs row is the verdict pair (the
            // up-thumb is the SAME like as the heart below,
            // one store, both read it; the down-thumb is
            // the dislike the more-sheet carries), and the
            // quick row wires three features that already
            // exist — add to playlist, the offline
            // download, the sleep timer — to their own
            // flows.
            //
            // v1.0.30-test: the panel is ALWAYS composed
            // (v1.0.29-test composed/uncomposed it at the
            // 0.01 threshold — a composition hitch mid-drag)
            // and its height is read from the fraction in
            // the LAYOUT phase, like the sheet's own height:
            // 122dp at full growth (116 of rows + the 6dp
            // gap below, now the panel's own last element),
            // 0 at rest — and capped at [panelCapHeight], so
            // on a screen too small for deck + full panel,
            // the panel compresses and the deck never does.
            Column(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val target = minOf(
                            ExpandedPanelFullHeight * deckSheet.fraction,
                            panelCapHeight,
                        )
                        val hPx = target.roundToPx().coerceAtLeast(0)
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = hPx, maxHeight = hPx),
                        )
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(0, 0)
                        }
                    }
                    .clipToBounds()
                    .graphicsLayer { alpha = deckSheet.fraction },
            ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    if (isFavorite) {
                                        actions.toggleFavorite(track)
                                    } else {
                                        actions.toggleFavorite(track)
                                        controller.leanIntoSimilar(track)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.ThumbUp,
                                contentDescription = "Like it",
                                tint = if (isFavorite) ForteColors.Red else Color.White,
                                modifier = Modifier.size(21.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Like it",
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isFavorite) ForteColors.Red else Color.White,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    val wasDisliked = isDisliked
                                    toggleDislike()
                                    if (!wasDisliked) {
                                        app.showMessage(
                                            "Got it — won't play this again",
                                        )
                                    }
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.ThumbDown,
                                contentDescription = "Don't like",
                                tint = if (isDisliked) ForteColors.TrueRed else Color.White,
                                modifier = Modifier.size(21.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Don't like",
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isDisliked) ForteColors.TrueRed else Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PlayerQuickAction(
                            icon = Icons.Filled.PlaylistAdd,
                            label = "Add to playlist",
                            tint = Color.White,
                            modifier = Modifier.weight(1f),
                        ) { actions.addToPlaylist(track) }
                        PlayerQuickAction(
                            icon = if (isDownloaded) Icons.Filled.Check else Icons.Filled.Download,
                            label = if (isDownloaded) "Downloaded" else "Download",
                            tint = if (isDownloaded) ForteColors.Red else Color.White,
                            busy = dlProgress != null && dlProgress.error == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (!isDownloaded && dlProgress == null) {
                                actions.download(track)
                            }
                        }
                        PlayerQuickAction(
                            icon = Icons.Filled.Timer,
                            label = "Sleep timer",
                            tint = if (state.sleepArmed) ForteColors.Red else Color.White,
                            modifier = Modifier.weight(1f),
                        ) { showSleepTimer = true }
                    }
                    // The 6dp gap under the panel (it was a
                    // sibling spacer in v1.0.29-test; it rides
                    // inside the panel's measured height now,
                    // so a capped panel absorbs it too).
                    Spacer(Modifier.height(6.dp))
            }

            // Actions row (Sample B): Liked · Up Next · Share.
            // Everything the old chip row also carried —
            // download, dislike, audio output — lives in the
            // more-sheet now; nothing was dropped.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            if (isFavorite) {
                                actions.toggleFavorite(track)
                            } else {
                                actions.toggleFavorite(track)
                                controller.leanIntoSimilar(track)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "Liked" else "Like",
                        tint = if (isFavorite) ForteColors.Red else Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (isFavorite) "Liked" else "Like",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isFavorite) ForteColors.Red else Color.White,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onOpenQueue() }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.QueueMusic, contentDescription = "Up next",
                        tint = Color.White, modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Up Next", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { actions.share(track) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Share, contentDescription = "Share",
                        tint = Color.White, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Share", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }

            // Sleep timer state, visible while armed (the
            // label itself is computed above the layout —
            // v1.0.28-test hoisted it so the sheet's height
            // can make room for it there).
            if (sleepLabel != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    sleepLabel, fontSize = 12.sp, color = ForteColors.Red,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            // No lyrics card in the deck: lyrics open from the
            // more-sheet, as an overlay (unchanged).

            state.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(ForteColors.Red.copy(alpha = 0.16f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(err, color = Color.White, fontSize = 13.sp)
                }
            }
        }
        }

        // Top row: two frost discs over the poster's top
        // corners — collapse (the sheet-drag does the same)
        // and the more-sheet, which carries everything the
        // old chip row and header menu held. They are drawn
        // after the page column and live OUTSIDE the recorded
        // subtree, sampling the recorded page (poster
        // included) behind themselves, like the dock discs
        // in ForteRoot.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .liveCapsuleGlass(playerBackdrop, CircleShape, capsuleBlurPx)
                    .clickable { onCollapse() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Minimize player",
                    tint = Color.White, modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .liveCapsuleGlass(playerBackdrop, CircleShape, capsuleBlurPx)
                    .clickable { showMoreSheet = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert, contentDescription = "More",
                    tint = Color.White, modifier = Modifier.size(22.dp),
                )
            }
        }
      }
    }

    if (showFullLyrics && lyrics != null) {
        AlertDialog(
            onDismissRequest = { showFullLyrics = false },
            containerColor = DockFrostTint,
            modifier = Modifier.border(1.dp, DockFrostOutline, RoundedCornerShape(28.dp)),
            title = { Text(track.title, color = Color.White) },
            text = {
                val synced = lyricsResult?.synced
                if (synced != null) {
                    SyncedLyricsList(
                        lines = synced,
                        positionMs = state.positionMs + lyricsOffsetMs,
                        onSeek = { controller.seekTo((it - lyricsOffsetMs).coerceAtLeast(0)) },
                    )
                    // Timing shift for lyrics that run early/late
                    // against this recording (±5 s, half-second steps).
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Timing", fontSize = 12.sp, color = ForteColors.TextDim)
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { lyricsOffsetMs = (lyricsOffsetMs - 500).coerceAtLeast(-5000) },
                        ) { Text("−0.5s", color = Color.White) }
                        Text(
                            when {
                                lyricsOffsetMs > 0 -> "+${lyricsOffsetMs / 1000.0}s"
                                lyricsOffsetMs < 0 -> "${lyricsOffsetMs / 1000.0}s"
                                else -> "0s"
                            },
                            fontSize = 12.sp, color = ForteColors.Red, fontWeight = FontWeight.Bold,
                        )
                        TextButton(
                            onClick = { lyricsOffsetMs = (lyricsOffsetMs + 500).coerceAtMost(5000) },
                        ) { Text("+0.5s", color = Color.White) }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(lyrics, color = ForteColors.Text, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Lyrics from LRCLIB", color = ForteColors.TextDim, fontSize = 11.sp)
            },
            confirmButton = {
                TextButton(onClick = { showFullLyrics = false }) {
                    Text("Close", color = ForteColors.Red)
                }
            },
        )
    }

    if (showCredits) {
        // Credits ride the shared sheet (v1.0.14), same content:
        // YouTube's published lines, or the honest empty state.
        ForteActionSheet(
            onDismiss = { showCredits = false },
            frost = true,
            header = {
                Text(
                    "Song credits", fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, color = Color.White,
                )
            },
        ) {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val lines = credits
                when {
                    lines == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = ForteColors.Red,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "Loading credits…",
                                color = ForteColors.TextDim, fontSize = 13.sp,
                            )
                        }
                    }
                    lines.isEmpty() -> {
                        CreditLine("Song", track.title)
                        CreditLine("Artist", track.artist)
                        if (track.durationSec > 0) {
                            CreditLine(
                                "Duration",
                                formatDuration(track.durationSec),
                            )
                        }
                        state.streamInfo?.let { CreditLine("Stream", it) }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "YouTube didn't publish full credits for this song.",
                            color = ForteColors.TextDim, fontSize = 12.sp,
                        )
                    }
                    else -> lines.forEach { credit ->
                        CreditLine(credit.label, credit.value)
                    }
                }
            }
        }
    }

    if (showOutput) {
        val outputs = remember(showOutput) { controller.audioOutputs() }
        // Output rides the shared sheet (v1.0.14): the live output
        // wears a red "Playing here" pill instead of a radio
        // button, and a successful pick closes the sheet (a failed
        // pick keeps it open and says why, as the dialog did).
        ForteActionSheet(
            onDismiss = { showOutput = false },
            frost = true,
            header = {
                Column {
                    Text(
                        "Audio output", fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.streamInfo ?: "Choose where this phone plays.",
                        color = ForteColors.TextDim, fontSize = 12.sp,
                    )
                }
            },
        ) {
            OutputSheetRow(
                label = "Automatic",
                detail = "Android chooses the output",
                selected = state.outputName == null,
            ) {
                controller.clearAudioOutput()
                showOutput = false
            }
            if (outputs.isEmpty()) {
                Text(
                    "Android didn't report any audio output.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            outputs.forEach { output ->
                OutputSheetRow(
                    label = output.name,
                    detail = output.kind,
                    selected = state.outputName == output.name,
                ) {
                    if (controller.selectAudioOutput(output.id)) {
                        showOutput = false
                    } else {
                        app.showMessage("That output is no longer available.")
                    }
                }
            }
        }
    }

    if (showMoreSheet) {
        // The player's overflow sheet (v1.0.14): every secondary
        // action the old labelled tiles carried lives here now.
        val speedLabel = if (state.speed == state.speed.toInt().toFloat()) {
            "${state.speed.toInt()}×"
        } else {
            "${state.speed}×"
        }
        ForteActionSheet(onDismiss = { showMoreSheet = false }, frost = true) {
            if (lyrics != null) {
                ForteSheetRow(Icons.Filled.Lyrics, "Lyrics") {
                    showMoreSheet = false
                    showFullLyrics = true
                }
            }
            ForteSheetRow(
                Icons.Filled.Timer, "Sleep timer",
                iconTint = if (state.sleepArmed) ForteColors.Red else Color.White,
                trailing = {
                    val label = when {
                        state.sleepAtTrackEnd -> "End of song"
                        state.sleepArmed -> "On"
                        else -> null
                    }
                    if (label != null) {
                        Text(label, fontSize = 12.sp, color = ForteColors.Red)
                    }
                },
            ) {
                showMoreSheet = false
                showSleepTimer = true
            }
            ForteSheetRow(Icons.Filled.GraphicEq, "Equalizer") {
                showMoreSheet = false
                showEq = true
            }
            ForteSheetRow(
                Icons.Filled.Speed, "Playback speed",
                trailing = {
                    Text(
                        speedLabel, fontSize = 13.sp,
                        color = if (state.speed != 1f) ForteColors.Red else ForteColors.TextDim,
                    )
                },
            ) {
                val rates = listOf(1f, 1.25f, 1.5f, 2f, 0.5f)
                val next = rates[(rates.indexOf(state.speed) + 1) % rates.size]
                controller.setSpeed(next)
            }
            // Playback quality's home in the player (owner's order,
            // v1.0.18): the ⋮ sheet. It sets the same base quality
            // Settings carries — one setting, one store; the
            // per-network ceilings in Settings still apply on
            // Wi-Fi/mobile when set there.
            ForteSheetRow(
                Icons.Filled.HighQuality, "Audio quality",
                trailing = {
                    Text(
                        playerQualityLabel(forteSettings.audioQuality),
                        fontSize = 13.sp, color = ForteColors.TextDim,
                    )
                },
            ) {
                showMoreSheet = false
                showQuality = true
            }
            ForteSheetRow(Icons.Filled.Share, "Share") {
                showMoreSheet = false
                actions.share(track)
            }
            ForteSheetRow(Icons.Filled.PlaylistAdd, "Add to playlist") {
                showMoreSheet = false
                actions.addToPlaylist(track)
            }
            ForteSheetRow(Icons.Filled.Info, "Credits") {
                showMoreSheet = false
                showCredits = true
            }
            ForteSheetRow(
                Icons.Filled.Radio, "Start radio",
                iconTint = if (state.radio) ForteColors.Red else Color.White,
            ) {
                showMoreSheet = false
                actions.startRadio(track)
            }
            // The rows the Sample B deck no longer carries on its
            // face (v1.0.23-test): the old chip row's download and
            // dislike, the output pill, and the song menu's queue
            // actions — same wiring, sheet homes.
            ForteSheetRow(Icons.Filled.PlaylistPlay, "Play next") {
                showMoreSheet = false
                actions.playNext(track)
            }
            ForteSheetRow(Icons.Filled.QueueMusic, "Add to queue") {
                showMoreSheet = false
                actions.addToQueue(track)
            }
            ForteSheetRow(
                if (isDownloaded) Icons.Filled.Check else Icons.Filled.Download,
                if (isDownloaded) "Downloaded" else "Download",
                iconTint = if (isDownloaded) ForteColors.Red else Color.White,
                trailing = {
                    if (dlProgress != null && dlProgress.error == null) {
                        CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            ) {
                if (!isDownloaded && dlProgress == null) {
                    showMoreSheet = false
                    actions.download(track)
                }
            }
            ForteSheetRow(Icons.Filled.Speaker, "Audio output") {
                showMoreSheet = false
                showOutput = true
            }
            ForteSheetRow(
                Icons.Filled.ThumbDown,
                if (isDisliked) "Remove dislike" else "Dislike — skip this song",
                iconTint = if (isDisliked) ForteColors.TrueRed else Color.White,
            ) {
                showMoreSheet = false
                toggleDislike()
            }
            if (!track.artistUrl.isNullOrBlank()) {
                ForteSheetRow(Icons.Filled.Person, "Go to artist") {
                    showMoreSheet = false
                    actions.goToArtist(track)
                }
            }
        }
    }

    if (showSleepTimer) {
        SleepTimerDialog(onDismiss = { showSleepTimer = false })
    }
    if (showEq) {
        EqualizerDialog(onDismiss = { showEq = false })
    }
    if (showQuality) {
        PlayerQualityDialog(
            current = forteSettings.audioQuality,
            onPick = { app.settings.setAudioQuality(it) },
            onDismiss = { showQuality = false },
        )
    }
}

/** The expanded panel's full height (v1.0.30-test): its
 * two rows (116dp) plus the 6dp gap that used to be a
 * sibling spacer under it. The panel's measured height is
 * this times the sheet fraction — capped by the room the
 * deck leaves (see panelCapHeight in PlayerScreen). */
private val ExpandedPanelFullHeight = 122.dp

/**
 * The deck sheet's two resting states (v1.0.29-test):
 * fraction 0 = the v1.0.28-test rest (top edge ~64% of the
 * screen), 1 = grown to ~82%. Modelled on the outer
 * [PlayerSheetState]: the finger writes the fraction
 * directly (no animation between the finger and the
 * sheet), a release settles with the same spring the
 * player sheet uses, and fling wins over position.
 */
private class PlayerDeckSheetState(private val scope: kotlinx.coroutines.CoroutineScope) {

    var fraction by mutableFloatStateOf(0f)
        private set

    /**
     * True while a drag or a settle animation is in flight
     * (v1.0.30-test). The player watches this to FREEZE its
     * backdrop recordings for the duration — the recorded
     * poster cannot change under a finger, and re-recording
     * + re-blurring its layers per drag frame was pure cost
     * (see BackdropState.frozen).
     */
    var motionActive by mutableStateOf(false)
        private set

    private var settleJob: Job? = null

    // Incremented by every new motion; a settle job only
    // clears [motionActive] if its generation is still the
    // current one — a drag that cancels the job mid-flight
    // must not let the dead job's cleanup unfreeze the
    // backdrop under the finger.
    private var motionGen = 0

    /** Feed a finger delta (px, positive = finger moved down). */
    fun dragBy(dyPx: Float, rangePx: Float) {
        if (rangePx <= 0f) return
        settleJob?.cancel()
        settleJob = null
        motionGen++
        motionActive = true
        fraction = (fraction - dyPx / rangePx).coerceIn(0f, 1f)
    }

    /** Release: fling wins, otherwise settle to the nearest state. */
    fun settle(velocityYPxPerSec: Float) {
        val target = when {
            velocityYPxPerSec < -FLING_PX_PER_SEC -> 1f
            velocityYPxPerSec > FLING_PX_PER_SEC -> 0f
            fraction >= 0.5f -> 1f
            else -> 0f
        }
        animateTo(target)
    }

    /** Handle tap: jump to the other resting state. */
    fun toggle() = animateTo(if (fraction >= 0.5f) 0f else 1f)

    private fun animateTo(target: Float) {
        settleJob?.cancel()
        val from = fraction
        if (from == target) {
            fraction = target
            settleJob = null
            motionActive = false
            return
        }
        val gen = ++motionGen
        motionActive = true
        settleJob = scope.launch {
            Animatable(from).animateTo(
                target,
                spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { fraction = value }
            fraction = target
            if (gen == motionGen) motionActive = false
        }
    }

    private companion object {
        const val FLING_PX_PER_SEC = 550f
    }
}

/** One quick-action cell of the player's expanded panel
 * (v1.0.29-test): icon over a small label on a faint pill.
 * [busy] swaps the icon for a spinner (the download's
 * in-flight state, mirroring the more-sheet's row). */
@Composable
private fun PlayerQuickAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.size(21.dp),
            )
        } else {
            Icon(
                icon, contentDescription = label,
                tint = tint, modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Short label for one quality step, matching Settings' wording. */
private fun playerQualityLabel(q: AudioQuality): String = when (q) {
    AudioQuality.AUTO -> "Auto"
    AudioQuality.HIGH -> "High · up to 256k"
    AudioQuality.MEDIUM -> "Medium · ~96k"
    AudioQuality.LOW -> "Low · ~50k"
}

/** The player's quality picker (v1.0.18): the four steps of the
 * base streaming quality, written to the one Settings store. A
 * pick applies from the next track the engine resolves — the
 * playing song is never restarted mid-stream. */
@Composable
private fun PlayerQualityDialog(
    current: AudioQuality,
    onPick: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DockFrostTint,
        modifier = Modifier.border(1.dp, DockFrostOutline, RoundedCornerShape(28.dp)),
        title = { Text("Audio quality", color = Color.White) },
        text = {
            Column {
                AudioQuality.entries.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(q); onDismiss() }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = q == current,
                            onClick = { onPick(q); onDismiss() },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = ForteColors.Red,
                                unselectedColor = ForteColors.TextDim,
                            ),
                        )
                        Text(playerQualityLabel(q), color = Color.White, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Separate Wi-Fi / mobile choices in Settings still apply on those networks.",
                    color = ForteColors.TextDim, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = ForteColors.TextDim) }
        },
    )
}

@Composable
private fun CreditLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(
            label.uppercase(),
            fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = ForteColors.Red, letterSpacing = 1.sp,
        )
        Text(value, fontSize = 14.sp, color = Color.White)
    }
}

/** One output-sheet row: 56dp, name + kind, and a red
 * "Playing here" pill on the live output (v1.0.14). */
@Composable
private fun OutputSheetRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = ForteColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ForteColors.Red)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    "Playing here", fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
        }
    }
}

/** The player's thin progress line (v1.0.14): a 3dp track in a
 * 28dp touch strip, red fill, and a 12dp white thumb that exists
 * only while a finger is scrubbing. Tap seeks; drag scrubs and
 * seeks on release. */
@Composable
private fun ForteProgressBar(
    fraction: Float,
    scrubbing: Boolean,
    onScrub: (Float?) -> Unit,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.height(28.dp),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    // The played part is RED (owner's order,
                    // v1.0.28-test: "Seek bar red boss, that
                    // thing keep") — the brand red, the FORTE.
                    // dot's red: ForteColors.Red, the same red
                    // as the liked heart. (Sample B had made
                    // the bar white end to end; the owner
                    // asked for the red back.) The unplayed
                    // track keeps its white-at-22%, and the
                    // knob below matches the played part.
                    .background(ForteColors.Red),
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * fraction - 6.5.dp)
                .size(13.dp)
                .clip(CircleShape)
                // The knob wears the same brand red as the
                // played part (v1.0.28-test).
                .background(ForteColors.Red),
        )
        var lastScrub by remember { mutableStateOf<Float?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx) {
                    detectTapGestures { offset ->
                        if (widthPx > 0f) {
                            onSeekFraction((offset.x / widthPx).coerceIn(0f, 1f))
                        }
                    }
                }
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (widthPx > 0f) {
                                val f = (offset.x / widthPx).coerceIn(0f, 1f)
                                lastScrub = f
                                onScrub(f)
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            if (widthPx > 0f) {
                                val f = (change.position.x / widthPx).coerceIn(0f, 1f)
                                lastScrub = f
                                onScrub(f)
                            }
                        },
                        onDragEnd = {
                            lastScrub?.let { onSeekFraction(it) }
                            lastScrub = null
                            onScrub(null)
                        },
                        onDragCancel = {
                            lastScrub = null
                            onScrub(null)
                        },
                    )
                },
        )
    }
}

/** One chip in the player's action row — an oval pill since
 * v1.0.19-test (owner's Sample B pick; it was a round disc up to
 * v1.0.18-test). [filledRed] swaps the glass for a solid TRUE-red
 * fill (#FF0000 since v1.0.20-test — the shipped Red read muted
 * to the owner): the liked thumb up is the one chip that may
 * carry it (owner's small-points rule). v1.0.21-test: the
 * unfilled glass is the LIVE capsule glass over the player's
 * recorded poster backdrop, and both paths wear the true-red
 * press ring while the chip is touched. */
@Composable
private fun PlayerChip(
    onClick: () -> Unit,
    filledRed: Boolean = false,
    backdrop: BackdropState? = null,
    glassBlurPx: Float = 0f,
    content: @Composable () -> Unit,
) {
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .width(58.dp)
            .height(48.dp)
            .clip(shape)
            .then(
                if (filledRed) {
                    Modifier.background(ForteColors.TrueRed, shape)
                } else {
                    Modifier.liveCapsuleGlass(backdrop, shape, glassBlurPx)
                },
            )
            .trueRedPressRing(shape, pressed)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Synced lyrics: the current line lit and centred, tap a line to
 * jump the song to it. Unsynced lyrics never reach this composable. */
@Composable
private fun SyncedLyricsList(
    lines: List<com.kotlinsyd.forte.data.LyricLine>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val current = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    LaunchedEffect(current) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, current - 2))
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = Modifier.height(360.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            Text(
                line.text,
                fontSize = if (i == current) 16.sp else 14.sp,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                color = if (i == current) Color.White else ForteColors.TextDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 5.dp, horizontal = 4.dp),
            )
        }
    }
}

/** Sleep timer choices, shared by the player and Settings. Rule:
 * a timed timer fades the music out over its last seconds and
 * pauses; "End of track" stops cleanly when the song finishes. */
@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val controller = ForteApp.instance.player
    val playState by controller.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DockFrostTint,
        modifier = Modifier.border(1.dp, DockFrostOutline, RoundedCornerShape(28.dp)),
        title = { Text("Sleep timer", color = Color.White) },
        text = {
            Column {
                if (playState.sleepArmed) {
                    SleepOption("Off — cancel timer", false) {
                        controller.cancelSleepTimer()
                        onDismiss()
                    }
                }
                listOf(15, 30, 45, 60).forEach { minutes ->
                    SleepOption("$minutes minutes", false) {
                        controller.armSleepTimer(minutes)
                        onDismiss()
                    }
                }
                SleepOption("End of this track", playState.sleepAtTrackEnd) {
                    controller.armSleepEndOfTrack()
                    onDismiss()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = ForteColors.TextDim) }
        },
    )
}

@Composable
private fun SleepOption(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, fontSize = 14.sp,
            color = if (active) ForteColors.Red else Color.White,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Equalizer + bass boost on the player's audio session (Android's
 * own effects). Presets are the device's; anything the device lacks
 * simply doesn't apply — playback is never disturbed. */
@Composable
fun EqualizerDialog(onDismiss: () -> Unit) {
    val app = ForteApp.instance
    val settings by app.settings.settings.collectAsState()
    var presets by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        presets = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.kotlinsyd.forte.playback.AudioEffects.presetNames()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DockFrostTint,
        modifier = Modifier.border(1.dp, DockFrostOutline, RoundedCornerShape(28.dp)),
        title = { Text("Equalizer", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Equalizer", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White, modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = settings.eqEnabled,
                        onCheckedChange = {
                            app.settings.setEq(it, settings.eqPreset, settings.bassBoost)
                            app.player.refreshEffects()
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ForteColors.Red,
                            uncheckedThumbColor = ForteColors.TextDim,
                            uncheckedTrackColor = ForteColors.Surface,
                        ),
                    )
                }
                if (settings.eqEnabled) {
                    Spacer(Modifier.height(6.dp))
                    presets.forEachIndexed { index, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    app.settings.setEq(true, index, settings.bassBoost)
                                    app.player.refreshEffects()
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = index == settings.eqPreset,
                                onClick = {
                                    app.settings.setEq(true, index, settings.bassBoost)
                                    app.player.refreshEffects()
                                },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                    selectedColor = ForteColors.Red,
                                    unselectedColor = ForteColors.TextDim,
                                ),
                            )
                            Text(name, color = Color.White, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Bass boost", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Slider(
                        value = settings.bassBoost.toFloat(),
                        onValueChange = {
                            app.settings.setEq(true, settings.eqPreset, it.toInt())
                            app.player.refreshEffects()
                        },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = ForteColors.Red,
                            inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                        ),
                    )
                } else {
                    Text(
                        "Turn it on to shape the sound. Uses your phone's built-in audio effects.",
                        color = ForteColors.TextDim, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = ForteColors.Red) }
        },
    )
}

// ----------------------------------------------------------------------

@Composable
fun QueueScreen(
    state: PlaybackState,
    actions: TrackActions,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val controller = app.player
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    val forteSettings by app.settings.settings.collectAsState()

    @Composable
    fun entry(index: Int) {
        val track = state.queue[index]
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackRow(
                track = track,
                actions = actions,
                isFavorite = track.id in favoriteIds,
                isCurrent = index == state.index,
                isPlaying = state.playing,
                onClick = { controller.jumpTo(index) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { controller.removeAt(index) }) {
                Icon(Icons.Filled.Clear, contentDescription = "Remove from queue", tint = ForteColors.TextDim)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ListHeader(
            "Queue",
            if (state.radio) "Radio station · ${state.queue.size} songs · keeps going"
            else "${state.queue.size} songs",
            onBack,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueueToggle("Shuffle", state.shuffle) { controller.setShuffle(!state.shuffle) }
                QueueToggle(
                    when (state.repeat) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ALL -> "Repeat all"
                        RepeatMode.ONE -> "Repeat one"
                    },
                    state.repeat != RepeatMode.OFF,
                ) { controller.cycleRepeat() }
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (forteSettings.queueHistory && state.index > 0 && state.queue.isNotEmpty()) {
                // Sectioned queue (the "Previously played in queue"
                // setting): what already played sits above Now
                // playing, capped at the last 25, and Up next
                // below — the same rows, the same actions.
                val from = maxOf(0, state.index - 25)
                if (from < state.index) {
                    item { QueueSectionLabel("Previously played") }
                    items((from until state.index).toList()) { entry(it) }
                }
                item { QueueSectionLabel("Now playing") }
                item { entry(state.index) }
                if (state.index + 1 <= state.queue.lastIndex) {
                    item { QueueSectionLabel("Up next") }
                    items(((state.index + 1)..state.queue.lastIndex).toList()) { entry(it) }
                }
            } else {
                items(state.queue.indices.toList()) { entry(it) }
            }
        }
    }
}

@Composable
private fun QueueSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = ForteColors.Red, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun QueueToggle(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .then(
                if (active) {
                    Modifier
                        .clip(RoundedCornerShape(ForteGlass.RadiusCapsule))
                        .background(Color.White)
                } else {
                    Modifier.forteGlassChip(RoundedCornerShape(ForteGlass.RadiusCapsule))
                },
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (active) Color.Black else Color.White,
        )
    }
}
