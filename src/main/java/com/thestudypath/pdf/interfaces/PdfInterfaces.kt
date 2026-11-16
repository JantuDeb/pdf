package com.thestudypath.pdf.interfaces

import android.net.Uri
import android.view.View
import com.thestudypath.pdf.PdfConfig
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Annotation saving strategy — each host app provides its own implementation
// ─────────────────────────────────────────────────────────────────────────────

interface PdfAnnotationSaver {
    /**
     * Called on IO dispatcher after the fragment writes annotations to [workingFile].
     * The implementation decides how to persist it (re-encrypt, copy, upload, etc.)
     *
     * @param workingFile  The decrypted working copy with annotations baked in.
     * @param originalFile The original (possibly encrypted) file on disk.
     * @param password     The password used to decrypt, if any.
     * @return Result.success or Result.failure with an exception.
     */
    suspend fun save(workingFile: File, originalFile: File, password: String?): Result<Unit>
}

// ─────────────────────────────────────────────────────────────────────────────
// Lifecycle / UI callbacks — host app plugs in ads, analytics, menus, etc.
// ─────────────────────────────────────────────────────────────────────────────

interface PdfActivityCallbacks {
    /** Called once after the PDF is opened. Good place for analytics. */
    fun onPdfOpened(config: PdfConfig) {}

    /**
     * Called once the document is on screen and its page count is known, whichever viewer
     * opened it.
     */
    fun onDocumentLoaded(pageCount: Int) {}

    /** Called every time the visible page changes. Good place to persist last-read page. */
    fun onPageChanged(page: Int) {}

    /** Called when edit mode is entered or exited. */
    fun onEditModeChanged(isEditing: Boolean) {}

    /** Called when the user taps save and it succeeds. */
    fun onAnnotationsSaved() {}

    /** Called when annotations fail to save. */
    fun onAnnotationSaveFailed(error: Throwable) {}

    /** Called when the AndroidX PDF viewer reports a recoverable load or render/fetch failure. */
    fun onPdfViewerError(error: Throwable, recovery: PdfViewerRecovery) {}

    /** Called when the active viewer ultimately fails to open/render the PDF. */
    fun onPdfOpenFailed(error: Throwable, viewer: PdfViewerType) {}

    /** Called after a PDF download completes successfully. */
    fun onDownloadSucceeded(displayName: String, fileUri: Uri) {}

    /**
     * Return a View to display in the ad container at the top, or null for no ads.
     * The activity will call this once in onCreate.
     */
    fun getAdView(): View? = null

    /**
     * Called when a popup-menu action is tapped.
     * Return true if you handled it; false to let the activity do the default.
     */
    fun onMenuAction(action: PdfMenuAction): Boolean = false

    /**
     * Optional custom popup menu resource for the overflow button.
     * Return null to use the reader's default generated menu.
     */
    fun getPopupMenuResId(): Int? = null

    /**
     * Optional mapper from custom menu item IDs to typed PdfMenuAction values.
     * This lets host XML menus still use typed/default action handling.
     */
    fun mapMenuItemToAction(itemId: Int, title: CharSequence?): PdfMenuAction? = null

    /**
     * Raw fallback for custom menu items that are not mapped to PdfMenuAction.
     * Return true if handled.
     */
    fun onRawMenuItemAction(itemId: Int, title: CharSequence?): Boolean = false

    /**
     * Called when a tool of the Compose reader is tapped: one of [PdfConfig.tools], or the
     * bookmark in the top bar. [page] is the page on screen, counted from 0.
     *
     * Return true if you handled it. The reader's own tools ([PdfReaderTool.PAGES],
     * [PdfReaderTool.NIGHT]) run when this returns false; the rest are the host's alone.
     */
    fun onReaderTool(tool: PdfReaderTool, page: Int): Boolean = false

    /** Called when back is pressed normally (not in edit mode or fullscreen). */
    fun onNavigateBack() {}

    /**
     * Provide a custom page-persistence mechanism.
     * Return null to skip page saving entirely.
     */
    fun getPagePersistence(): PagePersistence? = null
}

// ─────────────────────────────────────────────────────────────────────────────
// Page persistence — optional, lets each host save/restore page its own way
// ─────────────────────────────────────────────────────────────────────────────

interface PagePersistence {
    fun savePage(fileName: String, page: Int)
    fun loadPage(fileName: String): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// Menu actions the popup can trigger
// ─────────────────────────────────────────────────────────────────────────────

enum class PdfMenuAction {
    DOWNLOAD,
    CHANGE_ORIENTATION
}

/**
 * What the Compose reader can offer beside the page. The reader draws them; what ASK, SAVE,
 * NEXT and BOOKMARK do is the host's ([PdfActivityCallbacks.onReaderTool]).
 */
enum class PdfReaderTool {
    /** The list of pages: tap one to go there. The reader's own. */
    PAGES,

    /** Night mode for the page. The reader's own, remembered between files. */
    NIGHT,

    /** Ask about this page. */
    ASK,

    /** Save or share the file. */
    SAVE,

    /** The next file in the chapter. */
    NEXT,

    /** The bookmark in the top bar ([PdfConfig.showBookmarkButton]); not a bottom tool. */
    BOOKMARK,
}

enum class PdfViewerRecovery {
    CONTINUE,
    LEGACY_FALLBACK,
}

enum class PdfViewerType {
    SOURCE_PREPARATION,
    ANDROIDX,

    /** The viewer fallen back to when AndroidX PDF cannot show the file. */
    LEGACY,
}
