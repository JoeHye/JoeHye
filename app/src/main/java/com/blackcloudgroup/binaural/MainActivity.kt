package com.blackcloudgroup.binaural

import android.Manifest
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.blackcloudgroup.binaural.audio.ToneParams
import com.blackcloudgroup.binaural.audio.parseSoundMode
import com.blackcloudgroup.binaural.data.AppDatabase
import com.blackcloudgroup.binaural.data.PresetEntity
import com.blackcloudgroup.binaural.ui.LissajousVisualizer
import com.blackcloudgroup.binaural.ui.PhoticEntrainmentCanvas
import com.blackcloudgroup.binaural.ui.PresetDialog
import com.blackcloudgroup.binaural.util.WavExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var audioService by mutableStateOf<BinauralAudioService?>(null)
    private var isBound by mutableStateOf(false)

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

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppContent(
                        audioService = audioService,
                        database = database
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    audioService: BinauralAudioService?,
    database: AppDatabase
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
    var showPresetDialog by remember { mutableStateOf(false) }
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
        if (!isPlaying) return
        try {
            service.updateParams(currentParams(), restartTimeline)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Not applying invalid parameters: ${e.message}")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PhoticEntrainmentCanvas(beatFreqHz = beat.toDouble(), isEnabled = enablePhotic && isPlaying)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Black Cloud Binaural",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LissajousVisualizer(
                carrierHz = carrier.toDouble(),
                beatHz = beat.toDouble(),
                isPlaying = isPlaying
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sound Mode Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterChip(
                    selected = soundMode == SoundMode.HEMI_SYNC,
                    onClick = {
                        soundMode = SoundMode.HEMI_SYNC
                        pushParams()
                    },
                    label = { Text("Hemi-Sync") }
                )
                FilterChip(
                    selected = soundMode == SoundMode.BINAURAL,
                    onClick = {
                        soundMode = SoundMode.BINAURAL
                        pushParams()
                    },
                    label = { Text("Binaural") }
                )
                FilterChip(
                    selected = soundMode == SoundMode.ISOCHRONIC,
                    onClick = {
                        soundMode = SoundMode.ISOCHRONIC
                        pushParams()
                    },
                    label = { Text("Isochronic") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Carrier Frequency: ${carrier.toInt()} Hz")
            Slider(
                value = carrier,
                onValueChange = {
                    carrier = it
                    pushParams()
                },
                valueRange = 100f..500f
            )

            Text(
                "Binaural Beat: ${String.format("%.1f", beat)} Hz" +
                    (rampTargetBeat?.let { " → ${String.format("%.1f", it)} Hz" } ?: "") +
                    (if (durationMinutes > 0) " over $durationMinutes min" else "")
            )
            Slider(
                value = beat,
                onValueChange = {
                    // Moving the beat by hand overrides the preset ramp (duration still applies).
                    beat = it
                    rampTargetBeat = null
                    pushParams()
                },
                valueRange = 0.5f..40f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Photic Light Flashing")
                Switch(
                    checked = enablePhotic,
                    onCheckedChange = { wantOn ->
                        // Turning on always goes through the photosensitivity warning.
                        if (wantOn) showPhoticWarning = true else enablePhotic = false
                    }
                )
            }

            Text("Volume: ${(volume * 100).toInt()}%")
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    try {
                        audioService?.setVolume(it)
                    } catch (e: IllegalArgumentException) {
                        Log.w("MainActivity", "Rejected volume $it: ${e.message}")
                    }
                },
                valueRange = 0f..1f
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = audioService != null,
                    onClick = {
                        val service = audioService
                        if (service == null) {
                            Log.w("MainActivity", "Playback toggle failed: Audio service is unbound.")
                            Toast.makeText(context, "Audio service initializing...", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (isPlaying) {
                            service.stopSession()
                            return@Button
                        }

                        val params = try {
                            currentParams()
                        } catch (e: IllegalArgumentException) {
                            Log.w("MainActivity", "Refusing to start with invalid parameters", e)
                            Toast.makeText(context, "Invalid settings: ${e.message}", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        requestNotificationPermissionOnce()
                        service.setVolume(volume)
                        when (val result = service.startSession(params)) {
                            SessionStartResult.Started -> Unit
                            SessionStartResult.AlreadyPlaying ->
                                Log.w("MainActivity", "Start tapped while service reports a running session")
                            SessionStartResult.FocusDenied ->
                                Toast.makeText(
                                    context,
                                    "Can't play right now — another app or a call is using audio.",
                                    Toast.LENGTH_LONG
                                ).show()
                            is SessionStartResult.Failed -> {
                                Log.e("MainActivity", "Session start failed: ${result.message}", result.cause)
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (audioService == null) "Connecting..." 
                        else if (isPlaying) "Stop Session" 
                        else "Start Session"
                    )
                }

                OutlinedButton(onClick = { showPresetDialog = true }) {
                    Text("Save Preset")
                }
            }

            (playback as? PlaybackState.Stopped)?.let { stopped ->
                val status = when (stopped.reason) {
                    StopReason.USER -> null
                    StopReason.COMPLETED -> "Session complete."
                    StopReason.FOCUS_LOSS -> "Stopped: ${stopped.message ?: "audio focus lost"}"
                    StopReason.ERROR -> "Playback error: ${stopped.message ?: "unknown"}"
                }
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (stopped.reason == StopReason.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Saved Presets",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(presets) { preset ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            carrier = preset.carrierHz.toFloat()
                            beat = preset.startBeatHz.toFloat()
                            rampTargetBeat = preset.targetBeatHz.toFloat().takeIf { it != beat }
                            durationMinutes = preset.durationMinutes
                            pinkNoise = preset.enablePinkNoise
                            soundMode = parseSoundMode(preset.soundMode)
                            // A new preset starts its ramp and duration from the beginning.
                            pushParams(restartTimeline = true)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(preset.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${preset.soundMode} | ${preset.carrierHz}Hz Base | ${preset.startBeatHz}Hz Beat | ${preset.durationMinutes}m",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    enabled = !exportInProgress,
                                    onClick = {
                                        if (WavExporter.estimatedSizeBytes(preset) > WavExporter.LARGE_EXPORT_BYTES) {
                                            pendingLargeExport = preset
                                        } else {
                                            startExport(preset)
                                        }
                                    }
                                ) {
                                    Text("WAV")
                                }

                                IconButton(onClick = { presetToDelete = preset }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Preset",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showPresetDialog) {
            PresetDialog(
                onDismiss = { showPresetDialog = false },
                onSave = { newPreset ->
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            database.presetDao().insertPreset(newPreset)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to insert custom preset", e)
                        }
                    }
                    showPresetDialog = false
                }
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
