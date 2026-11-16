package com.thestudypath.pdf.ui

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.dimensionResource
import com.rizzi.bouquet.VerticalPdfReader
import com.rizzi.bouquet.VerticalPdfReaderState
import com.thestudypath.pdf.R

/**
 * The fallback viewer: the pages one under the other, scrolled vertically, with pinch and
 * double-tap zoom. The reading is Bouquet's (Android's own `PdfRenderer` underneath); this only
 * gives it the reader's page gaps and night mode. Loading and failure are drawn by the reader's
 * own overlay, so Bouquet's are left empty.
 *
 * @param nightPaint the page's night-mode paint, the same one the AndroidX viewer is given.
 */
@Composable
internal fun PdfBuiltInPages(
    state: VerticalPdfReaderState,
    nightPaint: Paint?,
    modifier: Modifier = Modifier,
) {
    VerticalPdfReader(
        state = state,
        modifier =
            modifier
                .fillMaxSize()
                // The gaps are part of the page area: night mode darkens them with it.
                .nightLayer(nightPaint)
                .background(ViewportBackground),
        pageSpacing = dimensionResource(R.dimen.pdf_page_separator),
        loading = {},
        error = {},
    )
}

/** Draws everything inside through [paint], as the AndroidX viewer's layer paint does. */
private fun Modifier.nightLayer(paint: Paint?): Modifier =
    if (paint == null) {
        this
    } else {
        drawWithContent {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.saveLayer(0f, 0f, size.width, size.height, paint)
                drawContent()
                canvas.nativeCanvas.restore()
            }
        }
    }

// @color/pdf_viewport_background: independent of the app theme, darkened by the night filter.
private val ViewportBackground = Color(0xFFE0E0E0)
