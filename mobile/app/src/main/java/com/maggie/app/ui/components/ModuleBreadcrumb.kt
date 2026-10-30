package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.ui.theme.MaggieTokens

/** One entry of a module's sections menu; [count], when known, follows its label in the menu only (« À venir (2) »). */
data class BreadcrumbSection(val key: String, val label: String, val count: Int? = null)

/** One pill of the row of sub-views; [count] is shown after the label when known. */
data class SubviewItem(val key: String, val label: String, val count: Int? = null)

private val BAR_HEIGHT = 56.dp
private val TOUCH_TARGET = 48.dp

private val Color.isDark: Boolean get() = luminance() < 0.5f

/**
 * The title of a module's screens (MAG-537): ☰ · module › Section ▾.
 *
 * The section is a single 48 dp button that opens the menu of the module's [sections], the current one
 * checked. Used by Finance first; any module with sections wears it the same way, in its own colour
 * (the accent of the theme around it).
 *
 * @param actions what the screen offers at the right of the bar — the icons it had in its own top bar.
 */
@Composable
fun ModuleBreadcrumb(
    module: String,
    sections: List<BreadcrumbSection>,
    currentKey: String,
    onSelectSection: (BreadcrumbSection) -> Unit,
    onOpenDrawer: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val current = sections.firstOrNull { it.key == currentKey } ?: sections.first()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val caption = if (MaterialTheme.colorScheme.background.isDark) {
        MaggieTokens.surfaceDark.caption
    } else {
        MaggieTokens.surfaceLight.caption
    }

    Row(
        modifier = modifier.statusBarsPadding().fillMaxWidth().heightIn(min = BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onOpenDrawer != null) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag(UiTags.NAV_MENU)) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        } else {
            Box(modifier = Modifier.width(12.dp))
        }
        Text(
            text = module,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.testTag(UiTags.BREADCRUMB_MODULE),
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = caption,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Row(
            modifier = Modifier
                .sizeIn(minHeight = TOUCH_TARGET)
                .semantics {
                    role = Role.Button
                    contentDescription = "$module, section ${current.label} : changer de section"
                }
                .clickable { expanded = true }
                .padding(horizontal = 4.dp)
                .testTag(UiTags.BREADCRUMB_SECTION),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.label,
                color = accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = accent)
            if (expanded) {
                SectionsMenu(
                    sections = sections,
                    currentKey = current.key,
                    accent = accent,
                    onSelect = { section ->
                        expanded = false
                        onSelectSection(section)
                    },
                    onDismiss = { expanded = false },
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
        actions()
        LocalHeaderActions.current.invoke(this)
    }
}

private val BelowAnchor = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
}

private val WholeWindow = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * The menu under the section button: a dimmed window, the menu in a 20 dp-rounded card, a tap outside closes.
 * Two popups placed by the framework from the button's bounds, so nothing about the button's position is kept in state.
 */
@Composable
private fun SectionsMenu(
    sections: List<BreadcrumbSection>,
    currentKey: String,
    accent: Color,
    onSelect: (BreadcrumbSection) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = if (MaterialTheme.colorScheme.background.isDark) {
        MaggieTokens.surfaceDark.raised
    } else {
        MaggieTokens.surfaceLight.raised
    }
    Popup(
        popupPositionProvider = WholeWindow,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .uiTagRoot()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .testTag(UiTags.BREADCRUMB_SCRIM),
        )
    }
    Popup(
        popupPositionProvider = BelowAnchor,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(modifier = Modifier.uiTagRoot()) {
            Surface(
                shape = RoundedCornerShape(MaggieTokens.Radius.xl),
                color = container,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .testTag(UiTags.BREADCRUMB_MENU),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    sections.forEach { section ->
                        val selected = section.key == currentKey
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = TOUCH_TARGET)
                                .semantics {
                                    role = Role.RadioButton
                                    this.selected = selected
                                }
                                .clickable { onSelect(section) }
                                .padding(horizontal = 16.dp)
                                .testTag(UiTags.breadcrumbMenu(section.key)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = section.count?.let { "${section.label} ($it)" } ?: section.label,
                                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A detail screen's bar: the back arrow and the title, no sections menu. */
@Composable
fun ModuleDetailBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backDescription: String = "Retour",
    backModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.statusBarsPadding().fillMaxWidth().heightIn(min = BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = backModifier) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        actions()
        LocalHeaderActions.current.invoke(this)
    }
}

/**
 * The single row of a section's sub-views (MAG-537): 36 dp pills, never tabs with an underline.
 * The active one is the accent at 16 %, the others a faint white.
 */
@Composable
fun SubviewRow(
    items: List<SubviewItem>,
    selectedKey: String,
    onSelect: (SubviewItem) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.isDark
    val mutedText = if (dark) MaggieTokens.surfaceDark.textMuted else MaggieTokens.surfaceLight.textMuted
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState())
            .testTag(UiTags.SUBVIEW_ROW),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val selected = item.key == selectedKey
            val label = if (item.count != null) "${item.label} · ${item.count}" else item.label
            Box(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .background(if (selected) accent.copy(alpha = 0.16f) else faint, RoundedCornerShape(MaggieTokens.Radius.pill))
                    .semantics {
                        role = Role.Tab
                        this.selected = selected
                    }
                    .clickable { onSelect(item) }
                    .padding(horizontal = 16.dp)
                    .testTag(UiTags.subview(item.key)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) accent else mutedText,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }
    if (trailing != null) {
        Row(
            modifier = Modifier.testTag(UiTags.SUBVIEW_ROW_TRAILING),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
    }
}
