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
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.pdf.ink.EditablePdfViewerFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.thestudypath.pdf.interfaces.PdfActivityCallbacks
import com.thestudypath.pdf.interfaces.PdfAnnotationSaver
import com.thestudypath.pdf.interfaces.PdfMenuAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * A Compose-based PDF viewing/editing activity.
 *
 * Uses a hybrid layout: native Android views for the PDF fragment container
 * (to preserve proper ink drawing invalidation) and Jetpack Compose for
 * the toolbar chrome via [ComposeView].
 *
 * **Features:**
 * - Decrypt and render password-protected PDFs
 * - Night-mode (color inversion)
 * - In-document text search
 * - Fullscreen toggle
 * - Annotation editing (enter / exit / save lifecycle)
 * - Download decrypted copy to device Downloads folder
 * - Orientation change via menu
 * - Ad container slot
 * - Page persistence
 *
 * **Host app controls via overrides / interfaces:**
 * - [providePdfConfig]        → file location, password, display name, feature flags
 * - [provideAnnotationSaver]  → how annotations are persisted
 * - [provideCallbacks]        → ads, analytics, menu handling, page persistence
 */
open class PdfActivityCompose : FragmentActivity() {

    // ── Configuration & delegates ─────────────────────────────────────────
    protected lateinit var pdfConfig: PdfConfig
    protected var annotationSaver: PdfAnnotationSaver? = null
    protected var callbacks: PdfActivityCallbacks? = null

    // ── Fragment ───────────────────────────────────────────────────────────
    private var pdfViewerFragment: EditablePdfViewerFragment? = null

    // ── State ─────────────────────────────────────────────────────────────
    private var currentPage: Int = 0
    private var workingFileCleanupDone: Boolean = false
    private var pdfUri: Uri? = null
    private var pendingSaveAsSourceFile: File? = null

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { targetUri ->
        val sourceFile = pendingSaveAsSourceFile
        pendingSaveAsSourceFile = null

        if (targetUri == null) {
            Toast.makeText(this, "Save As cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        if (sourceFile == null || !sourceFile.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val success = copyFileToUri(sourceFile, targetUri)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@PdfActivityCompose,
                    if (success) "PDF saved" else "Save As failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── Compose state holders ─────────────────────────────────────────────
    private val _isFullScreen = mutableStateOf(false)
    private val _isNight = mutableStateOf(false)
    private val _isLoading = mutableStateOf(true)
    private val _isInEditMode = mutableStateOf(false)

    // ── Native views ──────────────────────────────────────────────────────
    private lateinit var rootLayout: LinearLayout
    private lateinit var topBarComposeView: ComposeView
    private lateinit var pdfContainer: FragmentContainerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var adComposeView: ComposeView

    // Fragment container view ID (stable across config changes)
    private val fragmentContainerId = R.id.pdf_fragment_container

    // =====================================================================
    //  LIFECYCLE
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Configuration
        pdfConfig = providePdfConfig()
        annotationSaver = provideAnnotationSaver()
        callbacks = provideCallbacks()

        // Startup cleanup of stale working files
        if (savedInstanceState == null) {
            runCatching {
                val staleWorkingFile = getWorkingFile()
                if (staleWorkingFile.exists()) {
                    val deleted = staleWorkingFile.delete()
                    Log.d(TAG, "Startup stale cleanup: deleted=$deleted")
                }
            }
        }

        // 2. Restore page
        currentPage = callbacks?.getPagePersistence()?.loadPage(pdfConfig.fileName)
            ?: pdfConfig.initialPage

        if (currentPage != 0) {
            val toast = Toast.makeText(this, "Last time you were here", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.BOTTOM, 0, 50)
            toast.show()
        }

        // 3. Night mode from prefs
        _isNight.value = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_PDF_DARK, false)

        // 4. Back press
        setupBackHandler()

        // 5. Status bar
        applyStatusBarStyle()

        // 6. Notify host
        callbacks?.onPdfOpened(pdfConfig)

        // 7. Build hybrid layout (native views + ComposeViews for chrome)
        buildLayout()
        setContentView(rootLayout)

        // 8. Open PDF (after setContentView so fragment container exists)
        if (savedInstanceState != null) {
            reattachExistingFragment()
        } else if (shouldAutoOpenPdf()) {
            prepareAndOpenPdf()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            cleanupWorkingFile("onDestroy")
        }
        super.onDestroy()
    }

    override fun onStop() {
        if (isFinishing && !isChangingConfigurations) {
            cleanupWorkingFile("onStop")
        }
        super.onStop()
    }

    // =====================================================================
    //  LAYOUT BUILDING
    // =====================================================================

    private fun buildLayout() {
        // Root vertical LinearLayout
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top bar (Compose)
        topBarComposeView = ComposeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setContent {
                MaterialTheme {
                    val isFullScreen by _isFullScreen
                    val isNight by _isNight
                    if (!isFullScreen) {
                        PdfTopBar(isNight = isNight)
                    }
                }
            }
        }
        rootLayout.addView(topBarComposeView)

        // PDF container (native FragmentContainerView — NOT inside AndroidView)
        pdfContainer = FragmentContainerView(this).apply {
            id = fragmentContainerId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f // weight=1 fills remaining space
            )
        }
        applyNightModeLayer(pdfContainer, _isNight.value)
        rootLayout.addView(pdfContainer)

