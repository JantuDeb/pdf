package com.thestudypath.pdf

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresExtension
import androidx.pdf.PdfDocument
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
class PdfViewerFragmentExtended : PdfViewerFragment() {
    @OptIn(ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        pdfView.setBackgroundResource(R.color.pdf_viewport_background)
        val pageSpacing = resources.getDimensionPixelSize(R.dimen.pdf_page_separator)
        pdfView.verticalPageSpacing = pageSpacing
        pdfView.horizontalPageSpacing = pageSpacing
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        Log.d("PdfViewerFragmentExtended", "onLoadDocumentSuccess: ${document.pageCount}")
    }

    override fun onLoadDocumentError(error: Throwable) {
       Log.d("PdfViewerFragmentExtended", "onLoadDocumentError: $error")
    }
}
