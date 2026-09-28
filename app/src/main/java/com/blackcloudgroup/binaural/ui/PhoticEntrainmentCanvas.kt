package com.blackcloudgroup.binaural.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun PhoticEntrainmentCanvas(beatFreqHz: Double, isEnabled: Boolean) {
    if (!isEnabled) return

    val infiniteTransition = rememberInfiniteTransition(label = "photic")
    val durationMillis = (1000.0 / beatFreqHz).toInt().coerceAtLeast(16)

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Cyan.copy(alpha = alpha))
    )
}
