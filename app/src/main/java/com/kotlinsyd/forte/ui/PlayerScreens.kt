package com.kotlinsyd.forte.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
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
import com.kotlinsyd.forte.data.Lyrics
import com.kotlinsyd.forte.data.YtAccount
import com.kotlinsyd.forte.data.formatDuration
import com.kotlinsyd.forte.playback.PlaybackState
import com.kotlinsyd.forte.playback.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    }
    val isDownloaded = downloads.any { it.videoId == track.id }
    val dlProgress = dlStates[track.id]
    // Posters: YTM cover first; when only a video frame exists, the
    // iTunes net supplies the real album cover (see ArtworkResolver).
    val heroUrl = rememberEnrichedArtwork(track)

    // Artwork-derived colour: the cover's own
    // dominant colour, darkened into the Onyx background — the sharp
    // artwork stays the hero, no blurred copy anywhere.
    val context = LocalContext.current
    var artColor by remember(track.id) { mutableStateOf(ForteColors.Bg) }
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
    val playerBg by animateColorAsState(
        artColor,
        animationSpec = if (forteSettings.reduceAnimation) {
            androidx.compose.animation.core.snap()
        } else {
            tween(600)
        },
        label = "playerBg",
    )
    Box(Modifier.fillMaxSize()) {
        // Ambience (v1.0.14): the cover's palette colour washes the
        // top of the page and deepens into the background colour —
        // no blurred copy of the cover floating behind itself.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            playerBg,
                            lerp(playerBg, ForteColors.Bg, 0.45f),
                            ForteColors.Bg,
                        ),
                    )
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(headerDrag)
                .nestedScroll(sheetScroll)
                .padding(horizontal = 24.dp),
        ) {
            // Fixed deck (v1.0.14): the whole player surface is the
            // sheet's drag handle — sliding down anywhere minimizes
            // to the mini-player, the finger followed the whole way.
            // Nothing in the deck scrolls: artwork, title, progress,
            // transport and actions are one fixed screen.
            // Sheet drag handle.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.28f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize player", tint = Color.White)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (forteSettings.hideSongStatus) {
                            "NOW PLAYING"
                        } else if (state.radio) {
                            "PLAYING FROM RADIO"
                        } else {
                            "PLAYING FROM " + (state.origin ?: "QUEUE").uppercase()
                        },
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ForteColors.TextDim, letterSpacing = 2.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist, fontSize = 13.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                TrackMenu(track, actions, isFavorite)
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // Fit fix (v1.0.16): the cover box is the deck's one
                // flexible element — it takes whatever height the
                // fixed rows (header, title, progress, transport,
                // chips, output pill) leave on THIS screen, and the
                // artwork is capped by that height as well as the
                // width. A width-only 360dp cover outgrew shorter
                // phones and pushed the output pill off the bottom
                // edge (owner's photo). Full-screen cover mode: the
                // artwork runs to the physical left and right edges
                // (the page's own 24.dp padding is added back),
                // sharp and square — still height-capped, so the
                // deck always fits. Off, the cover is the page
                // width capped at 360dp (v1.0.14).
                val widthCap = if (forteSettings.fullScreenCover) {
                    maxWidth + 48.dp
                } else {
                    minOf(maxWidth, 360.dp)
                }
                val coverSize = minOf(widthCap, maxHeight)
                Artwork(
                    heroUrl, track.title, coverSize,
                    corner = if (forteSettings.fullScreenCover) 0.dp else 20.dp,
                )
            }

            Spacer(Modifier.height(16.dp))
            // Title block: favorite and dislike live in the action
            // chip row below the transport now (v1.0.14) — the title
            // row itself only opens Credits, as before.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { showCredits = true },
            ) {
                Text(
                    track.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist, fontSize = 16.sp, color = ForteColors.TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            // Progress (v1.0.14): a thin 3dp line with a thumb that
            // exists only while scrubbing — tap or drag to seek.
            // The shown position is clamped into the range first:
            // while a stream loads, position and duration can
            // disagree, and the bar must never throw over it.
            // Playback speed lives in the more-sheet now.
            var scrubbing by remember { mutableStateOf<Float?>(null) }
            val duration = state.durationMs.coerceAtLeast(1)
            val shownPos = (scrubbing?.let { (it * duration).toLong() } ?: state.positionMs)
                .coerceIn(0L, duration)
            Spacer(Modifier.height(14.dp))
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
                    "-" + formatDuration(((duration - shownPos).coerceAtLeast(0)) / 1000),
                    fontSize = 12.sp, color = ForteColors.TextDim,
                )
            }
            // What is actually streaming (codec · bitrate), or the
            // saved-file note for downloads. The Settings row
            // "Stream info line" owns whether this shows at all.
            if (forteSettings.streamInfoLine) {
                state.streamInfo?.let { info ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        info, fontSize = 11.sp, color = ForteColors.TextDim,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }

            // No flexible spacer here since v1.0.16: the cover box
            // above carries the deck's weight(1f) and absorbs the
            // slack, so the rows below always start right under the
            // stream-info line and end flush at the bottom inset.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { controller.setShuffle(!state.shuffle) }) {
                        Icon(
                            Icons.Filled.Shuffle, contentDescription = "Shuffle",
                            tint = if (state.shuffle) ForteColors.Red else Color.White.copy(alpha = 0.8f),
                        )
                    }
                    if (state.shuffle) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(ForteColors.Red))
                    }
                }
                IconButton(onClick = { controller.previous() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                // Play/pause (v1.0.14): a 64dp glass circle with a
                // plain white glyph — red survives only as the
                // buffering ring inside it.
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(ForteColors.GlassFill)
                        .border(1.dp, ForteColors.GlassBorder, CircleShape)
                        .clickable { controller.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.buffering) {
                        CircularProgressIndicator(color = ForteColors.Red, modifier = Modifier.size(34.dp))
                    } else {
                        Icon(
                            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) "Pause" else "Play",
                            tint = Color.White, modifier = Modifier.size(34.dp),
                        )
                    }
                }
                IconButton(onClick = { controller.next(manual = true) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { controller.cycleRepeat() }) {
                        Icon(
                            if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Repeat",
                            tint = if (state.repeat != RepeatMode.OFF) ForteColors.Red else Color.White.copy(alpha = 0.8f),
                        )
                    }
                    if (state.repeat != RepeatMode.OFF) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(ForteColors.Red))
                    }
                }
            }

            // One row of glass icon chips (v1.0.14) replaces the
            // three labelled action tiles: favorite, dislike,
            // download, queue and the overflow sheet — which now
            // carries sleep, EQ, speed, share, add-to-playlist,
            // credits, radio and lyrics. Nothing was dropped; the
            // secondary actions moved into the sheet.
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerChip(onClick = { actions.toggleFavorite(track) }) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) ForteColors.Red else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                PlayerChip(onClick = toggleDislike) {
                    Icon(
                        Icons.Filled.ThumbDown,
                        contentDescription = if (isDisliked) "Remove dislike" else "Dislike",
                        tint = if (isDisliked) ForteColors.Red else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                PlayerChip(
                    onClick = { if (!isDownloaded && dlProgress == null) actions.download(track) },
                ) {
                    when {
                        dlProgress != null && dlProgress.error == null -> {
                            CircularProgressIndicator(
                                color = Color.White, strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        else -> Icon(
                            if (isDownloaded) Icons.Filled.Check else Icons.Filled.Download,
                            contentDescription = "Download",
                            tint = if (isDownloaded) ForteColors.Red else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                PlayerChip(onClick = onOpenQueue) {
                    Icon(
                        Icons.Filled.QueueMusic, contentDescription = "Queue",
                        tint = Color.White, modifier = Modifier.size(22.dp),
                    )
                }
                PlayerChip(onClick = { showMoreSheet = true }) {
                    Icon(
                        Icons.Filled.MoreVert, contentDescription = "More",
                        tint = Color.White, modifier = Modifier.size(22.dp),
                    )
                }
            }
            // The one output pill (v1.0.14): where the sound is
            // going, centred under the controls; tapping it opens
            // the output sheet.
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .background(ForteColors.GlassFill)
                    .border(1.dp, ForteColors.GlassBorder, RoundedCornerShape(50))
                    .clickable { showOutput = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Speaker, contentDescription = "Audio output",
                        tint = Color.White, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        state.outputName ?: "Automatic",
                        fontSize = 13.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Sleep timer state, visible while armed.
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
            if (sleepLabel != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    sleepLabel, fontSize = 12.sp, color = ForteColors.Red,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            // No lyrics card in the deck (v1.0.14): lyrics open
            // full-screen from the more-sheet, as an overlay.

            state.error?.let { err ->
                Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showFullLyrics && lyrics != null) {
        AlertDialog(
            onDismissRequest = { showFullLyrics = false },
            containerColor = ForteColors.SurfaceHi,
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
        ForteActionSheet(onDismiss = { showMoreSheet = false }) {
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
        }
    }

    if (showSleepTimer) {
        SleepTimerDialog(onDismiss = { showSleepTimer = false })
    }
    if (showEq) {
        EqualizerDialog(onDismiss = { showEq = false })
    }
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
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(ForteColors.SurfaceHi),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(ForteColors.Red),
            )
        }
        if (scrubbing) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * fraction - 6.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
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

/** One 48dp glass icon chip in the player's action row. */
@Composable
private fun PlayerChip(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(ForteColors.GlassFill)
            .border(1.dp, ForteColors.GlassBorder, CircleShape)
            .clickable { onClick() },
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
        containerColor = ForteColors.SurfaceHi,
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
        containerColor = ForteColors.SurfaceHi,
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
            .clip(RoundedCornerShape(50))
            .background(if (active) ForteColors.Red else ForteColors.SurfaceHi)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