        // Loading indicator (native, overlaid on the pdf container)
        loadingIndicator = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            isVisible = true
        }
        // We need a FrameLayout wrapper to overlay the spinner on the pdf container
        // Instead, we'll manage visibility and add it to the rootLayout's parent later.
        // Simpler approach: observe _isLoading and toggle visibility
        // We'll use a separate observer below.

        // Ad container (Compose)
        adComposeView = ComposeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setContent {
                MaterialTheme {
                    val isFullScreen by _isFullScreen
                    if (!isFullScreen) {
                        AdContent()
                    }
                }
            }
        }
        rootLayout.addView(adComposeView)

        // Observe state changes for night mode on the native container
        lifecycleScope.launch {
            snapshotFlow { _isNight.value }.collect { night ->
                applyNightModeLayer(pdfContainer, night)
            }
        }
    }

    /**
     * Helper to collect Compose snapshot state as a Flow from outside Compose.
     */
    private fun <T> snapshotFlow(block: () -> T): kotlinx.coroutines.flow.Flow<T> {
        return androidx.compose.runtime.snapshotFlow { block() }
    }

    // =====================================================================
    //  OPEN METHODS — override in each host app
    // =====================================================================

    protected open fun providePdfConfig(): PdfConfig = PdfConfig.fromIntent(intent)

    protected open fun provideAnnotationSaver(): PdfAnnotationSaver? = null

    protected open suspend fun preparePdfSource(): Result<Unit> = Result.success(Unit)

    protected open fun shouldAutoOpenPdf(): Boolean = true

    protected open suspend fun requestPdfPassword(isRetry: Boolean): String? = null

    protected open fun onPdfPasswordUpdated(password: String?) = Unit

    protected open fun onPdfPasswordRequestCancelled() = Unit

    protected open fun provideCallbacks(): PdfActivityCallbacks? = null

    protected open fun showDownloadUnavailableDialog() {
        Toast.makeText(this, "Download not available", Toast.LENGTH_SHORT).show()
    }

    /**
     * Override to provide a Composable ad banner at the bottom.
     */
    @Composable
    protected open fun AdContent() {
        val adView = callbacks?.getAdView()
        if (adView != null) {
            AndroidView(
                factory = { adView },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // =====================================================================
    //  COMPOSE UI (toolbar only)
    // =====================================================================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PdfTopBar(isNight: Boolean) {
        var showMenu by remember { mutableStateOf(false) }
        val isInEditMode by _isInEditMode

        TopAppBar(
            title = {
                Text(
                    text = pdfConfig.displayName ?: pdfConfig.fileName,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            navigationIcon = {
                if (isInEditMode) {
                    IconButton(onClick = { exitEditMode() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_cross_24),
                            contentDescription = "Exit edit mode"
                        )
                    }
                } else {
                    IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back_24_blue),
                            contentDescription = "Back"
                        )
                    }
                }
            },
            actions = {
                if (isInEditMode && pdfConfig.showEditButtons) {
                    // Save button in edit mode
                    IconButton(onClick = { performSaveAnnotations() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_download_done_24),
                            contentDescription = "Save annotations"
                        )
                    }
                }

                if (!isInEditMode) {
                    // Night mode toggle
                    if (pdfConfig.showNightModeToggle) {
                        Switch(
                            checked = isNight,
                            onCheckedChange = { checked ->
                                _isNight.value = checked
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit { putBoolean(KEY_PDF_DARK, checked) }
                                applyStatusBarStyle()
                            }
                        )
                    }

                    // Search button
                    if (pdfConfig.showSearchButton && isPdfEditingSupported()) {
                        IconButton(onClick = { toggleSearch() }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search_24),
                                contentDescription = "Search in PDF"
                            )
                        }
                    }

                    // Fullscreen button
                    if (pdfConfig.showFullscreenButton) {
                        IconButton(onClick = { toggleFullscreen() }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fullscreeen_menu),
                                contentDescription = "Fullscreen"
                            )
                        }
                    }

                    // Overflow menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (pdfConfig.showOrientationOption) {
                                DropdownMenuItem(
                                    text = { Text("Change Orientation") },
                                    onClick = {
                                        showMenu = false
                                        handleMenuAction(PdfMenuAction.CHANGE_ORIENTATION)
                                    }
                                )
                            }
                            if (pdfConfig.showDownloadOption) {
                                DropdownMenuItem(
                                    text = { Text("Download PDF") },
                                    onClick = {
                                        showMenu = false
                                        handleMenuAction(PdfMenuAction.DOWNLOAD)
                                    }
                                )
                            }
                            if (pdfConfig.showSaveAsOption) {
                                DropdownMenuItem(
                                    text = { Text("Save As") },
                                    onClick = {
                                        showMenu = false
                                        handleMenuAction(PdfMenuAction.SAVE_AS)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    // =====================================================================
    //  FULLSCREEN
    // =====================================================================

    private fun toggleFullscreen() {
        val newValue = !_isFullScreen.value
        _isFullScreen.value = newValue
        // Hide/show native chrome views for true fullscreen
        topBarComposeView.isVisible = !newValue
        adComposeView.isVisible = !newValue
    }

    // =====================================================================
    //  PDF OPEN / DECRYPT
    // =====================================================================

    private fun prepareAndOpenPdf() {
        lifecycleScope.launch {
            _isLoading.value = true
            while (true) {
                val prepareResult = withContext(Dispatchers.IO) { preparePdfSource() }
                if (prepareResult.isSuccess) {
                    if (isPdfEditingSupported()) {
                        openPdf()
                    } else {
                        Toast.makeText(
                            this@PdfActivityCompose,
                            "PDF viewing is not supported on this device",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    _isLoading.value = false
                    return@launch
                }

                val error = prepareResult.exceptionOrNull()
                if (error is PdfPasswordRequiredException) {
                    val password = requestPdfPassword(error.isIncorrectPassword)
                    if (password == null) {
                        onPdfPasswordRequestCancelled()
                        _isLoading.value = false
                        return@launch
                    }
                    pdfConfig = pdfConfig.copy(password = password)
                    onPdfPasswordUpdated(password)
                    continue
                }

                val message = error?.message ?: "Failed to open PDF"
                Toast.makeText(this@PdfActivityCompose, message, Toast.LENGTH_SHORT).show()
                _isLoading.value = false
                return@launch
            }
        }
    }

    private fun isPdfEditingSupported(): Boolean {
        return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18
    }

    @SuppressLint("NewApi")
    private fun reattachExistingFragment() {
        val fragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)
            as? EditablePdfViewerFragmentExtended
        if (fragment != null) {
            pdfViewerFragment = fragment
            setupEditModeCallbacks(fragment)
            _isLoading.value = false
        } else {
            if (shouldAutoOpenPdf()) {
                prepareAndOpenPdf()
            }
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
                            this@PdfActivityCompose,
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
                sourceFile.copyTo(workingFile, overwrite = true)
            } else {
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
        val fm = supportFragmentManager
        pdfViewerFragment = fm.findFragmentByTag(FRAGMENT_TAG) as? EditablePdfViewerFragment

        if (pdfViewerFragment == null) {
            val fragment = EditablePdfViewerFragmentExtended()
            pdfViewerFragment = fragment

            fm.beginTransaction()
                .replace(fragmentContainerId, fragment, FRAGMENT_TAG)
                .commitAllowingStateLoss()
            fm.executePendingTransactions()

            setupEditModeCallbacks(fragment)
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
        _isInEditMode.value = true
        pdfViewerFragment?.isTextSearchActive = false
        callbacks?.onEditModeChanged(true)
    }

    private fun onEditModeExited() {
        _isInEditMode.value = false
        callbacks?.onEditModeChanged(false)
    }

    @SuppressLint("NewApi")
    private fun exitEditMode() {
        if (!isPdfEditingSupported()) return
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

    @SuppressLint("NewApi")
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

        fragment.onSaveComplete = {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = saver.save(workingFile, getOriginalFile(), pdfConfig.password)
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@PdfActivityCompose,
                            "Annotations saved",
                            Toast.LENGTH_SHORT
                        ).show()
                        callbacks?.onAnnotationsSaved()
                    } else {
                        val error = result.exceptionOrNull() ?: Exception("Unknown error")
                        Toast.makeText(
                            this@PdfActivityCompose,
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
            callbacks?.onAnnotationSaveFailed(error)
        }

        fragment.saveAnnotations(workingFile)
    }

    // =====================================================================
    //  SEARCH
    // =====================================================================

    @SuppressLint("NewApi")
    private fun toggleSearch() {
        val fragment = pdfViewerFragment ?: return
        fragment.isTextSearchActive = !fragment.isTextSearchActive
    }

    // =====================================================================
    //  MENU ACTIONS
    // =====================================================================

    private fun handleMenuAction(action: PdfMenuAction) {
        val handled = callbacks?.onMenuAction(action) ?: false
        if (!handled) {
            when (action) {
                PdfMenuAction.CHANGE_ORIENTATION -> {
                    requestedOrientation =
                        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }

                PdfMenuAction.DOWNLOAD -> {
                    if (pdfConfig.isDownloadable) downloadPdf()
                    else showDownloadUnavailableDialog()
                }

                PdfMenuAction.SAVE_AS -> {
                    savePdfAs()
                }
            }
        }
    }

    private fun savePdfAs() {
        // Prefer the working file because it contains the rendered/annotated document.
        val sourceFile = getSaveAsSourceFile()
        if (!sourceFile.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        pendingSaveAsSourceFile = sourceFile
        val suggestedName = buildSuggestedPdfFileName(sourceFile)
        saveAsLauncher.launch(suggestedName)
    }

    private fun buildSuggestedPdfFileName(sourceFile: File): String {
        val baseName = (pdfConfig.displayName ?: pdfConfig.fileName)
            .ifBlank { sourceFile.name }
            .trim()

        return if (baseName.endsWith(".pdf", ignoreCase = true)) {
            baseName
        } else {
            "$baseName.pdf"
        }
    }

    private fun getSaveAsSourceFile(): File {
        val workingFile = getWorkingFile()
        return if (workingFile.exists() && workingFile.length() > 0L) {
            workingFile
        } else {
            getOriginalFile()
        }
    }

    private fun copyFileToUri(sourceFile: File, targetUri: Uri): Boolean {
        return try {
            contentResolver.openOutputStream(targetUri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            } != null
        } catch (_: Exception) {
            false
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
                Toast.makeText(
                    applicationContext,
                    if (success) "PDF download completed" else "PDF download failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun decryptAndCopyToDownloads(
        sourceFile: File, displayName: String, password: String?
    ): Boolean {
        return try {
            val decryptedFile = File(filesDir, "de_${sourceFile.name}")
            val inputFd =
                ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = if (!password.isNullOrEmpty()) {
                PdfRenderer(inputFd, LoadParams.Builder().setPassword(password).build())
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

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri)?.use { os ->
                    FileInputStream(decryptedFile).use { it.copyTo(os) }
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

    @SuppressLint("NewApi")
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    _isInEditMode.value -> exitEditMode()
                    _isFullScreen.value -> {
                        _isFullScreen.value = false
                        topBarComposeView.isVisible = true
                        adComposeView.isVisible = true
                    }
                    else -> {
                        saveCurrentPage()
                        callbacks?.onNavigateBack()
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
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !_isNight.value
    }

    private fun applyNightModeLayer(view: View, nightMode: Boolean) {
        if (nightMode) {
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
            view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun cleanupWorkingFile(reason: String) {
        if (workingFileCleanupDone) return
        runCatching {
            val workingFile = getWorkingFile()
            if (!workingFile.exists()) {
                workingFileCleanupDone = true
                return
            }
            if (workingFile.delete()) workingFileCleanupDone = true
            Log.d(TAG, "cleanupWorkingFile($reason): deleted=$workingFileCleanupDone")
        }.onFailure {
            Log.w(TAG, "cleanupWorkingFile($reason) failed", it)
        }
    }

    companion object {
        private const val FRAGMENT_TAG = "pdf_viewer_fragment_tag"
        private const val PREFS_NAME = "generic_pdf_prefs"
        private const val KEY_PDF_DARK = "isPdfDark"
        private const val TAG = "PdfActivityCompose"
    }
}
