package com.resonix.player.playback

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.resonix.player.util.AppMessages

private const val TAG = "PlaybackController"

/**
 * How the screens talk to PlaybackService. The service lives in the same process, so a command is
 * just a direct call — no session / controller connection in between that could be late, rejected
 * or silently drop a button press.
 *
 * Commands that need playback to exist ([run]) start the service first if it is not running yet
 * and are queued until it has come up. Commands that only make sense for a service that is already
 * playing ([runIfActive]) are ignored otherwise.
 *
 * Main thread only (everything the UI does is); calls from other threads are bounced to it.
 */
object PlaybackController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<(PlaybackService) -> Unit>()
    private var service: PlaybackService? = null

    internal fun attach(service: PlaybackService) {
        this.service = service
        while (pending.isNotEmpty()) {
            pending.removeFirst().invoke(service)
        }
    }

    internal fun detach(service: PlaybackService) {
        if (this.service === service) this.service = null
    }

    /** Runs [action] on the service, starting it if needed. */
    fun run(context: Context, action: (PlaybackService) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { run(context, action) }
            return
        }
        val running = service
        if (running != null) {
            action(running)
            return
        }
        pending.addLast(action)
        val app = context.applicationContext
        try {
            app.startService(Intent(app, PlaybackService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the playback service", e)
            pending.clear()
            AppMessages.post("Couldn't start the audio service")
        }
    }

    /** Runs [action] only if the service is up (there is something to control). */
    fun runIfActive(action: (PlaybackService) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { runIfActive(action) }
            return
        }
        service?.let(action)
    }

    // --- The commands the UI sends ----------------------------------------------

    fun playFolder(
        context: Context,
        rootFolderUri: String,
        folderPath: String,
        shuffle: Boolean,
        startContentUri: String?,
        sortKey: String?,
        sortAscending: Boolean,
    ) = run(context) {
        it.playFolder(rootFolderUri, folderPath, shuffle, startContentUri, sortKey, sortAscending)
    }

    fun togglePlayPause() = runIfActive { it.togglePlayPause() }

    fun next() = runIfActive { it.next() }

    fun previous() = runIfActive { it.previous() }

    fun seekTo(positionMs: Long) = runIfActive { it.seekTo(positionMs) }

    /** Starts the service if needed, so a timer set before the first song still fires. */
    fun setSleepTimer(context: Context, minutes: Int) {
        if (minutes <= 0) runIfActive { it.setSleepTimer(0) } else run(context) { it.setSleepTimer(minutes) }
    }
}
