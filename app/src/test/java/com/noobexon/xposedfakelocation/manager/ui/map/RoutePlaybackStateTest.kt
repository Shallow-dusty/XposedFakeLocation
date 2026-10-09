package com.noobexon.xposedfakelocation.manager.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlaybackStateTest {
    @Test
    fun firstBoundPlayingSnapshotDoesNotResumePersistedRoute() {
        val state = RoutePlaybackState()
        state.observePlaying(true)
        assertFalse(state.requested)
        state.observePlaying(true)
        assertFalse(state.requested)
    }

    @Test
    fun disconnectedDefaultAndDelayedBindingDoNotSynthesizeStart() {
        val state = RoutePlaybackState()
        state.observePlaying(null)
        state.observePlaying(true)
        assertFalse(state.requested)
    }

    @Test
    fun rebindDoesNotResumePreviouslyRunningRoute() {
        val state = RoutePlaybackState()
        state.observePlaying(false)
        state.observePlaying(true)
        assertTrue(state.requested)
        state.observePlaying(null)
        assertFalse(state.requested)
        state.observePlaying(true)
        assertFalse(state.requested)
    }

    @Test
    fun newRemoteStartAfterObservedStopCanPlayRoute() {
        val state = RoutePlaybackState()
        state.observePlaying(false)
        state.observePlaying(true)
        assertTrue(state.requested)
        state.observePlaying(false)
        assertFalse(state.requested)
    }

    @Test
    fun explicitLocalStartWorksWithoutInferringResumeFromSnapshot() {
        val state = RoutePlaybackState()
        state.request(true)
        state.observePlaying(true)
        assertTrue(state.requested)
        state.request(false)
        state.observePlaying(true)
        assertFalse(state.requested)
    }

    @Test
    fun firstLocalWaypointAndLoopLoadsCannotEnablePlayback() {
        for (boundFirst in listOf(true, false)) {
            val state = RoutePlaybackState()
            if (boundFirst) state.observePlaying(true)
            // Local preference collectors can restart only if playback was requested.
            assertFalse(state.requested)
            if (!boundFirst) state.observePlaying(true)
            assertFalse(state.requested)
        }
    }

    @Test
    fun segmentUsesInjectedMonotonicTimeInsteadOfWallClock() {
        var monotonic = 10_000L
        var wallClock = 1_000_000L
        val clock = RouteSegmentClock(1_000L) { monotonic }
        monotonic += 250L
        wallClock -= 300_000L
        assertEquals(0.25, clock.progress(), 0.0)
        monotonic += 250L
        wallClock += 900_000L
        assertEquals(0.5, clock.progress(), 0.0)
        assertEquals(1_600_000L, wallClock)
        monotonic += 500L
        assertEquals(1.0, clock.progress(), 0.0)
    }
}
