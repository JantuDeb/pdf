package com.thestudypath.pdf

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresExtension
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfWriteHandle
import androidx.pdf.ink.EditablePdfViewerFragment
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

    private var pendingOutputFile: File? = null

    // Callbacks for the activity
    var onSaveComplete: (() -> Unit)? = null
    var onSaveError: ((Throwable) -> Unit)? = null

    // UI callbacks — activity uses these to toggle save button / theme switch visibility
    var onEditModeEntered: (() -> Unit)? = null
    var onEditModeExited: (() -> Unit)? = null

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        _isDocumentLoaded = true
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
        onEditModeEntered?.invoke()
    }

    override fun onExitEditMode() {
        super.onExitEditMode()
        Log.d(TAG, "Edit mode exited")
        onEditModeExited?.invoke()
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
        _isDocumentLoaded = false
        pendingOutputFile = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "EditablePdfFragment"
    }
}
