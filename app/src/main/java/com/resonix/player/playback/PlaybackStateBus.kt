package com.resonix.player.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the screens need to know about playback, in one immutable snapshot. */
data class PlaybackSnapshot(
    val playing: NowPlaying? = null,
    /** True while audio is playing — and while a song is still loading after play was requested. */
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** What the output stream really is, e.g. "AAudio · Exclusive · 96 kHz". Empty before the first song. */
    val outputInfo: String = "",
)

/**
 * Hand-off from PlaybackService to the UI. The service runs in the app's own process, so the
 * screens read what is playing straight from here. (Commands travel the other way through
 * [PlaybackController].)
 */
object PlaybackStateBus {
    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    fun publish(snapshot: PlaybackSnapshot) {
        _snapshot.value = snapshot
    }
}

/** A running sleep timer: playback is paused when [endsAtElapsedMs] (SystemClock.elapsedRealtime) is reached. */
data class SleepTimerState(val totalMinutes: Int, val endsAtElapsedMs: Long)

/**
 * The sleep timer lives in PlaybackService (so it still fires when the screens are gone and the
 * phone is in a pocket); this is how the Tools screen sees it.
 */
object SleepTimerBus {
    private val _state = MutableStateFlow<SleepTimerState?>(null)
    val state: StateFlow<SleepTimerState?> = _state.asStateFlow()

    fun set(state: SleepTimerState?) {
        _state.value = state
    }
}
