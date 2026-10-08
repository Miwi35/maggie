package com.maggie.app.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six formats of MAG-35, named and asserted (MAG-35).
 *
 * This is the verification the ticket asks for: « tous les formats, sans appareil
 * physique […] pas par un achat de téléphone ». The owner has no foldable and no
 * tablet, so the layout's whole decision is a function of two integers, and every
 * format the ticket lists is a test that names it and the numbers it stands for.
 *
 * The dp pairs come from the devices themselves: a Fold's inner screen is ~674 dp
 * wide and nearly square, a Flip's cover screen ~280 × 290 dp, a 10" tablet
 * 800 × 1280 dp. `agent-os/specs/2026-10-06-adaptive-mobile-layout/plan.md` holds
 * the table these follow, and `WindowPreviews.kt` renders the same six.
 */
class WindowLayoutTest {

    @Test
    fun `a narrow very tall phone keeps the burger and the collapsed bar`() {
        val layout = appLayoutFor(412, 1000)

        assertEquals(WindowWidth.COMPACT, layout.width)
        assertEquals(WindowHeight.EXPANDED, layout.height)
        assertEquals(NavigationKind.MODAL_DRAWER, layout.navigation)
        assertEquals(
            "a 412 dp window has no room beside its content",
            ChatEntry.BOTTOM_BAR,
            layout.chatEntry,
        )
        assertFalse("1000 dp of window can pay for a 64 dp top bar", layout.denseTopBar)
    }

    @Test
    fun `a foldable closed is a phone`() {
        val layout = appLayoutFor(374, 840)

        assertEquals(WindowWidth.COMPACT, layout.width)
        assertEquals(WindowHeight.MEDIUM, layout.height)
        assertEquals(NavigationKind.MODAL_DRAWER, layout.navigation)
        assertEquals(ChatEntry.BOTTOM_BAR, layout.chatEntry)
        assertFalse(layout.denseTopBar)
    }

    @Test
    fun `the cover screen of a Flip is compact in both directions`() {
        val layout = appLayoutFor(280, 290)

        assertEquals(WindowWidth.COMPACT, layout.width)
        assertEquals(WindowHeight.COMPACT, layout.height)
        assertEquals(NavigationKind.MODAL_DRAWER, layout.navigation)
        assertEquals(
            "no rail to carry the buttons, so the band stays — on 290 dp it is all there is",
            ChatEntry.BOTTOM_BAR,
            layout.chatEntry,
        )
        assertTrue(layout.denseTopBar)
    }

    @Test
    fun `a foldable opened flat gets the rail, and is still too narrow for the panel`() {
        val layout = appLayoutFor(674, 841)

        assertEquals(WindowWidth.MEDIUM, layout.width)
        assertEquals(WindowHeight.MEDIUM, layout.height)
        assertEquals(NavigationKind.RAIL, layout.navigation)
        assertEquals(ChatEntry.BOTTOM_BAR, layout.chatEntry)
        assertFalse(layout.denseTopBar)
    }

    @Test
    fun `a tablet in portrait gets the rail and keeps the chat bar`() {
        val layout = appLayoutFor(800, 1280)

        assertEquals(WindowWidth.MEDIUM, layout.width)
        assertEquals(WindowHeight.EXPANDED, layout.height)
        assertEquals(NavigationKind.RAIL, layout.navigation)
        assertEquals("800 dp still belongs to the content", ChatEntry.BOTTOM_BAR, layout.chatEntry)
        assertFalse(layout.denseTopBar)
    }

    @Test
    fun `a tablet in landscape gets the rail and the permanent panel`() {
        val layout = appLayoutFor(1280, 800)

        assertEquals(WindowWidth.EXPANDED, layout.width)
        assertEquals(WindowHeight.MEDIUM, layout.height)
        assertEquals(NavigationKind.RAIL, layout.navigation)
        assertEquals(ChatEntry.PANEL, layout.chatEntry)
        assertFalse(layout.denseTopBar)
    }

