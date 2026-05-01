# Add project specific consumer ProGuard rules here.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# EditablePdfViewerFragmentExtended customizes AndroidX PDF's default pen through
# reflection because pdf-ink alpha17 does not expose a public defaults API.
-keep class androidx.pdf.ink.EditablePdfViewerFragment { *; }
-keep class androidx.pdf.ink.EditableDocumentViewModel { *; }
-keep class androidx.pdf.ink.view.AnnotationToolbar { *; }
-keep class androidx.pdf.ink.view.AnnotationToolbarViewModel { *; }
-keep class androidx.pdf.ink.view.tool.AnnotationToolInfo { *; }
-keep class androidx.pdf.ink.view.tool.Pen { *; }
-keep class androidx.pdf.ink.view.state.ToolbarIntent { *; }
-keep class androidx.pdf.ink.view.state.ToolbarIntent$ColorSelected { *; }
-keep class androidx.pdf.ink.view.state.ToolbarIntent$BrushSizeChanged { *; }
