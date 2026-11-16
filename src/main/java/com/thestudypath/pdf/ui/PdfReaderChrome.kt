package com.thestudypath.pdf.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.thestudypath.pdf.R
import com.thestudypath.pdf.interfaces.PdfReaderTool
import kotlinx.coroutines.launch

// The reader as StudyPath's design draws it (screen 15): a top bar, the page, a row of tools.
// Sizes are the design's own; colours and type come from PdfTheme.

/** The top-bar buttons the annotation walkthrough points at. */
enum class PdfTopBarAnchor { CLOSE_EDIT, SAVE }

/**
 * The bar above the page: back, the title over an optional subtitle, then the actions that are
 * switched on. While annotating it is close, the title and save.
 *
 * [onAnchor] reports where the close and save buttons are, in this bar's own coordinates.
 */
@Composable
fun PdfTopBar(
    state: PdfReaderState,
    actions: PdfReaderActions,
    modifier: Modifier = Modifier,
    onAnchor: (PdfTopBarAnchor, Rect) -> Unit = { _, _ -> },
) {
    val theme = LocalPdfTheme.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.isEditing) {
            PdfIconButton(
                PdfIcons.Close,
                stringResource(R.string.pdf_action_exit_edit),
                actions.exitEditMode,
                Modifier.onGloballyPositioned { onAnchor(PdfTopBarAnchor.CLOSE_EDIT, it.boundsInRoot()) },
            )
        } else {
            PdfIconButton(PdfIcons.Back, stringResource(R.string.pdf_action_back), actions.back)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                state.title,
                modifier = Modifier.semantics { heading() },
                style = if (state.subtitle == null) theme.typography.title else theme.typography.titleSmall,
                color = theme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.subtitle != null) {
                Text(
                    state.subtitle,
                    style = theme.typography.caption,
                    color = theme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.isEditing) {
            if (state.showSaveAnnotations) {
                PdfIconButton(
                    PdfIcons.Check,
                    stringResource(R.string.pdf_action_save_annotations),
                    actions.saveAnnotations,
                    Modifier.onGloballyPositioned { onAnchor(PdfTopBarAnchor.SAVE, it.boundsInRoot()) },
                    enabled = !state.isSaving,
                )
            }
        } else {
            if (state.showNightToggle) {
                PdfIconButton(
                    PdfIcons.Night,
                    stringResource(R.string.pdf_tool_night),
                    actions.toggleNight,
                    tint = if (state.isNight) theme.colors.brandText else theme.colors.inkMuted,
                    selected = state.isNight,
                )
            }
            if (state.showSearch) {
                PdfIconButton(PdfIcons.Search, stringResource(R.string.pdf_action_search), actions.search)
            }
            if (state.showFullscreen) {
                PdfIconButton(PdfIcons.Fullscreen, stringResource(R.string.pdf_action_fullscreen), actions.fullscreen)
            }
            if (state.showDownload) {
                PdfIconButton(PdfIcons.Save, stringResource(R.string.pdf_action_download), actions.download)
            }
            if (state.showBookmark) {
                PdfIconButton(
                    PdfIcons.Bookmark,
                    stringResource(R.string.pdf_action_bookmark),
                    { actions.tool(PdfReaderTool.BOOKMARK) },
                    tint = if (state.isBookmarked) theme.colors.brandText else theme.colors.inkMuted,
                    selected = state.isBookmarked,
                )
            }
            if (state.menu.isNotEmpty()) {
                OverflowMenu(state.menu, actions.menu)
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    entries: List<PdfMenuEntry>,
    onSelect: (PdfMenuEntry) -> Unit,
) {
    val theme = LocalPdfTheme.current
    var open by remember { mutableStateOf(false) }
    Box {
        PdfIconButton(PdfIcons.More, stringResource(R.string.pdf_action_more), { open = true })
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 0),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        // Room for the shadow inside the popup's own window.
                        .padding(start = 24.dp, bottom = 32.dp)
                        .pdfShadow(theme.colors, CardShape, y = 6, blur = 20, alpha = 0.12f)
                        .clip(CardShape)
                        .background(theme.colors.surfaceRaised)
                        .widthIn(min = 196.dp, max = 280.dp)
                        .padding(vertical = 8.dp),
                ) {
                    entries.forEach { entry ->
                        Text(
                            entry.title,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .pdfClickable {
                                        open = false
                                        onSelect(entry)
                                    }.defaultMinSize(minHeight = 48.dp)
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                            style = theme.typography.body,
                            color = theme.colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The tools under the page, where the app's tabs would be: an icon in a pill over a label. Ask
 * is the one drawn in the brand colour; Night joins it while night mode is on.
 */
@Composable
fun PdfToolBar(
    state: PdfReaderState,
    actions: PdfReaderActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPdfTheme.current.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(12.dp, ambientColor = shadowTint(colors), spotColor = shadowTint(colors))
                .background(colors.surfaceRaised)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tools.forEach { tool ->
            val look = tool.look() ?: return@forEach
            ToolItem(
                icon = look.icon,
                label = stringResource(look.label),
                on = tool == PdfReaderTool.ASK || (tool == PdfReaderTool.NIGHT && state.isNight),
                selected = if (tool == PdfReaderTool.NIGHT) state.isNight else null,
                onClick = { actions.tool(tool) },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolItem(
    icon: ImageVector,
    label: String,
    on: Boolean,
    selected: Boolean?,
    onClick: () -> Unit,
) {
    val theme = LocalPdfTheme.current
    val tint = if (on) theme.colors.brandText else theme.colors.inkMuted
    Column(
        modifier =
            Modifier
                .weight(1f)
                .clip(ControlShape)
                .pdfClickable(onClick = onClick)
                .semantics { if (selected != null) this.selected = selected }
                .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .width(52.dp)
                .height(28.dp)
                .clip(PillShape)
                .then(if (on) Modifier.background(theme.colors.brandSoft) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = theme.typography.toolLabel.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
    }
}

private class ToolLook(
    val icon: ImageVector,
    val label: Int,
)

private fun PdfReaderTool.look(): ToolLook? =
    when (this) {
        PdfReaderTool.PAGES -> ToolLook(PdfIcons.Pages, R.string.pdf_tool_pages)
        PdfReaderTool.NIGHT -> ToolLook(PdfIcons.Night, R.string.pdf_tool_night)
        PdfReaderTool.ASK -> ToolLook(PdfIcons.Ask, R.string.pdf_tool_ask)
        PdfReaderTool.SAVE -> ToolLook(PdfIcons.Save, R.string.pdf_tool_save)
        PdfReaderTool.NEXT -> ToolLook(PdfIcons.Next, R.string.pdf_tool_next)
        // Lives in the top bar.
        PdfReaderTool.BOOKMARK -> null
    }

/**
 * What sits over the page: the spinner while the file opens, the message when it could not, and
 * otherwise the page badge and the snackbar. Where it draws nothing, touches reach the page.
 */
@Composable
fun PdfPageOverlay(
    state: PdfReaderState,
    actions: PdfReaderActions,
    snackbar: String?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPdfTheme.current
    Box(modifier.fillMaxSize()) {
        when (val status = state.status) {
            PdfReaderStatus.Loading ->
                Column(
                    Modifier.fillMaxSize().background(theme.colors.surfaceSunken),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = theme.colors.brand, trackColor = Color.Transparent)
                    Text(
                        stringResource(R.string.pdf_loading_text),
                        style = theme.typography.sheetBody,
                        color = theme.colors.inkMuted,
                    )
                }

            is PdfReaderStatus.Failed ->
                Column(
                    Modifier.fillMaxSize().background(theme.colors.surfaceSunken).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.pdf_error_title),
                        style = theme.typography.title,
                        color = theme.colors.ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        status.message,
                        style = theme.typography.body,
                        color = theme.colors.inkMuted,
                        textAlign = TextAlign.Center,
                    )
                    PdfSecondaryButton(stringResource(R.string.pdf_action_retry), actions.retry)
                }

            PdfReaderStatus.Ready -> Unit
        }
        Column(
            Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val showBadge =
                state.showPageIndicator && state.status == PdfReaderStatus.Ready && state.pageCount > 0 && !state.isEditing
            if (showBadge) {
                PageBadge(state.page + 1, state.pageCount, Modifier.padding(bottom = 12.dp))
            }
            if (snackbar != null) {
                PdfSnackbar(snackbar)
            }
        }
    }
}

/** "6 / 10": the page on screen and how many there are. */
@Composable
private fun PageBadge(
    page: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPdfTheme.current
    val description = stringResource(R.string.pdf_page_of, page, pageCount)
    Text(
        stringResource(R.string.pdf_page_indicator, page, pageCount),
        modifier =
            modifier
                .clip(PillShape)
                .background(theme.colors.inverse)
                .semantics { contentDescription(description) }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        // Figures of one width, so the badge does not twitch as pages go by.
        style = theme.typography.badge.copy(fontFeatureSettings = "tnum"),
        color = theme.colors.onInverse,
        maxLines = 1,
    )
}

private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.contentDescription(text: String) {
    this[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] = listOf(text)
}

/** News that needs no decision: a dark slab that leaves by itself. 14dp from the sides, 22dp up. */
@Composable
private fun PdfSnackbar(
    message: String,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPdfTheme.current
    Text(
        message,
        modifier =
            modifier
                .padding(start = 14.dp, end = 14.dp, bottom = 22.dp)
                .fillMaxWidth()
                .pdfShadow(theme.colors, SnackbarShape, y = 10, blur = 24, alpha = if (theme.colors.isDark) 0.5f else 0.16f)
                .clip(SnackbarShape)
                .background(theme.colors.inverse)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        style = theme.typography.message,
        color = theme.colors.onInverse,
    )
}

/** The list of pages: tap one to go there. The page on screen is marked. */
@Composable
fun PdfPagesSheet(
    page: Int,
    pageCount: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalPdfTheme.current
    PdfSheet(onDismiss) {
        Text(
            stringResource(R.string.pdf_pages_title),
            modifier = Modifier.semantics { heading() },
            style = theme.typography.sheetTitle,
            color = theme.colors.ink,
        )
        // Opens with the current page in view.
        val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (page - PAGE_COLUMNS).coerceAtLeast(0))
        LazyVerticalGrid(
            columns = GridCells.Fixed(PAGE_COLUMNS),
            state = grid,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(pageCount) { index ->
                val current = index == page
                val description = stringResource(R.string.pdf_page_number, index + 1)
                Box(
                    Modifier
                        .height(48.dp)
                        .clip(ControlShape)
                        .background(if (current) theme.colors.brandSoft else theme.colors.surfaceSunken)
                        .pdfClickable { onSelect(index) }
                        .semantics {
                            selected = current
                            contentDescription(description)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (index + 1).toString(),
                        style =
                            theme.typography.button.copy(
                                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                        color = if (current) theme.colors.brandText else theme.colors.ink,
                    )
                }
            }
        }
    }
}

/** Asked when edit mode is closed with annotations that were not saved. */
@Composable
fun PdfUnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalPdfTheme.current
    Box(Modifier.fillMaxSize()) {
        Scrim(onDismiss)
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .pdfShadow(theme.colors, SheetCornerShape, y = 30, blur = 80, alpha = if (theme.colors.isDark) 0.6f else 0.16f)
                    .clip(SheetCornerShape)
                    .background(theme.colors.surfaceRaised)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { isTraversalGroup = true }
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.pdf_unsaved_title),
                modifier = Modifier.semantics { heading() },
                style = theme.typography.sheetTitle,
                color = theme.colors.ink,
            )
            Text(
                stringResource(R.string.pdf_unsaved_message),
                style = theme.typography.sheetBody,
                color = theme.colors.inkMuted,
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PdfPrimaryButton(stringResource(R.string.pdf_unsaved_save), onSave, Modifier.fillMaxWidth())
                PdfSecondaryButton(stringResource(R.string.pdf_unsaved_discard), onDiscard, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The status bar's height in the bar's colour, then [content]: the top of the reader. */
@Composable
fun PdfTopArea(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(LocalPdfTheme.current.colors.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
        content = content,
    )
}

/** Stands where the tool bar would: the navigation bar's height, so nothing sits under it. */
@Composable
fun PdfNavigationBarSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .background(LocalPdfTheme.current.colors.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    )
}

// ── Pieces ─────────────────────────────────────────────────────────────────

@Composable
private fun PdfSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalPdfTheme.current
    Box(Modifier.fillMaxSize()) {
        Scrim(onDismiss)
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    // However much it holds, a sheet stops short of the status bar and of the top third.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .fillMaxHeight(0.66f)
                    .wrapContentHeightBottom()
                    .fillMaxWidth()
                    .pdfShadow(theme.colors, SheetShape, y = -10, blur = 30, alpha = 0.14f, tinted = true)
                    .clip(SheetShape)
                    .background(theme.colors.surfaceRaised)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { isTraversalGroup = true }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 2.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(theme.colors.line),
            )
            content()
        }
    }
}

/** As tall as the content, up to the height offered, and resting on the bottom edge. */
private fun Modifier.wrapContentHeightBottom(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        layout(placeable.width, constraints.maxHeight) {
            placeable.place(0, constraints.maxHeight - placeable.height)
        }
    }

@Composable
private fun BoxScope.Scrim(onTap: () -> Unit) {
    val label = stringResource(R.string.pdf_action_close)
    Box(
        Modifier
            .matchParentSize()
            .background(LocalPdfTheme.current.colors.scrim)
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = label,
                onClick = onTap,
            ),
    )
}

/** An icon on its own (`.appbar .act`): drawn at 36dp, touched at 48dp. */
@Composable
private fun PdfIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalPdfTheme.current.colors.inkMuted,
    enabled: Boolean = true,
    selected: Boolean? = null,
) {
    Box(
        modifier =
            modifier
                .size(36.dp)
                .clip(ControlShape)
                .pdfClickable(enabled = enabled, onClick = onClick)
                .semantics { if (selected != null) this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The one brand-filled action. Pressed, it snaps to black (white in dark): no wash, no ripple. */
@Composable
private fun PdfPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPdfTheme.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pressedFill = if (theme.colors.isDark) Color.White else Color.Black
    val pressedLabel = if (theme.colors.isDark) Color.Black else Color.White
    Box(
        modifier =
            modifier
                .shadow(if (pressed) 0.dp else 8.dp, CardShape, spotColor = theme.colors.brand)
                .clip(CardShape)
                .background(if (pressed) pressedFill else theme.colors.brand)
                .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = theme.typography.button, color = if (pressed) pressedLabel else theme.colors.onBrand)
    }
}

/** Every other action: a raised surface on a soft shadow. */
@Composable
private fun PdfSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPdfTheme.current
    Box(
        modifier =
            modifier
                .pdfShadow(theme.colors, CardShape, y = 6, blur = 20, alpha = if (theme.colors.isDark) 0.35f else 0.07f)
                .clip(CardShape)
                .background(theme.colors.surfaceRaised)
                .pdfClickable(onClick = onClick)
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = theme.typography.button, color = theme.colors.ink)
    }
}

