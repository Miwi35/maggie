package com.maggie.app.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** MAG-535: a screen takes its colours from the theme or the tokens, never from a literal. */
class NoHardCodedColorsTest {

    private val literal = Regex("""\bColor\(\s*0x""")
    private val named = Regex("""\bColor\.(?!Transparent\b|Unspecified\b)[A-Z]\w*""")
    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    private fun screensDir(): File =
        listOf("src/main/java/com/maggie/app/ui/screens", "app/src/main/java/com/maggie/app/ui/screens")
            .map(::File)
            .first { it.isDirectory }

    @Test
    fun `no screen writes a colour literal or a named Color`() {
        val offenders = screensDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val code = blockComment.replace(file.readText()) { m -> "\n".repeat(m.value.count { it == '\n' }) }
                code.lines().mapIndexedNotNull { index, line ->
                    val bare = line.substringBefore("//")
                    if (literal.containsMatchIn(bare) || named.containsMatchIn(bare)) "${file.name}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        assertTrue("Hard-coded colours in ui/screens — use a token or the theme:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
