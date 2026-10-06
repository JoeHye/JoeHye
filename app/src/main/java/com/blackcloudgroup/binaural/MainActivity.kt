package com.blackcloudgroup.binaural

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import com.blackcloudgroup.binaural.audio.ToneParams
import com.blackcloudgroup.binaural.audio.parseSoundMode
import com.blackcloudgroup.binaural.data.AppDatabase
import com.blackcloudgroup.binaural.data.DefaultPresets
import com.blackcloudgroup.binaural.data.PresetEntity
import com.blackcloudgroup.binaural.health.HealthConnectManager
import com.blackcloudgroup.binaural.ui.LissajousVisualizer
import com.blackcloudgroup.binaural.ui.PhoticEntrainmentCanvas
import com.blackcloudgroup.binaural.ui.BinauralTheme
import com.blackcloudgroup.binaural.ui.CustomizeScreen
import com.blackcloudgroup.binaural.ui.HeroState
import com.blackcloudgroup.binaural.ui.HomeScreen
import com.blackcloudgroup.binaural.ui.SettingsSheet
import com.blackcloudgroup.binaural.ui.StatusLine
import com.blackcloudgroup.binaural.ui.formatHz
import com.blackcloudgroup.binaural.ui.PresetDialog
import com.blackcloudgroup.binaural.util.rememberHeadphonesConnected
import com.blackcloudgroup.binaural.util.WavExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var audioService by mutableStateOf<BinauralAudioService?>(null)
    private var isBound by mutableStateOf(false)
    // Set when Health Connect opens us to explain how its data is used (rationale / permission usage).
    private var showPrivacyInfo by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BinauralAudioService.LocalBinder
            audioService = binder.getService()
            isBound = true
            Log.i("MainActivity", "BinauralAudioService connected and bound successfully.")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioService = null
            isBound = false
            Log.w("MainActivity", "BinauralAudioService disconnected unexpectedly.")
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BinauralAudioService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            audioService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(this)
        val settings = AppSettings(this)
        showPrivacyInfo = isPrivacyIntent(intent)

        setContent {
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // targetSdk 35+ always draws edge-to-edge. Match the system-bar icons to the app theme,
            // not the system one, or they disappear when the two differ (e.g. app forced to light).
            DisposableEffect(darkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (darkTheme) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            BinauralTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppContent(
                        audioService = audioService,
                        database = database,
                        showPrivacyInfo = showPrivacyInfo,
                        onDismissPrivacyInfo = { showPrivacyInfo = false },
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeMode = it
                            settings.themeMode = it
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isPrivacyIntent(intent)) showPrivacyInfo = true
    }

    private fun isPrivacyIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_SHOW_PERMISSIONS_RATIONALE || intent?.action == Intent.ACTION_VIEW_PERMISSION_USAGE

    private companion object {
        const val ACTION_SHOW_PERMISSIONS_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    audioService: BinauralAudioService?,
    database: AppDatabase,
    showPrivacyInfo: Boolean,
    onDismissPrivacyInfo: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // remember the Flow so recomposition (e.g. every slider tick) doesn't re-run the Room query
    val presetsFlow = remember(database) { database.presetDao().getAllPresets() }
    val presets by presetsFlow.collectAsState(initial = emptyList())

    // Playback state comes from the service, so focus loss, notification Stop, session end and
    // activity recreation are all reflected here instead of a local flag drifting out of sync.
    val playbackFlow = remember(audioService) {
        audioService?.playbackState ?: MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    }
    val playback by playbackFlow.collectAsState()
    val isPlaying = playback is PlaybackState.Playing
    val isPaused = playback is PlaybackState.Paused
    // A session exists (playing or paused): parameters still apply and Stop is available.
    val isActive = isPlaying || isPaused
    val headphonesConnected = rememberHeadphonesConnected()

    var carrier by rememberSaveable { mutableFloatStateOf(200f) }
    var beat by rememberSaveable { mutableFloatStateOf(6f) }
    // Ramp/duration/pink noise come from the last tapped preset. null target = no ramp; 0 minutes = open-ended.
    var rampTargetBeat by rememberSaveable { mutableStateOf<Float?>(null) }
    var durationMinutes by rememberSaveable { mutableIntStateOf(0) }
    var pinkNoise by rememberSaveable { mutableStateOf(true) }
    var soundMode by rememberSaveable { mutableStateOf(SoundMode.HEMI_SYNC) }
    var enablePhotic by rememberSaveable { mutableStateOf(false) }
    var exportInProgress by remember { mutableStateOf(false) }
    var showPhoticWarning by remember { mutableStateOf(false) }
    // Linear output gain sent to the service; Android's media volume still applies on top of it.
    var volume by rememberSaveable { mutableFloatStateOf(DEFAULT_VOLUME) }
    var pendingLargeExport by remember { mutableStateOf<PresetEntity?>(null) }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    // Title of the last tapped preset; cleared by manual edits. Used as the Health Connect entry name.
    var presetTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Database id of the loaded preset, for highlighting its card; null after manual edits.
    var selectedPresetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var showSettings by remember { mutableStateOf(false) }

    val settings = remember { AppSettings(context) }
    val healthConnect = remember { HealthConnectManager(context) }
    var logToHealthConnect by remember { mutableStateOf(settings.logToHealthConnect) }
    val healthStatusFlow = remember(audioService) {
        audioService?.healthLogStatus ?: MutableStateFlow<HealthConnectManager.WriteResult?>(null)
    }
    val healthStatus by healthStatusFlow.collectAsState()

    fun setHealthLogging(enabled: Boolean) {
        settings.logToHealthConnect = enabled
        logToHealthConnect = enabled
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthConnect.requiredPermissions)) {
            setHealthLogging(true)
            Toast.makeText(context, "Sessions will be logged to Health Connect.", Toast.LENGTH_SHORT).show()
        } else {
            Log.w("MainActivity", "Health Connect permission not granted (got $granted)")
            Toast.makeText(context, "Health Connect permission wasn't granted.", Toast.LENGTH_LONG).show()
        }
    }

    fun enableHealthLogging() {
        val availability = try {
            healthConnect.availability()
        } catch (e: Exception) {
            Log.e("MainActivity", "Health Connect availability check failed", e)
            Toast.makeText(context, "Couldn't reach Health Connect: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        when (availability) {
            HealthConnectManager.Availability.Available -> coroutineScope.launch {
                if (healthConnect.hasRequiredPermissions()) {
                    setHealthLogging(true)
                } else {
                    healthPermissionLauncher.launch(healthConnect.requiredPermissions)
                }
            }
            HealthConnectManager.Availability.NotInstalled,
            HealthConnectManager.Availability.UpdateRequired -> {
                Toast.makeText(context, "Install or update Health Connect to log sessions.", Toast.LENGTH_LONG).show()
                try {
                    context.startActivity(healthConnect.providerInstallIntent())
                } catch (e: ActivityNotFoundException) {
                    Log.w("MainActivity", "No Play Store to install Health Connect", e)
                }
            }
            HealthConnectManager.Availability.MindfulnessUnsupported ->
                Toast.makeText(
                    context,
                    "This Health Connect version can't store mindfulness sessions yet. Update it and try again.",
                    Toast.LENGTH_LONG
                ).show()
        }
    }

    // Android 13+: without this permission the foreground-service notification (and its Stop button)
    // is hidden from the shade. Playback still works, so a denial is logged, not blocking.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Log.w("MainActivity", "POST_NOTIFICATIONS denied; playback notification will be hidden")
            Toast.makeText(
                context,
                "Notifications are off, so the playback controls won't show in the notification shade.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedForNotifications) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askedForNotifications = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun restoreDefaultPresets() {
        coroutineScope.launch {
            try {
                val added = database.presetDao().restoreMissingDefaults(DefaultPresets.all)
                val message = if (added == 0) "All default presets are already in your list."
                else "Added $added default preset${if (added == 1) "" else "s"}."
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to restore default presets", e)
                Toast.makeText(context, "Couldn't restore presets: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun shareExport(outFile: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share WAV export"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to share exported WAV", e)
            Toast.makeText(context, "Exported to ${outFile.name}, but sharing failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun startExport(preset: PresetEntity) {
        exportInProgress = true
        Toast.makeText(context, "Exporting ${preset.title}…", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            try {
                val outFile = WavExporter.exportToWav(preset, WavExporter.exportFileFor(context, preset))
                shareExport(outFile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i("MainActivity", "WAV export cancelled for '${preset.title}'")
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to export WAV file", e)
                Toast.makeText(context, "Export failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
            } finally {
                exportInProgress = false
            }
        }
    }
    // Preset being created/edited in the dialog (id 0 = new) and whether it's an edit; null = closed.
    var presetDialog by remember { mutableStateOf<Pair<PresetEntity, Boolean>?>(null) }
    var showHeadphoneWarning by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<PresetEntity?>(null) }

    fun currentParams() = ToneParams(
        carrierHz = carrier.toDouble(),
        startBeatHz = beat.toDouble(),
        targetBeatHz = (rampTargetBeat ?: beat).toDouble(),
        durationSeconds = durationMinutes * 60,
        soundMode = soundMode,
        pinkNoise = pinkNoise
    )

    /** Push the UI's parameters to a running session; invalid combinations are logged and skipped. */
    fun pushParams(restartTimeline: Boolean = false) {
        val service = audioService ?: return
        if (!isActive) return
        try {
            service.updateParams(currentParams(), restartTimeline)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Not applying invalid parameters: ${e.message}")
        }
    }

    fun showStartResult(result: SessionStartResult) {
        when (result) {
            SessionStartResult.Started -> Unit
            SessionStartResult.AlreadyPlaying ->
                Log.w("MainActivity", "Start/resume tapped while the service reports a running session")
            SessionStartResult.FocusDenied ->
                Toast.makeText(context, "Can't play right now — another app or a call is using audio.", Toast.LENGTH_LONG).show()
            is SessionStartResult.Failed -> {
                Log.e("MainActivity", "Session start failed: ${result.message}", result.cause)
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startPlayback() {
        val service = audioService
        if (service == null) {
            Log.w("MainActivity", "Start failed: audio service is unbound.")
            Toast.makeText(context, "Audio service initializing...", Toast.LENGTH_SHORT).show()
            return
        }
        val params = try {
            currentParams()
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Refusing to start with invalid parameters", e)
            Toast.makeText(context, "Invalid settings: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        requestNotificationPermissionOnce()
        service.setVolume(volume)
        val title = presetTitle ?: "${soundMode.label()} session"
        showStartResult(service.startSession(params, title))
    }

    /** Binaural and Hemi-Sync need a separate signal per ear; warn before playing them on a speaker. */
    fun onStartTapped() {
        if (soundMode != SoundMode.ISOCHRONIC && !headphonesConnected) {
            showHeadphoneWarning = true
        } else {
            startPlayback()
        }
    }

    fun newPresetFromCurrentSettings() = PresetEntity(
        title = "",
        startBeatHz = beat.toDouble(),
        targetBeatHz = (rampTargetBeat ?: beat).toDouble(),
        carrierHz = carrier.toDouble(),
        durationMinutes = if (durationMinutes > 0) durationMinutes else 20,
        soundMode = soundMode.name,
        enablePinkNoise = pinkNoise
    )


    // ---- Live session progress for the hero ring (polled; the service owns the clock) ----
    var progress by remember { mutableStateOf<SessionProgress?>(null) }
    LaunchedEffect(audioService, isActive) {
        if (!isActive) {
            progress = null
            return@LaunchedEffect
        }
        while (true) {
            progress = audioService?.currentProgress()
            delay(500)
        }
    }

    val modeLabel = when (soundMode) {
        SoundMode.HEMI_SYNC -> if (pinkNoise) "Hemi-Sync · pink noise" else "Hemi-Sync"
        SoundMode.BINAURAL -> "Binaural"
        SoundMode.ISOCHRONIC -> "Isochronic · speakers OK"
    }
    val live = progress
    val heroBeat = live?.currentBeatHz ?: beat.toDouble()
    val hero = HeroState(
        title = presetTitle ?: "Custom session",
        beatHz = heroBeat,
        carrierHz = carrier.toDouble(),
        rampLabel = rampTargetBeat?.let { "${formatHz(beat.toDouble())} → ${formatHz(it.toDouble())} Hz" }
            ?: "Steady ${formatHz(beat.toDouble())} Hz",
        timeLabel = when {
            live != null && live.totalSeconds > 0 ->
                "${((live.totalSeconds - live.elapsedSeconds).coerceAtLeast(0) + 59) / 60} min left"
            live != null -> "${live.elapsedSeconds / 60} min played"
            durationMinutes > 0 -> "$durationMinutes min"
            else -> "Open-ended"
        },
        progress = if (live != null && live.totalSeconds > 0) live.elapsedSeconds.toFloat() / live.totalSeconds else 0f,
        modeLabel = modeLabel
    )

    val needsHeadphones = soundMode != SoundMode.ISOCHRONIC
    val statusLines = buildList {
        (playback as? PlaybackState.Paused)?.let {
            add(
                StatusLine(
                    when (it.reason) {
                        PauseReason.USER -> "Paused."
                        PauseReason.INTERRUPTION -> "Paused for a call or notification. Resumes automatically."
                        PauseReason.OTHER_APP -> "Paused because another app started playing audio."
                        PauseReason.HEADPHONES_DISCONNECTED -> "Paused: headphones disconnected."
                    }
                )
            )
        }
        (playback as? PlaybackState.Stopped)?.let {
            when (it.reason) {
                StopReason.USER -> Unit
                StopReason.COMPLETED -> add(StatusLine("Session complete."))
                StopReason.FOCUS_LOSS -> add(StatusLine("Stopped: ${it.message ?: "audio focus lost"}"))
                StopReason.ERROR -> add(StatusLine("Playback error: ${it.message ?: "unknown"}", isError = true))
            }
        }
        if (needsHeadphones && !headphonesConnected) {
            add(StatusLine("No headphones detected. ${soundMode.label()} needs headphones to work."))
        }
    }
    val healthStatusLine = healthStatus?.let { result ->
        when (result) {
            HealthConnectManager.WriteResult.Written -> StatusLine("Last session logged to Health Connect.")
            is HealthConnectManager.WriteResult.Skipped -> StatusLine("Last session not logged: ${result.reason}")
            is HealthConnectManager.WriteResult.Failed -> StatusLine("Health Connect error: ${result.message}", isError = true)
        }
    }

    fun loadPreset(preset: PresetEntity) {
        carrier = preset.carrierHz.toFloat()
        beat = preset.startBeatHz.toFloat()
        rampTargetBeat = preset.targetBeatHz.toFloat().takeIf { it != beat }
        durationMinutes = preset.durationMinutes
        pinkNoise = preset.enablePinkNoise
        soundMode = parseSoundMode(preset.soundMode)
        presetTitle = preset.title
        selectedPresetId = preset.id
        // A new preset starts its ramp and duration from the beginning.
        pushParams(restartTimeline = true)
    }

    /** Any manual change makes this a custom session rather than the selected preset. */
    fun markCustom() {
        presetTitle = null
        selectedPresetId = null
    }

    fun requestExport(preset: PresetEntity) {
        if (WavExporter.estimatedSizeBytes(preset) > WavExporter.LARGE_EXPORT_BYTES) {
            pendingLargeExport = preset
        } else {
            startExport(preset)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PhoticEntrainmentCanvas(beatFreqHz = heroBeat, isEnabled = enablePhotic && isPlaying)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Keep content out from under the status bar and side cutouts; bottom insets are
                // handled by each screen so panels still reach the edge.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        ) {
            if (screen == Screen.CUSTOMIZE) {
                CustomizeScreen(
                    soundMode = soundMode,
                    onSoundModeChange = {
                        soundMode = it
                        markCustom()
                        pushParams()
                    },
                    beatHz = beat,
                    rampTargetHz = rampTargetBeat,
                    onBeatChange = {
                        // Moving the beat by hand overrides the preset ramp (duration still applies).
                        beat = it
                        rampTargetBeat = null
                        markCustom()
                        pushParams()
                    },
                    carrierHz = carrier,
                    onCarrierChange = {
                        carrier = it
                        markCustom()
                        pushParams()
                    },
                    pinkNoise = pinkNoise,
                    onPinkNoiseChange = {
                        pinkNoise = it
                        markCustom()
                        pushParams()
                    },
                    volume = volume,
                    onVolumeChange = {
                        volume = it
                        try {
                            audioService?.setVolume(it)
                        } catch (e: IllegalArgumentException) {
                            Log.w("MainActivity", "Rejected volume $it: ${e.message}")
                        }
                    },
                    lightPulses = enablePhotic,
                    onLightPulsesChange = { wantOn ->
                        // Turning on always goes through the photosensitivity warning.
                        if (wantOn) showPhoticWarning = true else enablePhotic = false
                    },
                    onSaveAsPreset = { presetDialog = newPresetFromCurrentSettings() to false },
                    onBack = { screen = Screen.HOME }
                )
            } else {
                HomeScreen(
                    hero = hero,
                    isPlaying = isPlaying,
                    isActive = isActive,
                    serviceReady = audioService != null,
                    headphonesConnected = headphonesConnected,
                    needsHeadphones = needsHeadphones,
                    statusLines = statusLines,
                    presets = presets,
                    selectedPresetId = selectedPresetId,
                    exportInProgress = exportInProgress,
                    onPlayPause = {
                        val service = audioService
                        when {
                            service == null -> Toast.makeText(context, "Audio service initializing...", Toast.LENGTH_SHORT).show()
                            isPlaying -> service.pauseSession(PauseReason.USER)
                            isPaused -> showStartResult(service.resumeSession())
                            else -> onStartTapped()
                        }
                    },
                    onStop = { audioService?.stopSession() },
                    onOpenCustomize = { screen = Screen.CUSTOMIZE },
                    onOpenSettings = { showSettings = true },
                    onUseIsochronic = {
                        soundMode = SoundMode.ISOCHRONIC
                        markCustom()
                        pushParams()
                    },
                    onSelectPreset = { loadPreset(it) },
                    onNewPreset = { presetDialog = newPresetFromCurrentSettings() to false },
                    onEditPreset = { presetDialog = it to true },
                    onExportPreset = { requestExport(it) },
                    onDeletePreset = { presetToDelete = it }
                )
            }
        }

        if (showSettings) {
            SettingsSheet(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                logToHealthConnect = logToHealthConnect,
                onLogToHealthConnectChange = { wantOn -> if (wantOn) enableHealthLogging() else setHealthLogging(false) },
                healthStatus = healthStatusLine,
                onRestoreDefaults = { restoreDefaultPresets() },
                onOpenPrivacyPolicy = { openPrivacyPolicy(context) },
                onDismiss = { showSettings = false }
            )
        }

        presetDialog?.let { (initial, isEdit) ->
            PresetDialog(
                initial = initial,
                isEdit = isEdit,
                onDismiss = { presetDialog = null },
                onSave = { preset ->
                    presetDialog = null
                    coroutineScope.launch {
                        try {
                            // REPLACE on the same id updates an edited preset in place.
                            database.presetDao().insertPreset(preset)
                            if (isEdit && presetTitle == initial.title) presetTitle = preset.title
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to save preset '${preset.title}'", e)
                            Toast.makeText(context, "Couldn't save preset: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }

        if (showHeadphoneWarning) {
            AlertDialog(
                onDismissRequest = { showHeadphoneWarning = false },
                title = { Text("No headphones detected") },
                text = {
                    Text(
                        "Binaural and Hemi-Sync beats only work when each ear hears its own tone, so they " +
                            "need headphones. Through a speaker you'll just hear a steady hum.\n\n" +
                            "Isochronic mode pulses the sound itself and works on speakers."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showHeadphoneWarning = false
                        soundMode = SoundMode.ISOCHRONIC
                        presetTitle = null
                        startPlayback()
                    }) { Text("Use Isochronic") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showHeadphoneWarning = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            showHeadphoneWarning = false
                            startPlayback()
                        }) { Text("Play anyway") }
                    }
                }
            )
        }

        if (showPrivacyInfo) {
            AlertDialog(
                onDismissRequest = onDismissPrivacyInfo,
                title = { Text("How Black Cloud Binaural uses Health Connect") },
                text = {
                    Text(
                        "If you turn on \"Log sessions to Health Connect\", each listening session of a minute or " +
                            "longer is saved to Health Connect as a mindfulness session: its start and end time and " +
                            "the preset name.\n\n" +
                            "The app only writes this data. It does not read any Health Connect data, and nothing is " +
                            "sent off your device by this app. You can turn logging off in the app, revoke access in " +
                            "Health Connect settings, or delete the entries there at any time."
                    )
                },
                confirmButton = { TextButton(onClick = onDismissPrivacyInfo) { Text("OK") } },
                dismissButton = { TextButton(onClick = { openPrivacyPolicy(context) }) { Text("Full privacy policy") } }
            )
        }

        if (showPhoticWarning) {
            AlertDialog(
                onDismissRequest = { showPhoticWarning = false },
                title = { Text("Photosensitivity warning") },
                text = {
                    Text(
                        "Photic mode flashes the whole screen at the beat frequency (up to 40 times a second). " +
                            "Flashing light in this range can trigger seizures in people with photosensitive " +
                            "epilepsy, including people who have never had a seizure before.\n\n" +
                            "Do not use it if you or anyone who can see the screen has epilepsy or a history of " +
                            "seizures. Stop immediately if you feel dizzy, disoriented or unwell."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        enablePhotic = true
                        showPhoticWarning = false
                    }) { Text("I understand, turn on") }
                },
                dismissButton = {
                    TextButton(onClick = { showPhoticWarning = false }) { Text("Cancel") }
                }
            )
        }

        pendingLargeExport?.let { preset ->
            val sizeMb = WavExporter.estimatedSizeBytes(preset) / (1024 * 1024)
            AlertDialog(
                onDismissRequest = { pendingLargeExport = null },
                title = { Text("Large export") },
                text = {
                    Text(
                        "\"${preset.title}\" is ${preset.durationMinutes} minutes, so the WAV file will be about " +
                            "$sizeMb MB. Exporting can take a while, and some apps refuse files this large when sharing."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pendingLargeExport = null
                        startExport(preset)
                    }) { Text("Export") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingLargeExport = null }) { Text("Cancel") }
                }
            )
        }

        presetToDelete?.let { preset ->
            AlertDialog(
                onDismissRequest = { presetToDelete = null },
                title = { Text("Delete Preset") },
                text = { Text("Are you sure you want to delete \"${preset.title}\"? This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    database.presetDao().deletePreset(preset)
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to delete preset from database", e)
                                }
                            }
                            presetToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { presetToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private const val DEFAULT_VOLUME = 0.2f

private fun SoundMode.label(): String = when (this) {
    SoundMode.HEMI_SYNC -> "Hemi-Sync"
    SoundMode.BINAURAL -> "Binaural"
    SoundMode.ISOCHRONIC -> "Isochronic"
}

private enum class Screen { HOME, CUSTOMIZE }

/** Published from docs/privacy via GitHub Pages (see docs/RELEASING.md). */
const val PRIVACY_POLICY_URL = "https://joehye.github.io/JoeHye/privacy/"

private fun openPrivacyPolicy(context: android.content.Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL)))
    } catch (e: ActivityNotFoundException) {
        Log.w("MainActivity", "No browser to open the privacy policy", e)
        Toast.makeText(context, "No browser found. The policy is at $PRIVACY_POLICY_URL", Toast.LENGTH_LONG).show()
    }
}
