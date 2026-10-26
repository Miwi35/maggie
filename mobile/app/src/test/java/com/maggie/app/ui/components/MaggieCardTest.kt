package com.maggie.app.ui.components

import androidx.compose.ui.unit.dp
import com.maggie.app.ui.theme.MaggieTokens
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MAG-579: a card is drawn on the mock-ups' `paper`, never on Material's default grey. */
class MaggieCardTest {

    @Test
    fun `a card is paper in both modes`() {
        assertEquals(MaggieTokens.surfaceDark.paper, maggieCardContainer(dark = true, raised = false))
        assertEquals(MaggieTokens.surfaceLight.paper, maggieCardContainer(dark = false, raised = false))
    }

    @Test
    fun `a selected or nested card is raised in both modes`() {
        assertEquals(MaggieTokens.surfaceDark.raised, maggieCardContainer(dark = true, raised = true))
        assertEquals(MaggieTokens.surfaceLight.raised, maggieCardContainer(dark = false, raised = true))
    }

    @Test
    fun `the corners and the rim follow the mock-ups`() {
        assertEquals(22.dp, CARD_RADIUS)
        assertEquals(18.dp, ROW_RADIUS)
        assertEquals(1.5.dp, SELECTED_BORDER)
    }

    @Test
    fun `no finance screen draws a Material card or reads a surface role the theme leaves on its baseline`() {
        val offenders = financeSources().flatMap(::offendingLines)

        assertTrue("Use MaggieCard and the tokens in the Finance sources:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun financeSources(): List<File> {
        val root = listOf("src/main/java/com/maggie/app/ui", "app/src/main/java/com/maggie/app/ui")
            .map(::File)
            .first { it.isDirectory }
        val screens = File(root, "screens/finance").walkTopDown().filter { it.isFile && it.extension == "kt" }
        // Shared components the Finance dashboard and month balance draw (MAG-579 recette: rings, rhythm, treasury).
        val shared = listOf("ScoreRings.kt", "RhythmCurve.kt", "TreasuryBanner.kt").map { File(root, "components/$it") }

        return (screens + shared).toList()
    }

    private fun offendingLines(file: File): List<String> {
        val card = Regex("""(^|[^A-Za-z.])(Outlined|Elevated)?Card\(""")
        val role = Regex("""\bsurfaceContainer\w*|\bsurfaceVariant\b""")

        return file.readLines().mapIndexedNotNull { index, line ->
            val code = line.substringBefore("//")
            if (card.containsMatchIn(code) || role.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
        }
    }
}
