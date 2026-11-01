package com.resonix.player.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Android 14's bit-perfect mixer for USB DACs. Once a stream is opened with exactly the sample rate,
 * channel count and encoding the DAC offers, the system hands those samples to the device
 * untouched: no resampling, no system volume, no effects. (That also means the volume buttons stop
 * working — which is why bit-perfect and Direct volume control go together, see PlaybackService.)
 *
 * Usage per song: [prepare] decides the format, the caller makes ready for the system volume to
 * disappear, then [commit] switches the mixer on. [release] gives the DAC back to the normal mixer.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class UsbBitPerfect(
    private val monitor: AudioRouteMonitor,
    private val audioManager: AudioManager,
) {
    class Plan(
        val deviceId: Int,
        val sampleRateHz: Int,
        /** AudioEngine::FormatPref for the stream that has to be opened. */
        val formatPref: Int,
        internal val attributes: AudioMixerAttributes,
        internal val key: String,
    )

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var engagedKey: String? = null
    private var engagedDeviceId = -1

    /** The best bit-perfect format the DAC offers for a file, or null if it offers none that fits. */
    fun prepare(deviceId: Int, fileRateHz: Int, fileBits: Int, channels: Int): Plan? {
        val device = monitor.findDevice(deviceId) ?: return null
        val supported = try {
            audioManager.getSupportedMixerAttributes(device)
        } catch (e: Exception) {
            return null
        }
        val options = ArrayList<OutputPlanner.MixerOption>()
        val attributes = ArrayList<AudioMixerAttributes>()
        for (candidate in supported) {
            if (candidate.mixerBehavior != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) continue
            val encoding = encodingOf(candidate.format.encoding) ?: continue
            options.add(OutputPlanner.MixerOption(candidate.format.sampleRate, candidate.format.channelCount, encoding))
            attributes.add(candidate)
        }
        val index = OutputPlanner.chooseMixerOption(options, fileRateHz, fileBits, channels) ?: return null
        val chosen = attributes[index]
        val format = chosen.format
        return Plan(
            deviceId = deviceId,
            sampleRateHz = format.sampleRate,
            formatPref = options[index].encoding.nativeFormatPref,
            attributes = chosen,
            key = "$deviceId/${format.sampleRate}/${format.encoding}/${format.channelMask}",
        )
    }

    /** Switches the bit-perfect mixer on for [plan]'s format. False if the system refuses. */
    fun commit(plan: Plan): Boolean {
        if (plan.key == engagedKey) return true
        val device = monitor.findDevice(plan.deviceId) ?: return false
        val ok = try {
            audioManager.setPreferredMixerAttributes(audioAttributes, device, plan.attributes)
        } catch (e: Exception) {
            false
        }
        if (ok) {
            engagedKey = plan.key
            engagedDeviceId = plan.deviceId
        }
        return ok
    }

    /** Hands the DAC back to Android's regular mixer. */
    fun release() {
        if (engagedKey == null) return
        monitor.findDevice(engagedDeviceId)?.let {
            try {
                audioManager.clearPreferredMixerAttributes(audioAttributes, it)
            } catch (_: Exception) {
            }
        }
        engagedKey = null
        engagedDeviceId = -1
    }

    private fun encodingOf(encoding: Int): OutputPlanner.Encoding? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> OutputPlanner.Encoding.PCM16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputPlanner.Encoding.PCM24
        AudioFormat.ENCODING_PCM_32BIT -> OutputPlanner.Encoding.PCM32
        AudioFormat.ENCODING_PCM_FLOAT -> OutputPlanner.Encoding.FLOAT
        else -> null
    }
}
