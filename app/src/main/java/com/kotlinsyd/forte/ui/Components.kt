package com.kotlinsyd.forte.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.formatDuration
import com.kotlinsyd.forte.playback.PlaybackState
import kotlinx.coroutines.launch

/** Actions every track row/menu can trigger. Wired once at the root. */
class TrackActions(
    val play: (Track, List<Track>, String?) -> Unit,
    val toggleFavorite: (Track) -> Unit,
    val download: (Track) -> Unit,
    val share: (Track) -> Unit,
    val addToPlaylist: (Track) -> Unit,
    val goToArtist: (Track) -> Unit,
    val playNext: (Track) -> Unit,
    val addToQueue: (Track) -> Unit,
    val startRadio: (Track) -> Unit,
) {
    /** Opens the sleep-timer choices (set at the root, where the
     * dialog lives). Song menus offer it too. */
    var openSleepTimer: (() -> Unit)? = null
}

/**
 * How much vertical room the floating dock (mini strip + capsule)
 * needs at the bottom of the stage. The stage itself reserves
 * NOTHING — page content runs to the physical bottom edge and
 * scrolls behind the glass (owner's no-solid-band rule); each
 * scrollable list adds this as invisible end padding so its last
 * row can still scroll clear of the dock.
 */
val LocalDockClearance = androidx.compose.runtime.compositionLocalOf { 92.dp }

// ----------------------------------------------------------------------
// Skeletons — the app's one loading language (owner's rule)
// ----------------------------------------------------------------------

/**
 * Skeleton placeholders, shaped like the content they stand in for.
 * They show ONLY while a load is genuinely in flight and fast: the
 * moment data (or the screen's last-good cache) exists, the real
 * content replaces them, and slow loads fall back to that last-good
 * content instead of holding the user on skeletons. One soft pulse,
 * a few flat boxes — cheap to render on low-memory phones.
 */
@Composable
private fun skeletonAlpha(): Float {
    // "Reduce animation" (Settings → Appearance): a still skeleton
    // at a mid alpha instead of the pulse.
    if (com.kotlinsyd.forte.ForteApp.instance.settings.settings
            .collectAsState().value.reduceAnimation
    ) {
        return 0.6f
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skel")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "skelAlpha",
    )
    return alpha
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val alpha = skeletonAlpha()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(ForteColors.SurfaceHi.copy(alpha = alpha)),
    )
}

/** One track-row skeleton: cover block, two lines, menu dot. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(56.dp), corner = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonBox(Modifier.fillMaxWidth(0.55f).height(13.dp), corner = 6.dp)
            Spacer(Modifier.height(7.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.35f).height(11.dp), corner = 6.dp)
        }
    }
}

@Composable
fun SkeletonRows(count: Int = 5) {
    Column { repeat(count) { SkeletonRow() } }
}

/** One home-shelf skeleton: title line + a row of card blocks. */
@Composable
fun SkeletonShelf() {
    Column {
        SkeletonBox(Modifier.fillMaxWidth(0.4f).height(18.dp), corner = 8.dp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Column(Modifier.width(156.dp)) {
                    SkeletonBox(Modifier.size(156.dp), corner = 14.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.8f).height(12.dp), corner = 6.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.55f).height(11.dp), corner = 6.dp)
                }
            }
        }
    }
}

/**
 * The best artwork URL for a track: its YouTube Music cover when it
 * has one; when the extractor only had a video frame, the iTunes
 * net is asked once (cached) for the real album cover. Returns the
 * frame meanwhile, so nothing ever waits on the second net.
 */
@Composable
fun rememberEnrichedArtwork(track: Track): String? {
    // A blank thumb starts on the frame floor, never on the letter:
    // the video's own frame exists even while the nets are asked.
    val floored = track.thumbUrl?.takeIf { it.isNotBlank() }
        ?: com.kotlinsyd.forte.data.frameFloorUrl(track.id)
    var url by remember(track.id) { mutableStateOf(floored) }
    androidx.compose.runtime.LaunchedEffect(track.id, track.thumbUrl) {
        url = floored
        if (com.kotlinsyd.forte.data.ArtworkResolver.needsUpgrade(track.thumbUrl)) {
            com.kotlinsyd.forte.data.ArtworkResolver.itunesCover(track)?.let { url = it }
        }
    }
    return url
}

