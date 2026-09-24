package dev.periy.bridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons the standard set does not have, drawn on the same 24-unit grid. */
object BlazeIcons {
    /** A trackpad: a rounded pad with its two buttons underneath. */
    val Trackpad: ImageVector = stroked(
        "trackpad",
        "M6,3 H18 A3,3 0 0 1 21,6 V14 A3,3 0 0 1 18,17 H6 A3,3 0 0 1 3,14 V6 A3,3 0 0 1 6,3 Z",
        "M6,21 H11",
        "M13,21 H18",
    )

    /** A pulse line, for the live monitor. */
    val Pulse: ImageVector = stroked("pulse", "M2,12 H6 L9,5 L14,19 L17,10 L19,12 H22")

    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
