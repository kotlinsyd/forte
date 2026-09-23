package com.kotlinsyd.forte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The player is ONE sheet with two resting states: collapsed = the
 * floating mini-player strip above the dock,
 * expanded = the full player. [progress] is 0 collapsed, 1 expanded,
 * and every pixel in between while a finger drags — the sheet follows
 * the finger continuously and settles with a spring, never a snap.
 *
 * Drag sources all feed [dragBy]:
 *  - the mini strip itself (draggable modifier in the root),
 *  - the full player's header (draggable modifier passed into it),
 *  - the player's scrolling body, through [nestedScrollConnection]:
 *    an upward drag expands the sheet before the body may scroll; a
 *    downward drag the body can't use (already at its top) collapses
 *    the sheet. The two never fight over the same pixels.
 */
class PlayerSheetState(private val scope: CoroutineScope) {

    var progress by mutableFloatStateOf(0f)
        private set

    /** Px between the collapsed and expanded resting positions.
     * Measured by the root from the strip's real position, so the
     * finger travel and the sheet travel are the same distance. */
    var travelPx: Float = 0f

    private var settleJob: Job? = null

    val isExpanded: Boolean get() = progress >= 0.999f
    val isCollapsed: Boolean get() = progress <= 0.001f

    /** Feed a finger delta (px, positive = finger moved down). */
    fun dragBy(dyPx: Float) {
        if (travelPx <= 0f) return
        settleJob?.cancel()
        settleJob = null
        progress = (progress - dyPx / travelPx).coerceIn(0f, 1f)
    }

    /** Release: fling wins, otherwise settle to the nearest state. */
    fun settle(velocityYPxPerSec: Float = 0f) {
        val target = when {
            velocityYPxPerSec < -FLING_PX_PER_SEC -> 1f
            velocityYPxPerSec > FLING_PX_PER_SEC -> 0f
            progress >= 0.5f -> 1f
            else -> 0f
        }
        animateTo(target)
    }

    fun expand() = animateTo(1f)

    fun collapse() = animateTo(0f)

    /** Instant reset (e.g. playback cleared): no animation. */
    fun reset() {
        settleJob?.cancel()
        settleJob = null
        progress = 0f
    }

    private fun animateTo(target: Float) {
        settleJob?.cancel()
        val from = progress
        if (from == target) {
            progress = target
            return
        }
        settleJob = scope.launch {
            val anim = Animatable(from)
            anim.animateTo(
                target,
                spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { progress = value }
            progress = target
        }
    }

    /**
     * Nested-scroll discipline for the player's scrolling body:
     * expand-before-scroll on the way up, collapse-on-leftover on the
     * way down, settle on fling.
     */
    fun nestedScrollConnection(): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Finger moving up: grow the sheet first; the body scrolls
            // only once the sheet is fully expanded.
            if (available.y < 0f && progress < 1f && travelPx > 0f) {
                val want = -available.y
                val used = minOf(want, (1f - progress) * travelPx)
                if (used > 0f) {
                    dragBy(-used)
                    return Offset(0f, -used)
                }
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // Leftover downward drag the body couldn't consume (it's
            // already at its top): hand it to the sheet to collapse.
            if (available.y > 0f && progress > 0f && travelPx > 0f) {
                val used = minOf(available.y, progress * travelPx)
                if (used > 0f) {
                    dragBy(used)
                    return Offset(0f, used)
                }
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (progress > 0f && progress < 1f) {
                settle(available.y)
                return Velocity(0f, available.y)
            }
            if (progress >= 1f && available.y > FLING_PX_PER_SEC) {
                // A hard downward fling on the fully open player.
                settle(available.y)
                return Velocity(0f, available.y)
            }
            return Velocity.Zero
        }
    }

    private companion object {
        const val FLING_PX_PER_SEC = 550f
    }
}