@Composable
fun Artwork(
    url: String?,
    title: String,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(artworkGradient(title))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().firstOrNull()?.uppercase() ?: "♪",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.Black,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                // Display-time upgrade to the largest artwork the
                // source offers (owner's rule), so even rows cached
                // by older builds heal. Coil scales it down to this
                // slot; the decode is bounded by the slot size.
                model = com.kotlinsyd.forte.data.bigArtwork(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ----------------------------------------------------------------------
// Frosted dark liquid glass — the house capsule material (owner's
// v1.0.7 order). The bottom bars must read DARK and premium: a strong
// dark tint over the backdrop blur, a cut-glass rim light for a
// border, and a soft sheen across the top. The blur mechanics stay
// exactly as built (recorded backdrop + RenderEffect); this is the
// material layered over it, nothing more.
// ----------------------------------------------------------------------

/** Cut-glass rim: bright top-left edge, defined bottom edge. */
private val DarkGlassRim: Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.42f),
        Color.White.copy(alpha = 0.12f),
        Color.White.copy(alpha = 0.05f),
        Color.White.copy(alpha = 0.24f),
    ),
)

/** Soft highlight across the top of a dark-glass surface. */
private fun Modifier.darkGlassSheen(): Modifier = drawWithCache {
    val endY = (size.height * 0.4f).takeIf { it > 0f } ?: 1f
    val brush = Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
        startY = 0f,
        endY = endY,
    )
    onDrawBehind { drawRect(brush) }
}

/**
 * The dark tint gradient for one of the bottom bars. Over a live
 * blurred backdrop the alphas stay translucent so the frosting
 * shows through the tint; without a backdrop (older Android) the
 * same colours go near-opaque so the bar still reads as dark
 * glass, never as clear plastic. v1.0.8 (owner: "bit more dark
 * and thick"): the live alphas step up to 0.78 → 0.88 and the
 * dock blur behind them is stronger (see GlassBox blurRadius) —
 * the v1.0.7 tint over a 26px blur read as flat plastic.
 */
private fun darkGlassBrush(live: Boolean): Brush {
    val top = Color(0xFF17171F)
    val bottom = Color(0xFF0B0B10)
    return Brush.verticalGradient(
        if (live) {
            listOf(top.copy(alpha = 0.78f), bottom.copy(alpha = 0.88f))
        } else {
            listOf(top.copy(alpha = 0.94f), bottom.copy(alpha = 0.97f))
        },
    )
}

