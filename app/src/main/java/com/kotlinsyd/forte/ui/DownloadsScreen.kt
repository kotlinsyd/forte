package com.kotlinsyd.forte.ui

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.data.formatBytes
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
) {
    val app = ForteApp.instance
    val scope = rememberCoroutineScope()
    val downloaded by app.library.downloads.collectAsState(initial = emptyList())
    val dlStates by app.downloads.states.collectAsState()
    val activeTracks by app.downloads.activeTracks.collectAsState()
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    val totalBytes = downloaded.sumOf { it.sizeBytes }
    val deviceTotal = remember {
        runCatching { StatFs(Environment.getDataDirectory().path).totalBytes }.getOrDefault(0L)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current),
    ) {
        item {
            Text(
                "Downloads",
                style = ForteType.pageTitle, color = Color.White,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 14.dp),
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ForteColors.Surface)
                    .padding(16.dp),
            ) {
                Text(
                    "${downloaded.size} songs · ${formatBytes(totalBytes)}",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
                Spacer(Modifier.height(10.dp))
                val frac = if (deviceTotal > 0) {
                    (totalBytes.toFloat() / deviceTotal).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { frac },
                    color = ForteColors.Red,
                    trackColor = ForteColors.SurfaceHi,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Forte downloads on this device" +
                        if (deviceTotal > 0) " · ${formatBytes(deviceTotal)} total storage" else "",
                    fontSize = 12.sp, color = ForteColors.TextDim,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // In-flight downloads first.
        items(activeTracks.entries.toList()) { (videoId, track) ->
            val progress = dlStates[videoId]
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackRow(
                        track = track,
                        actions = actions,
                        isFavorite = videoId in favoriteIds,
                        isCurrent = videoId == currentTrackId,
                        isPlaying = isPlaying,
                        downloadLabel = progress?.error ?: "Downloading",
                        onClick = { },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { app.downloads.cancel(videoId) }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Cancel download", tint = ForteColors.TextDim)
                    }
                }
                LinearProgressIndicator(
                    progress = { progress?.fraction ?: 0f },
                    color = ForteColors.Red,
                    trackColor = ForteColors.SurfaceHi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 72.dp, end = 48.dp, bottom = 6.dp),
                )
                progress?.error?.let { err ->
                    Text(
                        err, color = ForteColors.Red, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 72.dp, bottom = 6.dp),
                    )
                }
            }
        }

        if (downloaded.isEmpty() && activeTracks.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Nothing downloaded yet.\nDownloads play fully offline.",
                        color = ForteColors.TextDim, fontSize = 14.sp,
                    )
                }
            }
        }

        items(downloaded) { entity ->
            val track = entity.toTrack()
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackRow(
                    track = track,
                    actions = actions,
                    isFavorite = track.id in favoriteIds,
                    isCurrent = track.id == currentTrackId,
                    isPlaying = isPlaying,
                    downloadLabel = "${formatBytes(entity.sizeBytes)} · ${entity.bitrateKbps}k",
                    onClick = { actions.play(track, downloaded.map { it.toTrack() }, "Downloads") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmDeleteId = track.id }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove download", tint = ForteColors.TextDim)
                }
            }
        }
    }

    confirmDeleteId?.let { id ->
        val entity = downloaded.firstOrNull { it.videoId == id }
        ConfirmDialog(
            title = "Remove download?",
            body = "\"${entity?.title ?: "This song"}\" will be deleted from this phone. You can download it again anytime.",
            confirmLabel = "Remove",
            onDismiss = { confirmDeleteId = null },
            onConfirm = {
                confirmDeleteId = null
                scope.launch { app.library.removeDownload(id) }
            },
        )
    }
}
