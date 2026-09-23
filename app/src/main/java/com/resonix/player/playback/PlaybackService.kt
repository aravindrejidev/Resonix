package com.resonix.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.resonix.player.MainActivity
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate() {
        super.onCreate()

        engine = NativeAudioEngine()
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

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        // Reflect queue changes (new track started, folder played) into
        // the session, and request audio focus whenever something
        // actually starts rather than unconditionally at service start.
        serviceScope.launch {
            queueManager.currentTrack.collect { track ->
                if (track != null) audioFocusManager.requestFocus()
                player.setNowPlaying(track, engine.isPlaying())
            }
        }

        // Same polling approach the UI uses for its own position display
        // — also drives queue auto-advance and keeps the session's
        // reported position current.
        serviceScope.launch {
            while (true) {
                queueManager.checkForAutoAdvance()
                player.refreshFromEngine()
                delay(500)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    override fun onDestroy() {
        audioFocusManager.abandonFocus()
        queueManager.release()
        engine.release()
        player.release()
        mediaSession.release()
        super.onDestroy()
    }
}
