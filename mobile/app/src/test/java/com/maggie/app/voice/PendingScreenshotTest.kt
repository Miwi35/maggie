package com.maggie.app.voice

import org.junit.Assert.assertArrayEquals
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
}
