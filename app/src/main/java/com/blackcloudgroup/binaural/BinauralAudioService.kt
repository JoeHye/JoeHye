package com.blackcloudgroup.binaural

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.blackcloudgroup.binaural.audio.ToneGenerator
import com.blackcloudgroup.binaural.audio.ToneParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StopReason { USER, COMPLETED, FOCUS_LOSS, ERROR }

sealed interface PlaybackState {
    object Idle : PlaybackState
    data class Playing(val params: ToneParams) : PlaybackState
    data class Stopped(val reason: StopReason, val message: String? = null) : PlaybackState
}

sealed interface SessionStartResult {
    object Started : SessionStartResult
    object AlreadyPlaying : SessionStartResult
    object FocusDenied : SessionStartResult
    data class Failed(val message: String, val cause: Throwable? = null) : SessionStartResult
}

/**
 * Threading model:
 * - All public methods and all session bookkeeping run on the main thread.
 * - Each session gets its own audio thread which exclusively owns its [AudioTrack] after hand-off:
 *   it is the only code that calls play/write/stop/release, so there is no stop()/write() race.
 * - The audio thread reports its end back to the main thread via [mainHandler].
 */
class BinauralAudioService : Service(), AudioManager.OnAudioFocusChangeListener {

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private class Session(val id: Long, val generator: ToneGenerator) {
        /** Cleared to abandon the session immediately (no fade), e.g. on service destroy. */
        @Volatile var running = true
        /** First stop reason wins; read by the audio thread when it reports back. */
        @Volatile var stopReason: StopReason? = null
        @Volatile var stopMessage: String? = null
    }

    private var session: Session? = null
    private var nextSessionId = 0L

    private var volume = DEFAULT_VOLUME
    private var isDucked = false

