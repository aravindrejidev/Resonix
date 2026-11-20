package com.resonix.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.resonix.player.audio.NativeAudioEngine

private const val TAG = "UsbDirectManager"
private const val ACTION_USB_PERMISSION = "com.resonix.player.USB_PERMISSION"

/**
 * Finds a USB DAC, gets the user's permission for it and hands it to the engine, which then drives it
 * over USB itself (Direct USB output). This class only deals with Android: the device list, the
 * permission dialog, the connection and what to do when the DAC is plugged or unplugged. All of it runs on
 * the main thread.
 *
 * [onChanged] is called when a DAC became usable or stopped being usable, so the service can move the
 * song that is playing to the right output.
 */
class UsbDirectManager(
    private val context: Context,
    private val engine: NativeAudioEngine,
    private val onChanged: () -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var enabled = false
    private var receiverRegistered = false
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var permissionAskedFor: String? = null

    /** A DAC is attached to the engine and can be played to directly. */
    @Volatile
    var ready: Boolean = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Whatever happened — permission answered, a device plugged in or pulled out — look again.
            refresh()
        }
    }

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(ACTION_USB_PERMISSION)
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            refresh()
        } else {
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                }
                receiverRegistered = false
            }
            permissionAskedFor = null
            detach(announce = false)
            publish(UsbDirectStatus.State.OFF, "")
        }
    }

    /** Looks for a DAC again: after a plug, an unplug, a permission answer, or a change of route. */
    fun refresh() {
        if (!enabled) return
        val current = device
        if (current != null && usbManager.deviceList.values.none { it.deviceName == current.deviceName }) {
            detach(announce = true)  // it was unplugged
        }
        if (ready) return

        val candidate = usbManager.deviceList.values.firstOrNull { isAudioPlaybackDevice(it) }
        if (candidate == null) {
            publish(UsbDirectStatus.State.NO_DEVICE, "No USB DAC connected")
            return
        }
        val name = labelOf(candidate)
        if (!usbManager.hasPermission(candidate)) {
            publish(UsbDirectStatus.State.NEEDS_PERMISSION, "Waiting for your permission to use $name")
            if (permissionAskedFor != candidate.deviceName) {
                permissionAskedFor = candidate.deviceName
                try {
                    usbManager.requestPermission(candidate, permissionIntent())
                } catch (e: Exception) {
                    Log.w(TAG, "Asking for USB permission failed", e)
                }
            }
            return
        }
        attach(candidate, name)
    }

    /** The engine reported that the DAC stopped answering while it was playing. */
    fun onLost() {
        if (!enabled) return
        detach(announce = true)
        permissionAskedFor = null
        publish(UsbDirectStatus.State.ERROR, "The USB DAC stopped responding")
    }

    fun release() = setEnabled(false)

    /**
     * A plain-text report for finding out why a DAC does not work: the phone, what Android sees on the USB bus,
     * and what the engine's USB driver did (descriptors, the steps it took, its last log lines). On a phone there
     * is no adb to read logcat with, so this is what gets pasted into a message.
     */
    fun report(): String {
        val text = StringBuilder()
        text.append("Resonix USB report\n")
        text.append("phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            text.append("app: ${info.versionName}\n")
        } catch (_: Exception) {
        }
        val status = PlaybackStateBus.usbDirect.value
        text.append("Direct USB: ${if (enabled) "on" else "off"}, ${status.state} — ${status.message}\n")
        text.append("microphone permission: ")
        text.append(
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) "granted\n" else "not granted\n"
        )
        text.append("\nUSB devices Android sees:\n")
        val devices = try {
            usbManager.deviceList.values.toList()
        } catch (e: Exception) {
            emptyList()
        }
        if (devices.isEmpty()) text.append("  (none)\n")
        for (d in devices) {
            text.append(
                "  ${labelOf(d)}  vendor 0x%04X product 0x%04X  permission: %s  playback stream: %s\n".format(
                    d.vendorId, d.productId, usbManager.hasPermission(d), isAudioPlaybackDevice(d)
                )
            )
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    text.append(
                        "    interface ${itf.id} alt ${itf.alternateSetting}: audio subclass ${itf.interfaceSubclass}, ${itf.endpointCount} endpoint(s)\n"
                    )
                }
            }
        }
        text.append("\n--- engine ---\n")
        text.append(engine.usbReport())
        return text.toString()
    }

    private fun attach(candidate: UsbDevice, name: String) {
        val opened = try {
            usbManager.openDevice(candidate)
        } catch (e: Exception) {
            Log.w(TAG, "Opening the USB device failed", e)
            null
        }
        if (opened == null) {
            publish(UsbDirectStatus.State.ERROR, "Could not open $name")
            return
        }
        val raw = opened.rawDescriptors
        val summary = if (raw != null) engine.usbAttach(opened.fileDescriptor, raw, name) else ""
        if (summary.isEmpty()) {
            opened.close()
            publish(UsbDirectStatus.State.UNSUPPORTED, "$name has no PCM playback stream Resonix can drive directly")
            return
        }
        connection = opened
        device = candidate
        ready = true
        publish(UsbDirectStatus.State.READY, "$name · $summary")
        onChanged()
    }

    private fun detach(announce: Boolean) {
        val wasReady = ready
        ready = false
        engine.usbDetach()  // hands the DAC back to Android's own driver
        connection?.close()
        connection = null
        device = null
        if (wasReady && announce) {
            publish(UsbDirectStatus.State.NO_DEVICE, "No USB DAC connected")
            onChanged()
        }
    }

    private fun publish(state: UsbDirectStatus.State, message: String) {
        PlaybackStateBus.publishUsbDirect(UsbDirectStatus(state, message))
    }

    private fun labelOf(d: UsbDevice): String =
        d.productName?.takeIf { it.isNotBlank() } ?: d.deviceName

    /** An audio-streaming interface with an isochronous OUT endpoint: something that plays sound. */
    private fun isAudioPlaybackDevice(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_AUDIO || itf.interfaceSubclass != AUDIO_STREAMING) continue
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT) return true
            }
        }
        return false
    }

    private fun permissionIntent(): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // The system adds the answer to the intent, so it has to be mutable (from Android 12 on).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private companion object {
        const val AUDIO_STREAMING = 2
    }
}
