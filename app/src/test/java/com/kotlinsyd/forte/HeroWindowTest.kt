package com.kotlinsyd.forte

import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.ui.heroWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero window's turnover contract (v1.0.33-test fix for
 * "the hero never changes"): over the live chart pool size
 * (top 20), consecutive days and consecutive pulls must bring
 * a NEW set of chart hits — the v1.0.22 window advanced one
 * position per day and ignored pulls entirely.
 */
class HeroWindowTest {

    private fun pool(n: Int): List<Track> =
        (1..n).map { Track(id = "t$it", title = "Song $it", artist = "Artist") }

    private fun ids(tracks: List<Track>): Set<String> = tracks.map { it.id }.toSet()

    @Test
    fun smallPoolIsTheWholeWindow() {
        val p = pool(8)
        assertEquals(p, heroWindow(p, heroDay = 20771, heroShift = 0))
        assertEquals(p, heroWindow(p, heroDay = 20772, heroShift = 16))
        val p5 = pool(5)
        assertEquals(p5, heroWindow(p5, heroDay = 20771, heroShift = 8))
    }

    @Test
    fun windowIsEightChartHitsInCyclicChartOrder() {
        val p = pool(20)
        val w = heroWindow(p, heroDay = 20771, heroShift = 0)
        assertEquals(8, w.size)
        assertTrue(p.containsAll(w))
        // Cyclic chart order: pool indices ascend, wrapping once at most.
        val idx = w.map { p.indexOf(it) }
        val ascents = idx.zipWithNext().count { (a, b) -> b == (a + 1) % p.size }
        assertEquals(7, ascents)
    }

    @Test
    fun consecutiveDaysBringANewSet() {
        val p = pool(20)
        val today = heroWindow(p, heroDay = 20771, heroShift = 0)
        val tomorrow = heroWindow(p, heroDay = 20772, heroShift = 0)
        assertTrue(
            "consecutive days must not repeat hero tracks",
            ids(today).intersect(ids(tomorrow)).isEmpty(),
        )
    }

    @Test
    fun aPullBringsANewSet() {
        val p = pool(20)
        val before = heroWindow(p, heroDay = 20771, heroShift = 0)
        val afterPull = heroWindow(p, heroDay = 20771, heroShift = 8)
        assertTrue(
            "a pull must not repeat hero tracks",
            ids(before).intersect(ids(afterPull)).isEmpty(),
        )
    }

    @Test
    fun windowWrapsAroundThePoolEnd() {
        val p = pool(20)
        // heroDay 2 -> start (2*8) % 20 = 16: tail of the chart,
        // then wraps to its head — still chart order, cyclically.
        val w = heroWindow(p, heroDay = 2, heroShift = 0)
        assertEquals(
            listOf("t17", "t18", "t19", "t20", "t1", "t2", "t3", "t4"),
            w.map { it.id },
        )
    }
}
