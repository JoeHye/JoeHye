package com.blackcloudgroup.binaural.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blackcloudgroup.binaural.SoundMode
import kotlin.math.roundToInt

@Composable
fun CustomizeScreen(
    soundMode: SoundMode,
    onSoundModeChange: (SoundMode) -> Unit,
    beatHz: Float,
    rampTargetHz: Float?,
    onBeatChange: (Float) -> Unit,
    carrierHz: Float,
    onCarrierChange: (Float) -> Unit,
    pinkNoise: Boolean,
    onPinkNoiseChange: (Boolean) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    lightPulses: Boolean,
    onLightPulsesChange: (Boolean) -> Unit,
    onSaveAsPreset: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to now playing", tint = colors.onSurfaceVariant)
            }
            Text("Customize", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }

        Section {
            Text(
                "SOUND",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    SoundMode.HEMI_SYNC to "Hemi-Sync",
                    SoundMode.BINAURAL to "Binaural",
                    SoundMode.ISOCHRONIC to "Isochronic"
                ).forEach { (mode, label) ->
                    val selected = mode == soundMode
                    if (selected) {
                        Button(
                            onClick = { onSoundModeChange(mode) },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        ) { Text(label, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { onSoundModeChange(mode) },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
            Text(
                when (soundMode) {
                    SoundMode.HEMI_SYNC -> "Two layered tone pairs with optional noise. Needs headphones."
                    SoundMode.BINAURAL -> "A slightly different tone in each ear. Needs headphones."
                    SoundMode.ISOCHRONIC -> "The tone itself pulses on and off. Works on speakers."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Section {
            val band = bandOf(beatHz.toDouble())
            LabeledValue("Pulse rate", "${formatHz(beatHz.toDouble())} Hz")
            Text(
                "${band.name} · ${band.feel}" +
                    (rampTargetHz?.let { " · preset ramps to ${formatHz(it.toDouble())} Hz" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = colors.primary
            )
            Slider(
                value = beatHz,
                onValueChange = onBeatChange,
                valueRange = 0.5f..40f,
                modifier = Modifier.semantics { contentDescription = "Pulse rate" }
            )
            if (rampTargetHz != null) {
                Text(
                    "Moving this replaces the preset's ramp with a steady pulse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }

            LabeledValue("Tone", "${carrierHz.roundToInt()} Hz", modifier = Modifier.padding(top = 12.dp))
            Text(
                "Lower feels warmer; 150–300 Hz suits long listening",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Slider(
                value = carrierHz,
                onValueChange = onCarrierChange,
                valueRange = 100f..500f,
                modifier = Modifier.semantics { contentDescription = "Tone" }
            )
        }

        Section(vertical = 6.dp) {
            val noiseAvailable = soundMode == SoundMode.HEMI_SYNC
            SwitchRow(
                title = "Background noise",
                subtitle = if (noiseAvailable) "Soft pink noise under the tones" else "Available in Hemi-Sync mode",
                checked = pinkNoise && noiseAvailable,
                enabled = noiseAvailable,
                onCheckedChange = onPinkNoiseChange
            )
            HorizontalDivider(color = colors.surfaceVariant)
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                LabeledValue("Volume", "${(volume * 100).roundToInt()}%")
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.semantics { contentDescription = "Volume" }
                )
            }
            HorizontalDivider(color = colors.surfaceVariant)
            SwitchRow(
                title = "Light pulses",
                subtitle = "Flashes the screen with the beat. Not for anyone with photosensitive epilepsy.",
                checked = lightPulses,
                onCheckedChange = onLightPulsesChange
            )
        }

        Button(
            onClick = onSaveAsPreset,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text("Save as preset") }
    }
}

@Composable
private fun Section(vertical: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = vertical), content = content)
    }
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontFamily = SoraFamily))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