    inner class LocalBinder : Binder() {
        fun getService(): BinauralAudioService = this@BinauralAudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "BinauralAudioService").apply { isActive = true }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> {
                Log.i(TAG, "Stop requested from notification")
                stopSession(StopReason.USER)
            }
            session == null -> {
                // Started without an active session (e.g. a stale start after the session already ended).
                Log.w(TAG, "onStartCommand with no active session; stopping self (startId=$startId)")
                stopSelf(startId)
            }
        }
        // Never restart after process death: there is no session state to restore, and a restarted
        // foreground service would show an "active" notification with no audio.
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------------------
    // Public API (main thread only)
    // ---------------------------------------------------------------------------------------------

    fun startSession(params: ToneParams): SessionStartResult {
        if (!isMainThread()) {
            Log.e(TAG, "startSession called off the main thread")
            return SessionStartResult.Failed("Internal error: startSession must be called on the main thread")
        }
        if (session != null) {
            Log.w(TAG, "startSession ignored: a session is already running")
            return SessionStartResult.AlreadyPlaying
        }
        if (!requestAudioFocus()) {
            return SessionStartResult.FocusDenied
        }

        // Start ourselves so playback outlives the activity's binding, and promote to foreground
        // synchronously so there is no startForegroundService() deadline to miss.
        try {
            startService(Intent(this, BinauralAudioService::class.java))
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        } catch (e: Exception) {
            // IllegalStateException: background start not allowed; ForegroundServiceStartNotAllowedException
            // (API 31+, subclass of IllegalStateException); SecurityException: missing FGS permission.
            Log.e(TAG, "Failed to enter foreground state", e)
            releasePlaybackResources()
            return SessionStartResult.Failed("Could not start background playback: ${e.message ?: e.javaClass.simpleName}", e)
        }

        val trackAndFrames = try {
            buildAudioTrack()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            releasePlaybackResources()
            return SessionStartResult.Failed("Audio output unavailable: ${e.message ?: e.javaClass.simpleName}", e)
        }

        acquireWakeLock(params)
        val generator = ToneGenerator(params, SAMPLE_RATE).apply { setVolume(effectiveVolume()) }
        val newSession = Session(++nextSessionId, generator)
        session = newSession

        val (track, framesPerChunk) = trackAndFrames
        Thread({ runAudioLoop(newSession, track, framesPerChunk) }, "BinauralSynth-${newSession.id}").start()

        _playbackState.value = PlaybackState.Playing(params)
        Log.i(TAG, "Session ${newSession.id} started: $params")
        return SessionStartResult.Started
    }

    /** Fades out and ends the current session. No-op when nothing is playing. */
    fun stopSession(reason: StopReason = StopReason.USER, message: String? = null) {
        if (!isMainThread()) {
            mainHandler.post { stopSession(reason, message) }
            return
        }
        val s = session ?: return
        if (s.stopReason == null) {
            s.stopReason = reason
            s.stopMessage = message
        }
        s.generator.requestStop(STOP_FADE_MS)

        // Watchdog: if the audio thread doesn't report back (e.g. write() wedged on a dead route),
        // release focus/wake lock/foreground on our side anyway. The thread still owns the track
        // and releases it whenever write() returns.
        mainHandler.postDelayed({
            if (session === s) {
                Log.e(TAG, "Session ${s.id}: audio thread did not finish within ${STOP_WATCHDOG_MS}ms; forcing cleanup")
                s.running = false
                onSessionEnded(s, s.stopReason ?: reason, "Audio thread did not stop cleanly")
            }
        }, STOP_WATCHDOG_MS)
    }

    /**
     * Update the running session. [restartTimeline] restarts the ramp/duration (new preset picked);
     * otherwise elapsed time is kept (slider moved).
     */
    fun updateParams(params: ToneParams, restartTimeline: Boolean = false) {
        val s = session ?: return
        s.generator.loadParams(params, restartTimeline)
        _playbackState.value = PlaybackState.Playing(params)
    }

    /** Output volume 0..1. Throws IllegalArgumentException outside that range. */
    fun setVolume(newVolume: Float) {
        require(newVolume in 0f..1f) { "volume must be within 0..1, was $newVolume" }
        volume = newVolume
        session?.generator?.setVolume(effectiveVolume())
    }

    // ---------------------------------------------------------------------------------------------
    // Audio thread
    // ---------------------------------------------------------------------------------------------

    private fun runAudioLoop(s: Session, track: AudioTrack, framesPerChunk: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var errorMessage: String? = null
        try {
            track.play()
            val buffer = ShortArray(framesPerChunk * 2)
            while (s.running) {
                val frames = s.generator.render(buffer, framesPerChunk)
                if (frames == 0) break // duration reached or stop fade finished
                if (!writeFully(s, track, buffer, frames * 2)) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session ${s.id}: audio thread failed", e)
            errorMessage = e.message ?: e.javaClass.simpleName
        } finally {
            try {
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Session ${s.id}: AudioTrack.stop() failed", e)
            }
            track.release()
            if (s.generator.clippedSamples > 0) {
                Log.w(TAG, "Session ${s.id}: ${s.generator.clippedSamples} samples clamped at full scale")
            }
        }

        val reason = when {
            errorMessage != null -> StopReason.ERROR
            else -> s.stopReason ?: StopReason.COMPLETED
        }
        val message = errorMessage ?: s.stopMessage
        mainHandler.post { onSessionEnded(s, reason, message) }
    }

    /** @return false when the session was abandoned mid-write. Throws on AudioTrack errors. */
    private fun writeFully(s: Session, track: AudioTrack, buffer: ShortArray, length: Int): Boolean {
        var offset = 0
        var zeroWrites = 0
        while (offset < length) {
            if (!s.running) return false
            val written = track.write(buffer, offset, length - offset)
            when {
                written < 0 -> throw IllegalStateException("AudioTrack.write failed: ${writeErrorName(written)} ($written)")
                written == 0 -> {
                    // Blocking writes only return 0 when the track isn't playing; don't spin forever.
                    if (++zeroWrites > MAX_ZERO_WRITES) {
                        throw IllegalStateException("AudioTrack accepted no data ${MAX_ZERO_WRITES}x in a row (playState=${track.playState})")
                    }
                }
                else -> {
                    zeroWrites = 0
                    offset += written
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Session teardown (main thread)
    // ---------------------------------------------------------------------------------------------

    private fun onSessionEnded(s: Session, reason: StopReason, message: String?) {
        if (session !== s) {
            Log.d(TAG, "Ignoring end report from stale session ${s.id}")
            return
        }
        session = null
        releasePlaybackResources()
        _playbackState.value = PlaybackState.Stopped(reason, message)
        Log.i(TAG, "Session ${s.id} ended: $reason${message?.let { " ($it)" } ?: ""}")
    }

    private fun releasePlaybackResources() {
        releaseWakeLock()
        abandonAudioFocus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf() // Stays alive while the activity is bound; destroyed once it unbinds.
    }

    override fun onDestroy() {
        session?.let { s ->
            Log.w(TAG, "Service destroyed with session ${s.id} still active; abandoning without fade")
            s.stopReason = s.stopReason ?: StopReason.USER
            s.running = false // audio thread exits after its current write and releases the track
            session = null
            releaseWakeLock()
            abandonAudioFocus()
        }
        mainHandler.removeCallbacksAndMessages(null)
        mediaSession.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Audio focus
    // ---------------------------------------------------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        return try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes())
                // Delayed gain is not supported: we would have to start playback later, possibly from
                // the background where entering the foreground is not allowed. Callers get FocusDenied.
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this, mainHandler)
                .build()
            when (val result = audioManager.requestAudioFocus(request)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    focusRequest = request
                    true
                }
                else -> {
                    Log.w(TAG, "Audio focus not granted (result=$result); another app or a call holds focus")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio focus request threw", e)
            false
        }
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager.abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS ->
                stopSession(StopReason.FOCUS_LOSS, "Another app started playing audio")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                stopSession(StopReason.FOCUS_LOSS, "Interrupted (call or notification)")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                session?.generator?.setVolume(effectiveVolume())
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                isDucked = false
                session?.generator?.setVolume(effectiveVolume())
            }
            else -> Log.d(TAG, "Unhandled audio focus change: $focusChange")
        }
    }

    private fun effectiveVolume(): Float = if (isDucked) volume * DUCK_FACTOR else volume

    // ---------------------------------------------------------------------------------------------
    // Resources
    // ---------------------------------------------------------------------------------------------

    /** @return the track (initialized, not yet playing) and the chunk size in frames to render per write. */
    private fun buildAudioTrack(): Pair<AudioTrack, Int> {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            throw IllegalStateException("AudioTrack.getMinBufferSize returned $minBufferBytes")
        }
        // Twice the minimum gives headroom against underruns on devices whose minimum is tight.
        val bufferBytes = minBufferBytes * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack not initialized (state=${track.state})")
        }
        val bytesPerFrame = 4 // 16-bit stereo
        return track to (minBufferBytes / bytesPerFrame).coerceAtLeast(256)
    }

    private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun acquireWakeLock(params: ToneParams) {
        try {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlackCloudBinaural::AudioSynthesisWakeLock")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            val timeoutMs = if (params.durationSeconds > 0) {
                params.durationSeconds * 1000L + WAKE_LOCK_SLACK_MS
            } else {
                OPEN_ENDED_WAKE_LOCK_MS
            }
            lock.acquire(timeoutMs)
        } catch (e: Exception) {
            // Playback continues without it; the foreground service usually keeps audio alive anyway.
            Log.e(TAG, "Failed to acquire wake lock; playback may stop when the screen is off", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Binaural Beats", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            ?: Log.e(TAG, "NotificationManager unavailable; foreground notification channel not created")
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BinauralAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Black Cloud Group | Binaural Session")
            .setContentText("Hemi-Sync & brainwave entrainment engine active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

    companion object {
        private const val TAG = "BinauralAudioService"
        const val ACTION_STOP = "com.blackcloudgroup.binaural.action.STOP"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "binaural_channel"
        private const val SAMPLE_RATE = 44_100
        private const val DEFAULT_VOLUME = 0.2f
        private const val DUCK_FACTOR = 0.5f
        private const val STOP_FADE_MS = 250
        private const val STOP_WATCHDOG_MS = 1_500L
        private const val MAX_ZERO_WRITES = 50
        private const val WAKE_LOCK_SLACK_MS = 5 * 60 * 1000L
        private const val OPEN_ENDED_WAKE_LOCK_MS = 4 * 60 * 60 * 1000L

        private fun writeErrorName(code: Int): String = when (code) {
            AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioTrack.ERROR -> "ERROR"
            else -> "UNKNOWN"
        }
    }
}
