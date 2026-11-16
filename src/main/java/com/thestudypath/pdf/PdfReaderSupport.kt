package com.thestudypath.pdf

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import java.io.File
import java.io.FileInputStream

// What PdfActivityCompose shares with nothing on screen: the same file handling and night filter
// as PdfActivity, kept apart from the activity so they can be read and tested on their own.

/**
 * AndroidX PDF with annotation needs SDK extension 18 of Android 12. Below that: the fallback viewer.
 * The Android 12 check is for a host that lowers this module's minSdk with `tools:overrideLibrary`.
 */
@SuppressLint("ObsoleteSdkInt")
internal fun isAndroidxPdfSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18

/**
 * The paint that turns a page dark: luminance inverted, a softened version of each hue kept.
 * Full RGB inversion turns blue headings into harsh yellow/orange. White paper maps to charcoal
 * (32) and black text to off-white (232).
 */
internal fun pdfNightModePaint(): Paint {
    val chroma = 0.55f
    val contrast = 200f / 255f
    val red = (chroma + contrast) * 0.2126f
    val green = (chroma + contrast) * 0.7152f
    val blue = (chroma + contrast) * 0.0722f
    val nightMatrix =
        ColorMatrix(
            floatArrayOf(
                chroma - red, -green, -blue, 0f, 232f,
                -red, chroma - green, -blue, 0f, 232f,
                -red, -green, chroma - blue, 0f, 232f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    return Paint().apply { colorFilter = ColorMatrixColorFilter(nightMatrix) }
}

internal fun canOpenPdfWithoutPassword(sourceFile: File): Boolean =
    runCatching {
        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { inputFd ->
            PdfRenderer(inputFd).use { renderer -> renderer.pageCount >= 0 }
        }
    }.getOrDefault(false)

/**
 * Writes [sourceFile] to [targetFile] without its password.
 *
 * Unlocking is the platform's work: Android 15 does it itself, Android 12 to 14 through the PDF
 * system update (SDK extension 13).
 *
 * @throws PdfPasswordRequiredException when the file is locked and [password] is missing or wrong.
 * @throws UnsupportedOperationException when the file is locked and this device cannot unlock it.
 */
@SuppressLint("NewApi")
internal fun writeDecryptedPdf(
    sourceFile: File,
    targetFile: File,
    password: String?,
) {
    if (canOpenPdfWithoutPassword(sourceFile)) {
        sourceFile.copyTo(targetFile, overwrite = true)
        return
    }
    if (password.isNullOrEmpty()) {
        throw PdfPasswordRequiredException()
    }
    val platformUnlocks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    val updateUnlocks =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
    if (!platformUnlocks && !updateUnlocks) {
        throw UnsupportedOperationException("This device cannot open password-protected PDFs")
    }

    try {
        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { inputFd ->
            val loadParams = LoadParams.Builder().setPassword(password).build()
            ParcelFileDescriptor
                .open(
                    targetFile,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                ).use { outputFd ->
                    if (platformUnlocks) {
                        PdfRenderer(inputFd, loadParams).use { it.write(outputFd, true) }
                    } else {
                        PdfRendererPreV(inputFd, loadParams).use { it.write(outputFd, true) }
                    }
                }
        }
    } catch (e: SecurityException) {
        // What the platform throws for a password that does not open the file.
        throw PdfPasswordRequiredException(isIncorrectPassword = true)
    }
}

/** Copies [file] into the device's Downloads as [displayName]. Null when it could not. */
internal fun copyPdfToDownloads(
    context: Context,
    file: File,
    displayName: String,
): Uri? =
    try {
        val contentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val resolver = context.applicationContext.contentResolver
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)?.also { targetUri ->
            resolver.openOutputStream(targetUri)?.use { outputStream ->
                FileInputStream(file).use { inputStream -> inputStream.copyTo(outputStream) }
            }
        }
    } catch (e: Exception) {
        Log.w("PdfReaderSupport", "Copy to Downloads failed", e)
        null
    }

internal fun View.runWhenMeasured(action: () -> Unit) {
    if (width > 0 && height > 0) {
        action()
        return
    }

    viewTreeObserver.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (width > 0 && height > 0) {
                    if (viewTreeObserver.isAlive) {
                        viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    action()
                }
                return true
            }
        },
    )
}

/**
 * The decrypted copies the viewer works on, in the cache directory. One per open reader; a copy
 * left behind by a session that was killed is removed the next time a reader starts.
 */
internal object PdfWorkingFiles {
    private const val PREFIX = "reader_working_"
    private const val TAG = "PdfWorkingFiles"
    private val active = mutableSetOf<String>()

    /** [fileName] may be a path under the files directory: the working copy is always flat. */
    fun nameFor(fileName: String): String = PREFIX + fileName.replace(File.separatorChar, '_')

    fun register(name: String) {
        synchronized(active) { active += name }
    }

    fun unregister(name: String) {
        synchronized(active) { active -= name }
    }

    fun deleteStale(cacheDir: File) {
        runCatching {
            val inUse = synchronized(active) { active.toSet() }
            cacheDir
                .listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.startsWith(PREFIX) }
                ?.filterNot { it.name in inUse }
                ?.forEach { stale -> Log.d(TAG, "Stale cleanup: file=${stale.name}, deleted=${stale.delete()}") }
        }.onFailure {
            Log.w(TAG, "Stale cleanup failed", it)
        }
    }
}
