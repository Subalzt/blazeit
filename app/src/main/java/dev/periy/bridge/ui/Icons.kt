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

    /** A sun. */
    val Sun: ImageVector = stroked(
        "sun",
        "M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8Z",
        "M12 2.5v2", "M12 19.5v2", "M2.5 12h2", "M19.5 12h2",
        "M5.3 5.3l1.4 1.4", "M17.3 17.3l1.4 1.4", "M5.3 18.7l1.4-1.4", "M17.3 6.7l1.4-1.4",
    )

    /** A crescent. */
    val Moon: ImageVector = stroked("moon", "M19.5 14.5A8 8 0 1 1 9.5 4.5a6.5 6.5 0 0 0 10 10Z")

    /** A chevron pointing on. */
    val Chevron: ImageVector = stroked("chevron", "M9.5,5.5 L16,12 L9.5,18.5")

    /** A link: typing an address. */
    val Link: ImageVector = stroked(
        "link",
        "M10,14 A4,4 0 0 0 15.66,14 L18.49,11.17 A4,4 0 0 0 12.83,5.51 L11.5,6.84",
        "M14,10 A4,4 0 0 0 8.34,10 L5.51,12.83 A4,4 0 0 0 11.17,18.49 L12.5,17.16",
    )

    /** A lightning bolt: the direct link. */
    val Bolt: ImageVector = stroked("bolt", "M13.5 2.5 5 13.5h6.5l-1 8 8.5-11h-6.5l1-8Z")

    /** Waves over a dot: the phone's hotspot. */
    val Hotspot: ImageVector = stroked(
        "hotspot",
        "M12 13.2a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6Z",
        "M8.2 11.6a5.4 5.4 0 0 1 7.6 0",
        "M5.2 8.6a9.6 9.6 0 0 1 13.6 0",
        "M12 16v4.5",
    )

    /** A power symbol: BlazeIt on and off. */
    val Power: ImageVector = stroked("power", "M12 3v8.5", "M6.9 6.4a7.5 7.5 0 1 0 10.2 0")

    /** A square of squares: show the QR code. */
    val Qr: ImageVector = stroked(
        "qr",
        "M4 4h6v6H4Z", "M14 4h6v6h-6Z", "M4 14h6v6H4Z",
        "M14 14h2.5", "M20 14v2.5", "M14 20h6", "M17 17h3", "M14 17v3",
    )

    /** A laptop. */
    val Laptop: ImageVector = stroked(
        "laptop",
        "M5.5 5.5h13a1 1 0 0 1 1 1V16h-15V6.5a1 1 0 0 1 1-1Z",
        "M2.5 18.5h19",
    )

    /** A document: a file on the phone. */
    val File: ImageVector = stroked(
        "file",
        "M13.5 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8.5Z",
        "M13.5 3.5v5h5",
    )

    /** An arrow coming down into a tray: received. */
    val Download: ImageVector = stroked(
        "download",
        "M12 4v11", "M7.5 10.5 12 15l4.5-4.5",
        "M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4",
    )

    /** A cross. */
    val Close: ImageVector = stroked("close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11")

    /** A tick. */
    val Check: ImageVector = stroked("check", "M5 12.5 10 17.5 19 7")

    /** A speech bubble: send text. */
    val Message: ImageVector = stroked(
        "message",
        "M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 17h-8l-4.5 3.5V17H5a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 5 5.5Z",
    )

    /** A half-filled circle: appearance. */
    val Contrast: ImageVector = stroked(
        "contrast",
        "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17Z",
        "M12 3.5v17",
        "M12 7.5h4.5", "M12 11.5h5.5", "M12 15.5h4.5",
    )

    /** Media keys: previous track, play or pause, next track. */
    val Prev: ImageVector = stroked("prev", "M6.5 6v12", "M18 6l-8.5 6 8.5 6Z")
    val Next: ImageVector = stroked("next", "M17.5 6v12", "M6 6l8.5 6-8.5 6Z")
    val PlayPause: ImageVector = stroked("playpause", "M4.5 6l7.5 6-7.5 6Z", "M15.5 6.5v11", "M19.5 6.5v11")

    /** A speaker with one wave, two waves, or crossed out: volume down, up, mute. */
    val VolumeDown: ImageVector = stroked("voldown", "M4 9.5h3.5L12 5.5v13l-4.5-4H4Z", "M15.5 9.5a3.5 3.5 0 0 1 0 5")
    val VolumeUp: ImageVector = stroked(
        "volup", "M4 9.5h3.5L12 5.5v13l-4.5-4H4Z", "M15.5 9.5a3.5 3.5 0 0 1 0 5", "M18 7a7 7 0 0 1 0 10",
    )
    val Mute: ImageVector = stroked("mute", "M4 9.5h3.5L12 5.5v13l-4.5-4H4Z", "M16 9.5l5 5", "M21 9.5l-5 5")

    /** A clock turning back: the clipboard's history. */
    val History: ImageVector = stroked(
        "history",
        "M4 12a8 8 0 1 0 2.4-5.7",
        "M4 4.5v4h4",
        "M12 7.5V12l3 2",
    )

    /** A framed landscape: a picture. */
    val Image: ImageVector = stroked(
        "image",
        "M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5Z",
        "M3.5 16l5-5 4 4 3-3 5 5",
        "M15.5 8.2a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0-2.4Z",
    )

    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
