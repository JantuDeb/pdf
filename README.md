# StudyPath PDF module

The PDF reader the StudyPath Android apps share: the AndroidX PDF viewer with annotation, a
legacy viewer to fall back on, and everything around them (passwords, night mode, search,
download, the annotation walkthrough, the remembered page).

It comes with two activities to subclass. They open, decrypt, save and recover in the same way;
they differ in how the bars are drawn.

| Activity | Bars | Needs |
| --- | --- | --- |
| `PdfActivityCompose` | Compose, drawn to the StudyPath design (Breeze) and themable | nothing from the host |
| `PdfActivity` | Views and XML | the old app's `:core` module, for its toolbar icons |

`PdfActivity` is compiled only when the host build has a `:core` project. A host without one
(studypath-mobile) gets `PdfActivityCompose` alone.

## Branches

- `main`: `PdfActivity` only. What the Play Store app ships.
- `compose`: `main` plus `PdfActivityCompose`. Merge `main` into it when `main` moves; a fix to
  shared behaviour (opening, saving, fallback) then has to be made in `PdfActivityCompose` too.

## What the reader does

Both activities:

- Opens a PDF from the app's files, password-protected or not
- Annotation (AndroidX PDF ink) with a pluggable save strategy, `PdfAnnotationSaver`
- Falls back to the legacy viewer when AndroidX PDF cannot load the file or the device lacks it
- Night mode that keeps the page's colours instead of inverting them
- Search in the document, fullscreen, orientation, download to the device's Downloads
- A first-run walkthrough of the annotation tools
- An ad slot, and hooks for analytics, menus and page persistence (`PdfActivityCallbacks`)

`PdfActivityCompose` adds:

- A subtitle under the title, and a bookmark action
- A page badge over the page ("6 / 10")
- A row of tools under the page: Pages, Night, Ask, Save, Next (`PdfConfig.tools`)
- A list of pages to jump to
- "Back where you stopped: page 6" on reopening a file
- The page count once the file has loaded (`onDocumentLoaded`)
- A message with "Try again" in place of the page when the file cannot be fetched or opened
- Light and dark bars from `PdfTheme`, edge to edge

## Setup

### 1. Include the module

```groovy
// settings.gradle
include ':pdf'
project(':pdf').projectDir = new File(rootDir, 'pdf')
```

```groovy
// the app's build.gradle
dependencies {
    implementation project(':pdf')
}
```

The module applies its plugins by id, so the host's root build file must put them on the
classpath: the Android Gradle plugin (`com.android.library`) and the Compose compiler
(`org.jetbrains.kotlin.plugin.compose`), each with `apply false`.

### 2. Version catalog

The module reads these from the host's `libs` catalog:

| Alias | Artifact |
| --- | --- |
| `appcompat` | `androidx.appcompat:appcompat` |
| `material` | `com.google.android.material:material` |
| `pdf-viewer-fragment` | `androidx.pdf:pdf-viewer-fragment` |
| `pdf-ink` | `androidx.pdf:pdf-ink` |
| version `ink` | the `androidx.ink` family |
| `androidpdfviewer` | the legacy viewer (`com.github.barteksc.pdfviewer`) |
| `compose-ui`, `compose-foundation`, `compose-material3` | Compose (AndroidX or JetBrains) |
| `androidx-activity-compose` | `androidx.activity:activity-compose` |
| `kotlinx-coroutines-core` | `org.jetbrains.kotlinx:kotlinx-coroutines-core` |
| `lifecycle-runtime-ktx` | `androidx.lifecycle:lifecycle-runtime-ktx` |
| `desugar-jdk-libs` | `com.android.tools:desugar_jdk_libs` |

With `:core` present, also `constraintlayout`, `kotlinx-coroutines-android`,
`lifecycle-viewmodel-ktx`, `lifecycle-livedata-ktx` and, if the catalog has them, `firebase-bom`
and `firebase-analytics`.

### 3. Manifest

The viewer reads the working copy of the file through a `FileProvider` that serves the cache
directory. The default authority is `${applicationId}.fileprovider`; override
`fileProviderAuthority()` for another.

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

```xml
<!-- res/xml/file_paths.xml -->
<paths>
    <cache-path name="pdf" path="." />
</paths>
```

Declare your subclass with a Material 3 theme: the AndroidX viewer inflates Material widgets.
`Theme.StudyPathPdf` gives them StudyPath's colours.

```xml
<activity
    android:name=".ReaderActivity"
    android:theme="@style/Theme.StudyPathPdf" />
```

