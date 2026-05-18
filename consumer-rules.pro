# Add project specific consumer ProGuard rules here.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# EditablePdfViewerFragmentExtended customizes AndroidX PDF's default pen through
# reflection because pdf-ink does not expose a public defaults API.
-keep class androidx.pdf.ink.EditablePdfViewerFragment { *; }
-keep class androidx.pdf.ink.EditableDocumentViewModel { *; }
-keep class androidx.pdf.ink.view.AnnotationToolbar { *; }
-keep class androidx.pdf.ink.view.AnnotationToolbarViewModel { *; }
-keep class androidx.pdf.ink.view.tool.AnnotationToolInfo { *; }
-keep class androidx.pdf.ink.view.tool.Pen { *; }
-keep class androidx.pdf.ink.view.state.ToolbarIntent { *; }
-keep class androidx.pdf.ink.view.state.ToolbarIntent$ColorSelected { *; }
-keep class androidx.pdf.ink.view.state.ToolbarIntent$BrushSizeChanged { *; }

# AndroidX PDF uses a sandboxed bound service, Binder stubs, saved state, and
# internal APIs across pdf-viewer/pdf-ink/pdf-document-service. Keep the stack
# stable in app release builds; debug builds do not exercise the same release
# shrink/obfuscation path.
-keep class androidx.pdf.** { *; }
-keep interface androidx.pdf.** { *; }
-keep enum androidx.pdf.** { *; }

# Keep the embedded legacy fallback viewer stable in release builds too.
-keep class com.github.barteksc.pdfviewer.** { *; }
-keep class com.shockwave.pdfium.** { *; }
