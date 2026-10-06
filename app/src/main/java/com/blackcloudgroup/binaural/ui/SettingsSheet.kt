package com.blackcloudgroup.binaural.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blackcloudgroup.binaural.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    logToHealthConnect: Boolean,
    onLogToHealthConnectChange: (Boolean) -> Unit,
    healthStatus: StatusLine?,
    onRestoreDefaults: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            Column {
                Text("Appearance", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Day", ThemeMode.DARK to "Night").forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Log sessions to Health Connect", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Sessions of a minute or more are saved as mindfulness sessions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Switch(checked = logToHealthConnect, onCheckedChange = onLogToHealthConnectChange)
                }
                healthStatus?.let {
                    Text(
                        it.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.isError) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Column {
                Text("Presets", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Adds back any built-in preset you deleted. Your own presets aren't changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                OutlinedButton(onClick = onRestoreDefaults, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Restore built-in presets")
                }
            }

            TextButton(onClick = onOpenPrivacyPolicy, contentPadding = PaddingValues(0.dp)) {
                Text("Privacy policy")
            }
        }
    }
}
