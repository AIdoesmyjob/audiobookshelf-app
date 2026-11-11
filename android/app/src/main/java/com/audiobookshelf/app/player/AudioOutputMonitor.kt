package com.audiobookshelf.app.player

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Detects the listener's audio output going away (leaving the car, unplugging/disconnecting
 * headphones, Android Auto ending) so playback can be paused instead of carrying on through
 * the phone speaker.
 *
 * ExoPlayer's setHandleAudioBecomingNoisy covers the common case, but it is not enough:
 *  - ACTION_AUDIO_BECOMING_NOISY is not delivered for every car disconnect, so device removal
 *    and leaving car mode (Android Auto) are watched as well.
 *  - A media button sent by the head unit as it shuts down can resume playback right after the
 *    pause. [PlayerNotificationService.isMediaButtonResumeBlocked] uses the time recorded here
 *    to reject those.
 */
class AudioOutputMonitor(
        private val context: Context,
        private val onAudioOutputLost: (reason: String) -> Unit,
        private val onPrivateOutputConnected: () -> Unit
) {
  private val tag = "AudioOutputMonitor"

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private var isRegistered = false

  private val broadcastReceiver =
          object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
              when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> onAudioOutputLost("audio becoming noisy")
                UiModeManager.ACTION_EXIT_CAR_MODE -> onAudioOutputLost("exited car mode")
              }
            }
          }

  private val audioDeviceCallback =
          object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
              // Also called once with the current devices on registration, which is harmless
              if (addedDevices.any { it.isSink && isPrivateOutput(it.type) }) {
                onPrivateOutputConnected()
              }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
              val removedOutputs = removedDevices.filter { it.isSink && isPrivateOutput(it.type) }
              if (removedOutputs.isEmpty()) return

              // If another headset/car output is still connected Android simply re-routes to it
              // (no "noisy" event either), so keep playing there.
              val removedIds = removedOutputs.map { it.id }.toSet()
              val remainingPrivateOutput =
                      audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                        it.id !in removedIds && isPrivateOutput(it.type)
                      }
              if (remainingPrivateOutput) {
                Log.d(tag, "Output removed but another private output remains connected")
                return
              }

              onAudioOutputLost(
                      "output device removed (${removedOutputs.joinToString { "${it.productName} type=${it.type}" }})"
              )
            }
          }

  fun register() {
    if (isRegistered) return
    val filter =
            IntentFilter().apply {
              addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
              addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            }
    // Both actions are system broadcasts, which are delivered to non-exported receivers
    ContextCompat.registerReceiver(
            context,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
    )
    audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
    isRegistered = true
  }

  fun unregister() {
    if (!isRegistered) return
    try {
      context.unregisterReceiver(broadcastReceiver)
    } catch (e: IllegalArgumentException) {
      Log.w(tag, "Receiver was not registered", e)
    }
    audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    isRegistered = false
  }

  companion object {
    /**
     * Outputs that only the listener hears. Losing the last one of these means audio would now
     * come out of the phone speaker. Bluetooth SCO (calls) and HDMI are intentionally excluded.
     */
    fun isPrivateOutput(type: Int): Boolean {
      return when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_DOCK,
        TYPE_USB_HEADSET,
        TYPE_HEARING_AID,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
        TYPE_BLE_BROADCAST -> true
        else -> false
      }
    }

    // AudioDeviceInfo constants newer than minSdk 24 (plain ints, safe on any API level)
    private const val TYPE_USB_HEADSET = 22 // API 26
    private const val TYPE_HEARING_AID = 23 // API 28
    private const val TYPE_BLE_HEADSET = 26 // API 31
    private const val TYPE_BLE_SPEAKER = 27 // API 31
    private const val TYPE_BLE_BROADCAST = 30 // API 33
  }
}
