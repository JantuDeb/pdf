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
    val Night = icon("Night", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z")
    val Ask = icon("Ask", "M7.9 20A9 9 0 1 0 4 16.1L3 21zM9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01")
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
