package com.thestudypath.pdf.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.thestudypath.pdf.interfaces.PdfReaderTool

/** What the Compose reader's bars and messages are drawn from. */
@Immutable
data class PdfReaderState(
    val title: String,
    val subtitle: String? = null,
    val status: PdfReaderStatus = PdfReaderStatus.Loading,
    /** The page on screen, counted from 0. */
    val page: Int = 0,
    /** 0 until the document has loaded. */
    val pageCount: Int = 0,
    val isEditing: Boolean = false,
    /** Annotations are being written: the save button waits. */
    val isSaving: Boolean = false,
    val isNight: Boolean = false,
    val isFullScreen: Boolean = false,
    val isBookmarked: Boolean = false,
    val showSaveAnnotations: Boolean = false,
    val showNightToggle: Boolean = false,
    val showSearch: Boolean = false,
    val showFullscreen: Boolean = false,
    val showDownload: Boolean = false,
    val showBookmark: Boolean = false,
    val showPageIndicator: Boolean = true,
    /** The overflow menu's entries. Empty: no overflow button. */
    val menu: List<PdfMenuEntry> = emptyList(),
    /** The bottom tools, in order. Empty: no bottom bar. */
    val tools: List<PdfReaderTool> = emptyList(),
)

sealed interface PdfReaderStatus {
    /** The file is being fetched, decrypted or opened. */
    data object Loading : PdfReaderStatus

    data object Ready : PdfReaderStatus

    /** Nothing could be shown. [message] says why, in the reader's own words or the host's. */
    data class Failed(
        val message: String,
    ) : PdfReaderStatus
}

/** One line of the overflow menu. [id] is the menu item's id, the host's or the reader's own. */
@Immutable
data class PdfMenuEntry(
    val id: Int,
    val title: String,
)

/** What the bars can ask the reader to do. */
@Stable
class PdfReaderActions(
    val back: () -> Unit,
    val exitEditMode: () -> Unit,
    val saveAnnotations: () -> Unit,
    val toggleNight: () -> Unit,
    val search: () -> Unit,
    val fullscreen: () -> Unit,
    val download: () -> Unit,
    val menu: (PdfMenuEntry) -> Unit,
    val tool: (PdfReaderTool) -> Unit,
    val retry: () -> Unit,
    val goToPage: (Int) -> Unit,
)
