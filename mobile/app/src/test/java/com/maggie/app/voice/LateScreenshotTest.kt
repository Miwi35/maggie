package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LateScreenshotTest {

    @Test
    fun `a screenshot that will not come reaches the overlay with its reason`() {
        LateScreenshot.expect()

        LateScreenshot.miss(ScreenshotMiss.LATE)

        assertEquals(ScreenshotMiss.LATE, LateScreenshot.hasMissed.value)
        assertFalse(LateScreenshot.hasArrived.value)
    }

    @Test
    fun `a new invocation forgets the previous miss`() {
        LateScreenshot.miss(ScreenshotMiss.REFUSED)

        LateScreenshot.expect()

        assertNull(LateScreenshot.hasMissed.value)
    }

    @Test
    fun `the overlay consumes a miss like an arrival`() {
        LateScreenshot.miss(ScreenshotMiss.UNREADABLE)

        LateScreenshot.consume()

        assertNull(LateScreenshot.hasMissed.value)
    }
}
