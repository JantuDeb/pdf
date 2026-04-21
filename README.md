# PDF Module for Android - Compose and View Integrations

Reusable **Android PDF Viewer module** with annotation, search, dark mode, fullscreen, and menu action hooks.

Use this module if you need a **Kotlin PDF viewer library** with both:
- `PdfActivityCompose` (Compose-based toolbar + hybrid PDF container)
- `PdfActivity` (classic View/XML activity)

## Screenshots (Placeholders)

> Add your screenshots later.

![Compose PDF Toolbar](../docs/images/pdf-compose-toolbar.png)
![Annotation Mode](../docs/images/pdf-annotation-mode.png)
![Save As Destination Picker](../docs/images/pdf-save-as-picker.png)

## What You Get

- Password-ready PDF opening flow
- Annotation editing with pluggable save strategy (`PdfAnnotationSaver`)
- Save As to user-selected filesystem destination (SAF CreateDocument)
- Optional Download menu action
- Popup/settings menu action callbacks (`PdfMenuAction`)
- Night mode toggle
- Search toggle
- Fullscreen mode
- Orientation switching
- Optional page persistence interface

## Module Setup

### 1) Include module

If you use this repository as-is:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":pdf"))
}
```

### 2) Manifest / FileProvider

Ensure your app has the required `FileProvider` setup used for temporary/working file URIs, matching your package authority pattern.

> Example authority pattern used in code:
> `${applicationContext.packageName}.fileprovider`

## Core APIs

- `PdfConfig`
- `PdfActivityCompose`
- `PdfActivity`
- `PdfAnnotationSaver`
- `PdfActivityCallbacks`
- `PdfMenuAction`

## Compose Integration Example (`PdfActivityCompose`)

```kotlin
package com.example.viewer

import com.thestudypath.pdf.PdfActivityCompose
import com.thestudypath.pdf.PdfConfig
import com.thestudypath.pdf.interfaces.PdfAnnotationSaver
import java.io.File

class ComposePdfViewerActivity : PdfActivityCompose() {

    override fun providePdfConfig(): PdfConfig {
        return PdfConfig(
            fileName = intent.getStringExtra("internal_file_name") ?: "",
            displayName = intent.getStringExtra("display_name") ?: "Document.pdf",
            isDownloadable = true,
            showNightModeToggle = true,
            showSearchButton = true,
            showEditButtons = true,
            showSaveAsOption = true,
            showDownloadOption = true,
            showOrientationOption = true,
            showFullscreenButton = true,
        )
    }

    override fun provideAnnotationSaver(): PdfAnnotationSaver {
        return object : PdfAnnotationSaver {
            override suspend fun save(
                workingFile: File,
                originalFile: File,
                password: String?
            ): Result<Unit> {
                return try {
                    // Save button writes annotations back to original
                    workingFile.copyTo(originalFile, overwrite = true)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
    }
}
```

## Non-Compose Integration Example (`PdfActivity`)

```kotlin
package com.example.viewer

import com.thestudypath.pdf.PdfActivity
import com.thestudypath.pdf.PdfConfig
import com.thestudypath.pdf.interfaces.PdfAnnotationSaver
import java.io.File

class ClassicPdfViewerActivity : PdfActivity() {

    override fun providePdfConfig(): PdfConfig {
        return PdfConfig(
            fileName = intent.getStringExtra("internal_file_name") ?: "",
            displayName = intent.getStringExtra("display_name") ?: "Document.pdf",
            isDownloadable = true,
            showNightModeToggle = true,
            showSearchButton = true,
            showEditButtons = true,
            showSaveAsOption = true,
            showDownloadOption = true,
            showOrientationOption = true,
            showFullscreenButton = true,
        )
    }

    override fun provideAnnotationSaver(): PdfAnnotationSaver {
        return object : PdfAnnotationSaver {
            override suspend fun save(
                workingFile: File,
                originalFile: File,
                password: String?
            ): Result<Unit> {
                return try {
                    workingFile.copyTo(originalFile, overwrite = true)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
    }
}
```

## Menu Behavior (Default)

- `Save` (in edit mode): saves annotations to original file via `PdfAnnotationSaver`
- `Save As`: opens filesystem destination picker and exports the annotated/working PDF
- `Download PDF`: optional action, controlled by config flags

## Customization Points

Implement `PdfActivityCallbacks` to customize:
- analytics events (`onPdfOpened`, `onPageChanged`)
- edit-state events (`onEditModeChanged`)
- save success/failure hooks
- popup menu handling and custom action mapping
- ad container view
- page persistence strategy

## Troubleshooting

- **File not found**: verify `PdfConfig.fileName` points to a file in expected app storage.
- **Save As failed**: confirm destination is writable and user did not cancel picker.
- **No save action**: ensure `provideAnnotationSaver()` returns a non-null implementation.
- **Menu options missing**: check `PdfConfig` flags (`showSaveAsOption`, `showDownloadOption`, etc.).

## SEO Keywords

Android PDF module, PDF viewer library Android Kotlin, Jetpack Compose PDF activity, Android PDF annotation save as, PDF reader with search and dark mode, reusable PDF component Android.

