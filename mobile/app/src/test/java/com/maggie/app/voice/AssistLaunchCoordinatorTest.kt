package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every path to the one launch the overlay is allowed (MAG-30): the regression
 * this guards is a session that opened before the screen context arrived — or
 * twice, because it arrived late.
 */
class AssistLaunchCoordinatorTest {

    private val launches = mutableListOf<ScreenContext>()
    private val coordinator = AssistLaunchCoordinator { launches += it }

    @Test
    fun `a session shown with no assist flag opens at once, with nothing`() {
        val waiting = coordinator.onShow(withAssist = false, withScreenshot = false)

        assertFalse(waiting)
        assertEquals(listOf(ScreenContext()), launches)
    }

    @Test
    fun `a session promised the screen content waits for it`() {
        val waiting = coordinator.onShow(withAssist = true, withScreenshot = false)

        assertTrue(waiting)
        assertTrue(launches.isEmpty())

        coordinator.onAssist(
            appPackage = "com.example.shop",
            appLabel = "Boutique",
            webUri = "https://boutique.example/cafe",
            texts = listOf("Café moulu 250 g"),
        )

        assertEquals(1, launches.size)
        assertEquals("Boutique", launches.single().appLabel)
        assertEquals(listOf("Café moulu 250 g"), launches.single().texts)
    }

    @Test
    fun `a session promised both waits for both`() {
        coordinator.onShow(withAssist = true, withScreenshot = true)

        coordinator.onAssist("com.example.shop", "Boutique", null, listOf("Café"))
        assertTrue("the screenshot has not arrived yet", launches.isEmpty())

        coordinator.onScreenshot(available = true)
        assertEquals(1, launches.size)
        assertTrue(launches.single().hasScreenshot)
    }

    @Test
    fun `a refused screenshot ends the wait all the same`() {
        coordinator.onShow(withAssist = false, withScreenshot = true)

        coordinator.onScreenshot(available = false)

        assertEquals(1, launches.size)
        assertFalse(launches.single().hasScreenshot)
    }

    @Test
    fun `the timeout opens the overlay with whatever arrived`() {
        coordinator.onShow(withAssist = true, withScreenshot = true)
        coordinator.onScreenshot(available = true)

        coordinator.onTimeout()

        assertEquals(1, launches.size)
        assertTrue(launches.single().hasScreenshot)
        assertEquals(emptyList<String>(), launches.single().texts)
    }

    @Test
    fun `a callback that arrives after the timeout does not open a second overlay`() {
        coordinator.onShow(withAssist = true, withScreenshot = false)
        coordinator.onTimeout()
        assertEquals(1, launches.size)

        coordinator.onAssist("com.example.shop", "Boutique", null, listOf("Trop tard"))

        assertEquals(1, launches.size)
        assertNull(launches.single().appLabel)
    }

    @Test
    fun `a second show does not open a second overlay`() {
        coordinator.onShow(withAssist = false, withScreenshot = false)

        assertFalse(coordinator.onShow(withAssist = true, withScreenshot = true))
        assertEquals(1, launches.size)
    }

    @Test
    fun `the first window wins when several are handed over`() {
        coordinator.onShow(withAssist = true, withScreenshot = true)

        coordinator.onAssist("com.example.shop", "Boutique", "https://boutique.example", listOf("Café"))
        coordinator.onAssist("com.example.other", "Autre", "https://autre.example", listOf("Autre chose"))
        coordinator.onScreenshot(available = false)

        val screen = launches.single()
        assertEquals("com.example.shop", screen.appPackage)
        assertEquals("Boutique", screen.appLabel)
        assertEquals("https://boutique.example", screen.webUri)
        assertEquals(listOf("Café"), screen.texts)
    }

    @Test
    fun `blank pieces are dropped rather than carried as empty strings`() {
        coordinator.onShow(withAssist = true, withScreenshot = false)

        coordinator.onAssist(appPackage = "  ", appLabel = "", webUri = " ", texts = emptyList())

        val screen = launches.single()
        assertNull(screen.appPackage)
        assertNull(screen.appLabel)
        assertNull(screen.webUri)
        assertTrue(screen.isEmpty)
    }

    @Test
    fun `a callback outside a shown session is ignored, not banked`() {
        // Android shows the session before it hands anything over, so this is
        // either a stray delivery or one that arrived after a dismissal. Keeping
        // it would open the *next* invocation at once, with the wrong screen.
        coordinator.onAssist("com.example.shop", "Boutique", null, listOf("Café"))
        coordinator.onScreenshot(available = true)
        coordinator.onTimeout()
        assertTrue(launches.isEmpty())

        val waiting = coordinator.onShow(withAssist = true, withScreenshot = false)

        assertTrue("the new invocation waits for its own content", waiting)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `a callback that lands after a dismissal does not arm the next invocation`() {
        coordinator.onShow(withAssist = true, withScreenshot = false)
        coordinator.onDismissed()

        coordinator.onAssist("com.example.shop", "Boutique", null, listOf("Café"))

        assertTrue(launches.isEmpty())
        assertTrue(coordinator.onShow(withAssist = true, withScreenshot = false))
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `a dismissed invocation does not leak its screen into the next one`() {
        coordinator.onShow(withAssist = true, withScreenshot = true)
        coordinator.onAssist("com.example.shop", "Boutique", null, listOf("Café"))

        coordinator.onDismissed()

        val waiting = coordinator.onShow(withAssist = true, withScreenshot = false)
        assertTrue("the new invocation waits for its own content", waiting)
        assertTrue(launches.isEmpty())

        coordinator.onAssist("com.example.bank", "Banque", null, listOf("Solde"))
        assertEquals("Banque", launches.single().appLabel)
        assertEquals(listOf("Solde"), launches.single().texts)
    }

    @Test
    fun `dismissing behind an open overlay does not re-arm a second launch`() {
        coordinator.onShow(withAssist = false, withScreenshot = false)
        assertEquals(1, launches.size)

        coordinator.onDismissed()
        coordinator.onShow(withAssist = false, withScreenshot = false)
        coordinator.onTimeout()

        assertEquals(1, launches.size)
    }

    @Test
    fun `hasLaunched says whether the clock still matters`() {
        assertFalse(coordinator.hasLaunched)

        coordinator.onShow(withAssist = false, withScreenshot = false)

        assertTrue(coordinator.hasLaunched)
    }
}
