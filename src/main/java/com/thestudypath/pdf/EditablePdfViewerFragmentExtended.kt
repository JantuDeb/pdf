package com.thestudypath.pdf

import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseArray
import android.view.View
import androidx.annotation.RequiresExtension
import androidx.lifecycle.lifecycleScope
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.PdfWriteHandle
import androidx.pdf.ink.EditablePdfViewerFragment
import androidx.pdf.view.PdfView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extended EditablePdfViewerFragment with correct annotation save flow.
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
class EditablePdfViewerFragmentExtended : EditablePdfViewerFragment() {

    private var _isDocumentLoaded = false
    val isDocumentLoaded: Boolean get() = _isDocumentLoaded

    private var viewer: PdfView? = null
    private var pendingOutputFile: File? = null
    private var viewportChangedListener: PdfView.OnViewportChangedListener? = null
    private var pendingInitialPage: Int? = null
    private var lastReportedPage: Int? = null

    var initialPage: Int = 0
    var onPageChanged: ((Int) -> Unit)? = null

    // Callbacks for the activity
    var onSaveComplete: (() -> Unit)? = null
    var onSaveError: ((Throwable) -> Unit)? = null

    // UI callbacks — activity uses these to toggle save button / theme switch visibility
    var onEditModeEntered: (() -> Unit)? = null
    var onEditModeExited: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.post { applyDefaultPenSettings() }
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        _isDocumentLoaded = true
        val targetPage = initialPage.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
        pendingInitialPage = targetPage
        viewer?.post {
            if (!isAdded || view == null) return@post
            viewer?.scrollToPage(targetPage)
            emitPageChanged(targetPage)
            pendingInitialPage = null
        }
        Log.d(TAG, "Document loaded: ${document.pageCount} pages")
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        _isDocumentLoaded = false
        Log.e(TAG, "Document load error: $error")
    }

    override fun onEnterEditMode() {
        super.onEnterEditMode()
        Log.d(TAG, "Edit mode entered")
        viewer?.post { applyDefaultPenSettings() }
        onEditModeEntered?.invoke()
    }

    override fun onExitEditMode() {
        super.onExitEditMode()
        Log.d(TAG, "Edit mode exited")
        onEditModeExited?.invoke()
    }

    @OptIn(ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        viewer = pdfView
        viewportChangedListener = object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: SparseArray<RectF>,
                zoomLevel: Float,
            ) {
                val pendingPage = pendingInitialPage
                if (pendingPage != null && firstVisiblePage != pendingPage) {
                    return
                }
                emitPageChanged(firstVisiblePage)
            }
        }
        viewportChangedListener?.let { listener ->
            pdfView.addOnViewportChangedListener(listener)
        }
    }

    private fun emitPageChanged(page: Int) {
        if (lastReportedPage == page) return
        lastReportedPage = page
        onPageChanged?.invoke(page)
    }

    private fun applyDefaultPenSettings() {
        applyDefaultPenToDocumentViewModel()
        applyDefaultPenToToolbar()
    }

    private fun applyDefaultPenToDocumentViewModel() {
        runCatching {
            val viewModel = findNoArgMethod("getDocumentViewModel").invoke(this)
            val penTool = Class.forName("androidx.pdf.ink.view.tool.Pen")
                .getConstructor(Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(DEFAULT_PEN_THICKNESS, DEFAULT_PEN_COLOR)
            val toolInfoClass = Class.forName("androidx.pdf.ink.view.tool.AnnotationToolInfo")
            viewModel.javaClass
                .getDeclaredMethod($$"setCurrentToolInfo$pdf_ink", toolInfoClass)
                .apply { isAccessible = true }
                .invoke(viewModel, penTool)
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply default pen to document state", error)
        }
    }

    private fun applyDefaultPenToToolbar() {
        runCatching {
            val toolbar = javaClass.superclass
                ?.getDeclaredField("annotationToolbar")
                ?.apply { isAccessible = true }
                ?.get(this)
                ?: return@runCatching
            val viewModel = toolbar.javaClass
                .getDeclaredField("viewModel")
                .apply { isAccessible = true }
                .get(toolbar)
            val penPaletteItems = toolbar.javaClass
                .getDeclaredField("penPaletteItems")
                .apply { isAccessible = true }
                .get(toolbar) as List<*>
            val selectedPaletteItem = penPaletteItems[DEFAULT_PEN_COLOR_INDEX]
            val toolbarIntentClass = Class.forName("androidx.pdf.ink.view.state.ToolbarIntent")
            val colorIntent = Class
                .forName("androidx.pdf.ink.view.state.ToolbarIntent\$ColorSelected")
                .constructors
                .first { it.parameterTypes.size == 2 }
                .newInstance(DEFAULT_PEN_COLOR_INDEX, selectedPaletteItem)
            val brushIntent = Class
                .forName("androidx.pdf.ink.view.state.ToolbarIntent\$BrushSizeChanged")
                .constructors
                .first { it.parameterTypes.size == 1 }
                .newInstance(DEFAULT_PEN_THICKNESS_INDEX)

            viewModel.javaClass
                .getDeclaredMethod("onAction", toolbarIntentClass)
                .apply { isAccessible = true }
                .also { onAction ->
                    onAction.invoke(viewModel, colorIntent)
                    onAction.invoke(viewModel, brushIntent)
                }
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply default pen to toolbar", error)
        }
    }

    private fun findNoArgMethod(name: String): java.lang.reflect.Method {
        var type: Class<*>? = javaClass
        while (type != null) {
            runCatching {
                return type.getDeclaredMethod(name).apply { isAccessible = true }
            }
            type = type.superclass
        }
        throw NoSuchMethodException(name)
    }

    fun saveAnnotations(outputFile: File) {
        if (!hasUnsavedChanges) {
            Log.d(TAG, "No unsaved changes, skipping save")
            onSaveComplete?.invoke()
            return
        }

        pendingOutputFile = outputFile

        try {
            applyDraftEdits()
        } catch (e: Exception) {
            Log.e(TAG, "applyDraftEdits failed: ${e.message}", e)
            pendingOutputFile = null
            onSaveError?.invoke(e)
        }
    }

    override fun onApplyEditsSuccess(handle: PdfWriteHandle) {
        val outputFile = pendingOutputFile
        if (outputFile == null) {
            Log.e(TAG, "No output file set")
            handle.close()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fd = ParcelFileDescriptor.open(
                    outputFile,
                    ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_READ_WRITE or
                            ParcelFileDescriptor.MODE_TRUNCATE
                )

                handle.writeTo(fd)
                fd.close()
                handle.close()

                withContext(Dispatchers.Main) {
                    isEditModeEnabled = false
                }

                Log.d(TAG, "Annotations saved -> ${outputFile.absolutePath}")

                withContext(Dispatchers.Main) {
                    onSaveComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write failed: ${e.message}", e)
                handle.close()
                withContext(Dispatchers.Main) {
                    onSaveError?.invoke(e)
                }
            } finally {
                pendingOutputFile = null
            }
        }
    }

    override fun onApplyEditsFailed(error: Throwable) {
        Log.e(TAG, "Apply edits failed: ${error.message}", error)
        pendingOutputFile = null
        onSaveError?.invoke(error)
    }

    fun exitEditMode() {
        if (isEditModeEnabled) {
            isEditModeEnabled = false
        }
    }

    override fun onDestroyView() {
        viewportChangedListener?.let { listener ->
            runCatching { viewer?.removeOnViewportChangedListener(listener) }
        }
        viewportChangedListener = null
        viewer = null
        _isDocumentLoaded = false
        pendingOutputFile = null
        pendingInitialPage = null
        lastReportedPage = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "EditablePdfFragment"
        private const val DEFAULT_PEN_COLOR = 0xFF202FB0.toInt()
        private const val DEFAULT_PEN_COLOR_INDEX = 1
        private const val DEFAULT_PEN_THICKNESS = 8f
        private const val DEFAULT_PEN_THICKNESS_INDEX = 0
    }
}
