package dev.periy.bridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** BlazeIt's own line icons: one 24-unit grid, one stroke weight, round ends. */
object BlazeIcons {
    /** A house. */
    val Home: ImageVector = stroked(
        "home",
        "M3.5,10.5 L12,3.5 L20.5,10.5",
        "M5.5,9 V19 A1.5,1.5 0 0 0 7,20.5 H17 A1.5,1.5 0 0 0 18.5,19 V9",
        "M10,20.5 V15 A2,2 0 0 1 14,15 V20.5",
    )

    /** A phone sending out waves either side: other phones nearby. */
    val Phones: ImageVector = stroked(
        "phones",
        "M10,3 H14 A2,2 0 0 1 16,5 V19 A2,2 0 0 1 14,21 H10 A2,2 0 0 1 8,19 V5 A2,2 0 0 1 10,3 Z",
        "M11,17.5 H13",
        "M5,9 A4.5,4.5 0 0 0 5,15",
        "M2.2,6.8 A8,8 0 0 0 2.2,17.2",
        "M19,9 A4.5,4.5 0 0 1 19,15",
        "M21.8,6.8 A8,8 0 0 1 21.8,17.2",
    )

    /** A trackpad: a rounded pad with its two buttons underneath. */
    val Trackpad: ImageVector = stroked(
        "trackpad",
        "M6,3 H18 A3,3 0 0 1 21,6 V14 A3,3 0 0 1 18,17 H6 A3,3 0 0 1 3,14 V6 A3,3 0 0 1 6,3 Z",
        "M6,21 H11",
        "M13,21 H18",
    )

    /** Two sliders, for settings. */
    val Sliders: ImageVector = stroked(
        "sliders",
        "M3.5,7.5 H13", "M18,7.5 H20.5",
        "M15.5,5 A2.5,2.5 0 1 1 15.5,10 A2.5,2.5 0 1 1 15.5,5 Z",
        "M3.5,16.5 H6", "M11,16.5 H20.5",
        "M8.5,14 A2.5,2.5 0 1 1 8.5,19 A2.5,2.5 0 1 1 8.5,14 Z",
    )

    /** A pulse line, for the live monitor. */
    val Pulse: ImageVector = stroked("pulse", "M2,12 H6 L9,5 L14,19 L17,10 L19,12 H22")

    /** An arrow rising out of a tray: send. */
    val Upload: ImageVector = stroked(
        "upload",
        "M12,15 V4", "M7.5,8.5 L12,4 L16.5,8.5",
        "M4,14 V18 A2,2 0 0 0 6,20 H18 A2,2 0 0 0 20,18 V14",
    )

    /** A clipboard: paste. */
    val Paste: ImageVector = stroked(
        "paste",
        "M9 5.5H7.5A1.5 1.5 0 0 0 6 7v12a1.5 1.5 0 0 0 1.5 1.5h9A1.5 1.5 0 0 0 18 19V7a1.5 1.5 0 0 0-1.5-1.5H15",
        "M10 3.5h4a1 1 0 0 1 1 1v1.5a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1Z",
    )

    /** Two sheets: copy. */
    val Copy: ImageVector = stroked(
        "copy",
        "M15 8.5V6a1.5 1.5 0 0 0-1.5-1.5H6A1.5 1.5 0 0 0 4.5 6v7.5A1.5 1.5 0 0 0 6 15h2.5",
        "M10 8.5h8a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 8.5 18v-8A1.5 1.5 0 0 1 10 8.5Z",
    )

    /** A bin: clear. */
    val Trash: ImageVector = stroked(
        "trash",
        "M4.5 7h15",
        "M9.5 7V5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v2",
        "M6.5 7l.8 11.5A1.5 1.5 0 0 0 8.8 20h6.4a1.5 1.5 0 0 0 1.5-1.5L17.5 7",
    )

    /** An arrow going up and away: send. */
    val Send: ImageVector = stroked("send", "M12 19V5.5", "M6.5 11 12 5.5 17.5 11")

    /** A chevron pointing on. */
    val Chevron: ImageVector = stroked("chevron", "M9.5,5.5 L16,12 L9.5,18.5")

    /** A link: typing an address. */
    val Link: ImageVector = stroked(
        "link",
        "M10,14 A4,4 0 0 0 15.66,14 L18.49,11.17 A4,4 0 0 0 12.83,5.51 L11.5,6.84",
        "M14,10 A4,4 0 0 0 8.34,10 L5.51,12.83 A4,4 0 0 0 11.17,18.49 L12.5,17.16",
    )

    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
