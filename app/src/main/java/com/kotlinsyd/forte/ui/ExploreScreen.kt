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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.R
import com.kotlinsyd.forte.data.LastGood
import com.kotlinsyd.forte.data.YtAccount
import kotlinx.coroutines.launch

/**
 * Explore is its own place now (the parity restructure): personal
 * picks, the real Moods & moments grid, and Genres A-Z. Category
 * cards wear the category colour plus one real cover harvested from
 * that category's own page — the same pipeline Home's cards used,
 * moved here with the categories themselves.
 */
@Composable
fun ExploreScreen(
    actions: TrackActions,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenAccount: () -> Unit,
    onOpenYtPlaylist: (String) -> Unit,
    onOpenMood: (String, String, String?) -> Unit,
) {
    val app = ForteApp.instance
    val context = LocalContext.current
    val settings by app.settings.settings.collectAsState()
    val favoriteIds by app.library.favoriteIds.collectAsState(initial = emptySet())
    val accountTick by app.accountVersion.collectAsState()
    val signedIn = remember(accountTick) { YtAccount.isSignedIn() }
    val region = app.settings.region()
    val cacheKey = com.kotlinsyd.forte.data.HomeCache.key(region.gl, signedIn)

    var shelves by remember {
        mutableStateOf(LastGood.getShelves(context, "explore:shelves:$cacheKey"))
    }
    var forYouShelves by remember {
        mutableStateOf(LastGood.getShelves(context, "explore:foryou:$cacheKey"))
    }
    var categories by remember {
        mutableStateOf(
            LastGood.getMoodSections(context, "explore:categories:$cacheKey")
                ?.map { YtAccount.CategoryShelf(it.title, it.items) },
        )
    }
    var moodSections by remember {
        mutableStateOf(LastGood.getMoodSections(context, "explore:moods:$cacheKey"))
    }
    var failed by remember { mutableStateOf(false) }
    var loadedKey by remember { mutableStateOf("") }

    LaunchedEffect(cacheKey) {
        if (cacheKey != loadedKey) {
            loadedKey = cacheKey
            shelves = LastGood.getShelves(context, "explore:shelves:$cacheKey")
            forYouShelves = LastGood.getShelves(context, "explore:foryou:$cacheKey")
            categories = LastGood.getMoodSections(context, "explore:categories:$cacheKey")
                ?.map { YtAccount.CategoryShelf(it.title, it.items) }
            moodSections = LastGood.getMoodSections(context, "explore:moods:$cacheKey")
        }
        failed = false
        launch {
            runCatching { YtAccount.explorePage() }.onSuccess { page ->
                if (page.shelves.isNotEmpty()) {
                    shelves = page.shelves
                    LastGood.putShelves(context, "explore:shelves:$cacheKey", page.shelves)
                }
                if (page.categories.isNotEmpty()) {
                    categories = page.categories
                    LastGood.putMoodSections(
                        context,
                        "explore:categories:$cacheKey",
                        page.categories.map { YtAccount.MoodSection(it.title, it.items) },
                    )
                }
            }.onFailure {
                if (shelves == null && categories == null && moodSections == null) {
                    failed = true
                }
            }
        }
        launch {
            runCatching { YtAccount.moodsAndGenres() }.onSuccess { fresh ->
                if (fresh.isNotEmpty()) {
                    moodSections = fresh
                    LastGood.putMoodSections(context, "explore:moods:$cacheKey", fresh)
                }
            }
        }
        launch {
            runCatching { YtAccount.browseShelves("FEmusic_mixed_for_you") }
                .onSuccess { fresh ->
                    if (fresh.isNotEmpty()) {
                        forYouShelves = fresh
                        LastGood.putShelves(context, "explore:foryou:$cacheKey", fresh)
                    }
                }
        }
    }

    // Harvest one real cover per category, once, from the
    // category's own page; the colour paints until the photo lands.
    val allCategories = remember(categories, moodSections) {
        buildList {
            categories.orEmpty().forEach { addAll(it.items) }
            moodSections.orEmpty().forEach { addAll(it.items) }
        }.distinctBy { it.browseId + "|" + (it.params ?: "") }
    }
    var moodArt by remember { mutableStateOf(app.settings.moodArt()) }
    LaunchedEffect(allCategories) {
        allCategories.forEach { mood ->
            val key = mood.browseId + "|" + (mood.params ?: "")
            if (key !in moodArt) {
                val url = runCatching {
                    YtAccount.moodArtwork(mood.browseId, mood.params)
                }.getOrNull()
                if (!url.isNullOrBlank()) {
                    app.settings.putMoodArt(key, url)
                    moodArt = moodArt + (key to url)
                }
            }
        }
    }

    val avatar = app.settings.accountAvatar()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp + LocalDockClearance.current),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.forte_wordmark),
                    contentDescription = "FORTE.",
                    modifier = Modifier.height(24.dp),
                )
                Spacer(Modifier.weight(1f))
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
            Spacer(Modifier.height(16.dp))
            Text(
                "Explore",
                style = ForteType.pageTitle, color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        val forYouCategory = categories.orEmpty()
            .firstOrNull { it.title.contains("For you", ignoreCase = true) }
        if (forYouCategory != null || !forYouShelves.isNullOrEmpty()) {
            item {
                Spacer(Modifier.height(26.dp))
                SectionHeader("For you", modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
            }
            if (forYouCategory != null) {
                item {
                    CategoryGrid(forYouCategory.items, moodArt, onOpenMood)
                }
            }
            forYouShelves.orEmpty().forEach { shelf ->
                item {
                    ExploreShelfRow(
                        shelf = shelf,
                        actions = actions,
                        favoriteIds = favoriteIds,
                        currentTrackId = currentTrackId,
                        isPlaying = isPlaying,
                        onOpenYtPlaylist = onOpenYtPlaylist,
                    )
                }
            }
        }

        val moods = moodSections.orEmpty()
            .firstOrNull { it.title.contains("Mood", ignoreCase = true) }
        if (moods != null) {
            item {
                Spacer(Modifier.height(26.dp))
                SectionHeader(
                    "Moods & moments",
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                CategoryGrid(moods.items, moodArt, onOpenMood)
            }
        }

        val genres = moodSections.orEmpty()
            .firstOrNull { it.title.equals("Genres", ignoreCase = true) }
        if (genres != null) {
            item {
                Spacer(Modifier.height(26.dp))
                SectionHeader("Genres", modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                CategoryGrid(
                    genres.items.sortedBy { it.title.lowercase() },
                    moodArt,
                    onOpenMood,
                )
            }
        }

        categories.orEmpty()
            .filterNot {
                it.title.contains("For you", ignoreCase = true) ||
                    it.title.equals("Moods & genres", ignoreCase = true)
            }
            .forEach { shelf ->
                item {
                    Spacer(Modifier.height(26.dp))
                    SectionHeader(shelf.title, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(12.dp))
                    CategoryGrid(shelf.items, moodArt, onOpenMood)
                }
            }

        shelves.orEmpty().forEach { shelf ->
            item {
                ExploreShelfRow(
                    shelf = shelf,
                    actions = actions,
                    favoriteIds = favoriteIds,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    onOpenYtPlaylist = onOpenYtPlaylist,
                )
            }
        }

        if (shelves == null && categories == null && moodSections == null && !failed) {
            items(2) {
                Spacer(Modifier.height(26.dp))
                Box(Modifier.padding(horizontal = 20.dp)) { SkeletonShelf() }
            }
        }
        if (failed) {
            item {
                Spacer(Modifier.height(26.dp))
                Text(
                    "Explore couldn't load. Check your connection and reopen Forte.",
                    color = ForteColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryGrid(
    items: List<YtAccount.MoodGenre>,
    moodArt: Map<String, String>,
    onOpenMood: (String, String, String?) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { mood ->
                    val key = mood.browseId + "|" + (mood.params ?: "")
                    MoodCard(
                        title = mood.title,
                        colorArgb = mood.colorArgb,
                        artUrl = moodArt[key],
                        modifier = Modifier.weight(1f),
                    ) {
                        onOpenMood(mood.title, mood.browseId, mood.params)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExploreShelfRow(
    shelf: YtAccount.HomeShelf,
    actions: TrackActions,
    favoriteIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onOpenYtPlaylist: (String) -> Unit,
) {
    if (shelf.tracks.isEmpty() && shelf.cards.isEmpty()) return
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
