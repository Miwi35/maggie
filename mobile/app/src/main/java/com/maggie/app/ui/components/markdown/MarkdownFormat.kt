package com.maggie.app.ui.components.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** A line of a Markdown text, as the reading view draws it (MAG-713). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock

    /** [index] is the checkbox's rank among the text's checkboxes, the key of [MarkdownFormat.toggleCheckbox]. */
    data class Check(val index: Int, val checked: Boolean, val text: String, val indent: Int) : MdBlock

    data class Bullet(val text: String, val indent: Int) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    data object Blank : MdBlock
}

/** A piece of a line: plain, bold, italic or a link when [url] is set. */
data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val url: String? = null)

/**
 * Markdown GFM kept as the stored text: `- [ ]` / `- [x]`, `- `, `**bold**`, `# `, `[text](url)`.
 * Pure functions, so the reading view and the editor's toolbar are tested without Compose.
 */
object MarkdownFormat {
    private val itemPattern = Regex("""^(\s*)[-*+] (\[[ xX]](?: |$))?(.*)$""")
    private val headingPattern = Regex("""^(#{1,6}) (.*)$""")
    private val inlinePattern = Regex("""\*\*(.+?)\*\*|\*([^*\s][^*]*?)\*|\[([^\]\n]+)]\(([^)\s]+)\)""")

    private enum class Kind { NONE, BULLET, CHECK, HEADING }

    private data class Prefix(val kind: Kind, val indent: String, val rest: String)

    private fun prefixOf(line: String): Prefix {
        itemPattern.matchEntire(line)?.let { m ->
            return Prefix(if (m.groupValues[2].isEmpty()) Kind.BULLET else Kind.CHECK, m.groupValues[1], m.groupValues[3])
        }
        headingPattern.matchEntire(line)?.let { m -> return Prefix(Kind.HEADING, "", m.groupValues[2]) }
        return Prefix(Kind.NONE, "", line)
    }

    fun parse(text: String): List<MdBlock> {
        var checks = 0
        return text.split("\n").map { line ->
            val item = itemPattern.matchEntire(line)
            val heading = headingPattern.matchEntire(line)
            when {
                item != null && item.groupValues[2].isNotEmpty() -> MdBlock.Check(
                    index = checks++,
                    checked = item.groupValues[2][1] != ' ',
                    text = item.groupValues[3],
                    indent = item.groupValues[1].length,
                )
                item != null -> MdBlock.Bullet(item.groupValues[3], item.groupValues[1].length)
                heading != null -> MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                line.isBlank() -> MdBlock.Blank
                else -> MdBlock.Paragraph(line)
            }
        }
    }

    /** Checked and total checkboxes, null when the text has none. */
    fun progress(text: String): Pair<Int, Int>? {
        val checks = parse(text).filterIsInstance<MdBlock.Check>()
        return if (checks.isEmpty()) null else checks.count { it.checked } to checks.size
    }

    /** The text with the [index]-th checkbox flipped; any other character is left as is. */
    fun toggleCheckbox(text: String, index: Int): String {
        var seen = 0
        return text.split("\n").joinToString("\n") { line ->
            val m = itemPattern.matchEntire(line)
            if (m == null || m.groupValues[2].isEmpty() || seen++ != index) {
                line
            } else {
                val at = m.groups[2]!!.range.first + 1
                line.substring(0, at) + (if (line[at] == ' ') 'x' else ' ') + line.substring(at + 1)
            }
        }
    }

    fun spans(line: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        var from = 0
        for (m in inlinePattern.findAll(line)) {
            if (m.range.first > from) out += MdSpan(line.substring(from, m.range.first))
            out += when {
                m.groups[1] != null -> MdSpan(m.groupValues[1], bold = true)
                m.groups[2] != null -> MdSpan(m.groupValues[2], italic = true)
                else -> MdSpan(m.groupValues[3], url = m.groupValues[4])
            }
            from = m.range.last + 1
        }
        if (from < line.length) out += MdSpan(line.substring(from))
        return out
    }

