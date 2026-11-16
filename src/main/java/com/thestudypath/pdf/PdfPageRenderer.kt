package com.thestudypath.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * Draws the pages of an unlocked PDF with Android's own renderer: what the built-in viewer shows
 * when AndroidX PDF is not available on the device or could not load the file. No native library
 * comes with it, and nothing but pictures of pages comes out of it: no search, no annotation.
 *
 * The platform opens one page at a time, so every call waits its turn.
 */
internal class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Height over width of each page, by index. */
    val aspectRatios: List<Float>,
) : Closeable {
    val pageCount: Int get() = aspectRatios.size

    private val lock = Mutex()
    private var closed = false

    /** The page at [index], [widthPx] wide, on white. Null once the renderer is closed. */
    suspend fun render(
        index: Int,
        widthPx: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed || index !in 0 until pageCount || widthPx <= 0) return@withLock null
                val heightPx = (widthPx * aspectRatios[index]).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                // A PDF page has no paper of its own.
                bitmap.eraseColor(Color.WHITE)
                renderer.openPage(index).use { page ->
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                bitmap
            }
        }

    /** Returns at once; the file is let go as soon as a page that is being drawn has finished. */
    override fun close() {
        CoroutineScope(Dispatchers.IO).launch { lock.withLock { closeLocked() } }
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * Opens [file], which must not need a password.
         *
         * @throws java.io.IOException or SecurityException when the platform cannot read it.
         */
        suspend fun open(file: File): PdfPageRenderer =
            withContext(Dispatchers.IO) {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val renderer = PdfRenderer(descriptor)
                    val ratios =
                        List(renderer.pageCount) { index ->
                            renderer.openPage(index).use { page ->
                                if (page.width > 0) page.height.toFloat() / page.width else 1.4142f
                            }
                        }
                    PdfPageRenderer(descriptor, renderer, ratios)
                } catch (e: Throwable) {
                    runCatching { descriptor.close() }
                    throw e
                }
            }
    }
}
