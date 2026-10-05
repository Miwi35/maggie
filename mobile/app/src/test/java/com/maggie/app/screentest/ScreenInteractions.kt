package com.maggie.app.screentest

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals

/**
 * « This one is under that one » — Maestro's `below:`, on the JVM (MAG-242).
 *
 * The grocery journeys asserted the *order* of the list and not only its
 * contents: a screen that drew the right lines under the wrong shop would have
 * passed a bare « is it visible ». Compose has no `below` selector, so the nodes
 * are read in the order they are laid out and compared with the order they were
 * named in.
 *
 * Reading `positionInRoot` rather than counting children, because the rows of one
 * shop and the header of the next are siblings of nothing in particular: they are
 * items of one `LazyColumn`, and what the shopper reads is the y axis.
 *
 * The failure names the order it found, so a broken grouping reads as « Timbres
 * came before Câpres » instead of as a count.
 */
fun assertTopToBottom(vararg nodes: Pair<String, SemanticsNodeInteraction>) {
    val found = nodes
        .map { (label, node) -> label to node.fetchSemanticsNode().positionInRoot.y }
        .sortedBy { it.second }
        .map { it.first }

    assertEquals(
        "the screen is not laid out in that order",
        nodes.map { it.first },
        found,
    )
}

/**
 * Invoke a control's click action instead of touching it.
 *
 * `performClick()` injects a pointer event and lets Compose hit-test it, which is
 * the better test and what the screen tests use by default. Two Material 3
 * containers never receive it here, and both were measured rather than guessed:
 *
 * - **`ModalDrawerSheet`** — a `NavigationDrawerItem` on its own takes the touch;
 *   the same item inside the sheet takes none.
 * - **`ModalBottomSheet`** — a window of its own, added through the
 *   `WindowManager`, and touch injection does not reach it. (An `AlertDialog`
 *   does receive it: the test framework knows about dialogs.)
 *
 * In both the touch is swallowed and the test reads as « the button did
 * nothing » — a regression's own signature, on a build where nothing is wrong.
 * Hence this, at the few call sites inside those two containers, each saying so.
 *
 * What it gives up is the question « could a finger reach this »: a control
 * covered by the keyboard, by a scrim or by another window answers this just the
 * same. That question needs a real window manager and stays in Maestro
 * (`e2e/mobile/README.md`, *The keyboard does not clip node bounds*).
 */
fun SemanticsNodeInteraction.tap() {
    performSemanticsAction(SemanticsActions.OnClick)
}
