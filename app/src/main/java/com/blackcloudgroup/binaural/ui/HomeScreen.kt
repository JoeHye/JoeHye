package com.blackcloudgroup.binaural.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackcloudgroup.binaural.data.PresetEntity

/** One line under the controls: paused/stopped reasons, headphone hints, Health Connect results. */
data class StatusLine(val text: String, val isError: Boolean = false)

/** Everything the hero shows, computed by the caller from settings + live session progress. */
data class HeroState(
    val title: String,
    val beatHz: Double,
    val carrierHz: Double,
    val rampLabel: String,
    val timeLabel: String,
    val progress: Float,
    val modeLabel: String
)

@Composable
fun HomeScreen(
    hero: HeroState,
    isPlaying: Boolean,
    isActive: Boolean,
    serviceReady: Boolean,
    headphonesConnected: Boolean,
    needsHeadphones: Boolean,
    statusLines: List<StatusLine>,
    presets: List<PresetEntity>,
    selectedPresetId: Long?,
    exportInProgress: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onOpenCustomize: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseIsochronic: () -> Unit,
    onSelectPreset: (PresetEntity) -> Unit,
    onNewPreset: () -> Unit,
    onEditPreset: (PresetEntity) -> Unit,
    onExportPreset: (PresetEntity) -> Unit,
    onDeletePreset: (PresetEntity) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        // Upper part scrolls on short screens; the preset panel stays anchored at the bottom.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "BLACK CLOUD",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = SoraFamily, letterSpacing = 2.8.sp),
                    color = colors.onSurfaceVariant
                )
                OutlinedIconButton(
                    onClick = onOpenSettings,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = colors.onSurfaceVariant)
                }
            }

            HeroRing(hero = hero, isPlaying = isPlaying, modifier = Modifier.padding(top = 4.dp))

            Text(
                hero.title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Text(
                "${hero.rampLabel} · ${hero.timeLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val headphoneWarning = needsHeadphones && !headphonesConnected
                Pill(
                    text = if (headphonesConnected) "Headphones on" else "No headphones",
                    emphasized = headphoneWarning,
                    leading = {
                        Icon(AppIcons.Headphones, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
                Pill(text = hero.modeLabel)
            }

            Row(
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                OutlinedIconButton(
                    onClick = onStop,
                    enabled = isActive,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(AppIcons.Stop, contentDescription = "Stop", modifier = Modifier.size(20.dp))
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    enabled = serviceReady,
                    shape = CircleShape,
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        if (isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (isPlaying) "Pause" else if (isActive) "Resume" else "Play",
                        modifier = Modifier.size(32.dp)
                    )
                }
                OutlinedIconButton(
                    onClick = onOpenCustomize,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(AppIcons.Tune, contentDescription = "Customize", modifier = Modifier.size(22.dp))
                }
            }

            statusLines.forEach { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.isError) colors.error else colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp)
                )
            }
            if (needsHeadphones && !headphonesConnected) {
                TextButton(onClick = onUseIsochronic) { Text("Switch to Isochronic (works on speakers)") }
            }
            Spacer(Modifier.height(12.dp))
        }

        PresetPanel(
            presets = presets,
            selectedPresetId = selectedPresetId,
            exportInProgress = exportInProgress,
            onSelectPreset = onSelectPreset,
            onNewPreset = onNewPreset,
            onEditPreset = onEditPreset,
            onExportPreset = onExportPreset,
            onDeletePreset = onDeletePreset
        )
    }
}

@Composable
private fun HeroRing(hero: HeroState, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val band = bandOf(hero.beatHz)
    Box(modifier = modifier.size(268.dp), contentAlignment = Alignment.Center) {
        LissajousVisualizer(
            carrierHz = hero.carrierHz,
            beatHz = hero.beatHz,
            isPlaying = isPlaying,
            modifier = Modifier
                .size(212.dp)
                .alpha(0.45f)
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(colors.surfaceVariant, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (hero.progress > 0f) {
                drawArc(
                    colors.primary, -90f, 360f * hero.progress.coerceIn(0f, 1f), false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                band.name,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 42.sp, letterSpacing = (-0.5).sp),
                color = colors.onBackground
            )
            Text(
                "${formatHz(hero.beatHz)} Hz beat",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                band.feel,
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary
            )
        }
    }
}

@Composable
private fun Pill(text: String, emphasized: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (emphasized) colors.primaryContainer else colors.surface,
        contentColor = if (emphasized) colors.onPrimaryContainer else colors.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leading?.invoke()
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PresetPanel(
    presets: List<PresetEntity>,
    selectedPresetId: Long?,
    exportInProgress: Boolean,
    onSelectPreset: (PresetEntity) -> Unit,
    onNewPreset: () -> Unit,
    onEditPreset: (PresetEntity) -> Unit,
    onExportPreset: (PresetEntity) -> Unit,
    onDeletePreset: (PresetEntity) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val selectedGoal = presets.firstOrNull { it.id == selectedPresetId }?.let { PresetCatalog.goalOf(it) }
    var chosenGoal by rememberSaveable { mutableStateOf(selectedGoal ?: Goal.SLEEP) }
    // "My presets" only appears once the user has saved one.
    val goals = Goal.values().filter { g -> g != Goal.MINE || presets.any { PresetCatalog.goalOf(it) == Goal.MINE } }
    val goal = if (chosenGoal in goals) chosenGoal else Goal.SLEEP
    val shown = presets.filter { PresetCatalog.goalOf(it) == goal }

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(top = 16.dp, bottom = 18.dp)) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                goals.forEach { g ->
                    FilterChip(
                        selected = g == goal,
                        onClick = { chosenGoal = g },
                        label = { Text(g.label) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primary,
                            selectedLabelColor = colors.onPrimary
                        )
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                items(shown, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        selected = preset.id == selectedPresetId,
                        exportInProgress = exportInProgress,
                        onClick = { onSelectPreset(preset) },
                        onEdit = { onEditPreset(preset) },
                        onExport = { onExportPreset(preset) },
                        onDelete = { onDeletePreset(preset) }
                    )
                }
                item(key = "new") {
                    OutlinedCard(
                        onClick = onNewPreset,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.size(width = 120.dp, height = 148.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = colors.primary)
                            Text("New preset", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: PresetEntity,
    selected: Boolean,
    exportInProgress: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        border = BorderStroke(2.dp, if (selected) colors.primary else colors.surfaceContainerHigh),
        modifier = Modifier.size(width = 176.dp, height = 148.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Text(
                    preset.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 26.dp)
                )
                PresetCatalog.descriptionOf(preset)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(PresetCatalog.metaOf(preset), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options for ${preset.title}",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (exportInProgress) "Exporting…" else "Export audio (WAV)") },
                        enabled = !exportInProgress,
                        onClick = { menuOpen = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = colors.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = colors.error) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}
