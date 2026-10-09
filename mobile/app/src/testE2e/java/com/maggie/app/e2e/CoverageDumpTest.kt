package com.maggie.app.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What `run.sh` gets when it asks the app for the coverage of the flow that just ended.
 * The JaCoCo runtime itself is only in an instrumented APK — the nightly proves that
 * part; here, what the receiver does with what it is given.
 */
class CoverageDumpTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun target() = File(folder.root, "files/${CoverageDumpReceiver.FILE_NAME}")

    @Test
    fun `the counts are written where run-sh pulls them, and the result says how many bytes`() {
        val counts = byteArrayOf(0x01, 0xC0.toByte(), 0xC0.toByte(), 0x10, 0x07)

        val outcome = CoverageDump { counts }.writeTo(target())

        assertEquals("dumped 5", outcome)
        assertArrayEquals(counts, target().readBytes())
    }

    @Test
    fun `a build without JaCoCo writes nothing and says so`() {
        val outcome = CoverageDump { null }.writeTo(target())

        assertEquals("no-jacoco", outcome)
        assertFalse(target().exists())
    }

    @Test
    fun `a runtime that throws is reported, not raised`() {
        val outcome = CoverageDump { throw IllegalStateException("agent not started") }.writeTo(target())

        assertTrue(outcome, outcome.startsWith("failed IllegalStateException"))
        assertFalse(target().exists())
    }
}
