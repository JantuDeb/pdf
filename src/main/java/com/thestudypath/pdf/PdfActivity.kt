package com.thestudypath.pdf

import android.annotation.SuppressLint

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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

        // Best-effort startup cleanup in case a prior session was killed before onDestroy.
        runCatching {
            val staleWorkingFile = getWorkingFile()
            if (staleWorkingFile.exists()) {
                val deleted = staleWorkingFile.delete()
                Log.d(TAG, "Startup stale cleanup: deleted=$deleted")
            }
        }.onFailure {
            Log.w(TAG, "Startup stale cleanup failed", it)
        }

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

        // 8. Ads — host provides the view, we just slot it in
        callbacks?.getAdView()?.let { adView ->
            adContainer.removeAllViews()
            adContainer.addView(adView)
            adContainer.visibility = View.VISIBLE
        }

        // 9. Notify host
        callbacks?.onPdfOpened(pdfConfig)

        // 10. Status-bar style (light icons on dark bg or vice versa)
        applyStatusBarStyle()
    }

    override fun onDestroy() {
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
                        initializePdfViewerFragment(uri)
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

            if (password.isNullOrEmpty()) {
                // No password — just copy to working location
                sourceFile.copyTo(workingFile, overwrite = true)
            } else {
                // Decrypt using PdfRenderer
                val inputFd = ParcelFileDescriptor.open(
                    sourceFile, ParcelFileDescriptor.MODE_READ_ONLY
                )
                val loadParams = LoadParams.Builder().setPassword(password).build()
                val renderer = PdfRenderer(inputFd, loadParams)
                val outputFd = ParcelFileDescriptor.open(
                    workingFile,
                    ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_READ_WRITE or
                            ParcelFileDescriptor.MODE_TRUNCATE
                )
                renderer.write(outputFd, true)
                outputFd.close()
                renderer.close()
            }

            getUriForFile(workingFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
    }

    // =====================================================================
    //  EDIT MODE
    // =====================================================================

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
    private fun setupEditModeCallbacks(fragment: EditablePdfViewerFragmentExtended) {
        fragment.onEditModeEntered = { onEditModeEntered() }
        fragment.onEditModeExited = { onEditModeExited() }
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
    }

    private fun onEditModeExited() {
        isInEditMode = false
        buttonSave.visibility = View.GONE
        buttonCloseEdit.visibility = View.GONE
        buttonBack.visibility = View.VISIBLE
        if (pdfConfig.showNightModeToggle) materialSwitch.visibility = View.VISIBLE
        if (pdfConfig.showSearchButton) buttonSearch.visibility = View.VISIBLE
        callbacks?.onEditModeChanged(false)
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
            val success = decryptAndCopyToDownloads(
                sourceFile,
                pdfConfig.displayName ?: pdfConfig.fileName,
                pdfConfig.password
            )
            withContext(Dispatchers.Main) {
                if (success) {
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
    ): Boolean {
        return try {
            val decryptedFile = File(filesDir, "de_${sourceFile.name}")

            // Decrypt
            val inputFd = ParcelFileDescriptor.open(
                sourceFile, ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = if (!password.isNullOrEmpty()) {
                val params = LoadParams.Builder().setPassword(password).build()
                PdfRenderer(inputFd, params)
            } else {
                PdfRenderer(inputFd)
            }
            val outputFd = ParcelFileDescriptor.open(
                decryptedFile,
                ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_TRUNCATE
            )

            renderer.write(outputFd, true)
            outputFd.close()
            renderer.close()

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
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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

    private fun getWorkingFile(): File = File(cacheDir, "working_${pdfConfig.fileName}")

    private fun getOriginalFile(): File = File(filesDir, pdfConfig.fileName)

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
        if (workingFileCleanupDone) return

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
    }

    // =====================================================================
    //  CONSTANTS
    // =====================================================================

    companion object {
        private const val FRAGMENT_TAG = "pdf_viewer_fragment_tag"
        private const val PREFS_NAME = "generic_pdf_prefs"
        private const val KEY_PDF_DARK = "isPdfDark"

        private const val MENU_ORIENTATION = 1
        private const val MENU_DOWNLOAD = 2

        private const val TAG = "PdfActivity"
    }
}