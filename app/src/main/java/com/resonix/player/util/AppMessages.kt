package com.resonix.player.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide, one-way channel for short messages the UI should show to the
 * user — mostly "why didn't that play / scan" errors raised deep inside the
 * playback service or the scanners, where there is no screen to talk to.
 *
 * The playback service runs in the app's own process, so a plain object is
 * enough. Messages posted while no screen is listening are dropped on purpose
 * (a stale error is worse than none).
 */
object AppMessages {
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun post(message: String) {
        _messages.tryEmit(message)
    }
}
