@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.thestudypath.pdf

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresExtension
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.pdf.ink.EditablePdfViewerFragment
import com.thestudypath.pdf.interfaces.PdfActivityCallbacks
import com.thestudypath.pdf.interfaces.PdfAnnotationSaver
import com.thestudypath.pdf.interfaces.PdfMenuAction
import com.thestudypath.pdf.interfaces.PdfReaderTool
import com.thestudypath.pdf.interfaces.PdfViewerRecovery
import com.thestudypath.pdf.interfaces.PdfViewerType
import com.thestudypath.pdf.ui.PdfBuiltInPages
import com.thestudypath.pdf.ui.PdfMenuEntry
import com.thestudypath.pdf.ui.PdfNavigationBarSpacer
import com.thestudypath.pdf.ui.PdfPageOverlay
import com.thestudypath.pdf.ui.PdfPagesSheet
import com.thestudypath.pdf.ui.PdfReaderActions
import com.thestudypath.pdf.ui.PdfReaderState
import com.thestudypath.pdf.ui.PdfReaderStatus
import com.thestudypath.pdf.ui.PdfReaderTheme
import com.thestudypath.pdf.ui.PdfTheme
import com.thestudypath.pdf.ui.PdfToolBar
import com.thestudypath.pdf.ui.PdfTopArea
import com.thestudypath.pdf.ui.PdfTopBar
import com.thestudypath.pdf.ui.PdfTopBarAnchor
import com.thestudypath.pdf.ui.PdfUnsavedChangesDialog
import com.thestudypath.pdf.walkthrough.PdfAnnotationWalkthrough
import com.thestudypath.pdf.walkthrough.SpotlightCardPlacement
import com.thestudypath.pdf.walkthrough.SpotlightStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The PDF reader with its bars, messages and sheets drawn in Compose.
 *
 * It does everything [PdfActivity] does, the same way: password-protected files, the AndroidX
 * viewer with annotation and a viewer to fall back on, the colour-preserving night
 * mode, search, fullscreen, download, the annotation walkthrough, the ad slot and the remembered
 * page. The PDF itself stays in ordinary views (ink drawing redraws properly there); only what is
 * around it is Compose.
 *
 * On top of that it draws the reader of StudyPath's design: a subtitle under the title, a
 * bookmark, the "6 / 10" page badge, a row of tools under the page ([PdfConfig.tools]), a list of
 * pages, and "Back where you stopped: page 6" on reopening.
 *
 * **What the host controls:**
 * - [providePdfConfig]        → where the file is, password, title, which actions and tools show
 * - [provideAnnotationSaver]  → how annotations are persisted (re-encrypt, upload, plain copy…)
 * - [provideCallbacks]        → ads, analytics, menu and tool handling, page persistence
 * - [providePdfTheme]         → the colours and type of the bars; Breeze by default
 * - [TopBar], [ToolBar]       → replace a bar outright with the host's own components
 *
 * The activity's own theme must be a Material 3 one (the AndroidX viewer inflates Material
 * widgets): `Theme.StudyPathPdf` is ready to use.
 */
open class PdfActivityCompose : AppCompatActivity() {
    // ── Configuration & delegates ─────────────────────────────────────────
    protected lateinit var pdfConfig: PdfConfig
    protected var annotationSaver: PdfAnnotationSaver? = null
    protected var callbacks: PdfActivityCallbacks? = null

    // ── Fragment ───────────────────────────────────────────────────────────
    private var pdfViewerFragment: EditablePdfViewerFragment? = null

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var topBarView: ComposeView
    private lateinit var pageOverlayView: ComposeView
    private lateinit var bottomBarView: ComposeView
    private lateinit var modalLayerView: ComposeView
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var builtInViewerView: ComposeView
    private lateinit var adContainer: FrameLayout

    // ── What Compose draws from ───────────────────────────────────────────
    private var state by mutableStateOf(PdfReaderState(title = ""))
    private var snackbarMessage by mutableStateOf<String?>(null)
    private var isPagesSheetOpen by mutableStateOf(false)
    private var isUnsavedDialogOpen by mutableStateOf(false)
    private var pdfTheme by mutableStateOf(PdfTheme.light())
    private var menuEntries: List<PdfMenuEntry> = emptyList()
    private val topBarAnchors = mutableMapOf<PdfTopBarAnchor, Rect>()

    // ── State ─────────────────────────────────────────────────────────────
    private var currentPage: Int = 0

    /** The page this session started on, when it is not the first: announced once the file is up. */
    private var resumedPage: Int? = null
    private var isNight = false
    private var pendingPdfUri: Uri? = null
    private var workingFileCleanupDone: Boolean = false
    private var annotationWalkthrough: PdfAnnotationWalkthrough? = null
    private var adLoadRequested = false
    private var introWalkthroughScheduled = false
    private var isBuiltInViewerActive = false

    // The built-in viewer: Android's own renderer, when AndroidX PDF cannot show the file.
    private var builtInRenderer by mutableStateOf<PdfPageRenderer?>(null)
    private var builtInListState by mutableStateOf(LazyListState())
    private var nightPaint by mutableStateOf<Paint?>(null)
    private var openJob: Job? = null
    private var builtInJob: Job? = null
    private var snackbarJob: Job? = null

    private val actions =
        PdfReaderActions(
            back = { onBackPressedDispatcher.onBackPressed() },
            exitEditMode = { exitEditMode() },
            saveAnnotations = { performSaveAnnotations() },
            toggleNight = { setNightMode(!isNight) },
            search = { toggleSearch() },
            fullscreen = { toggleFullscreen() },
            download = { dispatchMenuAction(PdfMenuAction.DOWNLOAD) },
            menu = { onMenuEntrySelected(it) },
            tool = { onToolSelected(it) },
            retry = { prepareAndOpenPdf() },
            goToPage = { goToPage(it) },
        )

