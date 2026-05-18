package com.thestudypath.pdf

import android.annotation.SuppressLint

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.pdf.ink.EditablePdfViewerFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.thestudypath.pdf.interfaces.PdfActivityCallbacks
import com.thestudypath.pdf.interfaces.PdfAnnotationSaver
import com.thestudypath.pdf.interfaces.PdfMenuAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import androidx.core.content.edit
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.thestudypath.pdf.walkthrough.PdfAnnotationWalkthrough
import com.thestudypath.pdf.walkthrough.SpotlightCardPlacement
import com.thestudypath.pdf.walkthrough.SpotlightStep
import androidx.core.view.isVisible

/**
 * A fully self-contained PDF viewing/editing activity.
 *
 * **What it handles internally (core PDF functionality):**
 * - Decrypt and render password-protected PDFs
 * - Night-mode (color inversion)
 * - In-document text search
 * - Fullscreen toggle
 * - Annotation editing (enter / exit / save lifecycle)
 * - Download decrypted copy to device Downloads folder
 * - Orientation change via menu
 *
 * **What the host app controls via overrides / interfaces:**
 * - [providePdfConfig]        → where the file is, password, display name, feature flags
 * - [provideAnnotationSaver]  → how annotations are persisted (re-encrypt, upload, plain copy…)
 * - [provideCallbacks]        → ads, analytics, menu handling, page persistence
 *
 * Subclass this in each project or use the open `provide*` methods.
 */

open class PdfActivity : AppCompatActivity() {

    // ── Configuration & delegates ─────────────────────────────────────────
    protected lateinit var pdfConfig: PdfConfig
    protected var annotationSaver: PdfAnnotationSaver? = null
    protected var callbacks: PdfActivityCallbacks? = null

    // ── Fragment ───────────────────────────────────────────────────────────
    private var pdfViewerFragment: EditablePdfViewerFragment? = null

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var constraintLayoutPdfHeader: View
    private lateinit var adContainer: FrameLayout
    private lateinit var materialSwitch: MaterialSwitch
    private lateinit var buttonBack: ImageButton
    private lateinit var buttonSave: ImageButton
    private lateinit var buttonCloseEdit: ImageButton
    private lateinit var buttonSearch: ImageButton
    private lateinit var buttonFullscreen: ImageButton
    private lateinit var buttonPopup: ImageButton
    private lateinit var textViewTitle: TextView

    private lateinit var flPdfViewFragment: FrameLayout

    // ── State ─────────────────────────────────────────────────────────────
    private var isFullScreen = false
    private var isInEditMode = false
    private var isNight = false
    private var currentPage: Int = 0
    private var pdfUri: Uri? = null
    private var workingFileCleanupDone: Boolean = false
    private var annotationWalkthrough: PdfAnnotationWalkthrough? = null
    private var adLoadRequested = false
    private var hasFallenBackToLegacyViewer = false

    private lateinit var legacyPdfView: PDFView

