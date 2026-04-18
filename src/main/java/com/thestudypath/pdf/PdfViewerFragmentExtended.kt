package com.thestudypath.pdf

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresExtension
import androidx.pdf.PdfDocument
import androidx.pdf.viewer.fragment.PdfViewerFragment

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
class PdfViewerFragmentExtended : PdfViewerFragment() {
    override fun onLoadDocumentSuccess(document: PdfDocument) {
        Log.d("PdfViewerFragmentExtended", "onLoadDocumentSuccess: ${document.pageCount}")
    }

    override fun onLoadDocumentError(error: Throwable) {
       Log.d("PdfViewerFragmentExtended", "onLoadDocumentError: $error")
    }
}
