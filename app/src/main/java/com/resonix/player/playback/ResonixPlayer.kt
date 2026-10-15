package com.resonix.player.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.audio.displayFormatName
import com.resonix.player.data.Track

/**
 * A Player implementation wrapping NativeAudioEngine/PlaybackQueueManager
 * rather than ExoPlayer, so this app's own FFmpeg/Oboe engine — not a
 * second decode path — is what MediaSession exposes to the lock screen,
 * notification, and Bluetooth/headset buttons. SimpleBasePlayer handles
 * the Player interface's listener bookkeeping; this only needs to
 * implement getState() plus the handlers for commands it declares
 * available (see Media3's SimpleBasePlayer docs).
 *
 * The playlist exposed to controllers is a small sliding window: the
 * previous track (if any), the current one, and the next one (if any).
 * Media3 decides whether "next" / "previous" can work from whether the
 * playlist *contains* a next / previous item — with a single-item
 * playlist (what this class used to expose) seekToNext() is silently
 * ignored by the framework and the Next button never reaches the queue.
 * Three items are enough for that, and keep every state update tiny
 * regardless of how many songs the folder holds.
 *
 * Playback itself is started by PlaybackQueueManager.playFolder() (via the
 * PlaybackService custom command); [setNowPlaying] is how that is reflected
 * here. An external controller calling setMediaItems() directly (e.g.
 * Android Auto browsing) is not supported.
 */
@UnstableApi
class ResonixPlayer(
    looper: Looper,
    private val engine: NativeAudioEngine,
    private val queueManager: PlaybackQueueManager
) : SimpleBasePlayer(looper) {

    private var nowPlaying: NowPlaying? = null
    private var items: List<MediaItemData> = emptyList()
    private var currentIndex = 0

    /** Call periodically (see PlaybackService's polling loop), and whenever the
     * engine's play/pause/loading state may have changed, so the session's
     * reported state and position stay in sync with the engine's. */
    fun refreshFromEngine() {
        invalidateState()
    }

    /** Call whenever PlaybackQueueManager.nowPlaying changes. */
    fun setNowPlaying(playing: NowPlaying?) {
        nowPlaying = playing
        if (playing == null) {
            items = emptyList()
            currentIndex = 0
        } else {
            val window = ArrayList<MediaItemData>(3)
            playing.previous?.let { window.add(it.toMediaItemData(null)) }
            currentIndex = window.size
            window.add(playing.track.toMediaItemData(playing.info))
            playing.next?.let { window.add(it.toMediaItemData(null)) }
            items = window
        }
        invalidateState()
    }

    override fun getState(): State {
        val playing = nowPlaying
        val loading = queueManager.isLoading.value

        val commands = Player.Commands.Builder()
        if (playing != null) {
            commands.addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,  // "previous" restarts the track when there is nothing before it
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA
            )
            if (playing.next != null) {
                commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            if (playing.previous != null) {
                commands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
        }

        val playbackState = when {
            items.isEmpty() -> Player.STATE_IDLE  // Media3 only allows IDLE / ENDED with an empty playlist
            loading -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        // The audible position can overshoot the duration by a frame or two at the very end.
        val durationMs = playing?.let { p -> p.info.durationMs.takeIf { it > 0L } ?: p.track.durationMs } ?: 0L
        var positionMs = engine.getCurrentPositionMs().coerceAtLeast(0L)
        if (durationMs > 0L) positionMs = minOf(positionMs, durationMs)

        return State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(items)
            .setCurrentMediaItemIndex(currentIndex)
            .setPlayWhenReady(
                engine.isPlaying() || loading,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(playbackState)
            .setContentPositionMs(positionMs)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) queueManager.resumeOrRestart() else engine.pauseTrack()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when {
            mediaItemIndex > currentIndex -> queueManager.playNext()
            mediaItemIndex < currentIndex -> queueManager.playPrevious()
            else -> engine.seekTo(positionMs.coerceAtLeast(0L))  // TIME_UNSET ("default position") → start
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        engine.pauseTrack()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        // Not supported — playback is driven by PlaybackQueueManager.playFolder().
        return Futures.immediateVoidFuture()
    }
}

/**
 * [info] is what the decoder reported for the track that is actually loaded; it is preferred
 * over the library's stored values, which are 0 / empty for files that scanned badly.
 * Neighbouring tracks (no [info]) fall back to the library values.
 */
private fun Track.toMediaItemData(info: AudioTrackInfo?): SimpleBasePlayer.MediaItemData {
    val trackDurationMs = info?.durationMs?.takeIf { it > 0L } ?: durationMs
    val extras = Bundle().apply {
        putInt("sampleRateHz", info?.sampleRateHz?.takeIf { it > 0 } ?: sampleRateHz)
        putInt("bitDepth", info?.bitDepth?.takeIf { it > 0 } ?: bitDepth)
        putString("format", displayFormatName(info?.format?.takeIf { it.isNotBlank() } ?: format))
        putString("albumArtUri", albumArtUri)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setExtras(extras)
        .build()
    val mediaItem = MediaItem.Builder()
        .setMediaId(contentUri)
        .setMediaMetadata(metadata)
        .build()
    return SimpleBasePlayer.MediaItemData.Builder(contentUri)
        .setMediaItem(mediaItem)
        .setDurationUs(if (trackDurationMs > 0L) trackDurationMs * 1000 else C.TIME_UNSET)
        .build()
}
