package com.thestudypath.pdf.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.thestudypath.pdf.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The built-in viewer: every page under the one before, scrolled vertically, each as wide as the
 * screen until it is pinched or double-tapped larger.
 *
 * @param aspectRatios height over width of each page.
 * @param renderPage draws a page at a width in pixels; null when it cannot.
 * @param nightPaint the page's night-mode paint, the same one the AndroidX viewer is given.
 */
@Composable
internal fun PdfBuiltInPages(
    aspectRatios: List<Float>,
    listState: LazyListState,
    nightPaint: Paint?,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val horizontal = rememberScrollState()
    // While fingers move the pages only stretch; they are drawn again, sharp, once they let go.
    var zoom by remember { mutableFloatStateOf(1f) }
    var drawnZoom by remember { mutableFloatStateOf(1f) }
    val gap = dimensionResource(R.dimen.pdf_page_separator)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // The gaps are part of the page area: night mode darkens them with it.
            .background(ViewportBackground)
            .nightLayer(nightPaint)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        // Before the list sees them: two fingers stretch the pages, they do not scroll them.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val before = zoom
                            zoom = (zoom * event.calculateZoom()).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            // Keep what is under the fingers under the fingers, sideways.
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (before != zoom) {
                                val shift = (horizontal.value + centroid.x) * (zoom / before - 1f)
                                scope.launch { horizontal.scrollBy(shift) }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    drawnZoom = zoom
                }
            }.pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM
                        drawnZoom = zoom
                    },
                )
            },
    ) {
        val viewportWidthPx = constraints.maxWidth
        val viewportWidth = maxWidth
        // Sharp up to twice the screen; past that the picture stretches, to keep memory in hand.
        val renderWidthPx = (viewportWidthPx * drawnZoom).roundToInt().coerceAtMost(viewportWidthPx * MAX_BITMAP_SCALE)
        Box(Modifier.horizontalScroll(horizontal, enabled = zoom > 1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(viewportWidth * zoom),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(aspectRatios.size, key = { it }) { index ->
                    val description = stringResource(R.string.pdf_page_number, index + 1)
                    val bitmap by produceState<Bitmap?>(null, index, renderWidthPx) {
                        value = renderPage(index, renderWidthPx) ?: value
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / aspectRatios[index])
                            .background(Color.White)
                            .semantics { contentDescription(description) },
                    ) {
                        bitmap?.let {
                            Image(
                                it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds,
                            )
                        }
                    }
                }
            }
        }
    }
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

private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.contentDescription(text: String) {
    this[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] = listOf(text)
}

// @color/pdf_viewport_background: independent of the app theme, darkened by the night filter.
private val ViewportBackground = Color(0xFFE0E0E0)
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2f
private const val MAX_BITMAP_SCALE = 2