    // =====================================================================
    //  LIFECYCLE
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        val darkTheme = isDarkTheme()
        // The bars reach under the system bars and pad themselves; the icons follow the theme.
        val barStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        removeRestoredPdfViewerFragment()
        setContentView(R.layout.activity_pdf_compose)

        // 1. Let subclass / host provide configuration
        pdfConfig = providePdfConfig()
        annotationSaver = provideAnnotationSaver()
        callbacks = provideCallbacks()
        pdfTheme = providePdfTheme(darkTheme)

        PdfWorkingFiles.register(getWorkingFileName())

        // Best-effort startup cleanup in case a prior session was killed before onDestroy.
        PdfWorkingFiles.deleteStale(cacheDir)

        // 2. Restore page
        currentPage = callbacks?.getPagePersistence()?.loadPage(pdfConfig.fileName)
            ?: pdfConfig.initialPage
        resumedPage = currentPage.takeIf { it > 0 }

        // 3. Night mode
        if (isNightModeOffered()) {
            isNight = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_PDF_DARK, false)
        }

        // 4. Bind views and draw the bars
        bindViews()
        menuEntries = buildMenuEntries()
        state =
            PdfReaderState(
                title = pdfConfig.displayName ?: pdfConfig.fileName,
                subtitle = pdfConfig.subtitle,
                page = currentPage,
                isNight = isNight,
                isBookmarked = pdfConfig.isBookmarked,
            ).withConfiguredChrome()
        setComposeContent()
        applyNightMode(isNight)

        // 5. Back press
        setupBackHandler()

        // 6. Prepare the source if needed, then open the PDF
        if (shouldAutoOpenPdf()) {
            prepareAndOpenPdf()
        }

        // 7. Ads — loaded straight away, kept hidden while a walkthrough is on screen.
        maybeLoadAdView()

        // 8. Notify host
        callbacks?.onPdfOpened(pdfConfig)
    }

    override fun onDestroy() {
        pendingPdfUri = null
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        builtInRenderer?.close()
        builtInRenderer = null
        cleanupWorkingFile("onDestroy")
        super.onDestroy()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (isAndroidxPdfSupported()) {
            tryInitializePendingPdfViewer()
        }
    }

    override fun onStop() {
        if (isFinishing && !isChangingConfigurations) {
            cleanupWorkingFile("onStop")
        }
        super.onStop()
    }

    // =====================================================================
    //  OPEN METHODS — override in each host app
    // =====================================================================

    /**
     * Build the [PdfConfig] for this session.
     * Default reads standard extras from the intent.
     */
    protected open fun providePdfConfig(): PdfConfig = PdfConfig.fromIntent(intent)

    /**
     * Provide an [PdfAnnotationSaver] implementation.
     * Return null to leave annotations unsaved ("Save not configured").
     */
    protected open fun provideAnnotationSaver(): PdfAnnotationSaver? = null

    /**
     * Optional pre-open hook, run off the main thread while the reader shows "Opening PDF…".
     *
     * Hosts override this to download, import or transform a document into the local file
     * ([resolveSourceFile]) before rendering begins. A failure is shown in place of the page with
     * "Try again", which runs this again.
     */
    protected open suspend fun preparePdfSource(): Result<Unit> = Result.success(Unit)

    /**
     * Hosts can override this when they need to defer opening until some other UI flow
     * (for example, a document picker) has provided a source first; they then call [openPdf].
     */
    protected open fun shouldAutoOpenPdf(): Boolean = true

    /**
     * Called when a password-protected PDF needs a password before it can be opened.
     * Return null to cancel the open attempt.
     */
    protected open suspend fun requestPdfPassword(isRetry: Boolean): String? = null

    /**
     * Notification hook after a host-provided password has been accepted for retry.
     */
    protected open fun onPdfPasswordUpdated(password: String?) = Unit

    /**
     * Called when the password request is cancelled.
     */
    protected open fun onPdfPasswordRequestCancelled() = Unit

    /**
     * Provide [PdfActivityCallbacks] for ads, analytics, menu and tool actions, etc.
     * Return null for a bare-bones viewer.
     */
    protected open fun provideCallbacks(): PdfActivityCallbacks? = null

    /**
     * Override to show a custom dialog when download is not available.
     * Default shows a short message.
     */
    protected open fun showDownloadUnavailableDialog() {
        showMessage(getString(R.string.pdf_download_unavailable))
    }

    /** Where the PDF is on disk. Default: [PdfConfig.fileName] under the app's files directory. */
    protected open fun resolveSourceFile(): File = File(filesDir, pdfConfig.fileName)

    /** The FileProvider that can serve the cache directory, where the working copy is kept. */
    protected open fun fileProviderAuthority(): String = "${applicationContext.packageName}.fileprovider"

    /** Whether the bars are drawn dark. Default: the device's setting. Night mode for the page is separate. */
    protected open fun isDarkTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The colours and type of the bars, messages and sheets. Default: StudyPath's Breeze. */
    protected open fun providePdfTheme(darkTheme: Boolean): PdfTheme = PdfTheme.default(darkTheme)

    /** The bar above the page. Override to draw it with the host's own components. */
    @Composable
    protected open fun TopBar(
        state: PdfReaderState,
        actions: PdfReaderActions,
    ) {
        PdfTopBar(state, actions, onAnchor = { anchor, bounds -> topBarAnchors[anchor] = bounds })
    }

    /** The tools under the page; drawn only when [PdfConfig.tools] has any. */
    @Composable
    protected open fun ToolBar(
        state: PdfReaderState,
        actions: PdfReaderActions,
    ) {
        PdfToolBar(state, actions)
    }

    // =====================================================================
    //  FOR THE HOST TO CALL
    // =====================================================================

    /** Opens the PDF now; for hosts whose [shouldAutoOpenPdf] is false, once they have a source. */
    protected fun openPdf() = prepareAndOpenPdf()

    /** Shows whether this file is bookmarked. The host calls it once the bookmark is saved or removed. */
    protected fun setBookmarked(bookmarked: Boolean) {
        updateState { copy(isBookmarked = bookmarked) }
    }

    /** A short message over the bottom of the page that leaves by itself. One shows at a time. */
    protected fun showMessage(message: String) {
        snackbarJob?.cancel()
        snackbarJob =
            lifecycleScope.launch {
                snackbarMessage = message
                delay(SNACKBAR_MILLIS)
                snackbarMessage = null
            }
    }

    /** The page on screen, counted from 0. */
    protected val currentPageIndex: Int get() = currentPage

    // =====================================================================
    //  VIEW BINDING
    // =====================================================================

    private fun bindViews() {
        topBarView = findViewById(R.id.pdf_reader_top_bar)
        pageOverlayView = findViewById(R.id.pdf_reader_page_overlay)
        bottomBarView = findViewById(R.id.pdf_reader_bottom_bar)
        modalLayerView = findViewById(R.id.pdf_reader_modal_layer)
        fragmentContainer = findViewById(R.id.pdf_reader_fragment_container)
        builtInViewerView = findViewById(R.id.pdf_reader_built_in_viewer)
        adContainer = findViewById(R.id.pdf_reader_ad_container)

        if (!isAndroidxPdfSupported()) showBuiltInViewer()
    }

    private fun showBuiltInViewer() {
        isBuiltInViewerActive = true
        fragmentContainer.visibility = View.GONE
        builtInViewerView.visibility = View.VISIBLE
    }

    private fun setComposeContent() {
        topBarView.setContent {
            ReaderTheme {
                PdfTopArea {
                    if (!state.isFullScreen) TopBar(state, actions)
                }
            }
        }
        builtInViewerView.setContent {
            val renderer = builtInRenderer ?: return@setContent
            val listState = builtInListState
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }.collect { onPageShown(it) }
            }
            PdfBuiltInPages(renderer.aspectRatios, listState, nightPaint, renderer::render)
        }
        pageOverlayView.setContent {
            ReaderTheme { PdfPageOverlay(state, actions, snackbarMessage) }
        }
        bottomBarView.setContent {
            ReaderTheme {
                // Annotating brings the viewer's own toolbar; the reader's tools step aside.
                if (state.tools.isNotEmpty() && !state.isFullScreen && !state.isEditing) {
                    ToolBar(state, actions)
                } else {
                    PdfNavigationBarSpacer()
                }
            }
        }
        modalLayerView.setContent {
            ReaderTheme {
                when {
                    isUnsavedDialogOpen ->
                        PdfUnsavedChangesDialog(
                            onSave = {
                                isUnsavedDialogOpen = false
                                performSaveAnnotations()
                            },
                            onDiscard = {
                                isUnsavedDialogOpen = false
                                discardAnnotations()
                            },
                            onDismiss = { isUnsavedDialogOpen = false },
                        )

                    isPagesSheetOpen ->
                        PdfPagesSheet(
                            page = state.page,
                            pageCount = state.pageCount,
                            onSelect = actions.goToPage,
                            onDismiss = { isPagesSheetOpen = false },
                        )
                }
            }
        }
    }

    @Composable
    private fun ReaderTheme(content: @Composable () -> Unit) = PdfReaderTheme(pdfTheme, content)

    private inline fun updateState(change: PdfReaderState.() -> PdfReaderState) {
        state = state.change().withConfiguredChrome()
    }

    /** Which actions and tools show: the config's switches, and what the viewer on screen can do. */
    private fun PdfReaderState.withConfiguredChrome(): PdfReaderState =
        copy(
            showSaveAnnotations = pdfConfig.showEditButtons,
            showNightToggle = pdfConfig.showNightModeToggle && PdfReaderTool.NIGHT !in pdfConfig.tools,
            showSearch = pdfConfig.showSearchButton && isAndroidxPdfSupported() && !isBuiltInViewerActive,
            showFullscreen = pdfConfig.showFullscreenButton,
            showDownload = pdfConfig.showDownloadOption,
            showBookmark = pdfConfig.showBookmarkButton,
            showPageIndicator = pdfConfig.showPageIndicator,
            menu = menuEntries,
            tools = pdfConfig.tools.filter { it != PdfReaderTool.BOOKMARK }.distinct(),
        )

    private fun setStatus(status: PdfReaderStatus) {
        if (state.status != status) updateState { copy(status = status) }
    }

    private fun showFailure(message: String?) {
        setStatus(PdfReaderStatus.Failed(message?.takeIf { it.isNotBlank() } ?: getString(R.string.pdf_error_open)))
    }

    // =====================================================================
    //  PDF OPEN / DECRYPT
    // =====================================================================

    @SuppressLint("NewApi")
    private fun prepareAndOpenPdf() {
        openJob?.cancel()
        openJob =
            lifecycleScope.launch {
                while (true) {
                    setStatus(PdfReaderStatus.Loading)
                    val prepareResult = withContext(Dispatchers.IO) { preparePdfSource() }
                    val preparationError = prepareResult.exceptionOrNull()
                    if (preparationError != null) {
                        if (preparationError is PdfPasswordRequiredException) {
                            if (requestUpdatedPassword(preparationError)) {
                                continue
                            }
                            showFailure(preparationError.message)
                            return@launch
                        }

                        callbacks?.onPdfOpenFailed(
                            preparationError,
                            PdfViewerType.SOURCE_PREPARATION,
                        )
                        showFailure(preparationError.message)
                        return@launch
                    }

                    // The unlocked copy both viewers read.
                    val sourceFile = resolveSourceFile()
                    val workingFile = getWorkingFile()
                    val error =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                if (!sourceFile.exists()) {
                                    throw FileNotFoundException("PDF source file was not found")
                                }
                                if (workingFile.exists() && !workingFile.delete()) {
                                    throw IOException("Unable to replace the previous prepared PDF")
                                }
                                writeDecryptedPdfToFile(sourceFile, workingFile, pdfConfig.password)
                            }.exceptionOrNull()
                        }
                    if (error is PdfPasswordRequiredException) {
                        if (requestUpdatedPassword(error)) {
                            continue
                        }
                        showFailure(error.message)
                        return@launch
                    }
                    if (error != null) {
                        Log.e(TAG, "Could not prepare the PDF", error)
                        callbacks?.onPdfOpenFailed(error, PdfViewerType.SOURCE_PREPARATION)
                        showFailure(
                            getString(
                                if (error is FileNotFoundException) R.string.pdf_error_not_found else R.string.pdf_error_open,
                            ),
                        )
                        return@launch
                    }

                    if (!isAndroidxPdfSupported()) {
                        openBuiltInViewer()
                        return@launch
                    }

                    val uri = getUriForFile(workingFile)
                    if (uri == null) {
                        fallbackToBuiltInViewer(IOException("Unable to create a URI for the prepared PDF"))
                        return@launch
                    }
                    initializePdfViewerFragmentWhenReady(uri)
                    return@launch
                }
            }
    }

    private suspend fun requestUpdatedPassword(error: PdfPasswordRequiredException): Boolean {
        val resumedState =
            lifecycle.currentStateFlow.first { state ->
                state == Lifecycle.State.DESTROYED ||
                    state.isAtLeast(Lifecycle.State.RESUMED)
            }
        if (!resumedState.isAtLeast(Lifecycle.State.RESUMED)) {
            return false
        }

        val password = requestPdfPassword(error.isIncorrectPassword)
        if (password.isNullOrEmpty()) {
            onPdfPasswordRequestCancelled()
            return false
        }

        pdfConfig = pdfConfig.copy(password = password)
        onPdfPasswordUpdated(password)
        return true
    }

    /**
     * Shows the prepared file with Android's own renderer: pages only, scrolled vertically, with
     * zoom and night mode. No native library, no search, no annotation.
     */
    private fun openBuiltInViewer() {
        showBuiltInViewer()
        setStatus(PdfReaderStatus.Loading)
        builtInJob?.cancel()
        builtInJob =
            lifecycleScope.launch {
                runCatching { PdfPageRenderer.open(getWorkingFile()) }
                    .onSuccess { renderer ->
                        builtInRenderer?.close()
                        builtInListState = LazyListState(currentPage.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0)))
                        builtInRenderer = renderer
                        onDocumentLoaded(renderer.pageCount)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.e(TAG, "Built-in PDF viewer failed", error)
                        callbacks?.onPdfOpenFailed(error, PdfViewerType.LEGACY)
                        showFailure(getString(R.string.pdf_error_open))
                    }
            }
    }

    /** The page on screen changed, in either viewer. */
    private fun onPageShown(page: Int) {
        if (currentPage != page) {
            currentPage = page
            saveCurrentPage()
            callbacks?.onPageChanged(page)
        }
        if (state.page != page) updateState { copy(page = page) }
    }

    /** Either viewer has the document up: the page count is known and the page can be announced. */
    private fun onDocumentLoaded(pageCount: Int) {
        updateState { copy(status = PdfReaderStatus.Ready, pageCount = pageCount) }
        callbacks?.onDocumentLoaded(pageCount)
        resumedPage?.let { page ->
            resumedPage = null
            if (page < pageCount) {
                showMessage(getString(R.string.pdf_resumed_page, page + 1))
            }
        }
    }

    /**
     * Writes the unlocked copy of [sourceFile] the viewer opens. Hosts with their own protection
     * (a file encrypted by the app, not by a PDF password) override this.
     */
    protected open fun writeDecryptedPdfToFile(
        sourceFile: File,
        targetFile: File,
        password: String?,
    ) = writeDecryptedPdf(sourceFile, targetFile, password)

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun initializePdfViewerFragmentWhenReady(uri: Uri) {
        pendingPdfUri = uri
        tryInitializePendingPdfViewer()
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun tryInitializePendingPdfViewer() {
        val uri = pendingPdfUri ?: return
        if (
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            supportFragmentManager.isStateSaved
        ) {
            return
        }

        fragmentContainer.runWhenMeasured {
            if (
                pendingPdfUri == uri &&
                !isFinishing &&
                !isDestroyed &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !supportFragmentManager.isStateSaved
            ) {
                pendingPdfUri = null
                initializePdfViewerFragment(uri)
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun initializePdfViewerFragment(uri: Uri) {
        try {
            val fm = supportFragmentManager
            pdfViewerFragment = fm.findFragmentByTag(FRAGMENT_TAG) as? EditablePdfViewerFragment

            if (pdfViewerFragment == null) {
                val fragment = EditablePdfViewerFragmentExtended()
                pdfViewerFragment = fragment

                fm
                    .beginTransaction()
                    .replace(R.id.pdf_reader_fragment_container, fragment, FRAGMENT_TAG)
                    .commitNow()

                setupFragmentCallbacks(fragment)
            } else {
                (pdfViewerFragment as? EditablePdfViewerFragmentExtended)?.let {
                    setupFragmentCallbacks(it)
                }
            }

            applyNightMode(isNight)
            pdfViewerFragment?.documentUri = uri
            // The androidx viewer shows its own progress indicator from here on.
            setStatus(PdfReaderStatus.Ready)
            introWalkthroughScheduled = true
            fragmentContainer.postDelayed({ maybeShowAnnotationIntroWalkthrough() }, WALKTHROUGH_DELAY_MS)
        } catch (error: Throwable) {
            fallbackToBuiltInViewer(error)
        }
    }

    private fun removeRestoredPdfViewerFragment() {
        supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)?.let { fragment ->
            supportFragmentManager
                .beginTransaction()
                .remove(fragment)
                .commitAllowingStateLoss()
            supportFragmentManager.executePendingTransactions()
        }
    }

    // =====================================================================
    //  EDIT MODE
    // =====================================================================

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun setupFragmentCallbacks(fragment: EditablePdfViewerFragmentExtended) {
        fragment.initialPage = currentPage
        fragment.onPageChanged = { page -> onPageShown(page) }
        fragment.onDocumentLoaded = { pageCount -> onDocumentLoaded(pageCount) }
        fragment.onEditModeEntered = { onEditModeEntered() }
        fragment.onEditModeExited = { onEditModeExited() }
        fragment.onDocumentLoadError = { error -> fallbackToBuiltInViewer(error) }
        fragment.onDocumentRequestFailed = { error ->
            Log.e(TAG, "AndroidX PDF render request failed", error)
            callbacks?.onPdfViewerError(error, PdfViewerRecovery.CONTINUE)
        }
    }

    private fun fallbackToBuiltInViewer(error: Throwable) {
        if (isBuiltInViewerActive) return
        pendingPdfUri = null
        Log.w(TAG, "AndroidX PDF failed to load; falling back to the built-in viewer", error)
        callbacks?.onPdfViewerError(error, PdfViewerRecovery.LEGACY_FALLBACK)
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        isUnsavedDialogOpen = false
        updateState { copy(isEditing = false, isSaving = false) }
        openBuiltInViewer()
        maybeLoadAdView()
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun onEditModeEntered() {
        updateState { copy(isEditing = true) }
        isPagesSheetOpen = false
        pdfViewerFragment?.isTextSearchActive = false
        callbacks?.onEditModeChanged(true)
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        fragmentContainer.postDelayed({ maybeShowEditModeWalkthrough() }, WALKTHROUGH_DELAY_MS)
    }

    private fun onEditModeExited() {
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        updateState { copy(isEditing = false, isSaving = false) }
        callbacks?.onEditModeChanged(false)
        maybeLoadAdView()
    }

    @SuppressLint("NewApi")
    private fun exitEditMode() {
        if (!isAndroidxPdfSupported()) return
        val fragment = pdfViewerFragment as? EditablePdfViewerFragmentExtended ?: return
        if (fragment.hasUnsavedChanges) {
            isUnsavedDialogOpen = true
        } else {
            fragment.exitEditMode()
        }
    }

    @SuppressLint("NewApi")
    private fun discardAnnotations() {
        if (!isAndroidxPdfSupported()) return
        (pdfViewerFragment as? EditablePdfViewerFragmentExtended)?.exitEditMode()
    }

    // =====================================================================
    //  ANNOTATION WALKTHROUGH
    // =====================================================================

    private fun maybeShowAnnotationIntroWalkthrough() {
        introWalkthroughScheduled = false
        if (state.isEditing || !shouldShowAnnotationWalkthrough(KEY_ANNOTATION_INTRO_SEEN)) {
            maybeLoadAdView()
            return
        }

        annotationWalkthrough?.dismiss(markFinished = false)
        val walkthrough = PdfAnnotationWalkthrough(this)
        annotationWalkthrough = walkthrough
        hideAdDuringWalkthrough()
        walkthrough.start(
            steps =
                listOf(
                    SpotlightStep(
                        targetRectProvider = { findEditButtonRect() ?: fallbackEditButtonRect() },
                        title = getString(R.string.pdf_walkthrough_intro_title),
                        message = getString(R.string.pdf_walkthrough_intro_message),
                    ),
                ),
            onFinished = {
                markAnnotationWalkthroughSeen(KEY_ANNOTATION_INTRO_SEEN)
                annotationWalkthrough = null
                maybeLoadAdView()
            },
        )
    }

    private fun maybeShowEditModeWalkthrough() {
        if (!state.isEditing) return
        if (!shouldShowAnnotationWalkthrough(KEY_ANNOTATION_EDIT_SEEN)) {
            maybeLoadAdView()
            return
        }

        annotationWalkthrough?.dismiss(markFinished = false)
        val walkthrough = PdfAnnotationWalkthrough(this)
        annotationWalkthrough = walkthrough
        hideAdDuringWalkthrough()
        walkthrough.start(
            steps =
                listOf(
                    SpotlightStep(
                        targetRectProvider = { findAnnotationControlsRect() ?: fallbackAnnotationToolbarRect() },
                        title = getString(R.string.pdf_walkthrough_tool_title),
                        message = getString(R.string.pdf_walkthrough_tool_message),
                        cardPlacement = SpotlightCardPlacement.TOP,
                    ),
                    SpotlightStep(
                        targetRectProvider = { topBarAnchorRect(PdfTopBarAnchor.SAVE) },
                        title = getString(R.string.pdf_walkthrough_save_title),
                        message = getString(R.string.pdf_walkthrough_save_message),
                    ),
                    SpotlightStep(
                        targetRectProvider = { topBarAnchorRect(PdfTopBarAnchor.CLOSE_EDIT) },
                        title = getString(R.string.pdf_walkthrough_exit_title),
                        message = getString(R.string.pdf_walkthrough_exit_message),
                    ),
                ),
            onFinished = {
                markAnnotationWalkthroughSeen(KEY_ANNOTATION_EDIT_SEEN)
                annotationWalkthrough = null
                maybeLoadAdView()
            },
        )
    }

    private fun isWalkthroughActive(): Boolean = annotationWalkthrough != null || introWalkthroughScheduled

    /** INVISIBLE rather than GONE so the layout (and the spotlight targets) do not shift. */
    private fun hideAdDuringWalkthrough() {
        if (adLoadRequested) adContainer.visibility = View.INVISIBLE
    }

    private fun maybeLoadAdView() {
        if (isWalkthroughActive()) return
        if (adLoadRequested) {
            adContainer.visibility = View.VISIBLE
            return
        }

        callbacks?.getAdView()?.let { adView ->
            adLoadRequested = true
            adContainer.removeAllViews()
            adContainer.addView(adView)
            adContainer.visibility = View.VISIBLE
        }
    }

    private fun shouldShowAnnotationWalkthrough(key: String): Boolean {
        if (!isAndroidxPdfSupported()) return false
        if (!isAndroidxPdfViewerVisible()) return false
        if (!pdfConfig.showEditButtons) return false
        return !getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(key, false)
    }

    private fun isAndroidxPdfViewerVisible(): Boolean =
        !isBuiltInViewerActive && fragmentContainer.isVisible

    private fun markAnnotationWalkthroughSeen(key: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(key, true)
        }
    }

    /**
     * Where a top-bar button is, for the spotlight. A host that draws its own top bar reports no
     * buttons; the spotlight then rests on that end of the bar.
     */
    private fun topBarAnchorRect(anchor: PdfTopBarAnchor): RectF {
        val bar = rectInOverlayCoordinates(topBarView, padding = 0f)
        val bounds = topBarAnchors[anchor]
        if (bounds != null && !bounds.isEmpty) {
            return RectF(
                bar.left + bounds.left - SPOTLIGHT_PADDING,
                bar.top + bounds.top - SPOTLIGHT_PADDING,
                bar.left + bounds.right + SPOTLIGHT_PADDING,
                bar.top + bounds.bottom + SPOTLIGHT_PADDING,
            )
        }
        val size = 56f * resources.displayMetrics.density
        return when (anchor) {
            PdfTopBarAnchor.CLOSE_EDIT -> RectF(bar.left, bar.bottom - size, bar.left + size, bar.bottom)
            PdfTopBarAnchor.SAVE -> RectF(bar.right - size, bar.bottom - size, bar.right, bar.bottom)
        }
    }

    private fun findAnnotationToolbarRect(): RectF? {
        val fragmentView = pdfViewerFragment?.view ?: return null
        val toolbar = findViewByClassName(fragmentView, "AnnotationToolbar") ?: return null
        if (toolbar.width == 0 || toolbar.height == 0 || toolbar.visibility != View.VISIBLE) return null
        return rectInOverlayCoordinates(toolbar, padding = 8f)
    }

    private fun findAnnotationControlsRect(): RectF? {
        val fragmentView = pdfViewerFragment?.view ?: return null
        val annotationRects = mutableListOf<RectF>()
        collectAnnotationControlRects(fragmentView, annotationRects)

        val toolbarRect = findAnnotationToolbarRect()
        if (toolbarRect != null) {
            annotationRects += toolbarRect
        }

        return annotationRects
            .takeIf { it.isNotEmpty() }
            ?.reduce { union, rect ->
                union.apply { union(rect) }
            }
    }

    private fun collectAnnotationControlRects(
        view: View,
        output: MutableList<RectF>,
    ) {
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return

        if (isAnnotationControlView(view)) {
            val rect = rectInOverlayCoordinates(view, padding = 8f)
            if (isReasonableAnnotationControlRect(rect)) {
                output += rect
            }
        }

        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectAnnotationControlRects(group.getChildAt(index), output)
        }
    }

    private fun isAnnotationControlView(view: View): Boolean {
        val name = view.javaClass.name
        if (!name.startsWith("androidx.pdf")) return false
        if (name.contains("PdfView")) return false

        val controlNameHints =
            listOf(
                "Annotation",
                "Toolbar",
                "Palette",
                "Brush",
                "Color",
                "Pen",
                "Highlighter",
                "Eraser",
                "Ink",
            )
        if (controlNameHints.none { name.contains(it, ignoreCase = true) }) return false

        val rect = rectInOverlayCoordinates(view, padding = 0f)
        val screenHeight = window.decorView.height
        return rect.bottom > screenHeight * 0.45f
    }

    private fun isReasonableAnnotationControlRect(rect: RectF): Boolean {
        val screenWidth = window.decorView.width.toFloat()
        val screenHeight = window.decorView.height.toFloat()
        if (rect.width() < 24f || rect.height() < 24f) return false
        if (rect.height() > screenHeight * 0.34f) return false
        if (rect.width() > screenWidth * 0.98f && rect.height() > screenHeight * 0.18f) return false
        return rect.bottom > screenHeight * 0.45f
    }

    private fun findEditButtonRect(): RectF? {
        val fragmentView = pdfViewerFragment?.view ?: return null
        val editButton =
            findViewByClassName(fragmentView, "FloatingActionButton")
                ?: findViewByClassName(fragmentView, "ExtendedFloatingActionButton")
                ?: return null
        if (editButton.width == 0 || editButton.height == 0 || editButton.visibility != View.VISIBLE) return null
        val rect = rectInOverlayCoordinates(editButton, padding = 8f)
        val containerRect = rectInOverlayCoordinates(fragmentContainer, padding = 0f)
        if (rect.centerY() < containerRect.centerY()) return null
        return rect
    }

    private fun findViewByClassName(
        view: View,
        classNamePart: String,
    ): View? {
        if (view.javaClass.name.contains(classNamePart)) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findViewByClassName(group.getChildAt(index), classNamePart)?.let { return it }
        }
        return null
    }

    private fun fallbackAnnotationToolbarRect(): RectF {
        val containerRect = rectInOverlayCoordinates(fragmentContainer, padding = 0f)
        val toolbarHeight = 92f * resources.displayMetrics.density
        return RectF(
            containerRect.left + 16f,
            containerRect.bottom - toolbarHeight - 16f,
            containerRect.right - 16f,
            containerRect.bottom - 16f,
        )
    }

    private fun fallbackEditButtonRect(): RectF {
        val containerRect = rectInOverlayCoordinates(fragmentContainer, padding = 0f)
        val size = 76f * resources.displayMetrics.density
        val margin = 28f * resources.displayMetrics.density
        return RectF(
            containerRect.right - size - margin,
            containerRect.bottom - size - margin,
            containerRect.right - margin,
            containerRect.bottom - margin,
        )
    }

    private fun rectInOverlayCoordinates(
        view: View,
        padding: Float,
    ): RectF {
        val viewLocation = IntArray(2)
        val decorLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        window.decorView.getLocationOnScreen(decorLocation)
        return RectF(
            viewLocation[0] - decorLocation[0] - padding,
            viewLocation[1] - decorLocation[1] - padding,
            viewLocation[0] - decorLocation[0] + view.width + padding,
            viewLocation[1] - decorLocation[1] + view.height + padding,
        )
    }

    // =====================================================================
    //  SAVE ANNOTATIONS
    // =====================================================================

    @SuppressLint("NewApi")
    private fun performSaveAnnotations() {
        if (!isAndroidxPdfSupported()) {
            showMessage(getString(R.string.pdf_save_unsupported))
            return
        }

        val saver = annotationSaver
        if (saver == null) {
            showMessage(getString(R.string.pdf_save_not_configured))
            return
        }

        val fragment = pdfViewerFragment as? EditablePdfViewerFragmentExtended
        if (fragment == null || !fragment.isDocumentLoaded) {
            showMessage(getString(R.string.pdf_save_no_document))
            return
        }

        val workingFile = getWorkingFile()
        updateState { copy(isSaving = true) }

        fragment.onSaveComplete = {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = saver.save(workingFile, resolveSourceFile(), pdfConfig.password)
                withContext(Dispatchers.Main) {
                    updateState { copy(isSaving = false) }
                    if (result.isSuccess) {
                        showMessage(getString(R.string.pdf_save_done))
                        callbacks?.onAnnotationsSaved()
                    } else {
                        val error = result.exceptionOrNull() ?: Exception("Unknown error")
                        showMessage(getString(R.string.pdf_save_failed, error.message.orEmpty()))
                        callbacks?.onAnnotationSaveFailed(error)
                    }
                }
            }
        }

        fragment.onSaveError = { error ->
            showMessage(getString(R.string.pdf_save_annotation_failed))
            updateState { copy(isSaving = false) }
            callbacks?.onAnnotationSaveFailed(error)
        }

        fragment.saveAnnotations(workingFile)
    }

    // =====================================================================
    //  NIGHT MODE
    // =====================================================================

    private fun isNightModeOffered(): Boolean = pdfConfig.showNightModeToggle || PdfReaderTool.NIGHT in pdfConfig.tools

    private fun setNightMode(enabled: Boolean) {
        isNight = enabled
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_PDF_DARK, enabled)
        }
        applyNightMode(enabled)
        updateState { copy(isNight = enabled) }
    }

    /** Recolours the page only: the bars follow the app's theme, not this. */
    @SuppressLint("NewApi")
    private fun applyNightMode(enabled: Boolean) {
        val paint = if (enabled) pdfNightModePaint() else null
        if (isAndroidxPdfSupported()) {
            (pdfViewerFragment as? EditablePdfViewerFragmentExtended)?.setNightModePaint(paint)
        }
        nightPaint = paint
    }

    // =====================================================================
    //  SEARCH, FULLSCREEN, PAGES
    // =====================================================================

    @SuppressLint("NewApi")
    private fun toggleSearch() {
        if (!isAndroidxPdfSupported()) return
        val fragment = pdfViewerFragment ?: return
        fragment.isTextSearchActive = !fragment.isTextSearchActive
    }

    /** Hides both bars. Back brings them back. */
    private fun toggleFullscreen() {
        updateState { copy(isFullScreen = !isFullScreen) }
    }

    @SuppressLint("NewApi")
    private fun goToPage(page: Int) {
        isPagesSheetOpen = false
        if (page !in 0 until state.pageCount) return
        if (isBuiltInViewerActive) {
            lifecycleScope.launch { builtInListState.scrollToItem(page) }
        } else if (isAndroidxPdfSupported()) {
            (pdfViewerFragment as? EditablePdfViewerFragmentExtended)?.scrollToPage(page)
        }
    }

    // =====================================================================
    //  TOOLS AND MENU
    // =====================================================================

    private fun onToolSelected(tool: PdfReaderTool) {
        if (callbacks?.onReaderTool(tool, currentPage) == true) return
        when (tool) {
            PdfReaderTool.PAGES -> if (state.pageCount > 0) isPagesSheetOpen = true
            PdfReaderTool.NIGHT -> setNightMode(!isNight)
            // The host's alone: nothing happens until it handles them.
            PdfReaderTool.ASK, PdfReaderTool.SAVE, PdfReaderTool.NEXT, PdfReaderTool.BOOKMARK -> Unit
        }
    }

    /** The overflow menu's lines: the host's menu resource if it gives one, else the reader's own. */
    private fun buildMenuEntries(): List<PdfMenuEntry> {
        val customMenuResId =
            callbacks?.getPopupMenuResId()
                ?: return buildList {
                    if (pdfConfig.showOrientationOption) {
                        add(PdfMenuEntry(MENU_ORIENTATION, getString(R.string.pdf_menu_orientation)))
                    }
                    if (pdfConfig.showDownloadOption) {
                        add(PdfMenuEntry(MENU_DOWNLOAD, getString(R.string.pdf_action_download)))
                    }
                }

        // Inflated only to be read: the menu itself is drawn in Compose.
        val menu = PopupMenu(this, topBarView).menu
        menuInflater.inflate(customMenuResId, menu)
        return (0 until menu.size)
            .map { menu[it] }
            .filter { item ->
                item.isVisible &&
                    when (resolveMenuAction(item.itemId, item.title)) {
                        PdfMenuAction.CHANGE_ORIENTATION -> pdfConfig.showOrientationOption
                        PdfMenuAction.DOWNLOAD -> pdfConfig.showDownloadOption
                        null -> true
                    }
            }.map { item -> PdfMenuEntry(item.itemId, item.title?.toString().orEmpty()) }
    }

    private fun resolveMenuAction(
        itemId: Int,
        title: CharSequence?,
    ): PdfMenuAction? =
        when (itemId) {
            MENU_ORIENTATION -> PdfMenuAction.CHANGE_ORIENTATION
            MENU_DOWNLOAD -> PdfMenuAction.DOWNLOAD
            else -> callbacks?.mapMenuItemToAction(itemId, title)
        }

    private fun onMenuEntrySelected(entry: PdfMenuEntry) {
        val action = resolveMenuAction(entry.id, entry.title)
        if (action != null) {
            dispatchMenuAction(action)
        } else {
            callbacks?.onRawMenuItemAction(entry.id, entry.title)
        }
    }

    /** Gives the host a chance to handle [action] first. */
    private fun dispatchMenuAction(action: PdfMenuAction) {
        if (callbacks?.onMenuAction(action) == true) return
        when (action) {
            PdfMenuAction.CHANGE_ORIENTATION -> {
                requestedOrientation =
                    if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
            }

            PdfMenuAction.DOWNLOAD -> {
                if (pdfConfig.isDownloadable) {
                    downloadPdf()
                } else {
                    showDownloadUnavailableDialog()
                }
            }
        }
    }

    // =====================================================================
    //  DOWNLOAD
    // =====================================================================

    /** Copies the PDF, without its password, to the device's Downloads. */
    protected fun downloadPdf() {
        val sourceFile = resolveSourceFile()
        if (!sourceFile.exists()) return

        showMessage(getString(R.string.pdf_download_started))
        val displayName = pdfConfig.displayName ?: pdfConfig.fileName

        lifecycleScope.launch {
            val downloadedFileUri =
                withContext(Dispatchers.IO) {
                    val decryptedFile = File(cacheDir, "download_${System.nanoTime()}.pdf")
                    try {
                        writeDecryptedPdfToFile(sourceFile, decryptedFile, pdfConfig.password)
                        copyPdfToDownloads(this@PdfActivityCompose, decryptedFile, displayName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Download failed", e)
                        null
                    } finally {
                        decryptedFile.delete()
                    }
                }
            if (downloadedFileUri != null) {
                callbacks?.onDownloadSucceeded(displayName, downloadedFileUri)
                showMessage(getString(R.string.pdf_download_done))
            } else {
                showMessage(getString(R.string.pdf_download_failed))
            }
        }
    }

    // =====================================================================
    //  BACK PRESS
    // =====================================================================

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        isUnsavedDialogOpen -> isUnsavedDialogOpen = false
                        isPagesSheetOpen -> isPagesSheetOpen = false
                        state.isEditing -> exitEditMode()
                        state.isFullScreen -> toggleFullscreen()
                        else -> {
                            saveCurrentPage()
                            callbacks?.onNavigateBack()
                            // Remove this callback so the default back behavior fires
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
    }

    // =====================================================================
    //  PAGE PERSISTENCE
    // =====================================================================

    private fun saveCurrentPage() {
        callbacks?.getPagePersistence()?.savePage(pdfConfig.fileName, currentPage)
    }

    // =====================================================================
    //  HELPERS
    // =====================================================================

    private fun getWorkingFileName(): String = PdfWorkingFiles.nameFor(pdfConfig.fileName)

    private fun getWorkingFile(): File = File(cacheDir, getWorkingFileName())

    private fun getUriForFile(file: File): Uri? =
        try {
            FileProvider.getUriForFile(this, fileProviderAuthority(), file)
        } catch (e: Exception) {
            Log.w(TAG, "No FileProvider URI for ${file.name}", e)
            null
        }

    private fun cleanupWorkingFile(reason: String) {
        if (!::pdfConfig.isInitialized) return
        if (workingFileCleanupDone) {
            PdfWorkingFiles.unregister(getWorkingFileName())
            return
        }

        runCatching {
            val workingFile = getWorkingFile()
            if (!workingFile.exists()) {
                workingFileCleanupDone = true
                Log.d(TAG, "cleanupWorkingFile($reason): no file")
            } else {
                val deleted = workingFile.delete()
                if (deleted) {
                    workingFileCleanupDone = true
                }
                Log.d(TAG, "cleanupWorkingFile($reason): deleted=$deleted")
            }
        }.onFailure {
            Log.w(TAG, "cleanupWorkingFile($reason) failed", it)
        }

        PdfWorkingFiles.unregister(getWorkingFileName())
    }

    // =====================================================================
    //  CONSTANTS
    // =====================================================================

    companion object {
        private const val FRAGMENT_TAG = "pdf_viewer_fragment_tag"

        // Shared with PdfActivity: night mode and the walkthroughs are remembered once per app.
        private const val PREFS_NAME = "generic_pdf_prefs"
        private const val KEY_PDF_DARK = "isPdfDark"
        private const val KEY_ANNOTATION_INTRO_SEEN = "pdf_annotation_intro_seen"
        private const val KEY_ANNOTATION_EDIT_SEEN = "pdf_annotation_edit_seen"
        private const val WALKTHROUGH_DELAY_MS = 450L
        private const val SNACKBAR_MILLIS = 4000L
        private const val SPOTLIGHT_PADDING = 10f

        private const val MENU_ORIENTATION = 1
        private const val MENU_DOWNLOAD = 2

        private const val TAG = "PdfActivityCompose"
    }
}
