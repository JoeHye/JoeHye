package com.blackcloudgroup.binaural.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blackcloudgroup.binaural.SoundMode
import com.blackcloudgroup.binaural.audio.parseSoundMode
import com.blackcloudgroup.binaural.data.PresetEntity

@Composable
/**
 * Create or edit a preset. [initial] pre-fills every field; its id is kept on save, so editing
 * an existing preset (id != 0) replaces it in place instead of adding a copy.
 */
fun PresetDialog(
    initial: PresetEntity,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (PresetEntity) -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var carrier by remember { mutableFloatStateOf(initial.carrierHz.toFloat().coerceIn(100f, 500f)) }
    var startBeat by remember { mutableFloatStateOf(initial.startBeatHz.toFloat().coerceIn(0.5f, 40f)) }
    var targetBeat by remember { mutableFloatStateOf(initial.targetBeatHz.toFloat().coerceIn(0.5f, 40f)) }
    var duration by remember { mutableIntStateOf(initial.durationMinutes.coerceIn(1, 60)) }
    var selectedMode by remember { mutableStateOf(parseSoundMode(initial.soundMode)) }
    var enablePinkNoise by remember { mutableStateOf(initial.enablePinkNoise) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Preset" else "Save Preset") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Preset Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Sound Mode", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FilterChip(
                        selected = selectedMode == SoundMode.HEMI_SYNC,
                        onClick = { selectedMode = SoundMode.HEMI_SYNC },
                        label = { Text("Hemi-Sync") }
                    )
                    FilterChip(
                        selected = selectedMode == SoundMode.BINAURAL,
                        onClick = { selectedMode = SoundMode.BINAURAL },
                        label = { Text("Binaural") }
                    )
                    FilterChip(
                        selected = selectedMode == SoundMode.ISOCHRONIC,
                        onClick = { selectedMode = SoundMode.ISOCHRONIC },
                        label = { Text("Isochronic") }
                    )
                }

                Text("Carrier Frequency: ${carrier.toInt()} Hz")
                Slider(
                    value = carrier,
                    onValueChange = { carrier = it },
                    valueRange = 100f..500f
                )

                Text("Start Beat: ${String.format("%.1f", startBeat)} Hz")
                Slider(
                    value = startBeat,
                    onValueChange = { startBeat = it },
                    valueRange = 0.5f..40f
                )

                Text("Target Beat (Ramp End): ${String.format("%.1f", targetBeat)} Hz")
                Slider(
                    value = targetBeat,
                    onValueChange = { targetBeat = it },
                    valueRange = 0.5f..40f
                )

                Text("Duration: $duration Minutes")
                Slider(
                    value = duration.toFloat(),
                    onValueChange = { duration = it.toInt() },
                    valueRange = 1f..60f
                )

                if (selectedMode == SoundMode.HEMI_SYNC) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pink Noise Masking")
                        Switch(checked = enablePinkNoise, onCheckedChange = { enablePinkNoise = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    val newPreset = initial.copy(
                        title = title.trim(),
                        startBeatHz = startBeat.toDouble(),
                        targetBeatHz = targetBeat.toDouble(),
                        carrierHz = carrier.toDouble(),
                        durationMinutes = duration,
                        soundMode = selectedMode.name,
                        // The switch is only shown for Hemi-Sync; don't persist a hidden "on" for other modes.
                        enablePinkNoise = selectedMode == SoundMode.HEMI_SYNC && enablePinkNoise
                    )
                    onSave(newPreset)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