The module's `minSdk` is 31. An app with a lower one adds
`<uses-sdk tools:overrideLibrary="com.thestudypath.pdf" />` and opens the reader only on
Android 12 or newer.

## `PdfActivityCompose`

```kotlin
class ReaderActivity : PdfActivityCompose() {

    override fun providePdfConfig() = PdfConfig(
        fileName = intent.getStringExtra("file_name").orEmpty(),
        displayName = "Exercise 6.3",
        subtitle = "Triangles · NCERT Solutions",
        password = intent.getStringExtra("password"),
        showBookmarkButton = true,
        showFullscreenButton = false,
        showDownloadOption = false,
        showOrientationOption = false,
        tools = listOf(
            PdfReaderTool.PAGES,
            PdfReaderTool.NIGHT,
            PdfReaderTool.ASK,
            PdfReaderTool.SAVE,
            PdfReaderTool.NEXT,
        ),
    )

    // Runs off the main thread under "Opening PDF…". A failure shows "Try again".
    override suspend fun preparePdfSource(): Result<Unit> = runCatching {
        downloadIfMissing(resolveSourceFile())
    }

    override fun provideCallbacks() = object : PdfActivityCallbacks {
        override fun onDocumentLoaded(pageCount: Int) = reportPageCount(pageCount)

        override fun onReaderTool(tool: PdfReaderTool, page: Int): Boolean = when (tool) {
            PdfReaderTool.ASK -> { openAsk(page); true }
            PdfReaderTool.SAVE -> { openSaveSheet(); true }
            PdfReaderTool.NEXT -> { openNextFile(); true }
            PdfReaderTool.BOOKMARK -> { toggleBookmark { saved -> setBookmarked(saved) }; true }
            else -> false // Pages and Night are the reader's own
        }

        override fun getPagePersistence() = pageStore
    }
}
```

With the default `PdfConfig` (no `tools`) the reader has `PdfActivity`'s actions instead: night
mode, search, fullscreen, download and the overflow menu, all in the top bar.

### What to override

| Member | For |
| --- | --- |
| `providePdfConfig()` | the file, its title, which actions and tools show |
| `preparePdfSource()` | fetching or importing the file before it opens |
| `resolveSourceFile()` | where the file is, if not `filesDir/fileName` |
| `writeDecryptedPdfToFile()` | files protected by the app rather than by a PDF password |
| `provideAnnotationSaver()` | where saved annotations go |
| `provideCallbacks()` | tools, menus, analytics, ads, the remembered page |
| `requestPdfPassword()` | asking the user for a password |
| `providePdfTheme(darkTheme)` | the colours and type of the bars |
| `isDarkTheme()` | an app with its own light/dark setting |
| `TopBar()`, `ToolBar()` | drawing a bar with the host's own components |

And to call: `setBookmarked()`, `showMessage()`, `downloadPdf()`, `openPdf()`,
`currentPageIndex`.

### Theme

`PdfTheme` holds the colours (`PdfColors`) and text styles (`PdfTypography`) of everything the
reader draws in Compose. The defaults are Breeze, light and dark, with the brand `#122DA4`. A host
passes its own values and font:

```kotlin
override fun providePdfTheme(darkTheme: Boolean) =
    PdfTheme.default(darkTheme, fontFamily = Inter)
```

Night mode is a different thing: it recolours the page and leaves the bars alone.

## `PdfActivity`

```kotlin
class ClassicPdfViewerActivity : PdfActivity() {

    override fun providePdfConfig() = PdfConfig(
        fileName = intent.getStringExtra("file_name").orEmpty(),
        displayName = intent.getStringExtra("display_name") ?: "Document.pdf",
        isDownloadable = true,
    )

    override fun provideAnnotationSaver() = object : PdfAnnotationSaver {
        override suspend fun save(workingFile: File, originalFile: File, password: String?) =
            runCatching { workingFile.copyTo(originalFile, overwrite = true); Unit }
    }
}
```

## Troubleshooting

- **"That didn't load" / "File not found"**: `PdfConfig.fileName` must name a file under the
  app's files directory, or `resolveSourceFile()` must point at it.
- **The legacy viewer opens instead of the AndroidX one**: the working copy could not be served.
  Check the `FileProvider` and its `cache-path`.
- **"Save not configured"**: `provideAnnotationSaver()` returns null.
- **A crash inflating the viewer**: the activity's theme is not a Material 3 theme.