    // =====================================================================
    //  LIFECYCLE
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutResId())

        // 1. Let subclass / host provide configuration
        pdfConfig = providePdfConfig()
        annotationSaver = provideAnnotationSaver()
        callbacks = provideCallbacks()

        registerActiveWorkingFile()

        // Best-effort startup cleanup in case a prior session was killed before onDestroy.
        cleanupStaleWorkingFiles()

        // 2. Restore page
        currentPage = callbacks?.getPagePersistence()?.loadPage(pdfConfig.fileName)
            ?: pdfConfig.initialPage

        if (currentPage != 0) {
            val toast = Toast.makeText(this, "Last time you were here", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.BOTTOM, 0, 50)
            toast.show()
        }

        // 3. Bind views
        bindViews()

        // 4. Apply feature flags
        applyFeatureFlags()

        // 5. Night mode
        setupNightModeToggle()

        // 6. Back press
        setupBackHandler()

        // 7. Prepare the source if needed, then open the PDF
        if (shouldAutoOpenPdf()) {
            prepareAndOpenPdf()
        }

        // 8. Ads — wait until annotation walkthroughs are complete before requesting ads.
        maybeLoadAdView()

        // 9. Notify host
        callbacks?.onPdfOpened(pdfConfig)

        // 10. Status-bar style (light icons on dark bg or vice versa)
        applyStatusBarStyle()
    }

    override fun onDestroy() {
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        cleanupWorkingFile("onDestroy")
        super.onDestroy()
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
     * Provide the layout resource. Override if you want a completely custom layout,
     * but it must contain the same view IDs (see activity_generic_pdf.xml).
     */
    protected open fun getLayoutResId(): Int = R.layout.activity_androidx_pdf

    /**
     * Build the [PdfConfig] for this session.
     * Default reads standard extras from the intent.
     */
    protected open fun providePdfConfig(): PdfConfig {
        return PdfConfig.fromIntent(intent)
    }

    /**
     * Provide an [PdfAnnotationSaver] implementation.
     * Return null to disable the save button entirely.
     */
    protected open fun provideAnnotationSaver(): PdfAnnotationSaver? = null

    /**
     * Optional pre-open hook.
     *
     * Hosts can override this to import or transform an incoming document into the
     * local file expected by this activity before rendering begins.
     */
    protected open suspend fun preparePdfSource(): Result<Unit> = Result.success(Unit)

    /**
     * Hosts can override this when they need to defer opening until some other UI flow
     * (for example, a document picker) has provided a source first.
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
     * Provide [PdfActivityCallbacks] for ads, analytics, menu actions, etc.
     * Return null for a bare-bones viewer.
     */
    protected open fun provideCallbacks(): PdfActivityCallbacks? = null

    /**
     * Override to show a custom dialog when download is not available.
     * Default shows a simple toast.
     */
    protected open fun showDownloadUnavailableDialog() {
        Toast.makeText(this, "Download not available", Toast.LENGTH_SHORT).show()
    }

    // =====================================================================
    //  VIEW BINDING
    // =====================================================================

    private fun bindViews() {

        legacyPdfView = findViewById(R.id.legacy_pdf_view)
        flPdfViewFragment = findViewById(R.id.fragment_pdf_container)

        if (isPdfEditingSupported()) {
            legacyPdfView.visibility = View.GONE
        } else {
            flPdfViewFragment.visibility = View.GONE
            legacyPdfView.visibility = View.VISIBLE
        }

        constraintLayoutPdfHeader = findViewById(R.id.pdf_top_header)
        adContainer = findViewById(R.id.fl_adplaceholder)
        materialSwitch = findViewById(R.id.material_switch)
        textViewTitle = findViewById(R.id.text_title)
        buttonBack = findViewById(R.id.btn_back)
        buttonSave = findViewById(R.id.btn_save_exit)
        buttonCloseEdit = findViewById(R.id.btn_close_edit)
        buttonSearch = findViewById(R.id.btn_search)
        buttonFullscreen = findViewById(R.id.btn_menu_full)
        buttonPopup = findViewById(R.id.btn_popup)

        textViewTitle.text = pdfConfig.displayName ?: pdfConfig.fileName

        buttonSearch.visibility = if (isPdfEditingSupported()) View.VISIBLE else View.GONE

        buttonBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        buttonFullscreen.setOnClickListener { toggleFullscreen() }
        buttonSearch.setOnClickListener {
            if (isPdfEditingSupported()) {
                toggleSearch()
            }
        }
        buttonPopup.setOnClickListener { showPopupMenu(it) }

        buttonSave.visibility = View.GONE
        buttonSave.setOnClickListener { performSaveAnnotations() }

        buttonCloseEdit.visibility = View.GONE
        buttonCloseEdit.setOnClickListener {
            if (isPdfEditingSupported()) {
                exitEditMode()
            }
        }
    }

    private fun applyFeatureFlags() {
        if (!pdfConfig.showNightModeToggle) materialSwitch.visibility = View.GONE
        if (!pdfConfig.showSearchButton) buttonSearch.visibility = View.GONE
        if (!pdfConfig.showFullscreenButton) buttonFullscreen.visibility = View.GONE
        if (!pdfConfig.showEditButtons) {
            buttonSave.visibility = View.GONE
            buttonCloseEdit.visibility = View.GONE
        }
    }

    // =====================================================================
    //  PDF OPEN / DECRYPT
    // =====================================================================

    @SuppressLint("NewApi")
    private fun prepareAndOpenPdf() {
        lifecycleScope.launch {
            while (true) {
                val prepareResult = withContext(Dispatchers.IO) { preparePdfSource() }
                if (prepareResult.isSuccess) {
                    if (isPdfEditingSupported()) {
                        openPdf()
                    } else {
                        Toast.makeText(
                            this@PdfActivity,
                            "PDF annotation not supported on this device",
                            Toast.LENGTH_LONG
                        ).show()
                        openPdfLegacy()
                    }
                    return@launch
                }

                val error = prepareResult.exceptionOrNull()
                if (error is PdfPasswordRequiredException) {
                    val password = requestPdfPassword(error.isIncorrectPassword)
                    if (password == null) {
                        onPdfPasswordRequestCancelled()
                        return@launch
                    }

                    pdfConfig = pdfConfig.copy(password = password)
                    onPdfPasswordUpdated(password)
                    continue
                }

                val message = error?.message ?: "Failed to open PDF"
                Toast.makeText(this@PdfActivity, message, Toast.LENGTH_SHORT).show()
                return@launch
            }
        }
    }

    private fun isPdfEditingSupported(): Boolean {
        return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18
    }


    private fun openPdfLegacy() {
        hasFallenBackToLegacyViewer = true
        val sourceFile = getOriginalFile()
        if (!sourceFile.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "openPdfLegacy: ${sourceFile.path} ")

//        val file = getFileStreamPath(sourceFile)
        try {
            legacyPdfView.fromFile(sourceFile)
                .enableSwipe(true)
                .defaultPage(currentPage)
                .nightMode(isNight)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .password(pdfConfig.password).enableAntialiasing(true)
                .onPageChange { page, _ ->
                    currentPage = page
                    callbacks?.onPageChanged(currentPage)
                }
                .enableAnnotationRendering(true)
                .onRender { legacyPdfView.fitToWidth(currentPage) }
                .onError { t: Throwable -> Log.d(TAG, " onError" + t.message) }
                .scrollHandle(DefaultScrollHandle(this)).swipeHorizontal(false)
                .enableAntialiasing(true)
                .spacing(10).pageFitPolicy(FitPolicy.WIDTH).load()

        } catch (e: Exception) {
            e.message?.let { Log.d(TAG, it) }
            e.printStackTrace()
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun openPdf() {
        try {
            val sourceFile = getOriginalFile()
            if (!sourceFile.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                val uri = prepareDecryptedPdfUri(sourceFile, pdfConfig.password)
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        pdfUri = uri
                        initializePdfViewerFragmentWhenReady(uri)
                    } else {
                        Toast.makeText(
                            this@PdfActivity,
                            "Failed to open PDF",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("NewApi")
    private fun prepareDecryptedPdfUri(sourceFile: File, password: String?): Uri? {
        return try {
            val workingFile = getWorkingFile()

            writeDecryptedPdfToFile(sourceFile, workingFile, password)

            getUriForFile(workingFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun initializePdfViewerFragmentWhenReady(uri: Uri) {
        flPdfViewFragment.runWhenMeasured {
            if (!isFinishing && !isDestroyed) {
                initializePdfViewerFragment(uri)
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun initializePdfViewerFragment(uri: Uri) {
        val fm: FragmentManager = supportFragmentManager
        pdfViewerFragment = fm.findFragmentByTag(FRAGMENT_TAG) as? EditablePdfViewerFragment

        if (pdfViewerFragment == null) {
            val fragment = EditablePdfViewerFragmentExtended()
            pdfViewerFragment = fragment

            fm.beginTransaction()
                .replace(R.id.fragment_pdf_container, fragment, FRAGMENT_TAG)
                .commitAllowingStateLoss()
            fm.executePendingTransactions()

            setupEditModeCallbacks(fragment)
//            fragment.markDocumentLoaded()
        } else {
            (pdfViewerFragment as? EditablePdfViewerFragmentExtended)?.let {
                setupEditModeCallbacks(it)
            }
        }

        pdfViewerFragment?.documentUri = uri
        flPdfViewFragment.postDelayed({ maybeShowAnnotationIntroWalkthrough() }, WALKTHROUGH_DELAY_MS)
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

    // =====================================================================
    //  EDIT MODE
    // =====================================================================

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun setupEditModeCallbacks(fragment: EditablePdfViewerFragmentExtended) {
        fragment.initialPage = currentPage
        fragment.onPageChanged = { page ->
            if (currentPage != page) {
                currentPage = page
                saveCurrentPage()
                callbacks?.onPageChanged(page)
            }
        }
        fragment.onEditModeEntered = { onEditModeEntered() }
        fragment.onEditModeExited = { onEditModeExited() }
        fragment.onDocumentLoadError = { error -> fallbackToLegacyViewer(error) }
        fragment.onDocumentRequestFailed = { error ->
            Log.e(TAG, "AndroidX PDF render request failed", error)
            callbacks?.onPdfViewerError(error)
        }
    }

    private fun fallbackToLegacyViewer(error: Throwable) {
        if (hasFallenBackToLegacyViewer) return
        Log.w(TAG, "AndroidX PDF failed to load; falling back to legacy viewer", error)
        callbacks?.onPdfViewerError(error)
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        buttonSave.visibility = View.GONE
        buttonCloseEdit.visibility = View.GONE
        buttonSearch.visibility = View.GONE
        flPdfViewFragment.visibility = View.GONE
        legacyPdfView.visibility = View.VISIBLE
        openPdfLegacy()
        maybeLoadAdView()
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun onEditModeEntered() {
        isInEditMode = true
        buttonSave.visibility = if (pdfConfig.showEditButtons) View.VISIBLE else View.GONE
        buttonCloseEdit.visibility = if (pdfConfig.showEditButtons) View.VISIBLE else View.GONE
        buttonBack.visibility = View.GONE
        materialSwitch.visibility = View.GONE
        buttonSearch.visibility = View.GONE
        pdfViewerFragment?.isTextSearchActive = false
        callbacks?.onEditModeChanged(true)
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        flPdfViewFragment.postDelayed({ maybeShowEditModeWalkthrough() }, WALKTHROUGH_DELAY_MS)
    }

    private fun onEditModeExited() {
        isInEditMode = false
        annotationWalkthrough?.dismiss(markFinished = false)
        annotationWalkthrough = null
        buttonSave.visibility = View.GONE
        buttonCloseEdit.visibility = View.GONE
        buttonBack.visibility = View.VISIBLE
        if (pdfConfig.showNightModeToggle) materialSwitch.visibility = View.VISIBLE
        if (pdfConfig.showSearchButton) buttonSearch.visibility = View.VISIBLE
        callbacks?.onEditModeChanged(false)
    }

    // =====================================================================
    //  ANNOTATION WALKTHROUGH
    // =====================================================================

    private fun maybeShowAnnotationIntroWalkthrough() {
        if (!shouldShowAnnotationWalkthrough(KEY_ANNOTATION_INTRO_SEEN)) return
        if (isInEditMode) return
        if (!isAndroidxPdfViewerVisible()) {
            maybeLoadAdView()
            return
        }

        annotationWalkthrough?.dismiss(markFinished = false)
        val walkthrough = PdfAnnotationWalkthrough(this)
        annotationWalkthrough = walkthrough
        walkthrough.start(
            steps = listOf(
                SpotlightStep(
                    targetRectProvider = { findEditButtonRect() ?: fallbackEditButtonRect() },
                    title = "Annotate PDFs",
                    message = "Tap the pen button to draw, highlight, and mark important steps.",
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
        if (!isInEditMode) return
        if (!isAndroidxPdfViewerVisible()) {
            maybeLoadAdView()
            return
        }
        if (!shouldShowAnnotationWalkthrough(KEY_ANNOTATION_EDIT_SEEN)) {
            maybeLoadAdView()
            return
        }

        annotationWalkthrough?.dismiss(markFinished = false)
        val walkthrough = PdfAnnotationWalkthrough(this)
        annotationWalkthrough = walkthrough
        walkthrough.start(
            steps = listOf(
                SpotlightStep(
                    targetRectProvider = { findAnnotationControlsRect() ?: fallbackAnnotationToolbarRect() },
                    title = "Choose a tool",
                    message = "Tap pen or highlighter. Tap the selected pen again to change thickness, and use color to switch ink.",
                    cardPlacement = SpotlightCardPlacement.TOP,
                ),
                SpotlightStep(
                    target = buttonSave,
                    title = "Save annotations",
                    message = "Tap save when you want to keep your notes in this PDF.",
                ),
                SpotlightStep(
                    target = buttonCloseEdit,
                    title = "Exit edit mode",
                    message = "Close editing when you are done. If changes are unsaved, we will ask before closing.",
                ),
            ),
            onFinished = {
                markAnnotationWalkthroughSeen(KEY_ANNOTATION_EDIT_SEEN)
                annotationWalkthrough = null
                maybeLoadAdView()
            },
        )
    }

    private fun maybeLoadAdView() {
        if (adLoadRequested) return
        if (hasPendingAnnotationWalkthrough()) return

        callbacks?.getAdView()?.let { adView ->
            adLoadRequested = true
            adContainer.removeAllViews()
            adContainer.addView(adView)
            adContainer.visibility = View.VISIBLE
        }
    }

    private fun hasPendingAnnotationWalkthrough(): Boolean {
        if (!isPdfEditingSupported()) return false
        if (!isAndroidxPdfViewerVisible()) return false
        if (!pdfConfig.showEditButtons) return false
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return !prefs.getBoolean(KEY_ANNOTATION_INTRO_SEEN, false) ||
                !prefs.getBoolean(KEY_ANNOTATION_EDIT_SEEN, false)
    }

    private fun shouldShowAnnotationWalkthrough(key: String): Boolean {
        if (!isPdfEditingSupported()) return false
        if (!isAndroidxPdfViewerVisible()) return false
        if (!pdfConfig.showEditButtons) return false
        return !getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(key, false)
    }

    private fun isAndroidxPdfViewerVisible(): Boolean {
        return !hasFallenBackToLegacyViewer &&
                flPdfViewFragment.isVisible &&
                legacyPdfView.visibility != View.VISIBLE
    }

    private fun markAnnotationWalkthroughSeen(key: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(key, true)
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

    private fun collectAnnotationControlRects(view: View, output: MutableList<RectF>) {
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return

        if (isAnnotationControlView(view)) {
            val rect = rectInOverlayCoordinates(view, padding = 8f)
            if (isReasonableAnnotationControlRect(rect)) {
                output += rect
            }
        }

        val group = view as? android.view.ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectAnnotationControlRects(group.getChildAt(index), output)
        }
    }

    private fun isAnnotationControlView(view: View): Boolean {
        val name = view.javaClass.name
        if (!name.startsWith("androidx.pdf")) return false
        if (name.contains("PdfView")) return false

        val controlNameHints = listOf(
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
        val editButton = findViewByClassName(fragmentView, "FloatingActionButton")
            ?: findViewByClassName(fragmentView, "ExtendedFloatingActionButton")
            ?: return null
        if (editButton.width == 0 || editButton.height == 0 || editButton.visibility != View.VISIBLE) return null
        val rect = rectInOverlayCoordinates(editButton, padding = 8f)
        val containerRect = rectInOverlayCoordinates(flPdfViewFragment, padding = 0f)
        if (rect.centerY() < containerRect.centerY()) return null
        return rect
    }

    private fun findViewByClassName(view: View, classNamePart: String): View? {
        if (view.javaClass.name.contains(classNamePart)) return view
        val group = view as? android.view.ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findViewByClassName(group.getChildAt(index), classNamePart)?.let { return it }
        }
        return null
    }

    private fun fallbackAnnotationToolbarRect(): RectF {
        val containerRect = rectInOverlayCoordinates(flPdfViewFragment, padding = 0f)
        val toolbarHeight = 92f * resources.displayMetrics.density
        return RectF(
            containerRect.left + 16f,
            containerRect.bottom - toolbarHeight - 16f,
            containerRect.right - 16f,
            containerRect.bottom - 16f,
        )
    }

    private fun fallbackEditButtonRect(): RectF {
        val containerRect = rectInOverlayCoordinates(flPdfViewFragment, padding = 0f)
        val size = 76f * resources.displayMetrics.density
        val margin = 28f * resources.displayMetrics.density
        return RectF(
            containerRect.right - size - margin,
            containerRect.bottom - size - margin,
            containerRect.right - margin,
            containerRect.bottom - margin,
        )
    }

    private fun rectInOverlayCoordinates(view: View, padding: Float): RectF {
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

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun exitEditMode() {
        val fragment = pdfViewerFragment as? EditablePdfViewerFragmentExtended ?: return
        if (fragment.hasUnsavedChanges) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unsaved changes")
                .setMessage("You have unsaved annotations. Do you want to save them before closing edit mode?")
                .setPositiveButton("Save") { _, _ -> performSaveAnnotations() }
                .setNegativeButton("Discard") { _, _ -> fragment.exitEditMode() }
                .show()
        } else {
            fragment.exitEditMode()
        }
    }

    // =====================================================================
    //  SAVE ANNOTATIONS
    // =====================================================================

    private fun performSaveAnnotations() {
        if (!isPdfEditingSupported()) {
            Toast.makeText(this, "Not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val saver = annotationSaver
        if (saver == null) {
            Toast.makeText(this, "Save not configured", Toast.LENGTH_SHORT).show()
            return
        }

        val fragment = pdfViewerFragment as? EditablePdfViewerFragmentExtended
        if (fragment == null || !fragment.isDocumentLoaded) {
            Toast.makeText(this, "No document loaded", Toast.LENGTH_SHORT).show()
            return
        }

        val workingFile = getWorkingFile()
        buttonSave.isEnabled = false

        fragment.onSaveComplete = {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = saver.save(workingFile, getOriginalFile(), pdfConfig.password)
                withContext(Dispatchers.Main) {
                    buttonSave.isEnabled = true
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@PdfActivity,
                            "Annotations saved",
                            Toast.LENGTH_SHORT
                        ).show()

                        callbacks?.onAnnotationsSaved()
                    } else {
                        val error = result.exceptionOrNull() ?: Exception("Unknown error")
                        Toast.makeText(
                            this@PdfActivity,
                            "Save failed: ${error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        callbacks?.onAnnotationSaveFailed(error)
                    }
                }
            }
        }

        fragment.onSaveError = { error ->
            Toast.makeText(this, "Annotation save failed", Toast.LENGTH_SHORT).show()
            buttonSave.isEnabled = true
            callbacks?.onAnnotationSaveFailed(error)
        }

        fragment.saveAnnotations(workingFile)
    }

    // =====================================================================
    //  NIGHT MODE
    // =====================================================================

    private fun setupNightModeToggle() {
        if (!pdfConfig.showNightModeToggle) return

        // Try to restore persisted preference; default false
        isNight = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_PDF_DARK, false)

        materialSwitch.isChecked = isNight
        applyNightMode(isNight)

        materialSwitch.setOnCheckedChangeListener { _, isChecked ->
            isNight = isChecked
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit {
                    putBoolean(KEY_PDF_DARK, isChecked)
                }
            applyNightMode(isChecked)
        }
    }

    private fun applyNightMode(enabled: Boolean) {
        val container = findViewById<FrameLayout>(R.id.fragment_pdf_container) ?: return
        if (enabled) {
            val invertMatrix = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(invertMatrix)
            }
            container.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            container.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    // =====================================================================
    //  SEARCH
    // =====================================================================

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun toggleSearch() {
        val fragment = pdfViewerFragment ?: return
        fragment.isTextSearchActive = !fragment.isTextSearchActive
    }

    // =====================================================================
    //  FULLSCREEN
    // =====================================================================

    private fun toggleFullscreen() {
        isFullScreen = !isFullScreen
        constraintLayoutPdfHeader.visibility = if (isFullScreen) View.GONE else View.VISIBLE
    }

    // =====================================================================
    //  POPUP MENU
    // =====================================================================

    private fun showPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val customMenuResId = callbacks?.getPopupMenuResId()

        if (customMenuResId != null) {
            popup.menuInflater.inflate(customMenuResId, popup.menu)
        } else {
            buildDefaultPopupMenu(popup.menu)
        }

        popup.setOnMenuItemClickListener { item: MenuItem ->
            val action = resolveDefaultMenuAction(item.itemId)
                ?: callbacks?.mapMenuItemToAction(item.itemId, item.title)

            if (action != null) {
                // Give host a chance to handle it first
                val handled = callbacks?.onMenuAction(action) ?: false
                if (!handled) {
                    handleDefaultMenuAction(action)
                }
            } else {
                callbacks?.onRawMenuItemAction(item.itemId, item.title)
            }
            true
        }
        popup.show()
    }

    private fun buildDefaultPopupMenu(menu: Menu) {
        if (pdfConfig.showOrientationOption) {
            menu.add(0, MENU_ORIENTATION, 0, "Change Orientation")
        }
        if (pdfConfig.showDownloadOption) {
            menu.add(0, MENU_DOWNLOAD, 1, "Download PDF")
        }
    }

    private fun resolveDefaultMenuAction(itemId: Int): PdfMenuAction? {
        return when (itemId) {
            MENU_ORIENTATION -> PdfMenuAction.CHANGE_ORIENTATION
            MENU_DOWNLOAD -> PdfMenuAction.DOWNLOAD
            else -> null
        }
    }

    private fun handleDefaultMenuAction(action: PdfMenuAction) {
        when (action) {
            PdfMenuAction.CHANGE_ORIENTATION -> {
                requestedOrientation =
                    if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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

    @SuppressLint("NewApi")
    private fun downloadPdf() {
        val sourceFile = getOriginalFile()
        if (!sourceFile.exists()) return

        Toast.makeText(this, "PDF is being downloaded…", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val downloadedFileUri = decryptAndCopyToDownloads(
                sourceFile,
                pdfConfig.displayName ?: pdfConfig.fileName,
                pdfConfig.password
            )
            withContext(Dispatchers.Main) {
                if (downloadedFileUri != null) {
                    callbacks?.onDownloadSucceeded(
                        pdfConfig.displayName ?: pdfConfig.fileName,
                        downloadedFileUri
                    )
                    Toast.makeText(applicationContext, "PDF download completed", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(applicationContext, "PDF download failed", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun decryptAndCopyToDownloads(
        sourceFile: File,
        displayName: String,
        password: String?
    ): Uri? {
        return try {
            val decryptedFile = File(filesDir, "de_${sourceFile.name}")

            writeDecryptedPdfToFile(sourceFile, decryptedFile, password)

            // Copy to MediaStore Downloads
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri)?.use { outputStream ->
                    FileInputStream(decryptedFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                decryptedFile.delete()
                targetUri
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @SuppressLint("NewApi")
    protected open fun writeDecryptedPdfToFile(sourceFile: File, targetFile: File, password: String?) {
        if (password.isNullOrEmpty() || canOpenPdfWithoutPassword(sourceFile)) {
            sourceFile.copyTo(targetFile, overwrite = true)
            return
        }

        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { inputFd ->
            val loadParams = LoadParams.Builder().setPassword(password).build()
            PdfRenderer(inputFd, loadParams).use { renderer ->
                ParcelFileDescriptor.open(
                    targetFile,
                    ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_READ_WRITE or
                            ParcelFileDescriptor.MODE_TRUNCATE
                ).use { outputFd ->
                    renderer.write(outputFd, true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    protected fun canOpenPdfWithoutPassword(sourceFile: File): Boolean {
        return runCatching {
            ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { inputFd ->
                PdfRenderer(inputFd).use { renderer ->
                    renderer.pageCount >= 0
                }
            }
        }.getOrDefault(false)
    }

    // =====================================================================
    //  BACK PRESS
    // =====================================================================

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
            override fun handleOnBackPressed() {
                when {
                    isInEditMode -> exitEditMode()
                    isFullScreen -> toggleFullscreen()
                    else -> {
                        saveCurrentPage()
                        callbacks?.onNavigateBack()
                        // Remove this callback so the default back behavior fires
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
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

    private fun getWorkingFileName(): String = buildWorkingFileName(pdfConfig.fileName)

    private fun getWorkingFile(): File = File(cacheDir, getWorkingFileName())

    private fun getOriginalFile(): File = File(filesDir, pdfConfig.fileName)

    private fun registerActiveWorkingFile() {
        synchronized(activeWorkingFiles) {
            activeWorkingFiles += getWorkingFileName()
        }
    }

    private fun unregisterActiveWorkingFile() {
        synchronized(activeWorkingFiles) {
            activeWorkingFiles -= getWorkingFileName()
        }
    }

    private fun cleanupStaleWorkingFiles() {
        runCatching {
            val activeFilesSnapshot = synchronized(activeWorkingFiles) {
                activeWorkingFiles.toSet()
            }

            cacheDir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.startsWith(WORKING_FILE_PREFIX) }
                ?.filterNot { it.name in activeFilesSnapshot }
                ?.forEach { staleWorkingFile ->
                    val deleted = staleWorkingFile.delete()
                    Log.d(
                        TAG,
                        "Startup stale cleanup: file=${staleWorkingFile.name}, deleted=$deleted"
                    )
                }
        }.onFailure {
            Log.w(TAG, "Startup stale cleanup failed", it)
        }
    }

    private fun getUriForFile(file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun applyStatusBarStyle() {
        // Light status bar icons when in light theme
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !isNight
    }

    private fun cleanupWorkingFile(reason: String) {
        if (workingFileCleanupDone) {
            unregisterActiveWorkingFile()
            return
        }

        runCatching {
            val workingFile = getWorkingFile()
            if (!workingFile.exists()) {
                workingFileCleanupDone = true
                Log.d(TAG, "cleanupWorkingFile($reason): no file")
                return
            }

            val deleted = workingFile.delete()
            if (deleted) {
                workingFileCleanupDone = true
            }
            Log.d(TAG, "cleanupWorkingFile($reason): deleted=$deleted")
        }.onFailure {
            Log.w(TAG, "cleanupWorkingFile($reason) failed", it)
        }

        unregisterActiveWorkingFile()
    }

    // =====================================================================
    //  CONSTANTS
    // =====================================================================

    companion object {
        private const val FRAGMENT_TAG = "pdf_viewer_fragment_tag"
        private const val PREFS_NAME = "generic_pdf_prefs"
        private const val KEY_PDF_DARK = "isPdfDark"
        private const val KEY_ANNOTATION_INTRO_SEEN = "pdf_annotation_intro_seen"
        private const val KEY_ANNOTATION_EDIT_SEEN = "pdf_annotation_edit_seen"
        private const val WORKING_FILE_PREFIX = "working_"
        private const val WALKTHROUGH_DELAY_MS = 450L

        private const val MENU_ORIENTATION = 1
        private const val MENU_DOWNLOAD = 2

        private const val TAG = "PdfActivity"

        private val activeWorkingFiles = mutableSetOf<String>()

        private fun buildWorkingFileName(fileName: String): String = "$WORKING_FILE_PREFIX$fileName"
    }
}
