package com.kotlinsyd.forte.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsyd.forte.ForteApp

/**
 * Your Forte in numbers, from this phone's own play history: top
 * artists, top songs, total listening time. Honest scope: history
 * keeps the most recent 200 plays, so this is "your recent listening
 * on this phone" — labelled as such, never dressed up as all-time.
 */
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val app = ForteApp.instance
    val rows by app.library.historyRows.collectAsState(initial = emptyList())

    val totalSec = rows.sumOf { it.durationSec }
    val topArtists = rows
        .filter { it.artist.isNotBlank() }
        .groupBy { it.artist }
        .entries.sortedByDescending { it.value.size }
        .take(5)
    val topSongs = rows
        .groupBy { it.videoId }
        .entries.sortedByDescending { it.value.size }
        .take(5)

    Column(Modifier.fillMaxSize()) {
        ListHeader("Your stats", "From your recent plays on this phone", onBack)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Play some music and your numbers will grow here.",
                    color = ForteColors.TextDim, fontSize = 14.sp,
                )
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp + LocalDockClearance.current)) {
            item {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    StatCard("Songs played", "${rows.size}", Modifier.weight(1f))
                    StatCard("Listening time", formatListeningTime(totalSec), Modifier.weight(1f))
                }
                Spacer(Modifier.height(26.dp))
                Text("Top artists", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
            }
            items(topArtists.size) { i ->
                val (artist, plays) = topArtists[i]
                RankRow("${i + 1}", artist, "${plays.size} plays")
            }
            item {
                Spacer(Modifier.height(22.dp))
                Text("Top songs", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
            }
            items(topSongs.size) { i ->
                val plays = topSongs[i].value
                val first = plays.first()
                RankRow("${i + 1}", first.title, "${plays.size} plays · ${first.artist}")
            }
        }
    }
}

private fun formatListeningTime(totalSec: Long): String {
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ForteColors.Surface)
            .padding(16.dp),
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = ForteColors.TextDim)
    }
}

@Composable
private fun RankRow(rank: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = ForteColors.TextDim, modifier = Modifier.width(24.dp),
        )
        Column {
            Text(
                title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, fontSize = 12.sp, color = ForteColors.TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
