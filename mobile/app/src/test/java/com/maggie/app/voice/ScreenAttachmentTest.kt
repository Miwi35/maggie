package com.maggie.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScreenAttachmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ScreenContext(appLabel = "Chrome", hasScreenshot = true)

    private fun file(): File = folder.newFile("screenshot.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `nothing is attached when the overlay opens`() {
        val attachment = ScreenAttachment(PendingScreenshot(file()))

        assertFalse(attachment.attached)
        assertNull(attachment.take(context))
    }

    @Test
    fun `a sentence sent without a touch carries no image and the capture is deleted`() {
        val file = file()
        val attachment = ScreenAttachment(PendingScreenshot(file))

        assertNull(attachment.take(context))
        attachment.sent()

        assertFalse(file.exists())
    }

    @Test
    fun `touching + Écran sends the screen with the sentence`() {
        val file = file()
        val attachment = ScreenAttachment(PendingScreenshot(file))

        attachment.attach()

        assertTrue(attachment.attached)
        assertArrayEquals(byteArrayOf(1, 2, 3), attachment.take(context))
        attachment.sent()
        assertFalse(file.exists())
        assertFalse(attachment.attached)
    }

    @Test
    fun `the cross before sending takes the screen back`() {
        val file = file()
        val attachment = ScreenAttachment(PendingScreenshot(file))
        attachment.attach()

        attachment.remove()

        assertFalse(attachment.attached)
        assertNull(attachment.take(context))
        attachment.sent()
        assertFalse(file.exists())
    }

    @Test
    fun `the cross forgets the circled zone`() {
        val attachment = ScreenAttachment(PendingScreenshot(file()))
        attachment.attach()
        attachment.select(CropRegion(0.1f, 0.1f, 0.5f, 0.5f))

        attachment.remove()

        assertNull(attachment.region)
    }

    @Test
    fun `a context without a screenshot sends no image even when attached`() {
        val attachment = ScreenAttachment(PendingScreenshot(file()))
        attachment.attach()

        assertNull(attachment.take(ScreenContext(appLabel = "Chrome")))
        assertNull(attachment.take(null))
    }

    @Test
    fun `a new invocation starts with nothing attached`() {
        val attachment = ScreenAttachment(PendingScreenshot(file()))
        attachment.attach()

        attachment.reset()

        assertEquals(false, attachment.attached)
    }
}
