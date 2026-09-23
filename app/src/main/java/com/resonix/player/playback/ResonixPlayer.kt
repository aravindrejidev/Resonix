package com.resonix.player.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.resonix.player.audio.NativeAudioEngine
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
 * Current scope: this app drives playback itself, through
 * PlaybackQueueManager.playFolder() (called from wherever your UI ends
 * up triggering it) — [setNowPlaying] is how that gets reflected here.
 * An external controller calling setMediaItems() directly (e.g. Android
 * Auto browsing) updates displayed state but isn't wired to actually
 * start playback yet; extend handleSetMediaItems() if you need that.
 */
@UnstableApi
class ResonixPlayer(
    looper: Looper,
    private val engine: NativeAudioEngine,
    private val queueManager: PlaybackQueueManager
) : SimpleBasePlayer(looper) {

    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var mediaItems: List<MediaItemData> = emptyList()
    private var currentIndex = 0

    /** Call periodically (see PlaybackService's polling loop) so the
     * session's reported position stays in sync with the engine's. */
    fun refreshFromEngine() {
        invalidateState()
    }

    /** Call whenever PlaybackQueueManager.currentTrack changes. */
    fun setNowPlaying(track: Track?, isPlaying: Boolean) {
        playWhenReady = isPlaying
        playbackState = if (track != null) Player.STATE_READY else Player.STATE_IDLE
        mediaItems = track?.let { listOf(it.toMediaItemData()) } ?: emptyList()
        currentIndex = 0
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM
            )
        if (queueManager.hasNext) commands.add(Player.COMMAND_SEEK_TO_NEXT)
        if (queueManager.hasPrevious) commands.add(Player.COMMAND_SEEK_TO_PREVIOUS)

        return State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(mediaItems)
            .setCurrentMediaItemIndex(currentIndex)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setContentPositionMs(engine.getCurrentPositionMs())
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) engine.resumeTrack() else engine.pauseTrack()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT -> queueManager.playNext()
            Player.COMMAND_SEEK_TO_PREVIOUS -> queueManager.playPrevious()
            else -> engine.seekTo(positionMs)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        playbackState = Player.STATE_READY
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pauseTrack()
        playWhenReady = false
        playbackState = Player.STATE_IDLE
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        // Bookkeeping only — see the class doc's "Current scope" note.
        this.mediaItems = mediaItems.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item-$i" })
                .setMediaItem(item)
                .build()
        }
        this.currentIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}

private fun Track.toMediaItemData(): SimpleBasePlayer.MediaItemData {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .build()
    val mediaItem = MediaItem.Builder()
        .setMediaId(contentUri)
        .setMediaMetadata(metadata)
        .build()
    return SimpleBasePlayer.MediaItemData.Builder(contentUri)
        .setMediaItem(mediaItem)
        .setDurationUs(durationMs * 1000)
        .build()
}
