package com.blackcloudgroup.binaural

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.math.sin

enum class SoundMode { BINAURAL, ISOCHRONIC, HEMI_SYNC }

class BinauralAudioService : Service(), AudioManager.OnAudioFocusChangeListener {

    private val binder = LocalBinder()

    @Volatile
    private var isPlaying = false

    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null

    var carrierFreq = 200.0
    var beatFreq = 6.0
    var soundMode = SoundMode.HEMI_SYNC

    private var isDucked = false

    var targetVolume = 0.2f
        set(value) {
            field = value
            masterVolume = if (isDucked) value * 0.5f else value
        }

    var masterVolume = 0.2f
        private set

    inner class LocalBinder : Binder() {
        fun getService(): BinauralAudioService = this@BinauralAudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        mediaSession = MediaSessionCompat(this, "BinauralAudioService").apply {
            isActive = true
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BlackCloudBinaural::AudioSynthesisWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(120 * 60 * 1000L) // 2-hour safety timeout limit
        } catch (e: SecurityException) {
            Log.e("BinauralAudioService", "Failed to acquire PARTIAL_WAKE_LOCK: Permission denied", e)
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Unexpected error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Error releasing WakeLock", e)
        }
    }

    private fun requestAudioFocus(): Boolean {
        return try {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            val res = audioManager.requestAudioFocus(focusRequest!!)
            res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Audio focus request failed explicitly", e)
            false
        }
    }

    private fun abandonAudioFocus() {
        try {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Error abandoning audio focus", e)
        } finally {
            focusRequest = null
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stopAudio()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                masterVolume = targetVolume * 0.5f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                isDucked = false
                masterVolume = targetVolume
            }
        }
    }

    fun startAudio() {
        if (isPlaying || !requestAudioFocus()) return
        acquireWakeLock()
        isPlaying = true

        val sampleRate = 44100
        val bufferSizeBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSizeBytes <= 0) {
            Log.e("BinauralAudioService", "Invalid AudioTrack min buffer size: $bufferSizeBytes")
            abandonAudioFocus()
            releaseWakeLock()
            isPlaying = false
            return
        }

        val bufferSizeShorts = bufferSizeBytes / 2

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Failed to initialize AudioTrack engine", e)
            abandonAudioFocus()
            releaseWakeLock()
            isPlaying = false
            return
        }

        audioThread = thread {
            var sampleIdx = 0L
            val buffer = ShortArray(bufferSizeShorts)
            val framesPerBuffer = bufferSizeShorts / 2

            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
            var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0

            while (isPlaying) {
                val leftFreq1 = carrierFreq - (beatFreq / 2.0)
                val rightFreq1 = carrierFreq + (beatFreq / 2.0)
                val harmonicCarrier = carrierFreq * 1.5
                val leftFreq2 = harmonicCarrier - (beatFreq / 2.0)
                val rightFreq2 = harmonicCarrier + (beatFreq / 2.0)

                val isIsochronic = soundMode == SoundMode.ISOCHRONIC
                val isHemiSync = soundMode == SoundMode.HEMI_SYNC
                val isochronicPeriod = sampleRate / beatFreq

                for (i in 0 until framesPerBuffer) {
                    var sampleL = sin(2.0 * Math.PI * sampleIdx * leftFreq1 / sampleRate)
                    var sampleR = sin(2.0 * Math.PI * sampleIdx * rightFreq1 / sampleRate)

                    if (isHemiSync) {
                        val sampleL2 = sin(2.0 * Math.PI * sampleIdx * leftFreq2 / sampleRate) * 0.5
                        val sampleR2 = sin(2.0 * Math.PI * sampleIdx * rightFreq2 / sampleRate) * 0.5

                        sampleL = (sampleL + sampleL2) / 1.5
                        sampleR = (sampleR + sampleR2) / 1.5

                        val white = Math.random() * 2.0 - 1.0
                        b0 = 0.99886 * b0 + white * 0.0555179
                        b1 = 0.99332 * b1 + white * 0.0750759
                        b2 = 0.96900 * b2 + white * 0.1538520
                        b3 = 0.86650 * b3 + white * 0.3104856
                        b4 = 0.55000 * b4 + white * 0.5329522
                        b5 = -0.7616 * b5 - white * 0.0168980
                        val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.02
                        b6 = white * 0.115926

                        sampleL += pink
                        sampleR += pink
                    } else if (isIsochronic) {
                        val phase = (sampleIdx % isochronicPeriod) / isochronicPeriod
                        val gain = if (phase < 0.5) 1.0 else 0.0
                        sampleL *= gain
                        sampleR *= gain
                    }

                    buffer[i * 2] = (sampleL * Short.MAX_VALUE * masterVolume).toInt().toShort()
                    buffer[i * 2 + 1] = (sampleR * Short.MAX_VALUE * masterVolume).toInt().toShort()
                    sampleIdx++
                }

                val track = audioTrack ?: break
                track.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stopAudio() {
        isPlaying = false

        try {
            audioThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e("BinauralAudioService", "Interrupted while joining audio thread", e)
            Thread.currentThread().interrupt()
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Error shutting down AudioTrack", e)
        } finally {
            audioTrack = null
            audioThread = null
            releaseWakeLock()
            abandonAudioFocus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopAudio()
        mediaSession.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "binaural_channel",
            "Binaural Beats",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "binaural_channel")
            .setContentTitle("Black Cloud Group | Binaural Session")
            .setContentText("Hemi-Sync & brainwave entrainment engine active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
