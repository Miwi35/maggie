package com.maggie.app.ui.components.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
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
 * rewritten (`- [ ]` → `- [x]`); without it the boxes are drawn and stay put.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onTextChange: ((String) -> Unit)? = null,
) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val body = MaterialTheme.typography.bodyMedium
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MarkdownFormat.progress(text)?.let { (done, total) ->
            Text(
                text = "$done/$total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(UiTags.MARKDOWN_COUNTER),
            )
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
