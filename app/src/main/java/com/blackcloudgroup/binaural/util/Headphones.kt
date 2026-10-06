package com.blackcloudgroup.binaural.util

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Output types that deliver a separate signal to each ear. Bluetooth A2DP can't be told apart from a
 * Bluetooth speaker, so it counts as headphones: a missed warning is better than a wrong one.
 */
private val HEADPHONE_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_BROADCAST
)

fun AudioManager.hasHeadphoneOutput(): Boolean = try {
    getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
} catch (e: Exception) {
    // If we can't tell, assume headphones rather than nagging the user.
    Log.w("Headphones", "Could not list audio outputs", e)
    true
}

/** Live headphone state; updates as devices connect and disconnect. */
@Composable
fun rememberHeadphonesConnected(): Boolean {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var connected by remember { mutableStateOf(audioManager.hasHeadphoneOutput()) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                connected = audioManager.hasHeadphoneOutput()
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                connected = audioManager.hasHeadphoneOutput()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    return connected
}
