package com.maggie.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** A piece of a message: plain text, or a web address when [url] is set (MAG-384). */
data class TextPart(val text: String, val url: String? = null)

private val urlPattern = Regex("""https?://[^\s<>]+""")
private val hostPattern = Regex("""^https?://([^/\s.]+\.[^\s]|localhost\b)""")
private const val trailingPunctuation = ".,;:!?'\"»”’]}"

private fun trimUrl(raw: String): String {
    var url = raw
    while (url.isNotEmpty()) {
        val last = url.last()
        url = when {
            last in trailingPunctuation -> url.dropLast(1)
            last == ')' && url.count { it == ')' } > url.count { it == '(' } -> url.dropLast(1)
            else -> return url
        }
    }
    return url
}

/**
 * Cuts a message into text and web addresses, in order.
 *
 * Messages are plain text everywhere, so the sources Maggie cites after a web search are the
 * last line of her answer, « Source : domaine — lien ». An address stops at the next space
 * and keeps no final punctuation.
 */
fun splitLinks(content: String): List<TextPart> {
    val parts = mutableListOf<TextPart>()
    var cursor = 0
    for (match in urlPattern.findAll(content)) {
        val url = trimUrl(match.value)
        if (!hostPattern.containsMatchIn(url)) continue
        if (match.range.first > cursor) parts += TextPart(content.substring(cursor, match.range.first))
        parts += TextPart(url, url)
        cursor = match.range.first + url.length
    }
    if (cursor < content.length) parts += TextPart(content.substring(cursor))
    return parts
}

/** The message with its web addresses as links the system opens in the browser. */
fun linkified(content: String, linkColor: Color): AnnotatedString {
    val style = TextLinkStyles(
        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium),
    )
    return buildAnnotatedString {
        for (part in splitLinks(content)) {
            if (part.url == null) {
                append(part.text)
            } else {
                withLink(LinkAnnotation.Url(part.url, style)) { append(part.text) }
            }
        }
    }
}