@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    glass: Boolean,
    backdrop: BackdropState? = null,
    dark: Boolean = false,
    blurRadius: Float = 26f,
    content: @Composable () -> Unit,
) {
    // With a live backdrop the recorded scene shows through blurred;
    // the fill drops to a light tint so the frosting reads. Without
    // one (old Android, glass off) the flat tint stands as before.
    // [dark] swaps the light tint for the frosted dark liquid
    // glass (bottom bars only — player cards keep the light glass).
    val liveGlass = glass && backdrop != null && backdropBlurSupported
    Box(
        modifier = modifier
            // A weightier presence for the dark dock surfaces: a
            // soft shadow lifts the assembly off the content it
            // floats over (glass v2 — player cards keep no shadow).
            .then(if (dark && glass) Modifier.shadow(16.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .then(if (liveGlass) Modifier.glassBackdrop(backdrop, shape, blurRadius) else Modifier)
            .then(
                if (dark && glass) {
                    Modifier
                        .background(darkGlassBrush(liveGlass), shape)
                        .darkGlassSheen()
                } else {
                    Modifier.background(
                        when {
                            liveGlass -> Color.White.copy(alpha = 0.10f)
                            glass -> ForteColors.GlassFill
                            else -> ForteColors.Surface
                        },
                        shape,
                    )
                },
            )
            .then(
                if (dark && glass) {
                    Modifier.border(1.dp, DarkGlassRim, shape)
                } else {
                    Modifier.border(
                        1.dp,
                        if (glass) ForteColors.GlassBorder else ForteColors.Border,
                        shape,
                    )
                },
            )
            .clip(shape),
    ) { content() }
}

@Composable
fun SectionHeader(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = ForteType.section,
            color = ForteColors.Text,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ForteColors.Red,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    actions: TrackActions,
    isFavorite: Boolean,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    downloadLabel: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.thumbUrl, track.title, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = ForteType.rowTitle,
                color = if (isCurrent) ForteColors.Red else ForteColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(track.artist, formatDuration(track.durationSec))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = ForteType.rowSub,
                color = ForteColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Text(
                text = if (isPlaying) "Playing" else "Paused",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ForteColors.Red,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (downloadLabel != null) {
            Text(
                text = downloadLabel,
                fontSize = 11.sp,
                color = ForteColors.TextDim,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        TrackMenu(track, actions, isFavorite)
    }
}

// ----------------------------------------------------------------------
// Action sheets — one menu language app-wide (v1.0.14). A bottom
// sheet with 24dp top corners on the dark surface, an optional song
// header, and plain icon rows replaces the stock dropdown menus and
// the stock dialogs for song/player actions.
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForteActionSheet(
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF17171F),
        contentColor = ForteColors.Text,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.25f)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            if (header != null) {
                header()
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

/** One row inside a ForteActionSheet: 52dp tall, 22dp icon, 15sp label. */
@Composable
fun ForteSheetRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color.White,
    labelColor: Color = ForteColors.Text,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            fontSize = 15.sp,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun TrackMenu(track: Track, actions: TrackActions, isFavorite: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = ForteColors.TextDim)
    }
    if (open) {
        val downloads by com.kotlinsyd.forte.ForteApp.instance.library.downloads
            .collectAsState(initial = emptyList())
        val downloaded = downloads.any { it.videoId == track.id }
        ForteActionSheet(
            onDismiss = { open = false },
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(rememberEnrichedArtwork(track), track.title, 44.dp, corner = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title, style = ForteType.cardTitle,
                            color = ForteColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist, style = ForteType.cardSub,
                            color = ForteColors.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        ) {
            ForteSheetRow(Icons.Filled.Radio, "Start radio") {
                open = false; actions.startRadio(track)
            }
            ForteSheetRow(Icons.Filled.PlaylistPlay, "Play next") {
                open = false; actions.playNext(track)
            }
            ForteSheetRow(Icons.Filled.QueueMusic, "Add to queue") {
                open = false; actions.addToQueue(track)
            }
            ForteSheetRow(Icons.Filled.PlaylistAdd, "Add to playlist") {
                open = false; actions.addToPlaylist(track)
            }
            ForteSheetRow(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                iconTint = if (isFavorite) ForteColors.Red else Color.White,
            ) {
                open = false; actions.toggleFavorite(track)
            }
            ForteSheetRow(
                if (downloaded) Icons.Filled.Check else Icons.Filled.Download,
                if (downloaded) "Downloaded" else "Download",
                iconTint = if (downloaded) ForteColors.Red else Color.White,
            ) {
                open = false
                if (!downloaded) actions.download(track)
            }
            if (actions.openSleepTimer != null) {
                ForteSheetRow(Icons.Filled.Timer, "Sleep timer") {
                    open = false; actions.openSleepTimer?.invoke()
                }
            }
            if (!track.artistUrl.isNullOrBlank()) {
                ForteSheetRow(Icons.Filled.Person, "Go to artist") {
                    open = false; actions.goToArtist(track)
                }
            }
            ForteSheetRow(Icons.Filled.Share, "Share") {
                open = false; actions.share(track)
            }
        }
    }
}

// ----------------------------------------------------------------------
// In-app update offer: download inside Forte, straight to installer
// ----------------------------------------------------------------------

@Composable
fun UpdateDialog(
    info: com.kotlinsyd.forte.data.UpdateInfo,
    onDismiss: () -> Unit,
    onSkip: () -> Unit = onDismiss,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val installState by com.kotlinsyd.forte.data.UpdateInstaller.state.collectAsState()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            com.kotlinsyd.forte.data.UpdateInstaller.reset()
            onDismiss()
        },
        containerColor = ForteColors.SurfaceHi,
        title = { Text("Forte ${info.version} is out", color = Color.White) },
        text = {
            Column {
                Text(
                    info.notes.ifBlank { "A new version of Forte is ready." },
                    color = ForteColors.TextDim, fontSize = 13.sp,
                )
                when (val s = installState) {
                    is com.kotlinsyd.forte.data.UpdateInstaller.State.Downloading -> {
                        Spacer(Modifier.height(14.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { s.fraction },
                            color = ForteColors.Red,
                            trackColor = ForteColors.Surface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Downloading… ${(s.fraction * 100).toInt()}%",
                            color = Color.White, fontSize = 13.sp,
                        )
                    }
                    is com.kotlinsyd.forte.data.UpdateInstaller.State.Failed -> {
                        Spacer(Modifier.height(10.dp))
                        Text(s.message, color = ForteColors.Red, fontSize = 13.sp)
                    }
                    com.kotlinsyd.forte.data.UpdateInstaller.State.NeedsPermission -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Android needs your OK once so Forte can install its own updates.",
                            color = Color.White, fontSize = 13.sp,
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    when (installState) {
                        is com.kotlinsyd.forte.data.UpdateInstaller.State.Downloading -> {}
                        com.kotlinsyd.forte.data.UpdateInstaller.State.Ready -> {
                            if (com.kotlinsyd.forte.data.UpdateInstaller.install(context)) {
                                com.kotlinsyd.forte.data.UpdateInstaller.reset()
                                onDismiss()
                            }
                        }
                        com.kotlinsyd.forte.data.UpdateInstaller.State.NeedsPermission -> {
                            com.kotlinsyd.forte.data.UpdateInstaller
                                .openInstallPermissionSettings(context)
                        }
                        else -> scope.launch {
                            com.kotlinsyd.forte.data.UpdateInstaller.download(context, info)
                            if (com.kotlinsyd.forte.data.UpdateInstaller.state.value ==
                                com.kotlinsyd.forte.data.UpdateInstaller.State.Ready
                            ) {
                                if (com.kotlinsyd.forte.data.UpdateInstaller.install(context)) {
                                    com.kotlinsyd.forte.data.UpdateInstaller.reset()
                                    onDismiss()
                                }
                            }
                        }
                    }
                },
            ) {
                Text(
                    when (installState) {
                        is com.kotlinsyd.forte.data.UpdateInstaller.State.Downloading -> "Downloading…"
                        com.kotlinsyd.forte.data.UpdateInstaller.State.Ready -> "Install"
                        com.kotlinsyd.forte.data.UpdateInstaller.State.NeedsPermission -> "Allow installs"
                        else -> "Update"
                    },
                    color = ForteColors.Red, fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                com.kotlinsyd.forte.data.UpdateInstaller.reset()
                onSkip()
            }) {
                Text("Later", color = ForteColors.TextDim)
            }
        },
    )
}

// ----------------------------------------------------------------------
// Bottom shell: mini-player + floating glass capsule dock
// ----------------------------------------------------------------------

data class DockTab(val route: String, val label: String, val icon: ImageVector)

val DockTabs = listOf(
    DockTab("home", "Home", Icons.Filled.Home),
    DockTab("explore", "Explore", Icons.Filled.Explore),
    DockTab("library", "Library", Icons.Filled.LibraryMusic),
    DockTab("search", "Search", Icons.Filled.Search),
)

@Composable
fun CapsuleDock(
    selectedRoute: String,
    onSelect: (String) -> Unit,
    glass: Boolean,
    modifier: Modifier = Modifier,
    backdrop: BackdropState? = null,
) {
    val shape = RoundedCornerShape(50)
    GlassBox(
        modifier = modifier, shape = shape, glass = glass,
        backdrop = backdrop, dark = true, blurRadius = 44f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockTabs.forEach { tab ->
                val selected = tab.route == selectedRoute
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) ForteColors.GlassFillHi else Color.Transparent)
                        .clickable { onSelect(tab.route) }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) Color.White else ForteColors.TextDim,
                            modifier = Modifier.size(21.dp),
                        )
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else ForteColors.TextDim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(
    state: PlaybackState,
    glass: Boolean,
    onTap: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: BackdropState? = null,
) {
    val track = state.current ?: return
    val shape = RoundedCornerShape(20.dp)
    // Ambience: the strip breathes with the playing song's colour.
    val rawTint by com.kotlinsyd.forte.ForteApp.instance.nowPlayingTint
        .collectAsState()
    val tint by androidx.compose.animation.animateColorAsState(
        rawTint, animationSpec = androidx.compose.animation.core.tween(700),
        label = "miniTint",
    )
    val liveGlass = glass && backdrop != null && backdropBlurSupported
    Box(
        modifier = modifier
            // A weightier presence for the dark dock surfaces: a
            // soft shadow lifts the assembly off the content it
            // floats over (glass v2 — player cards keep no shadow).
            .then(if (glass) Modifier.shadow(16.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .then(if (liveGlass) Modifier.glassBackdrop(backdrop, shape, 44f) else Modifier)
            // Frosted dark liquid glass, same material as the capsule
            // below it (the two bars are one assembly). The song's
            // colour still breathes over the dark tint — a soft wash,
            // kept low so the bar always reads dark first.
            .then(
                if (glass) {
                    Modifier
                        .background(darkGlassBrush(liveGlass), shape)
                        .background(tint.copy(alpha = 0.10f), shape)
                        .darkGlassSheen()
                } else {
                    Modifier.background(ForteColors.SurfaceHi, shape)
                },
            )
            .then(
                if (glass) Modifier.border(1.dp, DarkGlassRim, shape)
                else Modifier.border(1.dp, ForteColors.Border, shape),
            )
            .clip(shape)
            .clickable { onTap() },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(rememberEnrichedArtwork(track), track.title, 48.dp, corner = 12.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist, fontSize = 13.sp, color = ForteColors.TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Transport only (v1.0.14): favorite lives in the full
            // player and the song menus; the strip carries next +
            // play/pause as plain glass controls, no red disc, no
            // progress line.
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ForteColors.GlassFillHi)
                    .border(1.dp, ForteColors.GlassBorder, CircleShape)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                // Buffering honesty (owner's v1.0.7 note): while
                // a track is loading the mini strip shows the
                // spinner, exactly as the full player does — a
                // static play button made a trying track look
                // dead.
                if (state.buffering) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
