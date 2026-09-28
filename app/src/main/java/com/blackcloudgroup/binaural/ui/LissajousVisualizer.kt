package com.blackcloudgroup.binaural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun LissajousVisualizer(
    carrierHz: Double,
    beatHz: Double,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(220.dp)
) {
    var timePhase by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(isPlaying, carrierHz, beatHz) {
        if (isPlaying) {
            while (true) {
                timePhase += 0.03
                withFrameNanos { }
            }
        }
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) * 0.8f

        if (!isPlaying) {
            drawCircle(
                color = Color.DarkGray,
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2f)
            )
            return@Canvas
        }

        val leftFreq = carrierHz - (beatHz / 2.0)
        val rightFreq = carrierHz + (beatHz / 2.0)
        val freqRatio = rightFreq / leftFreq

        val path = Path()
        val pointCount = 300

        for (i in 0..pointCount) {
            val t = (i.toDouble() / pointCount) * (2.0 * Math.PI)
            val x = centerX + radius * sin(t * freqRatio + timePhase).toFloat()
            val y = centerY + radius * sin(t).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = Color(0xFF3B82F6),
            style = Stroke(width = 3f)
        )
    }
}
