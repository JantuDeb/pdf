# PDF reader module (Compose)

A PDF reader for Android to subclass and configure: the AndroidX PDF viewer with annotation, a
plain viewer to fall back on, and everything around them drawn in Compose.

It knows nothing about the app it is in. Where the file comes from, what its password is, what
"Ask" or "Save" do, how the bars look: the host says, through `PdfConfig`, a few overridable
methods and `PdfActivityCallbacks`.

## Branches

- `main`: the View-based reader (`PdfActivity`) the Play Store app ships. Needs the old app's
  `:core` module.
- `compose`: this reader, `PdfActivityCompose`, on its own. The two are not meant to be merged;
  a fix to shared behaviour (opening, saving, falling back) is made in each.

## What it does

- Opens a PDF from the app's files, password-protected or not
- Pages scroll vertically, one under the other
- Annotation (AndroidX PDF ink) with a pluggable save strategy, `PdfAnnotationSaver`
- Night mode that keeps the page's colours instead of inverting them
- Search in the document, fullscreen, orientation, download to the device's Downloads
- A top bar with a title, a subtitle and a bookmark; a page badge over the page ("6 / 10")
- A row of tools under the page: Pages, Night, Ask, Save, Next (`PdfConfig.tools`)
- A list of pages to jump to, and "Back where you stopped: page 6" on reopening a file
- A message with "Try again" in place of the page when the file cannot be fetched or opened
- A first-run walkthrough of the annotation tools
- An ad slot, and hooks for analytics, menus, tools and page persistence
- Light and dark bars from `PdfTheme`, edge to edge

### The two viewers

| | When | Gives |
| --- | --- | --- |
| AndroidX PDF (`pdf-viewer-fragment`, `pdf-ink`) | the device has SDK extension 18 of Android 12 | everything above |
| Fallback: [Bouquet](https://github.com/GRizzi91/bouquet) | the device lacks it, or AndroidX could not load the file | pages, vertical scroll, pinch and double-tap zoom, night mode, the page badge and list, the remembered page. No annotation, no search |

Bouquet is Compose on Android's own `PdfRenderer`: no native library comes with it.

### Passwords

The reader never asks the user for a password and never guesses where one comes from.

1. The host gives the password in `PdfConfig.password`, if it has one.
2. Before either viewer opens the file, `writeDecryptedPdfToFile()` writes an unlocked working
   copy to the cache. It throws `PdfPasswordRequiredException` when the password is missing or
   wrong.
3. The reader then calls the host's `requestPdfPassword(isRetry)`. The host decides what that
   means: fetch the key again, show its own prompt, or return null to give up. On null the reader
   shows its "could not open" message and calls `onPdfPasswordRequestCancelled()`.

The default `writeDecryptedPdfToFile()` leaves unlocking to the platform, which can do it on
Android 15, and on Android 12 to 14 with the PDF system update (SDK extension 13). An app that
must open locked files on every device overrides it with its own PDF library. With PdfBox:

```kotlin
override fun writeDecryptedPdfToFile(sourceFile: File, targetFile: File, password: String?) {
    if (canOpenPdfWithoutPassword(sourceFile)) {
        sourceFile.copyTo(targetFile, overwrite = true)
        return
    }
    if (password.isNullOrEmpty()) throw PdfPasswordRequiredException()
    try {
        PDDocument.load(sourceFile, password).use { document ->
            document.isAllSecurityToBeRemoved = true
            document.save(targetFile)
        }
    } catch (_: InvalidPasswordException) {
        throw PdfPasswordRequiredException(isIncorrectPassword = true)
    }
}
```

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
| `bouquet` | `io.github.grizzi91:bouquet` |
| `compose-ui`, `compose-foundation`, `compose-material3` | Compose (AndroidX or JetBrains) |
| `androidx-activity-compose` | `androidx.activity:activity-compose` |
| `kotlinx-coroutines-core` | `org.jetbrains.kotlinx:kotlinx-coroutines-core` |
| `lifecycle-runtime-ktx` | `androidx.lifecycle:lifecycle-runtime-ktx` |
| `desugar-jdk-libs` | `com.android.tools:desugar_jdk_libs` |

### 3. Manifest

The AndroidX viewer reads the working copy of the file through a `FileProvider` that serves the
cache directory. The default authority is `${applicationId}.fileprovider`; override
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
`Theme.StudyPathPdf` is ready to use, or give your own.

```xml
<activity
    android:name=".ReaderActivity"
    android:theme="@style/Theme.StudyPathPdf" />
```

The module's `minSdk` is 31. An app with a lower one adds
`<uses-sdk tools:overrideLibrary="com.thestudypath.pdf" />` and opens the reader only on
Android 12 or newer.

## Using it

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

    // The app knows the password; the user never types one.
    override suspend fun requestPdfPassword(isRetry: Boolean): String? = fetchKeyAgain()

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

With the default `PdfConfig` (no `tools`) there is no bottom bar: night mode, search, fullscreen,
download and the overflow menu sit in the top bar.

### What to override

| Member | For |
| --- | --- |
| `providePdfConfig()` | the file, its title, which actions and tools show |
| `preparePdfSource()` | fetching or importing the file before it opens |
| `resolveSourceFile()` | where the file is, if not `filesDir/fileName` |
| `writeDecryptedPdfToFile()` | how a locked file is unlocked |
| `requestPdfPassword()` | what happens when a password is missing or wrong |
| `provideAnnotationSaver()` | where saved annotations go |
| `provideCallbacks()` | tools, menus, analytics, ads, the remembered page |
| `providePdfTheme(darkTheme)` | the colours and type of the bars |
| `isDarkTheme()` | an app with its own light/dark setting |
| `TopBar()`, `ToolBar()` | drawing a bar with the host's own components |

And to call: `setBookmarked()`, `showMessage()`, `downloadPdf()`, `openPdf()`,
`canOpenPdfWithoutPassword()`, `currentPageIndex`.

### Theme

`PdfTheme` holds the colours (`PdfColors`) and text styles (`PdfTypography`) of everything the
reader draws in Compose. The defaults are StudyPath's Breeze, light and dark; any host passes its
own values and font:

```kotlin
override fun providePdfTheme(darkTheme: Boolean) =
    PdfTheme(colors = myColors(darkTheme), typography = pdfTypography(MyFont))
```

Night mode is a different thing: it recolours the page and leaves the bars alone.

## Troubleshooting

- **"That didn't load" / "File not found"**: `PdfConfig.fileName` must name a file under the
  app's files directory, or `resolveSourceFile()` must point at it.
- **Pages show but the pen and search are missing**: the fallback viewer is on screen. Either the
  device lacks AndroidX PDF, or the working copy could not be served: check the `FileProvider`
  and its `cache-path`.
- **A locked file does not open on an older phone**: the platform could not unlock it. Override
  `writeDecryptedPdfToFile()`.
- **"Save not configured"**: `provideAnnotationSaver()` returns null.
- **A crash inflating the viewer**: the activity's theme is not a Material 3 theme.
