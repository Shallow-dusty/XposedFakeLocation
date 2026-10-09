package com.noobexon.xposedfakelocation.manager.ui.map

/** Route playback requires a new start, not a persisted playing snapshot on binding. */
internal class RoutePlaybackState {
    private var lastPlayingSnapshot: Boolean? = null

    var requested: Boolean = false
        private set

    fun observePlaying(playing: Boolean?) {
        if (playing != true) {
            requested = false
        } else if (lastPlayingSnapshot == false) {
            requested = true
        }
        lastPlayingSnapshot = playing
    }

    fun request(playing: Boolean) {
        requested = playing
    }
}

/** Segment timing is supplied by a monotonic clock, independent of calendar changes. */
internal class RouteSegmentClock(private val durationMs: Long, private val now: () -> Long) {
    private val startedAt = now()

    fun progress(): Double = ((now() - startedAt).toDouble() / durationMs).coerceIn(0.0, 1.0)
}
