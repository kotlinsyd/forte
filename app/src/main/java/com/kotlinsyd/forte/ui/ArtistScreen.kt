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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.data.AlbumRef
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.ForteEngine
import com.kotlinsyd.forte.data.YtAccount
import com.kotlinsyd.forte.data.formatViews
import kotlinx.coroutines.launch

@Composable
fun ArtistScreen(
    channelUrl: String,
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenYtPlaylist: (String) -> Unit,
    onBack: () -> Unit,
) {
    val app = ForteApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    val follows by app.library.follows.collectAsState(initial = emptyList())
    var channel by remember(channelUrl) { mutableStateOf<YtAccount.ChannelPage?>(null) }
    var topSongs by remember(channelUrl) { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember(channelUrl) { mutableStateOf<List<AlbumRef>>(emptyList()) }
    var failed by remember(channelUrl) { mutableStateOf(false) }

    LaunchedEffect(channelUrl) {
        // Last-good top songs first, fresh behind (owner's rule).
        val key = "artist:$channelUrl"
        if (topSongs.isEmpty()) {
            topSongs = com.kotlinsyd.forte.data.LastGood.getTracks(context, key)
                ?: emptyList()
        }
        runCatching {
            val ch = YtAccount.channelPage(channelUrl)
            channel = ch
            topSongs = ForteEngine.searchSongs(ch.name).first
            com.kotlinsyd.forte.data.LastGood.putTracks(context, key, topSongs)
            albums = runCatching { ForteEngine.searchAlbums(ch.name) }.getOrDefault(emptyList())
        }.onFailure { if (channel == null && topSongs.isEmpty()) failed = true }
    }

    val isFollowing = follows.any { it.channelUrl == channelUrl }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current)) {
        item {
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                val banner = channel?.bannerUrl ?: channel?.avatarUrl
                if (banner != null) {
                    AsyncImage(
                        model = banner, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(artworkGradient(channel?.name ?: "artist"))
                        )
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, ForteColors.Bg.copy(alpha = 0.92f))
                        )
                    )
                )
                androidx.compose.material3.IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(4.dp),
                ) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back", tint = Color.White,
                    )
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 12.dp),
                ) {
                    Text(
                        channel?.name ?: "Artist",
                        fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val subs = channel?.subscribers ?: -1
                    if (subs > 0) {
                        Text("${formatViews(subs)} subscribers", fontSize = 13.sp, color = ForteColors.TextDim)
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isFollowing) ForteColors.SurfaceHi else ForteColors.Red)
                        .clickable {
                            val ch = channel ?: return@clickable
                            scope.launch {
                                app.library.toggleFollow(channelUrl, ch.name, ch.avatarUrl, isFollowing)
                            }
                        }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (isFollowing) "Following" else "Follow",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                }
                if (topSongs.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ForteColors.Surface)
                            .clickable { actions.play(topSongs.first(), topSongs, channel?.name ?: "Top songs") }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text("Play top songs", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
        when {
            channel == null && !failed -> item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    SkeletonRows(5)
                }
            }
            failed -> item {
                Text(
                    "Couldn't load this artist.", color = ForteColors.TextDim,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            else -> {
                item {
                    SectionHeader("Top songs", modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(6.dp))
                }
                items(topSongs.take(10)) { track ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TrackRow(
                            track = track,
                            actions = actions,
                            isFavorite = track.id in favoriteIds,
                            isCurrent = track.id == currentTrackId,
                            isPlaying = isPlaying,
                            onClick = { actions.play(track, topSongs, channel?.name ?: "Top songs") },
                        )
                    }
                }
                if (albums.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        SectionHeader("Albums", modifier = Modifier.padding(horizontal = 20.dp))
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(albums) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(156.dp)
                                        .clickable { onOpenYtPlaylist(album.url) },
                                ) {
                                    Artwork(album.thumbUrl, album.title, 156.dp, corner = 14.dp)
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        album.title, style = ForteType.cardTitle,
                                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                channel?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    item {
                        Spacer(Modifier.height(22.dp))
                        SectionHeader("About", modifier = Modifier.padding(horizontal = 20.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            desc.take(600),
                            fontSize = 13.sp, color = ForteColors.TextDim,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
    }
}
