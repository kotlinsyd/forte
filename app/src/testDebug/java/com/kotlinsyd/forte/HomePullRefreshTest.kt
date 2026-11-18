package com.kotlinsyd.forte

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.YtAccount
import com.kotlinsyd.forte.ui.FeaturedChartCard
import com.kotlinsyd.forte.ui.HomePullState
import com.kotlinsyd.forte.ui.HomeRefreshBox
import com.kotlinsyd.forte.ui.heroTracksFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pull PATH, tested end to end at the composition level
 * (Robolectric): the REAL refresh container HomeScreen uses
 * (HomeRefreshBox), the REAL pull handler (HomePullState.
 * onPull — the same call the screen's onRefresh makes), the
 * REAL hero-well selection (heroTracksFor) and the REAL
 * Listen Now featured card (FeaturedChartCard), arranged
 * exactly as the shipped Home composes them since
 * v1.0.35-test — no floating header, the title row first in
 * the list, the indicator at the very top. A synthetic
 * pull-down on the list must fire the handler and the
 * featured card on screen must turn over (its fronting track
 * strides a full window). The one link this cannot certify
 * is the physical touchscreen gesture on the owner's device;
 * everything past the gesture is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class HomePullRefreshTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun track(i: Int) = Track(
        id = "t$i",
        title = "Song %02d".format(i),
        artist = "Artist",
        thumbUrl = null,
    )

    private val pool20 = (0 until 20).map { track(it) }

    /** The shipped Home structure, reduced to the pull path. */
    @Composable
    private fun Harness(
        pull: HomePullState,
        charts: List<Track>?,
        homeShelves: List<YtAccount.HomeShelf>?,
        chartsFailed: Boolean,
    ) {
        val heroTracks = heroTracksFor(
            charts = charts,
            homeShelves = homeShelves,
            history = emptyList(),
            chartsFailed = chartsFailed,
            heroDay = 0,
            heroShift = pull.heroShift,
        )
        val featured = heroTracks.firstOrNull()
        HomeRefreshBox(
            isRefreshing = pull.isRefreshing,
            onRefresh = { pull.onPull() },
            indicatorTop = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("homeList"),
            ) {
                item {
                    Text(
                        "Listen Now",
                        modifier = Modifier.padding(
                            start = 20.dp, top = 20.dp, bottom = 12.dp,
                        ),
                    )
                }
                if (featured != null) {
                    item {
                        FeaturedChartCard(
                            track = featured,
                            label = "FEATURED CHART",
                            headline = "Top Songs — Pakistan",
                            onClick = {},
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
    }

    private fun pullDownOnList() {
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("homeList").performTouchInput {
            down(Offset(centerX, 300f))
            moveBy(Offset(0f, 250f), 120)
            moveBy(Offset(0f, 350f), 120)
            up()
        }
        composeRule.mainClock.advanceTimeBy(600)
    }

    @Test
    fun pullOnChartsFeaturedCardFiresHandlerAndStridesIt() {
        val pull = HomePullState()
        composeRule.setContent {
            Harness(pull, charts = pool20, homeShelves = null, chartsFailed = false)
        }
        composeRule.onNodeWithText("Song 00").assertIsDisplayed()

        pullDownOnList()

        assertEquals(1, pull.forceTick)
        assertEquals(8, pull.heroShift)
        assertTrue(pull.isRefreshing)
        composeRule.onNodeWithText("Song 08").assertIsDisplayed()
    }

    @Test
    fun pullOnFallbackFeaturedCardAlsoStrides() {
        // The v1.0.33 hole: when the hero came from the
        // taste-shelf fallback, heroShift was never read — a
        // pull could not move it at all. The featured card
        // fronts the same window, so the same proof applies.
        val pull = HomePullState()
        val shelves = listOf(YtAccount.HomeShelf("Mixed for you", pool20))
        composeRule.setContent {
            Harness(pull, charts = null, homeShelves = shelves, chartsFailed = true)
        }
        composeRule.onNodeWithText("Song 00").assertIsDisplayed()

        pullDownOnList()

        assertEquals(1, pull.forceTick)
        assertEquals(8, pull.heroShift)
        composeRule.onNodeWithText("Song 08").assertIsDisplayed()
    }

    @Test
    fun pullWithSmallPoolFiresButFeaturedCardStays() {
        // The honest data limit: a hero pool of 8 or fewer has
        // no second page to stride to. The handler still fires
        // (spinner + refetch); the featured card cannot change.
        val pull = HomePullState()
        val pool5 = (0 until 5).map { track(it) }
        composeRule.setContent {
            Harness(pull, charts = pool5, homeShelves = null, chartsFailed = false)
        }
        composeRule.onNodeWithText("Song 00").assertIsDisplayed()

        pullDownOnList()

        assertEquals(1, pull.forceTick)
        assertTrue(pull.isRefreshing)
        composeRule.onNodeWithText("Song 00").assertIsDisplayed()
    }
}
