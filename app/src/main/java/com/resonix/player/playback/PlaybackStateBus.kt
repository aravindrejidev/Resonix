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
)

/**
 * Hand-off from PlaybackService to the UI. The service runs in the app's own process, so the
 * screens can read what is playing straight from here instead of waiting for the Media3
 * MediaController to relay it. (The MediaController is still what the UI uses to *send* commands —
 * play, pause, next, seek — and it still feeds the notification, lock screen and headset buttons.)
 * Relaying state through the controller depends on message ordering and command availability;
 * this does not.
 */
object PlaybackStateBus {
    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    fun publish(snapshot: PlaybackSnapshot) {
        _snapshot.value = snapshot
    }
}