    /**
     * Not in the ticket's list, and asserted so that « expanded » never silently
     * means « tablet »: a phone on its side is 891 dp wide and 411 dp tall.
     *
     * This is the format the recette refused — « entre le header et le chat de maggie,
     * on n'a que très peu d'espace pour le contenu ». 411 dp cannot afford a 64 dp top
     * bar *and* a 72 dp band under the content, so the band's buttons move into the
     * rail, which costs no height, and the top bar is drawn dense.
     */
    @Test
    fun `a phone in landscape spends none of its height on the chat`() {
        val layout = appLayoutFor(891, 411)

        assertEquals(WindowWidth.EXPANDED, layout.width)
        assertEquals(WindowHeight.COMPACT, layout.height)
        assertEquals(NavigationKind.RAIL, layout.navigation)
        assertEquals(
            "a conversation in a 411 dp column is a header and two bubbles",
            ChatEntry.RAIL,
            layout.chatEntry,
        )
        assertTrue(layout.denseTopBar)
    }

    /** A railed window that is short gets the rail entry whatever its width class. */
    @Test
    fun `a short window of medium width takes the rail entry too`() {
        val short = appLayoutFor(674, 411)

        assertEquals(WindowWidth.MEDIUM, short.width)
        assertEquals(ChatEntry.RAIL, short.chatEntry)
    }

    @Test
    fun `the width breakpoints are 600 and 840`() {
        assertEquals(WindowWidth.COMPACT, WindowWidth.of(599))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.of(600))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.of(839))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.of(840))
    }

    @Test
    fun `the height breakpoints are 480 and 900`() {
        assertEquals(WindowHeight.COMPACT, WindowHeight.of(479))
        assertEquals(WindowHeight.MEDIUM, WindowHeight.of(480))
        assertEquals(WindowHeight.MEDIUM, WindowHeight.of(899))
        assertEquals(WindowHeight.EXPANDED, WindowHeight.of(900))
    }

    /**
     * MAG-263: room for a list and its detail is what is left after the rail and the
     * panel, in two [PANE_MIN_WIDTH]s — the seven formats of the table, named.
     */
    @Test
    fun `the detail pane fits only where two 360 dp panes are left beside the chrome`() {
        val fits = mapOf(
            "phone 21:9 (412)" to (412 to 1000),
            "foldable closed (374)" to (374 to 840),
            "Flip cover (280)" to (280 to 290),
            "foldable open (674): 594 dp would make two panes of 297" to (674 to 841),
            "tablet portrait (800): 800 - 80 = 720 = two panes" to (800 to 1280),
            "tablet landscape (1280): 1280 - 80 - 360 = 840" to (1280 to 800),
            "phone landscape (891): 891 - 80 = 811" to (891 to 411),
        ).mapValues { (_, size) -> appLayoutFor(size.first, size.second).detailPaneFits }

        assertEquals(
            mapOf(
                "phone 21:9 (412)" to false,
                "foldable closed (374)" to false,
                "Flip cover (280)" to false,
                "foldable open (674): 594 dp would make two panes of 297" to false,
                "tablet portrait (800): 800 - 80 = 720 = two panes" to true,
                "tablet landscape (1280): 1280 - 80 - 360 = 840" to true,
                "phone landscape (891): 891 - 80 = 811" to true,
            ),
            fits,
        )
    }

    @Test
    fun `the rail's edge is 800 dp`() {
        assertFalse(appLayoutFor(799, 1280).detailPaneFits)
        assertTrue(appLayoutFor(800, 1280).detailPaneFits)
    }

    @Test
    fun `the panel's edge is 1160 dp`() {
        assertEquals(ChatEntry.PANEL, appLayoutFor(1159, 800).chatEntry)
        assertFalse("1159 - 80 - 360 = 719", appLayoutFor(1159, 800).detailPaneFits)
        assertTrue("1160 - 80 - 360 = 720", appLayoutFor(1160, 800).detailPaneFits)
    }
}
