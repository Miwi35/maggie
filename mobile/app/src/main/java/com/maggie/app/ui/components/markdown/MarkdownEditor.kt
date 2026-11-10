package com.maggie.app.ui.components.markdown

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/**
 * The multiline text field of the app (MAG-713): it reads and writes Markdown, and while it has the
 * focus a bar offers a check box, bullets, bold, a title, a link and « retirer la mise en forme ».
 * Enter in a list opens the next item; Enter on an empty one leaves the list. [modifier] goes to the
 * field itself, so a test tag addresses the text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 2,
    maxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The owner may refuse a value (a length limit) or replace it (a reload): its text wins.
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    val visual = remember(shown.text) { MarkdownFormat.visual(shown.text) }
    val styles = markdownStyles()
    val transformation = remember(visual, styles) { MarkdownTransformation(visual, styles) }
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()

    fun edit(next: TextFieldValue) {
        field = next
        onValueChange(next.text)
    }

    val bringIntoView = remember { BringIntoViewRequester() }
    // The field and its bar move together into view when the keyboard opens or the text grows.
    LaunchedEffect(focused, field.text) { if (focused) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }

    Column(modifier = Modifier.bringIntoViewRequester(bringIntoView), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = shown,
            onValueChange = { edit(MarkdownFormat.onChange(shown, it)) },
            enabled = enabled,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            interactionSource = interaction,
            modifier = modifier
                .semantics(mergeDescendants = true) {}
                .padding(top = 8.dp)
                .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight)
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = shown.text,
                    innerTextField = {
                        Box {
                            inner()
                            CheckBoxes(visual, layout, enabled) { index -> edit(shown.copy(text = MarkdownFormat.toggleCheckbox(shown.text, index))) }
                        }
                    },
                    enabled = enabled,
                    singleLine = false,
                    visualTransformation = transformation,
                    interactionSource = interaction,
                    label = { Text(label) },
                    supportingText = supportingText,
                    colors = colors,
                    container = {
                        OutlinedTextFieldDefaults.Container(enabled = enabled, isError = false, interactionSource = interaction, colors = colors)
                    },
                )
            },
        )
        if (focused && enabled) {
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).testTag(UiTags.MARKDOWN_TOOLBAR)) {
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton("Case à cocher", UiTags.MARKDOWN_CHECK, { Icon(Icons.Filled.CheckBox, null) }) { edit(MarkdownFormat.toggleCheck(shown)) }
                    ToolButton("Puces", UiTags.MARKDOWN_BULLET, { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null) }) { edit(MarkdownFormat.toggleBullet(shown)) }
                    ToolButton("Gras", UiTags.MARKDOWN_BOLD, { Icon(Icons.Filled.FormatBold, null) }) { edit(MarkdownFormat.bold(shown)) }
                    ToolButton("Titre", UiTags.MARKDOWN_HEADING, { Text("H", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }) { edit(MarkdownFormat.toggleHeading(shown)) }
                    ToolButton("Lien", UiTags.MARKDOWN_LINK, { Icon(Icons.Filled.Link, null) }) { edit(MarkdownFormat.link(shown)) }
                    Spacer(Modifier.weight(1f))
                    ToolButton("Retirer la mise en forme", UiTags.MARKDOWN_CLEAR, { Icon(Icons.Filled.FormatClear, null) }) { edit(MarkdownFormat.clear(shown)) }
                }
            }
        }
    }
}

/** Real check boxes over the blanks the field leaves at the head of its `- [ ]` lines: they tick while editing. */
@Composable
private fun CheckBoxes(visual: MdVisual, layout: TextLayoutResult?, enabled: Boolean, onToggle: (Int) -> Unit) {
    layout ?: return
    if (layout.layoutInput.text.text != visual.text) return
    val density = LocalDensity.current
    for (box in visual.boxes) {
        val first = layout.getBoundingBox(box.at)
        val last = layout.getBoundingBox(box.at + 1)
        val size = with(density) { DpSize((last.right - first.left).toDp(), first.height.toDp()) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset(first.left.roundToInt(), first.top.roundToInt()) }
                .size(size)
                .testTag(UiTags.markdownEditCheckbox(box.index))
                .toggleable(value = box.checked, enabled = enabled, role = Role.Checkbox) { onToggle(box.index) },
        ) {
            Icon(
                imageVector = if (box.checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private class MarkdownStyles(val bold: SpanStyle, val italic: SpanStyle, val link: SpanStyle, val done: SpanStyle, val headings: List<SpanStyle>)

@Composable
private fun markdownStyles(): MarkdownStyles {
    val type = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    return remember(type, scheme) {
        MarkdownStyles(
            bold = SpanStyle(fontWeight = FontWeight.Bold),
            italic = SpanStyle(fontStyle = FontStyle.Italic),
            link = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline),
            done = SpanStyle(color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough),
            headings = listOf(type.titleLarge, type.titleMedium, type.titleSmall).map {
                SpanStyle(fontSize = it.fontSize, fontWeight = FontWeight.Bold)
            },
        )
    }
}

/** Draws [visual]: the stored text with its marks hidden and dressed, the cursor mapped both ways. */
private class MarkdownTransformation(private val visual: MdVisual, private val styles: MarkdownStyles) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = if (text.text == visual.originalText) visual else MarkdownFormat.visual(text.text)
        val dressed = buildAnnotatedString {
            append(shown.text)
            for (s in shown.styles) {
                val style = when (s.kind) {
                    MdStyleKind.BOLD -> styles.bold
                    MdStyleKind.ITALIC -> styles.italic
                    MdStyleKind.LINK -> styles.link
                    MdStyleKind.DONE -> styles.done
                    MdStyleKind.HEADING -> styles.headings[(s.level - 1).coerceAtMost(2)]
                }
                addStyle(style, s.start, s.end)
            }
        }
        return TransformedText(
            dressed,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = shown.toDisplayed(offset)

                override fun transformedToOriginal(offset: Int) = shown.toOriginal(offset)
            },
        )
    }
}

@Composable
private fun ToolButton(description: String, tag: String, content: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag).semantics { contentDescription = description }) { content() }
}
