package com.resonix.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.resonix.player.MainActivity
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.data.AppDatabase
import android.util.Log
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the whole playback stack (engine, queue,
 * player, audio focus) so playback survives the app leaving the
 * foreground. Media3's default notification/lock-screen UI is generated
 * automatically once the MediaSession + Player report correct state —
 * no separate notification code needed here.
 *
 * Declared in AndroidManifest.xml with foregroundServiceType="mediaPlayback"
 * and the FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK permissions.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var engine: NativeAudioEngine
    private lateinit var queueManager: PlaybackQueueManager
    private lateinit var player: ResonixPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var audioFocusManager: AudioFocusManager

    private var gainBeforeDucking: Double = 1.0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Headphones unplugged / Bluetooth dropped: pause instead of blasting the music out of the speaker. */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                engine.pauseTrack()
                player.refreshFromEngine()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        engine = NativeAudioEngine()
        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val trackDao = AppDatabase.getInstance(this).trackDao()
        queueManager = PlaybackQueueManager(this, engine, trackDao)
        player = ResonixPlayer(mainLooper, engine, queueManager)

        audioFocusManager = AudioFocusManager(
            context = this,
            onPause = {
                engine.pauseTrack()
                player.refreshFromEngine()
            },
            onResume = {
                engine.resumeTrack()
                player.refreshFromEngine()
            },
            onDuck = { duck ->
                if (duck) {
                    gainBeforeDucking = engine.getGain()
                    engine.setGain(gainBeforeDucking * 0.2)
                } else {
                    engine.setGain(gainBeforeDucking)
                }
            }
        )

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val defaultResult = super.onConnect(session, controller)
                val sessionCommands = defaultResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand(COMMAND_PLAY_FOLDER, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.accept(
                    sessionCommands, defaultResult.availablePlayerCommands
                )
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == COMMAND_PLAY_FOLDER) {
                    val rootFolderUri = args.getString(ARG_ROOT_FOLDER_URI).orEmpty()
                    val folderPath = args.getString(ARG_FOLDER_PATH).orEmpty()
                    val shuffle = args.getBoolean(ARG_SHUFFLE, false)
                    val startUri = args.getString(ARG_START_URI)
                    val sortKey = args.getString(ARG_SORT_KEY)
                    val sortAscending = args.getBoolean(ARG_SORT_ASCENDING, true)
                    serviceScope.launch {
                        try {
                            queueManager.playFolder(rootFolderUri, folderPath, shuffle, startUri, sortKey, sortAscending)
                        } catch (e: Exception) {
                            // Without this a database error here would take the whole app down.
                            Log.e(TAG, "playFolder failed", e)
                            AppMessages.post("Couldn't start playback")
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(sessionCallback)
            .build()

        // Reflect queue changes (new track started, folder played) into
        // the session, and request audio focus whenever something
        // actually starts rather than unconditionally at service start.
        serviceScope.launch {
            queueManager.nowPlaying.collect { playing ->
                if (playing != null) audioFocusManager.requestFocus()
                player.setNowPlaying(playing)
                publishSnapshot()
            }
        }

        // A track starting to load / finishing loading changes the session's
        // state (buffering vs playing) even though nothing else did.
        serviceScope.launch {
            queueManager.isLoading.collect {
                player.refreshFromEngine()
                publishSnapshot()
            }
        }

        // Polling loop: checks for a finished track often (so the next one
        // starts promptly — there is no gapless pre-loading yet), and keeps
        // the session's reported position current twice a second.
        serviceScope.launch {
            var ticks = 0
            while (true) {
                queueManager.checkForAutoAdvance()
                if (ticks % 5 == 0) player.refreshFromEngine()
                if (ticks % 2 == 0) publishSnapshot()
                ticks++
                delay(100)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    /** What the screens show: see PlaybackStateBus. */
    private fun publishSnapshot() {
        val playing = queueManager.nowPlaying.value
        val durationMs = playing?.let { p -> p.info.durationMs.takeIf { it > 0L } ?: p.track.durationMs } ?: 0L
        var positionMs = engine.getCurrentPositionMs().coerceAtLeast(0L)
        if (durationMs > 0L) positionMs = minOf(positionMs, durationMs)
        PlaybackStateBus.publish(
            PlaybackSnapshot(
                playing = playing,
                isPlaying = engine.isPlaying() || queueManager.isLoading.value,
                positionMs = positionMs,
                durationMs = durationMs,
            )
        )
    }

    override fun onDestroy() {
        PlaybackStateBus.publish(PlaybackSnapshot())
        unregisterReceiver(becomingNoisyReceiver)
        serviceScope.cancel()
        audioFocusManager.abandonFocus()
        queueManager.release()
        engine.release()
        player.release()
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val COMMAND_PLAY_FOLDER = "com.resonix.player.PLAY_FOLDER"
        const val ARG_ROOT_FOLDER_URI = "root_folder_uri"
        const val ARG_FOLDER_PATH = "folder_path"
        const val ARG_SHUFFLE = "shuffle"
        const val ARG_START_URI = "start_uri"
        const val ARG_SORT_KEY = "sort_key"
        const val ARG_SORT_ASCENDING = "sort_ascending"
    }
}
