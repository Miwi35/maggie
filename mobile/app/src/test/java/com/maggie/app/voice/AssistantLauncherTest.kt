package com.maggie.app.voice

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AssistantLauncherTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `opens the voice interaction session and nothing else when Maggie is the assistant`() {
        var activityStarts = 0

        AssistantLauncher.launch(showSession = { true }, startActivity = { activityStarts++ })

        assertEquals(0, activityStarts)
    }

    @Test
    fun `falls back to starting the activity when the session is not bound`() {
        var activityStarts = 0

        AssistantLauncher.launch(showSession = { false }, startActivity = { activityStarts++ })

        assertEquals(1, activityStarts)
    }

    @Test
    fun `falls back to starting the activity when the session throws`() {
        var activityStarts = 0

        AssistantLauncher.launch(
            showSession = { throw IllegalStateException("not active") },
            startActivity = { activityStarts++ },
        )

        assertEquals(1, activityStarts)
    }
}
