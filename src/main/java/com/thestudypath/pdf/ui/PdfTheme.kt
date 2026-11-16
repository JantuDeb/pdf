package com.thestudypath.pdf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * What the Compose reader is drawn with. The defaults are StudyPath's Breeze design system (brand
 * `#122DA4`), light and dark; a host with its own theme passes its values in through
 * `PdfActivityCompose.providePdfTheme`.
 *
 * Night mode for the page is a separate thing: it recolours the PDF, not the bars.
 */
@Immutable
data class PdfTheme(
    val colors: PdfColors,
    val typography: PdfTypography,
) {
    companion object {
        fun light(fontFamily: FontFamily = FontFamily.Default) = PdfTheme(PdfLightColors, pdfTypography(fontFamily))

        fun dark(fontFamily: FontFamily = FontFamily.Default) = PdfTheme(PdfDarkColors, pdfTypography(fontFamily))

        fun default(
            dark: Boolean,
            fontFamily: FontFamily = FontFamily.Default,
        ) = if (dark) dark(fontFamily) else light(fontFamily)
    }
}

/** Colours by role, named as Breeze names them. */
@Immutable
data class PdfColors(
    /** The top bar, and the area behind the page while it loads. */
    val surface: Color,
    /** The bottom bar, sheets and dialogs. */
    val surfaceRaised: Color,
    /** Behind a message that stands in for the page; an unselected cell. */
    val surfaceSunken: Color,
    val ink: Color,
    val inkMuted: Color,
    val line: Color,
    /** The filled button; the spinner. */
    val brand: Color,
    val onBrand: Color,
    /** Brand-coloured text and icons on a surface. */
    val brandText: Color,
    /** Behind the tool or page that is on. */
    val brandSoft: Color,
    /** Behind a sheet or dialog. */
    val scrim: Color,
    /** Snackbars and the page badge: a dark slab in either theme. */
    val inverse: Color,
    val onInverse: Color,
    /** The shadow under raised pieces: tinted with the brand in light, black in dark. */
    val shadow: Color,
    val isDark: Boolean,
)

val PdfLightColors =
    PdfColors(
        surface = Color(0xFFF8F9FC),
        surfaceRaised = Color(0xFFFFFFFF),
        surfaceSunken = Color(0xFFEEF1F7),
        ink = Color(0xFF0B0D14),
        inkMuted = Color(0xFF5F6170),
        line = Color(0xFFE3E7EB),
        brand = Color(0xFF122DA4),
        onBrand = Color(0xFFFFFFFF),
        brandText = Color(0xFF122DA4),
        brandSoft = Color(0xFFE8ECFB),
        scrim = Color(0x7A080C1E),
        inverse = Color(0xFF0B0D14),
        onInverse = Color(0xFFFFFFFF),
        shadow = Color(0xFF122DA4),
        isDark = false,
    )

val PdfDarkColors =
    PdfColors(
        surface = Color(0xFF0C111D),
        surfaceRaised = Color(0xFF151B2B),
        surfaceSunken = Color(0xFF1F2638),
        ink = Color(0xFFF3F5FA),
        inkMuted = Color(0xFFA5AABB),
        line = Color(0xFF2C3448),
        brand = Color(0xFF2F4FD6),
        onBrand = Color(0xFFFFFFFF),
        brandText = Color(0xFF93A5FF),
        brandSoft = Color(0xFF18244A),
        scrim = Color(0x7A080C1E),
        inverse = Color(0xFF000000),
        onInverse = Color(0xFFFFFFFF),
        shadow = Color(0xFF000000),
        isDark = true,
    )

@Immutable
data class PdfTypography(
    /** The title alone in the top bar. */
    val title: TextStyle,
    /** The title when a subtitle sits under it. */
    val titleSmall: TextStyle,
    /** The subtitle; the line under the spinner. */
    val caption: TextStyle,
    /** Under a bottom tool's icon. */
    val toolLabel: TextStyle,
    /** The page badge. */
    val badge: TextStyle,
    val body: TextStyle,
    val button: TextStyle,
    /** A snackbar; a menu item. */
    val message: TextStyle,
    val sheetTitle: TextStyle,
    val sheetBody: TextStyle,
)

fun pdfTypography(family: FontFamily = FontFamily.Default): PdfTypography {
    fun style(
        size: Number,
        line: Number,
        weight: FontWeight,
        tracking: Double = 0.0,
    ) = TextStyle(
        fontFamily = family,
        fontSize = size.toDouble().sp,
        lineHeight = line.toDouble().sp,
        fontWeight = weight,
        letterSpacing = tracking.em,
        // The full line height with the letters centred in it, as the design's CSS lays text out.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    return PdfTypography(
        title = style(20, 26, FontWeight.Bold, -0.025),
        titleSmall = style(16, 22, FontWeight.Bold, -0.025),
        caption = style(12, 16, FontWeight.Normal),
        toolLabel = style(11, 14, FontWeight.SemiBold),
        badge = style(11, 16, FontWeight.Bold),
        body = style(15, 22, FontWeight.Normal),
        button = style(15, 20, FontWeight.SemiBold, -0.01),
        message = style(13.5, 13.5 * 1.4, FontWeight.Normal),
        sheetTitle = style(22, 28, FontWeight.Bold, -0.025),
        sheetBody = style(14.5, 14.5 * 1.5, FontWeight.Normal),
    )
}

internal val LocalPdfTheme = staticCompositionLocalOf { PdfTheme.light() }

/** Draws [content] with [theme]: for a host that reuses the reader's pieces in a bar of its own. */
@Composable
fun PdfReaderTheme(
    theme: PdfTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalPdfTheme provides theme, content = content)
}
