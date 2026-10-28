package com.maggie.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MAG-532: without a `windowSoftInputMode`, the system picks « pan » for a window whose
 * content does not scroll by itself — a Compose root — and on a tablet, where the chat is a
 * panel of the activity's own window, the keyboard slid the whole window up instead of
 * shrinking it: a short reply stayed under the keyboard. `ChatPanel` relies on the window
 * being resized, so the manifest has to say so; nothing in the code under it can tell.
 */
class KeyboardResizesWindowDeclarationTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun activityNamed(name: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
        val activities = manifest.getElementsByTagName("activity")
        return (0 until activities.length)
            .map { activities.item(it) as Element }
            .firstOrNull { it.getAttributeNS(android, "name") == name }
            ?: throw AssertionError("$name is not declared as an <activity> in the manifest")
    }

    @Test
    fun `the main activity is resized by the keyboard, not panned`() {
        assertEquals("adjustResize", activityNamed(".MainActivity").getAttributeNS(android, "windowSoftInputMode"))
    }
}