private val ControlShape = RoundedCornerShape(12.dp)
private val CardShape = RoundedCornerShape(16.dp)
private val SnackbarShape = RoundedCornerShape(14.dp)
private val SheetCornerShape = RoundedCornerShape(28.dp)
private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val PillShape = RoundedCornerShape(percent = 50)
private const val PAGE_COLUMNS = 5

private fun shadowTint(colors: PdfColors): Color = if (colors.isDark) colors.surface else colors.shadow

/** One layer of the design's shadows: [blur] wide, [y] down, the theme's shadow colour at [alpha]. */
private fun Modifier.pdfShadow(
    colors: PdfColors,
    shape: Shape,
    y: Int,
    blur: Int,
    alpha: Float,
    // The sheet's shadow is brand-tinted in both themes.
    tinted: Boolean = false,
): Modifier {
    val color = if (tinted) PdfLightColors.shadow else colors.shadow
    return dropShadow(shape, Shadow(radius = blur.dp, color = color.copy(alpha = alpha), offset = DpOffset(0.dp, y.dp)))
}

/**
 * Tappable, the Breeze way: while the finger is down a faint wash covers it. Instant, no ripple.
 */
@Composable
private fun Modifier.pdfClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val colors = LocalPdfTheme.current.colors
    val wash = remember(colors) { PressWash(colors.ink.copy(alpha = if (colors.isDark) 0.10f else 0.06f)) }
    return clickable(
        interactionSource = null,
        indication = wash,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
    )
}

private data class PressWash(
    private val color: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressWashNode(interactionSource, color)
}

private class PressWashNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
) : Modifier.Node(),
    DrawModifierNode {
    private val presses = mutableListOf<PressInteraction.Press>()

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (presses.isNotEmpty()) drawRect(color)
        drawContent()
    }
}
