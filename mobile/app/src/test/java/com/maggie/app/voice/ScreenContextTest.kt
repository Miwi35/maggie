package com.maggie.app.voice

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `anything the overlay would show is something the model is told`() {
        // Whatever makes `source()` answer must also make a block: the two said
        // different things about a context carrying only a label.
        listOf(
            ScreenContext(appPackage = "com.example.shop"),
            ScreenContext(appLabel = "Boutique"),
            ScreenContext(webUri = "https://boutique.example"),
            ScreenContext(texts = listOf("Café")),
        ).forEach { screen ->
            assertFalse(screen.toString(), screen.isEmpty)
            assertNotNull(screen.toString(), screen.toPromptBlock())
        }
    }

    @Test
    fun `with a screenshot the block names the app and the domain, and leaves the content to the image`() {
        val screen = ScreenContext(
            appPackage = "com.example.shop",
            appLabel = "Boutique",
            webUri = "https://www.boutique.example/cafe?ref=42",
            texts = listOf("Café moulu 250 g", "4,90 €"),
            hasScreenshot = true,
        )

        assertEquals(
            """
            [Contexte de l'écran]
            Application : Boutique (com.example.shop)
            Page : boutique.example
            L'image jointe est une capture de cet écran.
            """.trimIndent(),
            screen.toPromptBlock(imageAttached = true),
        )
    }

    /** A file that could not be read is not announced: the model gets the texts instead. */
    @Test
    fun `a screenshot that could not be attached falls back to the texts`() {
        val screen = ScreenContext(
            appLabel = "Boutique",
            webUri = "https://boutique.example/cafe",
            texts = listOf("Café moulu 250 g"),
            hasScreenshot = true,
        )

        assertEquals(
            """
            [Contexte de l'écran]
            Application : Boutique
            Page : https://boutique.example/cafe
            Texte à l'écran :
            - Café moulu 250 g
            """.trimIndent(),
            screen.toPromptBlock(imageAttached = false),
        )
    }

    @Test
    fun `a screenshot alone that could not be attached says nothing`() {
        assertNull(ScreenContext(hasScreenshot = true).toPromptBlock(imageAttached = false))
    }

    @Test
    fun `a screenshot alone is still a context, and nothing says Maggie cannot read it`() {
        val screen = ScreenContext(hasScreenshot = true)

        assertFalse(screen.isEmpty)
        val block = screen.toPromptBlock(imageAttached = true)!!
        assertFalse(block.contains("je ne sais pas"))
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
    fun `a message that came back carrying a block shows only what was said`() {
        val screen = ScreenContext(
            appPackage = "com.example.shop",
            appLabel = "Boutique",
            texts = listOf("Café moulu 250 g", "4,90 €"),
        )
        val sent = "${screen.toPromptBlock()}\n\nc'est quoi ce produit ?"

        assertEquals("c'est quoi ce produit ?", ScreenContext.withoutPromptBlock(sent))
    }

    @Test
    fun `a message the user wrote himself is left alone`() {
        assertEquals("bonjour", ScreenContext.withoutPromptBlock("bonjour"))
        assertEquals(
            "Contexte : deux lignes\n\net une suite",
            ScreenContext.withoutPromptBlock("Contexte : deux lignes\n\net une suite"),
        )
    }

    @Test
    fun `a blank line in what was said does not cut the message short`() {
        val sent = "${ScreenContext(appLabel = "Boutique").toPromptBlock()}\n\nnote ça :\n\ndeux lignes"

        assertEquals("note ça :\n\ndeux lignes", ScreenContext.withoutPromptBlock(sent))
    }

    @Test
    fun `a header with nothing after it is kept rather than emptied`() {
        assertEquals(ScreenContext.PROMPT_HEADER, ScreenContext.withoutPromptBlock(ScreenContext.PROMPT_HEADER))
        assertEquals(
            "${ScreenContext.PROMPT_HEADER}\nApplication : Boutique\n\n   ",
            ScreenContext.withoutPromptBlock("${ScreenContext.PROMPT_HEADER}\nApplication : Boutique\n\n   "),
        )
    }

    @Test
    fun `an intent carrying no context yields none, so the overlay keeps what it had`() {
        val intent = mockk<Intent>()
        every { intent.getStringExtra(any()) } returns null
        every { intent.getStringArrayListExtra(any()) } returns null
        every { intent.getBooleanExtra(any(), false) } returns false

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
