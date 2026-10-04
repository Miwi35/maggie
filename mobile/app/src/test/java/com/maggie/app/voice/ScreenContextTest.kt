package com.maggie.app.voice

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextTest {

    @Test
    fun `an empty context has nothing to say`() {
        assertTrue(ScreenContext().isEmpty)
        assertNull(ScreenContext().toPromptBlock())
        assertNull(ScreenContext().source())
    }

    @Test
    fun `a screenshot alone is still a context, so Maggie can say she cannot read it`() {
        val screen = ScreenContext(hasScreenshot = true)

        assertFalse(screen.isEmpty)
        val block = screen.toPromptBlock()!!
        assertTrue(block.contains("seule une image est disponible"))
        assertTrue(block.contains("Ne devine pas"))
    }

    @Test
    fun `the screenshot warning disappears as soon as there is text to read`() {
        val screen = ScreenContext(texts = listOf("Café moulu 250 g"), hasScreenshot = true)

        val block = screen.toPromptBlock()!!
        assertTrue(block.contains("- Café moulu 250 g"))
        assertFalse(block.contains("seule une image"))
    }

    @Test
    fun `the block names the app, the page and every line read`() {
        val screen = ScreenContext(
            appPackage = "com.example.shop",
            appLabel = "Boutique",
            webUri = "https://boutique.example/cafe",
            texts = listOf("Café moulu 250 g", "4,90 €"),
        )

        assertEquals(
            """
            [Contexte de l'écran]
            Application : Boutique (com.example.shop)
            Page : https://boutique.example/cafe
            Texte à l'écran :
            - Café moulu 250 g
            - 4,90 €
            """.trimIndent(),
            screen.toPromptBlock(),
        )
    }

    @Test
    fun `a package with no label still names the app`() {
        val block = ScreenContext(appPackage = "com.example.shop").toPromptBlock()!!

        assertTrue(block.contains("Application : (com.example.shop)"))
    }

    @Test
    fun `the overlay shows the app's name first, then the site, then the package`() {
        assertEquals("Boutique", ScreenContext(appLabel = "Boutique", appPackage = "com.example.shop").source())
        assertEquals("boutique.example", ScreenContext(webUri = "https://www.boutique.example/cafe?x=1").source())
        assertEquals("com.example.shop", ScreenContext(appPackage = "com.example.shop").source())
        assertNull(ScreenContext(webUri = "pas une url").source())
    }

    @Test
    fun `an intent carrying no context yields none, so the overlay keeps what it had`() {
        val intent = mockk<Intent>()
        every { intent.getStringExtra(any()) } returns null
        every { intent.getStringArrayListExtra(any()) } returns null
        every { intent.getBooleanExtra(any(), any()) } returns false

        assertNull(ScreenContext.fromIntent(intent))
    }

    @Test
    fun `what the session put in the intent is what the overlay reads back`() {
        val sent = ScreenContext(
            appPackage = "com.example.shop",
            appLabel = "Boutique",
            webUri = "https://boutique.example/cafe",
            texts = listOf("Café moulu 250 g"),
            hasScreenshot = true,
        )

        val outgoing = mockk<Intent>(relaxed = true)
        sent.putInto(outgoing)
        verify { outgoing.putExtra(ScreenContext.EXTRA_PACKAGE, "com.example.shop") }
        verify { outgoing.putExtra(ScreenContext.EXTRA_LABEL, "Boutique") }
        verify { outgoing.putExtra(ScreenContext.EXTRA_WEB_URI, "https://boutique.example/cafe") }
        verify { outgoing.putStringArrayListExtra(ScreenContext.EXTRA_TEXTS, arrayListOf("Café moulu 250 g")) }
        verify { outgoing.putExtra(ScreenContext.EXTRA_SCREENSHOT, true) }

        val incoming = mockk<Intent>()
        every { incoming.getStringExtra(ScreenContext.EXTRA_PACKAGE) } returns "com.example.shop"
        every { incoming.getStringExtra(ScreenContext.EXTRA_LABEL) } returns "Boutique"
        every { incoming.getStringExtra(ScreenContext.EXTRA_WEB_URI) } returns "https://boutique.example/cafe"
        every { incoming.getStringArrayListExtra(ScreenContext.EXTRA_TEXTS) } returns arrayListOf("Café moulu 250 g")
        every { incoming.getBooleanExtra(ScreenContext.EXTRA_SCREENSHOT, false) } returns true

        assertEquals(sent, ScreenContext.fromIntent(incoming))
    }
}
