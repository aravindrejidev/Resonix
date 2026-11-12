package com.resonix.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.resonix.player.MainActivity
import com.resonix.player.R
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.audio.TrackProber
import com.resonix.player.data.AppDatabase
import com.resonix.player.data.Track
import com.resonix.player.ui.AlbumArtLoader
import com.resonix.player.ui.AppSettings
import com.resonix.player.ui.SettingsState
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "PlaybackService"

/**
 * Owns playback: the native engine, the queue, audio focus, the system media session, the
 * notification and the sleep timer. It runs in the app's own process and is driven through
 * [PlaybackController] (what the screens call); what the screens see comes back through
 * [PlaybackStateBus].
 *
 * Phone-side controls — the notification, the lock screen / quick-settings media player,
 * headset and Bluetooth buttons — all go through one [MediaSession] and end up in the very same
 * methods the screens use, so every control behaves identically wherever it is pressed.
 *
 * Everything here runs on the main thread.
 */
class PlaybackService : Service() {

    private lateinit var engine: NativeAudioEngine
    private lateinit var queueManager: PlaybackQueueManager
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var settings: AppSettings
    private lateinit var session: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Applies settings to the engine off the main thread: changing Output / Exclusive mode reopens
     * the audio stream, which can take a few hundred milliseconds. One thread, so quick successive
     * changes are applied in the order they were made.
     */
    private val engineSettingsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "resonix-engine-settings")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var routeMonitor: AudioRouteMonitor
    private lateinit var directVolume: DirectVolume
    private var usbBitPerfect: UsbBitPerfect? = null  // Android 14+ only

    /** Direct-volume gain from Settings, and the temporary dip while another app talks. */
    private var dvcGain = 1.0
    private var duckFactor = 1.0

    // Direct volume control state machine (see reconcileDirectVolume).
    @Volatile
    private var bitPerfectActive = false     // the USB bit-perfect mixer is engaged for the current song
    private var dvcEngaged = false
    private var systemFolded = false         // Android's volume has been folded into the engine level
    private var foldedForLock = false        // ... because DVC parks the system volume (not just bit-perfect)
    private var preFoldLevel: Float? = null
    private var parkPending = false
    private var dvcSequence = 0

    /** What the current song was planned for; a change of these re-plans it on the spot. */
    private var lastRouteKey: String? = null
    private var lastPlanSettingsKey: String? = null
    private var lastGapless: Boolean? = null

    private var isForeground = false
    private var destroyed = false
    private var idleTicks = 0

    private var artUri: String? = null
    private var artBitmap: Bitmap? = null
    private var artJob: Job? = null
    private var sleepJob: Job? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Headphones pulled out / Bluetooth dropped: don't blast the room.
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    // --- Lifecycle ----------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        // A notification left behind by an earlier, now dead, instance of this service.
        notificationManager.cancel(NOTIFICATION_ID)

        settings = AppSettings.get(this)
        engine = NativeAudioEngine()
        routeMonitor = AudioRouteMonitor.get(this)
        queueManager = PlaybackQueueManager(
            this, engine, AppDatabase.getInstance(this).trackDao(),
            beforePlay = ::planOutputFor,
            canPrepareNext = ::gaplessAllowed,
        )
        audioFocusManager = AudioFocusManager(
            context = this,
            isPlaying = ::isActuallyPlaying,
            onPause = { pausePlayback() },
            onResume = { resumeAfterFocusLoss() },
            onDuck = { duck ->
                duckFactor = if (duck) DUCK_GAIN else 1.0
                applyMasterGain()
            },
        )

        createSession()
        directVolume = DirectVolume(this, session)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            usbBitPerfect = UsbBitPerfect(routeMonitor, getSystemService(AudioManager::class.java))
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // The very first apply happens right here (no stream exists yet, so it is instant) so the
        // first song already opens with the saved Output / Exclusive / Bit-perfect choices.
        val initialSettings = settings.state.value
        EngineSettings.apply(engine, initialSettings)
        lastPlanSettingsKey = planSettingsKey(initialSettings)
        routeMonitor.refresh()
        val startRoute = routeMonitor.route.value
        lastRouteKey = routeKey(startRoute)
        if (!initialSettings.directVolume) {
            directVolume.restoreLeftover()      // a crash while parked must not leave the volume at maximum
        }
        directVolume.fixStale(startRoute.deviceType)
        reconcileDirectVolume()
        serviceScope.launch { settings.state.collect { applySettings(it) } }
        serviceScope.launch { routeMonitor.route.collect { onRouteChanged(it) } }
        serviceScope.launch { queueManager.nowPlaying.collect { onTrackChanged(it) } }
        serviceScope.launch { queueManager.isLoading.collect { refreshAll() } }
        serviceScope.launch { runTicker() }

        // Last: queued commands (e.g. the "play this folder" that started us) run now.
        PlaybackController.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_DISMISS -> if (!isActuallyPlaying()) stopSelf()  // notification swiped away while paused
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away: keep playing if music is on, otherwise there is nothing left to serve.
        if (!isActuallyPlaying()) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        PlaybackController.detach(this)
        serviceScope.cancel()
        // Give Android's volume back (at the loudness it was playing at) and the DAC to the normal mixer.
        directVolume.removeRemote()
        directVolume.unpark(settings.state.value.directVolumeLevel, routeMonitor.route.value.deviceType)
        usbBitPerfect?.release()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
        }
        audioFocusManager.abandonFocus()
        queueManager.release()  // waits for an in-flight load: the engine must outlive it
        channelProber?.release()
        engineSettingsExecutor.shutdown()
        try {
            engineSettingsExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        engine.release()
        releaseWakeLock()
        session.isActive = false
        session.release()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
        PlaybackStateBus.publish(PlaybackSnapshot())
        SleepTimerBus.set(null)
        super.onDestroy()
    }

    // --- Commands (called by PlaybackController, the session callback and the notification) ---

    fun playFolder(
        rootFolderUri: String,
        folderPath: String,
        shuffle: Boolean,
        startContentUri: String?,
        sortKey: String?,
        sortAscending: Boolean,
    ) {
        serviceScope.launch {
            try {
                queueManager.playFolder(rootFolderUri, folderPath, shuffle, startContentUri, sortKey, sortAscending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "playFolder failed", e)
                AppMessages.post("Couldn't start playback")
            }
        }
    }

    fun togglePlayPause() {
        if (isActuallyPlaying()) pausePlayback() else resumePlayback()
    }

    fun pausePlayback() {
        queueManager.pause()
        refreshAll()
    }

    fun resumePlayback() {
        if (!audioFocusManager.requestFocus()) {
            AppMessages.post("Another app is using the audio output")
            return
        }
        queueManager.resumeOrRestart()
        refreshAll()
    }

    private fun resumeAfterFocusLoss() {
        queueManager.resumeOrRestart()
        refreshAll()
    }

    fun next() {
        if (queueManager.playNext()) refreshAll()
    }

    /** Like most players: a few seconds in, "previous" restarts the song; early on it goes back one. */
    fun previous() {
        if (engine.getCurrentPositionMs() > RESTART_THRESHOLD_MS || !queueManager.hasPrevious) {
            seekTo(0L)
        } else {
            queueManager.playPrevious()
            refreshAll()
        }
    }

    fun seekTo(positionMs: Long) {
        queueManager.seekTo(positionMs)
        refreshAll()
    }

    /** Pauses after [minutes]; 0 cancels. Lives here so it fires even with every screen gone. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            SleepTimerBus.set(null)
            return
        }
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        SleepTimerBus.set(SleepTimerState(minutes, endsAt))
        sleepJob = serviceScope.launch {
            // Counts real elapsed time (delay() alone stops while the CPU sleeps).
            while (true) {
                val remaining = endsAt - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                delay(minOf(remaining, 1_000L))
            }
            pausePlayback()
            SleepTimerBus.set(null)
        }
    }

    private fun isActuallyPlaying(): Boolean = engine.isPlaying() || queueManager.isLoading.value

    // --- Settings -> engine ---------------------------------------------------------

    private fun applySettings(state: SettingsState) {
        reconcileDirectVolume()
        try {
            engineSettingsExecutor.execute {
                try {
                    EngineSettings.apply(engine, state)
                } catch (e: Exception) {
                    Log.w(TAG, "Applying settings to the engine failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The service is shutting down.
        }
        // Some choices change how the *current* song has to leave the app: re-plan it right now
        // instead of making the user wait for the next one.
        val key = planSettingsKey(state)
        if (key != lastPlanSettingsKey) {
            lastPlanSettingsKey = key
            replanCurrentSong()
        }
        // Gapless switched on / off: open the next song now, or let go of the one that is waiting.
        if (lastGapless != state.gapless) {
            val first = lastGapless == null
            lastGapless = state.gapless
            if (!first) queueManager.refreshGapless()
        }
    }

    private fun planSettingsKey(s: SettingsState): String =
        "${s.outputFormat}/${s.usbBitPerfect}/${s.bitPerfect}/${s.resamplerQuality}"

    private fun routeKey(route: OutputRoute): String = "${route.kind}/${route.deviceId}"

    /** Loads the current song again from where it is, so it goes through a freshly made output plan. */
    private fun replanCurrentSong() {
        if (queueManager.nowPlaying.value == null || queueManager.isLoading.value) return
        queueManager.reloadCurrent(engine.getCurrentPositionMs(), startPaused = !engine.isPlaying())
    }

    private fun onRouteChanged(route: OutputRoute) {
        if (destroyed) return
        val key = routeKey(route)
        val previous = lastRouteKey
        lastRouteKey = key
        refreshAll()
        if (previous == null || previous == key) return

        // The volume we parked belonged to the device that just went away.
        directVolume.forget(deviceTypeOfKey(previous))
        if (!settings.state.value.directVolume) directVolume.fixStale(route.deviceType)
        reconcileDirectVolume()
        updateSystemVolumePark()

        // A DAC arrived or left: the rest of the song should go out through the new route.
        val usbInvolved = route.kind == RouteKind.USB_DAC || previous.startsWith(RouteKind.USB_DAC.name)
        if (usbInvolved) replanCurrentSong()
    }

    /** The AudioDeviceInfo type recorded for a route key — enough to tell kinds of device apart. */
    private fun deviceTypeOfKey(key: String): Int = when (key.substringBefore('/')) {
        RouteKind.USB_DAC.name -> android.media.AudioDeviceInfo.TYPE_USB_DEVICE
        RouteKind.WIRED.name -> android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        RouteKind.BLUETOOTH.name -> android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        RouteKind.HDMI.name -> android.media.AudioDeviceInfo.TYPE_HDMI
        else -> android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    // --- Where and how each song leaves the app -------------------------------------------

    /**
     * Runs on the loader thread right before a song is handed to the engine: looks at the output
     * route and the user's fidelity choices and tells the engine which device, sample rate and
     * sample format to use — including switching on Android 14's bit-perfect mixer for USB DACs.
     */
    private fun planOutputFor(track: Track) {
        val s = settings.state.value
        val route = routeMonitor.route.value
        val nativeRate = routeMonitor.nativeSampleRateHz
        val usb = route.kind == RouteKind.USB_DAC

        var forcedRate = 0
        var formatPref = s.outputFormat.nativeValue
        var bitPerfectMixer = false

        val bitPerfect = usbBitPerfect
        if (usb && s.usbBitPerfect && bitPerfect != null && track.sampleRateHz > 0) {
            val plan = try {
                bitPerfect.prepare(route.deviceId, track.sampleRateHz, track.bitDepth, channelCountOf(track))
            } catch (e: Exception) {
                Log.w(TAG, "Bit-perfect planning failed", e)
                null
            }
            if (plan != null) {
                // Android's volume disappears the moment the mixer goes bit-perfect: move it into
                // the engine first (on the main thread; wait for it) so nothing gets louder.
                bitPerfectActive = true
                runOnMainAndWait(800) { reconcileDirectVolume() }
                if (bitPerfect.commit(plan)) {
                    bitPerfectMixer = true
                    forcedRate = plan.sampleRateHz
                    formatPref = plan.formatPref
                } else {
                    bitPerfectActive = false
                    runOnMainAndWait(800) { reconcileDirectVolume() }
                }
            }
        }
        if (!bitPerfectMixer) {
            bitPerfect?.release()
            if (bitPerfectActive) {
                bitPerfectActive = false
                runOnMainAndWait(800) { reconcileDirectVolume() }
            }
            forcedRate = OutputPlanner.chooseRate(track.sampleRateHz, route.sampleRates, nativeRate, s.bitPerfect)
        }

        // Where Android mixes at a rate we know in advance (the phone's own speaker / headphone jack),
        // the engine can convert to it itself instead of leaving that to Android's resampler. Elsewhere
        // (USB, Bluetooth, HDMI) the rate is not known ahead; Android 14+ tells the engine afterwards.
        val mixerRate = if (route.kind == RouteKind.SPEAKER || route.kind == RouteKind.WIRED) nativeRate else 0
        engine.setPlatformMixerRate(mixerRate, route.deviceId)

        engine.setOutputPlan(
            deviceId = if (bitPerfectMixer) route.deviceId else 0,
            forcedRateHz = forcedRate,
            formatPref = formatPref,
            bitPerfectMixer = bitPerfectMixer,
            nativeRateHz = nativeRate,
        )
    }

    /** Reads the channel count from the file itself (the library does not store it). Loader thread only. */
    private var channelProber: TrackProber? = null

    private fun channelCountOf(track: Track): Int {
        return try {
            val prober = channelProber ?: TrackProber().also { channelProber = it }
            contentResolver.openFileDescriptor(track.contentUri.toUri(), "r")?.use { pfd ->
                prober.probeFd(pfd.fd)?.channelCount?.takeIf { it > 0 }
            } ?: 2
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the channel count of ${track.title}", e)
            2
        }
    }

    /**
     * May [next] be opened ahead of time to follow [current] without a gap? Only when it would be played as
     * the very same output stream: with the USB bit-perfect mixer engaged that means the same sample rate
     * *and* bit depth (the mixer's format was chosen for the first song), with Bit-perfect output on the
     * same sample rate, and with it off every song is converted to one rate anyway. The engine checks the
     * decoded rate and channel count again on its own.
     */
    private fun gaplessAllowed(current: Track, next: Track): Boolean {
        val s = settings.state.value
        if (!s.gapless) return false
        return when {
            bitPerfectActive ->
                current.sampleRateHz > 0 && current.sampleRateHz == next.sampleRateHz &&
                    current.bitDepth > 0 && current.bitDepth == next.bitDepth
            !s.bitPerfect -> true
            else -> current.sampleRateHz == next.sampleRateHz
        }
    }

    private fun runOnMainAndWait(timeoutMs: Long, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                if (!destroyed) block()
            } finally {
                done.countDown()
            }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // --- Direct volume control ---------------------------------------------------------

    /**
     * Brings the volume machinery in line with the settings and the output:
     *
     *  * DVC is wanted when the user switched it on *or* the USB bit-perfect mixer is engaged (that
     *    mixer ignores Android's volume, so something has to control the level).
     *  * When it becomes wanted and Android's volume is about to stop mattering (DVC parks it at
     *    maximum, or the bit-perfect mixer bypasses it), the system attenuation is folded into the
     *    engine level once — so switching DVC on never changes how loud the music is.
     *  * The engine gain is `level²`; the volume keys are routed to the level; the system volume
     *    is parked at maximum only while music plays ([updateSystemVolumePark]).
     *  * When it stops being wanted, the system volume gets the step of equal loudness back first
     *    and the engine returns to unity a moment later.
     */
    private fun reconcileDirectVolume() {
        if (destroyed) return
        val s = settings.state.value
        val type = routeMonitor.route.value.deviceType
        val lockWanted = s.directVolume && s.dvcLockSystemVolume
        val wanted = s.directVolume || bitPerfectActive
        val foldWanted = lockWanted || bitPerfectActive
        var level = s.directVolumeLevel

        if (wanted) {
            dvcEngaged = true
            dvcSequence++
            if (foldWanted && !systemFolded) {
                preFoldLevel = level
                foldedForLock = lockWanted
                level = directVolume.fold(level, type)
                systemFolded = true
                if (level != s.directVolumeLevel) settings.update { it.copy(directVolumeLevel = level) }
            } else if (!foldWanted && systemFolded) {
                level = unfoldSystemVolume(level, type, keepUserLevel = !foldedForLock)
                settings.update { it.copy(directVolumeLevel = level) }
            }
            dvcGain = VolumeMath.gainForLevel(level)
            applyMasterGain()
            directVolume.installRemote(level) { newLevel ->
                settings.update { it.copy(directVolumeLevel = newLevel) }
            }
            directVolume.updateRemote(level)
            updateSystemVolumePark()
        } else {
            val wasEngaged = dvcEngaged
            dvcEngaged = false
            directVolume.removeRemote()
            if (systemFolded) {
                level = unfoldSystemVolume(level, type, keepUserLevel = !foldedForLock)
                settings.update { it.copy(directVolumeLevel = level) }
            }
            val sequence = ++dvcSequence
            if (wasEngaged) {
                // The system volume was just put back at equal loudness; only then let the engine
                // go to unity, so there is no instant in which both are open.
                serviceScope.launch {
                    delay(DVC_HANDOVER_MS)
                    if (sequence == dvcSequence && !dvcEngaged) {
                        dvcGain = 1.0
                        applyMasterGain()
                    }
                }
            } else {
                dvcGain = 1.0
                applyMasterGain()
            }
        }
    }

    /** Hands Android's volume back at the loudness the engine level had; returns the level to store. */
    private fun unfoldSystemVolume(level: Float, deviceType: Int, keepUserLevel: Boolean): Float {
        directVolume.unpark(level, deviceType)
        systemFolded = false
        val restored = if (keepUserLevel) (preFoldLevel ?: 1f) else 1f
        preFoldLevel = null
        foldedForLock = false
        return restored
    }

    /** Android's music volume sits at maximum exactly while music plays (and DVC owns the volume). */
    private fun updateSystemVolumePark() {
        if (destroyed || !::directVolume.isInitialized) return
        val s = settings.state.value
        val type = routeMonitor.route.value.deviceType
        val shouldPark = s.directVolume && s.dvcLockSystemVolume && systemFolded && isActuallyPlaying()
        if (shouldPark && !directVolume.parked && !parkPending) {
            parkPending = true
            // The engine already compensates; let the first audio go out before the system volume opens up.
            serviceScope.launch {
                delay(PARK_DELAY_MS)
                parkPending = false
                if (!destroyed && isActuallyPlaying() && settings.state.value.directVolume && systemFolded) {
                    directVolume.park(settings.state.value.directVolumeLevel, type)
                }
            }
        } else if (!shouldPark && directVolume.parked) {
            directVolume.unpark(s.directVolumeLevel, type)
        }
    }

    private fun applyMasterGain() {
        engine.setGain(dvcGain * duckFactor)
    }

    // --- Periodic work ------------------------------------------------------------

    private suspend fun runTicker() {
        var ticks = 0
        var lastKey = ""
        while (true) {
            queueManager.checkForAutoAdvance()

            // Engine-driven transitions (a song ending on its own, the stream being stopped)
            // don't come with a flow emission, so notice them here.
            val key = "${engine.isPlaying()}|${queueManager.isLoading.value}|${queueManager.nowPlaying.value?.track?.contentUri}"
            if (key != lastKey) {
                lastKey = key
                refreshAll()
            }

            ticks++
            if (ticks % 2 == 0) publishSnapshot()
            if (ticks % 50 == 0) {
                refreshSessionState()  // re-sync the system's progress bar with reality
                updateWakeLock()
            }

            // Nothing to play and nothing pending (a request that failed, a timer that was
            // cancelled): don't linger as an empty background service.
            val idle = queueManager.nowPlaying.value == null && !queueManager.isLoading.value &&
                SleepTimerBus.state.value == null
            idleTicks = if (idle) idleTicks + 1 else 0
            if (idleTicks > IDLE_STOP_TICKS) {
                stopSelf()
                return
            }
            delay(TICK_MS)
        }
    }

    // --- State -> UI, media session, notification ----------------------------------

    private fun refreshAll() {
        if (destroyed) return
        updateWakeLock()
        updateSystemVolumePark()
        refreshSessionState()
        refreshNotification()
        publishSnapshot()
    }

    private fun currentDurationMs(playing: NowPlaying): Long =
        playing.info.durationMs.takeIf { it > 0L } ?: playing.track.durationMs

    private fun publishSnapshot() {
        if (destroyed) return
        val playing = queueManager.nowPlaying.value
        val loading = queueManager.isLoading.value
        val duration = playing?.let { currentDurationMs(it) } ?: 0L
        var position = if (playing != null && !loading) engine.getCurrentPositionMs() else 0L
        if (duration > 0L) position = position.coerceIn(0L, duration)
        PlaybackStateBus.publish(
            PlaybackSnapshot(
                playing = playing,
                isPlaying = engine.isPlaying() || loading,
                positionMs = position,
                durationMs = duration,
                outputInfo = outputInfoText(),
            )
        )
    }

    /** "USB DAC · AAudio · Shared · 96 kHz · 24-bit · Bit-perfect" */
    private fun outputInfoText(): String {
        val engineText = engine.getOutputDescription()
        if (engineText.isBlank()) return ""
        return "${routeMonitor.route.value.kind.label} · $engineText"
    }

    private fun onTrackChanged(playing: NowPlaying?) {
        if (playing != null) {
            session.isActive = true
            audioFocusManager.requestFocus()
            updateMetadata(playing)
            loadArtwork(playing)
        }
        refreshAll()
    }

    private fun artUriOf(playing: NowPlaying): String =
        playing.track.albumArtUri.ifBlank { playing.track.contentUri }

    private fun loadArtwork(playing: NowPlaying) {
        val uri = artUriOf(playing)
        if (uri == artUri) return
        artUri = uri
        artBitmap = null
        artJob?.cancel()
        artJob = serviceScope.launch {
            val bitmap = try {
                AlbumArtLoader.load(applicationContext, uri.toUri(), ART_SIZE_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val current = queueManager.nowPlaying.value
            if (bitmap != null && current != null && artUriOf(current) == uri) {
                artBitmap = bitmap
                updateMetadata(current)
                refreshNotification()
            }
        }
    }

    private fun updateMetadata(playing: NowPlaying) {
        val track = playing.track
        val title = track.title.ifBlank { "Unknown title" }
        val artist = track.artist.ifBlank { "Unknown artist" }
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.contentUri)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs(playing))
        artBitmap?.let {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
        }
        session.setMetadata(builder.build())
    }

    private fun createSession() {
        session = MediaSession(this, "Resonix").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onStop() = pausePlayback()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
            setSessionActivity(contentIntent())
        }
    }

    private fun refreshSessionState() {
        if (destroyed) return
        val playing = queueManager.nowPlaying.value
        val loading = queueManager.isLoading.value
        val stateCode = when {
            playing == null && !loading -> PlaybackState.STATE_NONE
            loading -> PlaybackState.STATE_BUFFERING
            engine.isPlaying() -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val position = if (playing != null && !loading) engine.getCurrentPositionMs() else 0L
        val speed = if (stateCode == PlaybackState.STATE_PLAYING) 1f else 0f
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, position, speed, SystemClock.elapsedRealtime())
                .build()
        )
    }

    // --- Notification ------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun action(iconRes: Int, title: String, intentAction: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, iconRes), title, servicePendingIntent(intentAction, requestCode)
        ).build()

    private fun buildNotification(playing: NowPlaying, active: Boolean): Notification {
        val track = playing.track
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_resonix)
            .setContentTitle(track.title.ifBlank { "Unknown title" })
            .setContentText(track.artist.ifBlank { "Unknown artist" })
            .setContentIntent(contentIntent())
            .setDeleteIntent(servicePendingIntent(ACTION_DISMISS, 4))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(active)
            .addAction(action(R.drawable.ic_notif_previous, "Previous", ACTION_PREVIOUS, 1))
            .addAction(
                if (active) action(R.drawable.ic_notif_pause, "Pause", ACTION_TOGGLE, 2)
                else action(R.drawable.ic_notif_play, "Play", ACTION_TOGGLE, 2)
            )
            .addAction(action(R.drawable.ic_notif_next, "Next", ACTION_NEXT, 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        artBitmap?.let { builder.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /**
     * While music plays the service is a foreground service (so the system keeps it alive with the
     * screen off); while paused the notification stays, but can be swiped away, which ends the service.
     */
    private fun refreshNotification() {
        if (destroyed) return
        val playing = queueManager.nowPlaying.value ?: return
        val active = isActuallyPlaying()
        val notification = buildNotification(playing, active)
        if (active) {
            startForegroundSafely(notification)
        } else {
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                isForeground = false
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            // E.g. Android refusing a foreground start while the app is in the background.
            // Playback itself is unaffected — still show the notification.
            Log.w(TAG, "Could not enter the foreground", e)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    // --- CPU wake lock -----------------------------------------------------------

    /** Decoding runs on our own thread; keep the CPU awake while music plays with the screen off. */
    private fun updateWakeLock() {
        if (isActuallyPlaying()) {
            val lock = wakeLock ?: (getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Resonix:playback")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it })
            if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_TOGGLE = "com.resonix.player.action.TOGGLE"
        private const val ACTION_NEXT = "com.resonix.player.action.NEXT"
        private const val ACTION_PREVIOUS = "com.resonix.player.action.PREVIOUS"
        private const val ACTION_DISMISS = "com.resonix.player.action.DISMISS"

        private const val TICK_MS = 100L
        private const val IDLE_STOP_TICKS = 150  // 15 s
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val DUCK_GAIN = 0.2
        private const val PARK_DELAY_MS = 40L
        private const val DVC_HANDOVER_MS = 60L
        private const val ART_SIZE_PX = 512
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }
}
