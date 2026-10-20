package com.outman.tvlauncher.core

/** UI and watchdog decisions are independent of translated display text. */
enum class PlaybackPhase(val keepScreenOn: Boolean) {
    STOPPED(false), PREPARING(true), BUFFERING(true), PLAYING(true),
    RETRYING(true), RECOVERING(true), PAUSED(false), SUPPRESSED(false), ERROR(false)
}

object PlaybackPolicy {
    fun mayAdvance(playWhenReady: Boolean, suppressed: Boolean): Boolean =
        playWhenReady && !suppressed

    fun phase(playWhenReady: Boolean, suppressed: Boolean, ready: Boolean, frameRendered: Boolean): PlaybackPhase =
        when {
            suppressed -> PlaybackPhase.SUPPRESSED
            !playWhenReady -> PlaybackPhase.PAUSED
            ready && frameRendered -> PlaybackPhase.PLAYING
            else -> PlaybackPhase.BUFFERING
        }

    fun needsWatchdog(playWhenReady: Boolean, suppressed: Boolean, ready: Boolean, frameRendered: Boolean): Boolean =
        mayAdvance(playWhenReady, suppressed) && !(ready && frameRendered)
}
