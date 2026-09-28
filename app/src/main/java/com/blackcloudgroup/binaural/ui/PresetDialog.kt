package com.blackcloudgroup.binaural.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blackcloudgroup.binaural.SoundMode
import com.blackcloudgroup.binaural.data.PresetEntity

@Composable
fun PresetDialog(
    onDismiss: () -> Unit,
    onSave: (PresetEntity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var carrier by remember { mutableFloatStateOf(200f) }
    var startBeat by remember { mutableFloatStateOf(10f) }
    var targetBeat by remember { mutableFloatStateOf(2f) }
    var duration by remember { mutableIntStateOf(20) }
    var selectedMode by remember { mutableStateOf(SoundMode.HEMI_SYNC) }
    var enablePinkNoise by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Custom Preset") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                    val newPreset = PresetEntity(
                        title = title.trim(),
                        startBeatHz = startBeat.toDouble(),
                        targetBeatHz = targetBeat.toDouble(),
                        carrierHz = carrier.toDouble(),
                        durationMinutes = duration,
                        soundMode = selectedMode.name,
                        enablePinkNoise = enablePinkNoise
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
