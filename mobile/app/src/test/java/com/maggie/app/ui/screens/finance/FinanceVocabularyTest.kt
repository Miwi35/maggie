package com.maggie.app.ui.screens.finance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * « Transaction » is the real line of the account; « opération récurrente » is
 * the abstract entity (MAG-334). The word « opération » alone is no longer a
 * name for a transaction, so it may only appear in front of « récurrente ».
 */
class FinanceVocabularyTest {
    private val bareOperation = Regex("(?<!\\p{L})[Oo]pérations?(?!\\p{L})(?!\\s+récurrent)")

    @Test
    fun `no displayed string calls a transaction an opération`() {
        val main = File(repositoryRoot(), "mobile/app/src/main")
        assertTrue("Cannot find ${main.absolutePath}", main.isDirectory)

        val offenders = main.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (bareOperation.containsMatchIn(line)) "${file.relativeTo(main)}:${index + 1}  ${line.trim()}" else null
                }
            }
            .toList()

        assertEquals("say « transaction », or « opération récurrente » for the abstract entity", emptyList<String>(), offenders)
    }

    @Test
    fun `the check sees the bare word and lets the recurring one through`() {
        assertTrue(bareOperation.containsMatchIn("3 opération(s) importée(s)"))
        assertTrue(bareOperation.containsMatchIn("Les opérations"))
        assertFalse(bareOperation.containsMatchIn("Nouvelle opération récurrente"))
        assertFalse(bareOperation.containsMatchIn("Modifier l’opération récurrente"))
        assertFalse(bareOperation.containsMatchIn("Opérations récurrentes"))
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "api/contract").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("No directory containing api/contract/ found above ${File("").absolutePath}")
    }
}
