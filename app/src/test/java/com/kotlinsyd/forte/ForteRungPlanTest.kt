package com.kotlinsyd.forte

import com.kotlinsyd.forte.data.ForteEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression checks for the v1.0.31-test poison: "one song
 * could not play, then every next song showed the same error."
 *
 * The mechanism: the engine's bench map is keyed by RUNG, not
 * by song, and lives for the whole session. One song's hard
 * failure walks its recovery rotation rung by rung, and the
 * rung-health verdicts (recovery UrlDead, the client wall, a
 * mid-play refusal) bench each rung for ten minutes. The old
 * resolve loop then skipped every benched rung for EVERY
 * later song's fresh resolve too — once the cascade benched
 * the whole ladder, each following song threw instantly,
 * without one network attempt, and surfaced the same
 * "couldn't play" until the benches lapsed.
 *
 * The walk plan is the fix's contract, pinned here:
 *  - benches REORDER a walk (healthy rungs first), they
 *    never EMPTY it;
 *  - a track's own exclusions (its tried rungs, the recovery
 *    rotation's per-song evidence) stay absolute.
 */
class ForteRungPlanTest {

    private val ladder = listOf("ANDROID_VR", "VISIONOS", "ANDROID", "IOS")

    @Test
    fun `no benches and no exclusions walks the ladder as-is`() {
        assertEquals(
            ladder,
            ForteEngine.walkPlan(ladder, emptySet(), emptySet()),
        )
    }

    @Test
    fun `healthy rungs walk first and benched rungs follow as second chance`() {
        assertEquals(
            listOf("ANDROID_VR", "ANDROID", "VISIONOS", "IOS"),
            ForteEngine.walkPlan(
                ladder,
                excluded = emptySet(),
                benched = setOf("VISIONOS", "IOS"),
            ),
        )
    }

    @Test
    fun `one songs full bench cascade still leaves the next song a full walk`() {
        // Song A's recovery benched every rung. Song B's fresh
        // resolve must still be offered every rung, in ladder
        // order — B's error-free start cannot inherit A's
        // failure as an empty ladder.
        val plan = ForteEngine.walkPlan(
            ladder,
            excluded = emptySet(),
            benched = ladder.toSet(),
        )
        assertEquals(ladder, plan)
    }

    @Test
    fun `a tracks own tried rungs stay excluded even when benched`() {
        // A recovery walk for THIS track: its tried rungs are
        // excluded outright — the second chance must not
        // resurrect them — while a bench another song earned
        // on an untried rung only defers that rung.
        val plan = ForteEngine.walkPlan(
            ladder,
            excluded = setOf("ANDROID_VR", "VISIONOS"),
            benched = setOf("ANDROID_VR", "ANDROID"),
        )
        assertEquals(listOf("IOS", "ANDROID"), plan)
        assertFalse(plan.contains("ANDROID_VR"))
        assertFalse(plan.contains("VISIONOS"))
    }
}
