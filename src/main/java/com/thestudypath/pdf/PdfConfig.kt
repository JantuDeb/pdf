package com.thestudypath.pdf

import android.content.Intent

/**
 * Configuration for GenericPdfActivity.
 * Each host app builds this from its own intent extras, shared prefs, etc.
 */
data class PdfConfig(
    val fileName: String,
    val displayName: String? = null,
    val password: String? = null,
    val initialPage: Int = 0,
    val isDownloadable: Boolean = false,
    val showNightModeToggle: Boolean = true,
    val showSearchButton: Boolean = true,
    val showEditButtons: Boolean = true,
    val showDownloadOption: Boolean = true,
    val showOrientationOption: Boolean = true,
    val showFullscreenButton: Boolean = true,
) {
    companion object {
        /**
         * Convenience builder from a standard intent.
         * Host apps can use this as a starting point then .copy() what they need.
         */
        fun fromIntent(
            intent: Intent,
            resolvePassword: () -> String? = { null },
            resolveInitialPage: () -> Int = { 0 }
        ): PdfConfig {
            return PdfConfig(
                fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "",
                displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME),
                password = resolvePassword(),
                initialPage = resolveInitialPage(),
                isDownloadable = intent.getBooleanExtra(EXTRA_IS_DOWNLOADABLE, false),
            )
        }

        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_IS_DOWNLOADABLE = "is_downloadable"
    }
}