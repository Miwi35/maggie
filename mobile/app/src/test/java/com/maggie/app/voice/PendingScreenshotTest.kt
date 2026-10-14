package com.maggie.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingScreenshotTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun screenshot(): File = folder.newFile("screenshot.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `the first sentence takes the bytes and the file is gone`() {
        val file = screenshot()

        assertArrayEquals(byteArrayOf(1, 2, 3), PendingScreenshot(file).take())
        assertFalse(file.exists())
    }

    @Test
    fun `the next sentence gets no image`() {
        val pending = PendingScreenshot(screenshot())
        pending.take()

        assertNull(pending.take())
    }

    @Test
    fun `an overlay closed without sending deletes it unread`() {
        val file = screenshot()

        PendingScreenshot(file).discard()

        assertFalse(file.exists())
    }

    @Test
    fun `no file, no image`() {
        assertNull(PendingScreenshot(File(folder.root, "missing.jpg")).take())
    }

    private val zone = CropRegion(0.1f, 0.2f, 0.6f, 0.7f)

    @Test
    fun `a circled zone is all that is returned, and the file is gone`() {
        val file = screenshot()
        var cut: Pair<ByteArray, CropRegion>? = null
        val pending = PendingScreenshot(file) { bytes, region ->
            cut = bytes to region
            byteArrayOf(9)
        }

        assertArrayEquals(byteArrayOf(9), pending.take(zone))
        assertArrayEquals(byteArrayOf(1, 2, 3), cut?.first)
        assertEquals(zone, cut?.second)
        assertFalse(file.exists())
    }

    @Test
    fun `without a zone the whole screenshot is returned, uncut`() {
        val pending = PendingScreenshot(screenshot()) { _, _ -> error("nothing to cut") }

        assertArrayEquals(byteArrayOf(1, 2, 3), pending.take(null))
    }

    @Test
    fun `a zone that cannot be cut sends nothing, never the whole screen`() {
        val file = screenshot()

        assertNull(PendingScreenshot(file) { _, _ -> null }.take(zone))
        assertFalse(file.exists())
    }

    @Test
    fun `a cut that throws sends nothing either`() {
        assertNull(PendingScreenshot(screenshot()) { _, _ -> error("out of memory") }.take(zone))
    }
}
