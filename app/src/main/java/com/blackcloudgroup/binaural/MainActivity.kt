package com.blackcloudgroup.binaural

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.blackcloudgroup.binaural.data.AppDatabase
import com.blackcloudgroup.binaural.data.PresetEntity
import com.blackcloudgroup.binaural.ui.LissajousVisualizer
import com.blackcloudgroup.binaural.ui.PhoticEntrainmentCanvas
import com.blackcloudgroup.binaural.ui.PresetDialog
import com.blackcloudgroup.binaural.util.WavExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

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
    val presets by database.presetDao().getAllPresets().collectAsState(initial = emptyList())

    var isPlaying by remember { mutableStateOf(false) }
    var carrier by remember { mutableFloatStateOf(200f) }
    var beat by remember { mutableFloatStateOf(6f) }
    var soundMode by remember { mutableStateOf(SoundMode.HEMI_SYNC) }
    var enablePhotic by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<PresetEntity?>(null) }

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
                        audioService?.soundMode = SoundMode.HEMI_SYNC
                    },
                    label = { Text("Hemi-Sync") }
                )
                FilterChip(
                    selected = soundMode == SoundMode.BINAURAL,
                    onClick = {
                        soundMode = SoundMode.BINAURAL
                        audioService?.soundMode = SoundMode.BINAURAL
                    },
                    label = { Text("Binaural") }
                )
                FilterChip(
                    selected = soundMode == SoundMode.ISOCHRONIC,
                    onClick = {
                        soundMode = SoundMode.ISOCHRONIC
                        audioService?.soundMode = SoundMode.ISOCHRONIC
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
                    audioService?.carrierFreq = it.toDouble()
                },
                valueRange = 100f..500f
            )

            Text("Binaural Beat: ${String.format("%.1f", beat)} Hz")
            Slider(
                value = beat,
                onValueChange = {
                    beat = it
                    audioService?.beatFreq = it.toDouble()
                },
                valueRange = 0.5f..40f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Photic Light Flashing")
                Switch(checked = enablePhotic, onCheckedChange = { enablePhotic = it })
            }

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
                            service.stopAudio()
                            isPlaying = false
                        } else {
                            val intent = Intent(context, BinauralAudioService::class.java)
                            context.startForegroundService(intent)
                            service.carrierFreq = carrier.toDouble()
                            service.beatFreq = beat.toDouble()
                            service.soundMode = soundMode
                            service.startAudio()
                            isPlaying = true
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
                            soundMode = try {
                                SoundMode.valueOf(preset.soundMode)
                            } catch (e: IllegalArgumentException) {
                                SoundMode.BINAURAL
                            }

                            audioService?.let { service ->
                                service.carrierFreq = preset.carrierHz
                                service.beatFreq = preset.startBeatHz
                                service.soundMode = soundMode
                            }
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
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val outFile = File(context.cacheDir, "${preset.title.replace(" ", "_")}.wav")
                                                WavExporter.exportToWav(context, preset, outFile)
                                                launch(Dispatchers.Main) {
                                                    try {
                                                        val uri = FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            outFile
                                                        )
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
                                            } catch (e: Exception) {
                                                Log.e("MainActivity", "Failed to export WAV file", e)
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "Export failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                                }
                                            }
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
