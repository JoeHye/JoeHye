package com.blackcloudgroup.binaural

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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blackcloudgroup.binaural.audio.ToneGenerator
import com.blackcloudgroup.binaural.audio.ToneParams
import com.blackcloudgroup.binaural.health.HealthConnectManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant

enum class StopReason { USER, COMPLETED, FOCUS_LOSS, ERROR }

enum class PauseReason {
    USER,
    /** Call or notification; resumes by itself when it ends. */
    INTERRUPTION,
    /** Another app took over audio; the user resumes manually. */
    OTHER_APP,
    /** Headphones unplugged or disconnected; avoids suddenly playing out of the speaker. */
    HEADPHONES_DISCONNECTED
}

sealed interface PlaybackState {
    object Idle : PlaybackState
    data class Playing(val params: ToneParams) : PlaybackState
    data class Paused(val params: ToneParams, val reason: PauseReason) : PlaybackState
    data class Stopped(val reason: StopReason, val message: String? = null) : PlaybackState
}

/** Snapshot for the UI; [totalSeconds] is 0 for an open-ended session. */
data class SessionProgress(val elapsedSeconds: Int, val totalSeconds: Int, val currentBeatHz: Double)

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

    /** Outcome of the most recent Health Connect write, for the UI to show; null until one happens. */
    private val _healthLogStatus = MutableStateFlow<HealthConnectManager.WriteResult?>(null)
    val healthLogStatus: StateFlow<HealthConnectManager.WriteResult?> = _healthLogStatus.asStateFlow()

    private lateinit var settings: AppSettings
    private lateinit var healthConnect: HealthConnectManager

    private class Session(
        val id: Long,
        val generator: ToneGenerator,
        val title: String,
        val startedAt: Instant,
        initialParams: ToneParams
    ) {
        /** Main-thread view of the current parameters, for state updates and resume. */
        var params: ToneParams = initialParams
        var pauseReason: PauseReason? = null
        /** Set by a transient focus loss so focus regain resumes; a user pause clears it. */
        var resumeOnFocusGain = false
        var pausedSince: Instant? = null
        var pausedTotal: Duration = Duration.ZERO
        /** Audio thread waits on this while paused. */
        val pauseLock = Object()
        @Volatile var paused = false

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

    /** Pauses when headphones are unplugged/disconnected so audio doesn't jump to the speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "Audio becoming noisy (headphones disconnected); pausing")
                pauseSession(PauseReason.HEADPHONES_DISCONNECTED)
            }
        }
    }
    private var noisyReceiverRegistered = false

    /** Headset buttons, Bluetooth controls and the lock screen arrive here. */
    private val mediaCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (session?.paused == true) resumeSession()
            else Log.d(TAG, "Media play ignored: nothing paused")
        }
        override fun onPause() = pauseSession(PauseReason.USER)
        override fun onStop() = stopSession(StopReason.USER)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BinauralAudioService = this@BinauralAudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "BinauralAudioService").apply {
            setCallback(mediaCallback, mainHandler)
            isActive = true
        }
        updateMediaSession()
        settings = AppSettings(this)
        healthConnect = HealthConnectManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> {
                Log.i(TAG, "Stop requested from notification")
                stopSession(StopReason.USER)
            }
            intent?.action == ACTION_PAUSE -> pauseSession(PauseReason.USER)
            intent?.action == ACTION_RESUME -> {
                val result = resumeSession()
                if (result != SessionStartResult.Started) Log.w(TAG, "Resume from notification failed: $result")
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

    /** @param title shown in Health Connect if session logging is on (preset name or a generic label). */
    fun startSession(params: ToneParams, title: String): SessionStartResult {
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
        val newSession = Session(++nextSessionId, generator, title, Instant.now(), params)
        session = newSession

        val (track, framesPerChunk) = trackAndFrames
        Thread({ runAudioLoop(newSession, track, framesPerChunk) }, "BinauralSynth-${newSession.id}").start()

        registerNoisyReceiver()
        _playbackState.value = PlaybackState.Playing(params)
        updateMediaSession()
        updateNotification()
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
        synchronized(s.pauseLock) { s.pauseLock.notifyAll() } // a paused audio thread exits right away

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
        s.params = params
        val reason = s.pauseReason
        _playbackState.value = if (s.paused && reason != null) PlaybackState.Paused(params, reason) else PlaybackState.Playing(params)
    }

    /** Fades to silence and holds the session (timeline, ramp position) until [resumeSession]. */
    fun pauseSession(reason: PauseReason = PauseReason.USER) {
        if (!isMainThread()) {
            mainHandler.post { pauseSession(reason) }
            return
        }
        val s = session ?: return
        if (s.generator.isStopRequested) return
        if (s.paused) {
            // A user pause during an interruption means "stay paused" once the call ends.
            if (reason == PauseReason.USER) {
                s.resumeOnFocusGain = false
                s.pauseReason = PauseReason.USER
                _playbackState.value = PlaybackState.Paused(s.params, PauseReason.USER)
            }
            return
        }
        s.paused = true
        s.pauseReason = reason
        s.resumeOnFocusGain = reason == PauseReason.INTERRUPTION
        s.pausedSince = Instant.now()
        s.generator.setPaused(true)
        releaseWakeLock()
        _playbackState.value = PlaybackState.Paused(s.params, reason)
        updateMediaSession()
        updateNotification()
        Log.i(TAG, "Session ${s.id} paused: $reason")
    }

    fun resumeSession(): SessionStartResult {
        if (!isMainThread()) return SessionStartResult.Failed("Internal error: resumeSession must be called on the main thread")
        val s = session ?: return SessionStartResult.Failed("Nothing to resume")
        if (!s.paused) return SessionStartResult.AlreadyPlaying
        // After a permanent focus loss we no longer hold focus and must ask again.
        if (focusRequest == null && !requestAudioFocus()) return SessionStartResult.FocusDenied

        s.pausedSince?.let { s.pausedTotal = s.pausedTotal.plus(Duration.between(it, Instant.now())) }
        s.pausedSince = null
        s.paused = false
        s.pauseReason = null
        s.resumeOnFocusGain = false
        s.generator.setPaused(false)
        synchronized(s.pauseLock) { s.pauseLock.notifyAll() }
        acquireWakeLock(s.params)
        _playbackState.value = PlaybackState.Playing(s.params)
        updateMediaSession()
        updateNotification()
        Log.i(TAG, "Session ${s.id} resumed")
        return SessionStartResult.Started
    }

    /** Where the current session is (time played, current beat on the ramp); null when idle. */
    fun currentProgress(): SessionProgress? {
        val s = session ?: return null
        return SessionProgress(
            elapsedSeconds = s.generator.elapsedSeconds.toInt(),
            totalSeconds = s.params.durationSeconds,
            currentBeatHz = s.generator.currentBeatHz
        )
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
                if (s.generator.isSilencedForPause && !s.generator.isStopRequested) {
                    // Faded out for a pause: stop feeding the track and wait for resume or stop.
                    track.pause()
                    synchronized(s.pauseLock) {
                        while (s.running && s.paused && !s.generator.isStopRequested) s.pauseLock.wait(PAUSE_POLL_MS)
                    }
                    if (!s.running || s.generator.isStopRequested) break // already silent, no fade needed
                    track.play()
                }
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
        s.pausedSince?.let { s.pausedTotal = s.pausedTotal.plus(Duration.between(it, Instant.now())) }
        releasePlaybackResources()
        _playbackState.value = PlaybackState.Stopped(reason, message)
        updateMediaSession()
        Log.i(TAG, "Session ${s.id} ended: $reason${message?.let { " ($it)" } ?: ""}")
        logToHealthConnect(s)
    }

    /** Records what actually played, whatever ended it (completed, stopped, interrupted, error). */
    private fun logToHealthConnect(s: Session) {
        if (!settings.logToHealthConnect) return
        val endedAt = Instant.now()
        val played = Duration.between(s.startedAt, endedAt).minus(s.pausedTotal)
        if (played < HealthConnectManager.MIN_LOGGED_SESSION) {
            Log.d(TAG, "Session ${s.id} lasted ${played.seconds}s; too short to log to Health Connect")
            return
        }
        val manager = healthConnect
        HealthConnectManager.writeScope.launch {
            val result = manager.writeMindfulnessSession(
                startTime = s.startedAt,
                endTime = endedAt,
                title = s.title,
                clientRecordId = "binaural-session-${s.startedAt.toEpochMilli()}"
            )
            if (result !is HealthConnectManager.WriteResult.Written) {
                Log.w(TAG, "Session ${s.id} not logged to Health Connect: $result")
            }
            _healthLogStatus.value = result
        }
    }

    private fun releasePlaybackResources() {
        unregisterNoisyReceiver()
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
            synchronized(s.pauseLock) { s.pauseLock.notifyAll() }
            session = null
            releaseWakeLock()
            abandonAudioFocus()
        }
        unregisterNoisyReceiver()
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
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Focus is gone for good; resuming must request it again.
                pauseSession(PauseReason.OTHER_APP)
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Don't let a call override an explicit user pause.
                if (session?.paused != true) pauseSession(PauseReason.INTERRUPTION)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                session?.generator?.setVolume(effectiveVolume())
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                isDucked = false
                session?.generator?.setVolume(effectiveVolume())
                if (session?.resumeOnFocusGain == true) {
                    Log.i(TAG, "Interruption over; resuming")
                    resumeSession()
                }
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
        val s = session
        val paused = s?.paused == true
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = if (paused) {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Resume", serviceIntent(ACTION_RESUME, 2))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", serviceIntent(ACTION_PAUSE, 3))
        }
        val stop = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceIntent(ACTION_STOP, 1)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(s?.title ?: "Black Cloud Binaural")
            .setContentText(if (paused) pausedText(s?.pauseReason) else "Playing")
            .setSmallIcon(R.drawable.ic_stat_binaural)
            .setContentIntent(openApp)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(!paused)
            .build()
    }

    private fun pausedText(reason: PauseReason?): String = when (reason) {
        PauseReason.INTERRUPTION -> "Paused for a call or notification; resumes automatically"
        PauseReason.OTHER_APP -> "Paused because another app is playing audio"
        PauseReason.HEADPHONES_DISCONNECTED -> "Paused: headphones disconnected"
        PauseReason.USER, null -> "Paused"
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, BinauralAudioService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Re-posts the foreground notification so its Pause/Resume button and text match the state. */
    private fun updateNotification() {
        if (session == null) return
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied: the update is dropped; playback is unaffected.
            Log.w(TAG, "Could not update playback notification", e)
        }
    }

    /** Keeps headset/Bluetooth/lock-screen controls in step with what is actually happening. */
    private fun updateMediaSession() {
        val s = session
        val state = when {
            s == null -> PlaybackStateCompat.STATE_STOPPED
            s.paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s?.title ?: "Black Cloud Binaural")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Black Cloud Binaural")
                .build()
        )
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Noisy receiver was not registered", e)
        }
        noisyReceiverRegistered = false
    }

    private fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

    companion object {
        private const val TAG = "BinauralAudioService"
        const val ACTION_STOP = "com.blackcloudgroup.binaural.action.STOP"
        const val ACTION_PAUSE = "com.blackcloudgroup.binaural.action.PAUSE"
        const val ACTION_RESUME = "com.blackcloudgroup.binaural.action.RESUME"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "binaural_channel"
        private const val SAMPLE_RATE = 44_100
        private const val DEFAULT_VOLUME = 0.2f
        private const val DUCK_FACTOR = 0.5f
        private const val STOP_FADE_MS = 250
        private const val STOP_WATCHDOG_MS = 1_500L
        private const val PAUSE_POLL_MS = 250L
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
