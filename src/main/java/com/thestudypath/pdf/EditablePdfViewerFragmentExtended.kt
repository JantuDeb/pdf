package com.thestudypath.pdf

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ProgressBar
import androidx.annotation.RequiresExtension
import androidx.lifecycle.lifecycleScope
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.PdfWriteHandle
import androidx.pdf.event.PdfTrackingEvent
import androidx.pdf.event.RequestFailureEvent
import androidx.pdf.exceptions.RequestFailedException
import androidx.pdf.ink.EditablePdfViewerFragment
import androidx.pdf.view.PdfView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    private var hasReportedRequestFailure = false

    var initialPage: Int = 0
    var onPageChanged: ((Int) -> Unit)? = null

    // Callbacks for the activity
    var onSaveComplete: (() -> Unit)? = null
    var onSaveError: ((Throwable) -> Unit)? = null

    // UI callbacks — activity uses these to toggle save button / theme switch visibility
    var onEditModeEntered: (() -> Unit)? = null
    var onEditModeExited: (() -> Unit)? = null
    var onDocumentLoadError: ((Throwable) -> Unit)? = null
    var onDocumentRequestFailed: ((Throwable) -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
            tintLoadingIndicator(view)
            view.post { applyDefaultPenSettings() }
            view.post { keepEditFabVisible() }
        }
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        _isDocumentLoaded = true
        hasReportedRequestFailure = false
        if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
            view?.post { keepEditFabVisible(EDIT_FAB_VISIBILITY_RETRY_COUNT) }
        }
        if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
            val targetPage = initialPage.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
            if (targetPage == 0) {
                emitPageChanged(targetPage)
            } else {
                pendingInitialPage = targetPage
                scrollToInitialPageWhenReady(targetPage)
            }
        }
        Log.d(TAG, "Document loaded: ${document.pageCount} pages")
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        _isDocumentLoaded = false
        Log.e(TAG, "Document load error: $error")
        onDocumentLoadError?.invoke(error)
    }

    override fun onEnterEditMode() {
        super.onEnterEditMode()
        Log.d(TAG, "Edit mode entered")
        if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
            viewer?.post { applyDefaultPenSettings() }
        }
        onEditModeEntered?.invoke()
    }

    override fun onExitEditMode() {
        super.onExitEditMode()
        Log.d(TAG, "Edit mode exited")
        onEditModeExited?.invoke()
        if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
            view?.post { keepEditFabVisible() }
        }
    }

    override fun onRequestImmersiveMode(enterImmersive: Boolean) {
        super.onRequestImmersiveMode(enterImmersive)
        if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS && !isEditModeEnabled) {
            view?.post { keepEditFabVisible() }
        }
    }

    @SuppressLint("RestrictedApi")
    @OptIn(ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        viewer = pdfView
        if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
            val defaultRequestFailedListener = pdfView.requestFailedListener
            pdfView.requestFailedListener = object : PdfView.EventListener {
                override fun onEvent(event: PdfTrackingEvent) {
                    defaultRequestFailedListener?.onEvent(event)
                    if (event is RequestFailureEvent && !hasReportedRequestFailure) {
                        hasReportedRequestFailure = true
                        Log.e(TAG, "PDF request failed after document load", event.exception)
                        onDocumentRequestFailed?.invoke(event.exception.asReportableRequestFailure())
                    }
                }
            }
        }
        viewportChangedListener = object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: SparseArray<RectF>,
                zoomLevel: Float,
            ) {
                if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
                    val pendingPage = pendingInitialPage
                    if (pendingPage != null && firstVisiblePage != pendingPage) {
                        return
                    }
                }
                if (ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS) {
                    keepEditFabVisible()
                }
                emitPageChanged(firstVisiblePage)
            }
        }
        viewportChangedListener?.let { listener ->
            pdfView.addOnViewportChangedListener(listener)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun Throwable.asReportableRequestFailure(): Throwable {
        val requestError = this as? RequestFailedException ?: return this
        return RuntimeException(requestError.toString(), requestError.throwable)
    }

    private fun emitPageChanged(page: Int) {
        if (lastReportedPage == page) return
        lastReportedPage = page
        onPageChanged?.invoke(page)
    }

    @SuppressLint("DiscouragedApi")
    private fun tintLoadingIndicator(rootView: View) {
        val progressBar = rootView.findViewById<ProgressBar>(
            requireContext().resources.getIdentifier(
                "pdfLoadingProgressBar",
                "id",
                requireContext().packageName,
            )
        ) ?: return
        progressBar.indeterminateTintList = ColorStateList.valueOf(resolveThemeColor())
    }

    private fun resolveThemeColor(): Int {
        val typedValue = TypedValue()
        val theme = requireContext().theme
        val colorAttr = androidx.appcompat.R.attr.colorPrimary
        return if (theme.resolveAttribute(colorAttr, typedValue, true)) {
            typedValue.data
        } else {
            requireContext().getColor(android.R.color.holo_blue_light)
        }
    }

    private fun scrollToInitialPageWhenReady(targetPage: Int) {
        val pdfView = viewer ?: return
        pdfView.runWhenMeasured {
            pdfView.post {
                if (!isAdded || view == null || pendingInitialPage != targetPage) return@post
                if (pdfView.width <= 0 || pdfView.height <= 0) {
                    scrollToInitialPageWhenReady(targetPage)
                    return@post
                }
                pdfView.scrollToPage(targetPage)
                emitPageChanged(targetPage)
                pendingInitialPage = null
            }
        }
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

    private fun keepEditFabVisible(attemptsRemaining: Int = 0) {
        if (isEditModeEnabled) return
        val root = view ?: return
        isToolboxVisible = true
        if (!isToolboxVisible && attemptsRemaining > 0) {
            root.postDelayed(
                { keepEditFabVisible(attemptsRemaining - 1) },
                EDIT_FAB_VISIBILITY_RETRY_DELAY_MS,
            )
        }
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
            val tempOutputFile = File(
                outputFile.parentFile,
                "${outputFile.name}.annotations.${System.nanoTime()}.tmp",
            )
            try {
                handle.use { writeHandle ->
                    ParcelFileDescriptor.open(
                        tempOutputFile,
                        ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                ParcelFileDescriptor.MODE_TRUNCATE
                    ).use { fd ->
                        writeHandle.writeTo(fd)
                    }
                }
                replaceFile(tempOutputFile, outputFile)

                withContext(Dispatchers.Main) {
                    isEditModeEnabled = false
                }

                Log.d(TAG, "Annotations saved -> ${outputFile.absolutePath}")

                withContext(Dispatchers.Main) {
                    onSaveComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onSaveError?.invoke(e)
                }
            } finally {
                tempOutputFile.delete()
                pendingOutputFile = null
            }
        }
    }

    private fun replaceFile(sourceFile: File, targetFile: File) {
        try {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
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
        onDocumentLoadError = null
        onDocumentRequestFailed = null
        hasReportedRequestFailure = false
        super.onDestroyView()
    }

    private fun View.runWhenMeasured(action: () -> Unit) {
        if (width > 0 && height > 0) {
            action()
            return
        }

        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (width > 0 && height > 0) {
                    if (viewTreeObserver.isAlive) {
                        viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    action()
                }
                return true
            }
        })
    }

    companion object {
        /**
         * Flip to false for release/internal-test builds that should use AndroidX PDF with no
         * StudyPath hooks into its internal fragment/PdfView implementation.
         */
        private const val ENABLE_ANDROIDX_PDF_INTERNAL_CUSTOMIZATIONS = true
        private const val TAG = "EditablePdfFragment"
        private const val DEFAULT_PEN_COLOR = 0xFF202FB0.toInt()
        private const val DEFAULT_PEN_COLOR_INDEX = 1
        private const val DEFAULT_PEN_THICKNESS = 8f
        private const val DEFAULT_PEN_THICKNESS_INDEX = 0
        private const val EDIT_FAB_VISIBILITY_RETRY_COUNT = 8
        private const val EDIT_FAB_VISIBILITY_RETRY_DELAY_MS = 150L
    }
}
