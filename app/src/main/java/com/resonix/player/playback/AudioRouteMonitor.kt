package com.resonix.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What kind of output the music is going to. Decides which fidelity measures apply. */
enum class RouteKind(val label: String) {
    USB_DAC("USB DAC"),
    WIRED("AUX"),
    BLUETOOTH("Bluetooth"),
    HDMI("HDMI"),
    SPEAKER("Speaker"),
    OTHER("Output"),
}

/** The output device music is routed to right now, and what it says it can do. */
data class OutputRoute(
    val kind: RouteKind = RouteKind.SPEAKER,
    /** AudioDeviceInfo id (0 = unknown). */
    val deviceId: Int = 0,
    /** AudioDeviceInfo.TYPE_*, used for Android's volume curves. */
    val deviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    val name: String = "",
    /** Rates the device accepts; empty = it did not say (treated as "anything"). */
    val sampleRates: List<Int> = emptyList(),
    /** Android 14+: how many bit-perfect formats the device offers (0 = none / not supported). */
    val bitPerfectFormats: Int = 0,
) {
    val maxSampleRateHz: Int get() = sampleRates.maxOrNull() ?: 0
}

/**
 * Watches Android's audio routing. One instance for the whole process: the service reads it to plan
 * each song, the Settings screen to show what is connected.
 */
class AudioRouteMonitor private constructor(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val _route = MutableStateFlow(OutputRoute())
    val route: StateFlow<OutputRoute> = _route.asStateFlow()

    /** The device's own mixer rate (what Android resamples everything to unless told otherwise). */
    val nativeSampleRateHz: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it > 0 } ?: 48_000

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        _route.value = compute()
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        val next = compute()
        if (next != _route.value) _route.value = next
    }

    fun findDevice(id: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }

    private fun compute(): OutputRoute {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        var chosen: AudioDeviceInfo? = null
        if (Build.VERSION.SDK_INT >= 33) {
            // Exactly where Android would send music right now.
            chosen = try {
                audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()
            } catch (e: Exception) {
                null
            }
        }
        if (chosen == null) {
            chosen = outputs
                .filter { classify(it.type) != RouteKind.OTHER }
                .minByOrNull { priority(classify(it.type)) }
                ?: outputs.firstOrNull()
        }
        if (chosen == null) return OutputRoute(kind = RouteKind.SPEAKER)

        val kind = classify(chosen.type)
        var bitPerfectFormats = 0
        if (kind == RouteKind.USB_DAC && Build.VERSION.SDK_INT >= 34) {
            bitPerfectFormats = try {
                audioManager.getSupportedMixerAttributes(chosen)
                    .count { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            } catch (e: Exception) {
                0
            }
        }
        return OutputRoute(
            kind = kind,
            deviceId = chosen.id,
            deviceType = chosen.type,
            name = chosen.productName?.toString().orEmpty(),
            sampleRates = chosen.sampleRates.filter { it > 0 }.sorted(),
            bitPerfectFormats = bitPerfectFormats,
        )
    }

    companion object {
        private val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        @Volatile
        private var instance: AudioRouteMonitor? = null

        fun get(context: Context): AudioRouteMonitor =
            instance ?: synchronized(this) {
                instance ?: AudioRouteMonitor(context).also { instance = it }
            }

        /** AudioDeviceInfo.TYPE_* → what it means for sound quality. */
        fun classify(type: Int): RouteKind = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> RouteKind.USB_DAC

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> RouteKind.WIRED

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            26, /* TYPE_BLE_HEADSET */
            27, /* TYPE_BLE_SPEAKER */
            30 /* TYPE_BLE_BROADCAST */ -> RouteKind.BLUETOOTH

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            29 /* TYPE_HDMI_EARC */ -> RouteKind.HDMI

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            24 /* TYPE_BUILTIN_SPEAKER_SAFE */ -> RouteKind.SPEAKER

            else -> RouteKind.OTHER
        }

        private fun priority(kind: RouteKind): Int = when (kind) {
            RouteKind.USB_DAC -> 0
            RouteKind.WIRED -> 1
            RouteKind.BLUETOOTH -> 2
            RouteKind.HDMI -> 3
            RouteKind.SPEAKER -> 4
            RouteKind.OTHER -> 5
        }
    }
}
