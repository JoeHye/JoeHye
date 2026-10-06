package com.blackcloudgroup.binaural.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons the small material-icons-core set lacks, drawn from the mockup's SVG paths (24×24 grid).
 * Fill/stroke colour is a placeholder; Icon() tints with the content colour.
 */
object AppIcons {

    private fun filled(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()

    private fun stroked(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()

    val Play: ImageVector = filled("Play", "M8 5.5v13a1 1 0 0 0 1.5.86l10.4-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z")

    val Pause: ImageVector = filled(
        "Pause",
        "M7.2 5h1.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2H7.2A1.2 1.2 0 0 1 6 17.8V6.2A1.2 1.2 0 0 1 7.2 5z",
        "M15.2 5h1.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2V6.2A1.2 1.2 0 0 1 15.2 5z"
    )

    val Stop: ImageVector = filled("Stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z")

    val Tune: ImageVector = stroked(
        "Tune",
        "M4 7h10M18 7h2M4 17h4M12 17h8",
        "M14 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        "M8 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
    )

    val Headphones: ImageVector = stroked(
        "Headphones",
        "M3 18v-6a9 9 0 0 1 18 0v6",
        "M21 19a2 2 0 0 1-2 2h-1v-6h3zM3 19a2 2 0 0 0 2 2h1v-6H3z"
    )
}
