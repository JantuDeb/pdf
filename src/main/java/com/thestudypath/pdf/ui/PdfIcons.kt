package com.thestudypath.pdf.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The reader's icons: 24dp, 2dp rounded strokes, the shapes of the StudyPath design's icon
 * sheet. Tint them where they are used; the colour here is only a placeholder.
 */
object PdfIcons {
    val Back = icon("Back", "m15 6-6 6 6 6")
    val Close = icon("Close", "M6 6l12 12M18 6 6 18")
    val Check = icon("Check", "m5 12.5 4.5 4.5L19 7.5")
    val Search = icon("Search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4")
    val Bookmark = icon("Bookmark", "M7 4h10v16l-5-3.5L7 20z")
    val Pages = icon("Pages", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4")
    val Night = icon("Night", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7.5V12l3 2")
    val Ask =
        icon(
            "Ask",
            "M21 12a8 8 0 0 1-11.8 7L4 20l1.2-4.3A8 8 0 1 1 21 12zM12 8.5l.9 2.1 2.1.9-2.1.9-.9 2.1-.9-2.1L9 11.5l2.1-.9z",
        )
    val Save = icon("Save", "M12 4v11M7.5 11 12 15.5 16.5 11M5 20h14")
    val Next = icon("Next", "m9 6 6 6-6 6")

    // Not on the design's sheet (the design's reader has neither): drawn to the same rules.
    val Fullscreen = icon("Fullscreen", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5")
    val More = icon("More", "M12 5.5h.01M12 12h.01M12 18.5h.01")

    private fun icon(
        name: String,
        path: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ).build()
}
