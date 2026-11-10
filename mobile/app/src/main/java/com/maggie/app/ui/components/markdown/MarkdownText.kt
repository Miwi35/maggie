package com.maggie.app.ui.components.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.foundation.background
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/**
 * A Markdown text in reading (MAG-713): titles, bold, italic, bullets, links and check boxes. A plain
 * text reads as it is written. With [onTextChange] a check box ticks in place and hands back the text
 * rewritten (`- [ ]` → `- [x]`); without it the boxes are drawn and stay put. With a [label] the text sits
 * in a card headed by it, the « n/m » counter on its right.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onTextChange: ((String) -> Unit)? = null,
    label: String? = null,
) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val body = MaterialTheme.typography.bodyMedium
    val progress = MarkdownFormat.progress(text)
    val dark = isMaggieDark()
    val checkColors = CheckboxDefaults.colors(
        uncheckedColor = MaterialTheme.colorScheme.outline,
        checkedColor = if (dark) MaggieTokens.Module.comptes.second else MaggieTokens.Module.comptes.first,
    )
    val frame = if (label != null) {
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(12.dp)
    } else {
        modifier
    }
    Column(modifier = frame, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null || progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                progress?.let { (done, total) ->
                    Text(
                        text = "$done/$total",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(UiTags.MARKDOWN_COUNTER),
                    )
                }
            }
        }
        MarkdownFormat.parse(text).forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, linkStyle),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Check -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = (block.indent * 4).dp),
                ) {
                    Checkbox(
                        checked = block.checked,
                        onCheckedChange = onTextChange?.let { change ->
                            { change(MarkdownFormat.toggleCheckbox(text, block.index)) }
                        },
                        enabled = onTextChange != null,
                        colors = checkColors,
                        modifier = Modifier.testTag(UiTags.markdownCheckbox(block.index)),
                    )
                    Text(
                        text = inline(block.text, linkStyle),
                        style = body,
                        textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                    )
                }
                is MdBlock.Bullet -> Row(modifier = Modifier.padding(start = (block.indent * 4).dp)) {
                    Text("•", style = body)
                    Spacer(Modifier.width(8.dp))
                    Text(text = inline(block.text, linkStyle), style = body)
                }
                is MdBlock.Paragraph -> Text(text = inline(block.text, linkStyle), style = body)
                MdBlock.Blank -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun inline(line: String, linkStyle: TextLinkStyles): AnnotatedString = buildAnnotatedString {
    for (span in MarkdownFormat.spans(line)) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
        )
        when {
            span.url != null -> withLink(LinkAnnotation.Url(span.url, linkStyle)) { append(span.text) }
            else -> withStyle(style) { append(span.text) }
        }
    }
}
