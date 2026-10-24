package com.maggie.app.ui.activity

import android.app.Activity
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** The window side of the full-screen frame (MAG-580): the screen kept on while it runs, and given back. */
@RunWith(AndroidJUnit4::class)
class WindowImmersionTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val window get() = activity.window

    private fun keptOn() = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun `it keeps the screen on while it runs and releases the flag on exit`() {
        val immersion = WindowImmersion(window)

        immersion.enter()
        assertTrue(keptOn())

        immersion.exit()
        assertFalse(keptOn())
    }

    @Test
    fun `it leaves a flag the window already had`() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val immersion = WindowImmersion(window)

        immersion.enter()
        immersion.exit()

        assertTrue(keptOn())
    }

    @Test
    fun `entering twice and exiting twice is harmless`() {
        val immersion = WindowImmersion(window)

        immersion.enter()
        immersion.enter()
        immersion.exit()
        immersion.exit()

        assertFalse(keptOn())
    }

    @Test
    fun `an exit with no entry changes nothing`() {
        WindowImmersion(window).exit()

        assertFalse(keptOn())
    }
}