    // --- Toolbar -----------------------------------------------------------------------------

    private fun lineStart(text: String, at: Int) = text.lastIndexOf('\n', at - 1) + 1

    private fun lineEnd(text: String, at: Int) = text.indexOf('\n', at).let { if (it < 0) text.length else it }

    private fun replaceLinePrefix(value: TextFieldValue, newHead: (Prefix) -> String): TextFieldValue {
        val text = value.text
        val start = lineStart(text, value.selection.min)
        val end = lineEnd(text, value.selection.min)
        val prefix = prefixOf(text.substring(start, end))
        val head = newHead(prefix)
        val replaced = head + prefix.rest
        val delta = replaced.length - (end - start)
        val cursor = (value.selection.max + delta).coerceIn(start + head.length, start + replaced.length)
        return TextFieldValue(text.substring(0, start) + replaced + text.substring(end), TextRange(cursor))
    }

    fun toggleCheck(value: TextFieldValue): TextFieldValue = replaceLinePrefix(value) {
        if (it.kind == Kind.CHECK) "" else "${it.indent}- [ ] "
    }

    fun toggleBullet(value: TextFieldValue): TextFieldValue = replaceLinePrefix(value) {
        if (it.kind == Kind.BULLET) "" else "${it.indent}- "
    }

    fun toggleHeading(value: TextFieldValue): TextFieldValue = replaceLinePrefix(value) {
        if (it.kind == Kind.HEADING) "" else "# "
    }

    /** Wraps the selection in `**`; with none, writes `****` and puts the cursor between. */
    fun bold(value: TextFieldValue): TextFieldValue {
        val sel = value.selection
        val text = value.text
        val selected = text.substring(sel.min, sel.max)
        return TextFieldValue(
            text.substring(0, sel.min) + "**" + selected + "**" + text.substring(sel.max),
            if (sel.collapsed) TextRange(sel.min + 2) else TextRange(sel.min + 2, sel.max + 2),
        )
    }

    /** `[selection]()` with the cursor in the parentheses; with no selection `[]()` and the cursor in the brackets. */
    fun link(value: TextFieldValue): TextFieldValue {
        val sel = value.selection
        val text = value.text
        val selected = text.substring(sel.min, sel.max)
        val cursor = if (sel.collapsed) sel.min + 1 else sel.min + selected.length + 3
        return TextFieldValue(
            text.substring(0, sel.min) + "[" + selected + "]()" + text.substring(sel.max),
            TextRange(cursor),
        )
    }

    /** Takes the list and title marks off the lines touched, and the bold, italic and links off their text. */
    fun clear(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val start = lineStart(text, value.selection.min)
        val end = lineEnd(text, value.selection.max)
        val cleaned = text.substring(start, end).split("\n").joinToString("\n") { line ->
            spans(prefixOf(line).rest).joinToString("") { it.text }
        }
        return TextFieldValue(text.substring(0, start) + cleaned + text.substring(end), TextRange(start + cleaned.length))
    }

    /**
     * Enter in a list: the next item opens (a check box comes back unchecked), and Enter on an
     * empty item leaves the list. [old] is the field before the key, [new] after it.
     */
    fun onChange(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val at = new.selection.start
        val typedEnter = new.selection.collapsed && new.text.length == old.text.length + 1 && at > 0 &&
            new.text[at - 1] == '\n' && new.text.removeRange(at - 1, at) == old.text
        if (!typedEnter) return new
        val previous = new.text.substring(lineStart(new.text, at - 1), at - 1)
        val prefix = prefixOf(previous)
        val marker = when (prefix.kind) {
            Kind.CHECK -> "${prefix.indent}- [ ] "
            Kind.BULLET -> "${prefix.indent}- "
            else -> return new
        }
        if (prefix.rest.isBlank()) {
            val lineBegin = at - 1 - previous.length
            return TextFieldValue(new.text.removeRange(lineBegin, at), TextRange(lineBegin))
        }
        return TextFieldValue(new.text.substring(0, at) + marker + new.text.substring(at), TextRange(at + marker.length))
    }
}
