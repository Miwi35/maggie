package com.maggie.app.ui.components.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
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
    // The owner may refuse a value (a length limit) or replace it (a reload): its text wins.
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))

    fun edit(next: TextFieldValue) {
        field = next
        onValueChange(next.text)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = shown,
            onValueChange = { edit(MarkdownFormat.onChange(shown, it)) },
            label = { Text(label) },
            minLines = minLines,
            maxLines = maxLines,
            enabled = enabled,
            supportingText = supportingText,
            modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        if (focused && enabled) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag(UiTags.MARKDOWN_TOOLBAR).horizontalScroll(rememberScrollState()),
            ) {
                ToolButton(Icons.Filled.CheckBox, "Case à cocher", UiTags.MARKDOWN_CHECK) { edit(MarkdownFormat.toggleCheck(shown)) }
                ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Puces", UiTags.MARKDOWN_BULLET) { edit(MarkdownFormat.toggleBullet(shown)) }
                ToolButton(Icons.Filled.FormatBold, "Gras", UiTags.MARKDOWN_BOLD) { edit(MarkdownFormat.bold(shown)) }
                ToolButton(Icons.Filled.Title, "Titre", UiTags.MARKDOWN_HEADING) { edit(MarkdownFormat.toggleHeading(shown)) }
                ToolButton(Icons.Filled.Link, "Lien", UiTags.MARKDOWN_LINK) { edit(MarkdownFormat.link(shown)) }
                ToolButton(Icons.Filled.FormatClear, "Retirer la mise en forme", UiTags.MARKDOWN_CLEAR) { edit(MarkdownFormat.clear(shown)) }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = description)
    }
}
