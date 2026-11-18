package com.kotlinsyd.forte

import com.kotlinsyd.forte.data.HomeCache
import com.kotlinsyd.forte.ui.HomePullState
import com.kotlinsyd.forte.ui.ResumeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull handler's state contract and the auto-refresh
 * triggers, tested at the holder level (the gesture-level
 * proof lives in HomePullRefreshTest): a pull must raise the
 * spinner, stride the hero a full page and re-key the feed
 * effects; a settle must drop the spinner; the resume gate
 * must swallow exactly the registration replay.
 */
class HomePullStateTest {

    @Test
    fun pullRaisesSpinnerStridesHeroAndForcesRefetch() {
        val pull = HomePullState()
        assertFalse(pull.isRefreshing)
        assertEquals(0, pull.heroShift)
        assertEquals(0, pull.forceTick)

        pull.onPull()

        assertTrue(pull.isRefreshing)
        assertEquals(8, pull.heroShift)
        assertEquals(1, pull.forceTick)
    }

    @Test
    fun secondPullStridesAgainAndSettleDropsOnlyTheSpinner() {
        val pull = HomePullState()
        pull.onPull()
        pull.settle()
        assertFalse(pull.isRefreshing)

        pull.onPull()

        assertTrue(pull.isRefreshing)
        assertEquals(16, pull.heroShift)
        assertEquals(2, pull.forceTick)
    }

    @Test
    fun resumeGateSwallowsTheRegistrationReplayOnly() {
        val gate = ResumeGate()
        assertFalse(gate.onResume()) // the replay at registration
        assertTrue(gate.onResume()) // a real return
        assertTrue(gate.onResume()) // every later return counts
    }
}

/**
 * Fault-3 proof at the key level: Home's cache — and with it
 * the feed each region sees — is keyed by the SELECTED
 * country's gl and by sign-in state. A country change is a
 * different key, so the new country's feed can never be
 * served from the old country's cache; the effects are keyed
 * on the same gl, so the change refetches.
 */
class HomeCacheKeyTest {

    @Test
    fun cacheKeySeparatesCountries() {
        assertNotEquals(
            HomeCache.key("PK", signedIn = false),
            HomeCache.key("US", signedIn = false),
        )
        assertNotEquals(
            HomeCache.key("PK", signedIn = true),
            HomeCache.key("GB", signedIn = true),
        )
    }

    @Test
    fun cacheKeySeparatesSignInStateAndIsStable() {
        assertNotEquals(
            HomeCache.key("PK", signedIn = false),
            HomeCache.key("PK", signedIn = true),
        )
        assertEquals(
            HomeCache.key("pk", signedIn = false),
            HomeCache.key("PK", signedIn = false),
        )
    }
}
