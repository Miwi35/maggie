package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What no other test sees (MAG-216): whether Android will accept Maggie's voice
 * interaction service. The system refuses one with no `recognitionService` and quietly
 * falls back on the plain assist activity, so the screen-context callbacks are never
 * called — and nothing in the code under them can tell. Both ends of the declaration
 * are checked: the XML the system parses, and the manifest entries it points at.
 */
class VoiceInteractionDeclarationTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File("src/main/$path")).documentElement
    }

    private val info get() = parse("res/xml/interaction_service.xml")

    private val manifest get() = parse("AndroidManifest.xml")

    private fun serviceNamed(fqcn: String): Element {
        val shortName = ".voice." + fqcn.substringAfterLast('.')
        val services = manifest.getElementsByTagName("service")
        return (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull { it.getAttributeNS(android, "name") == shortName }
            ?: throw AssertionError("$fqcn is not declared as a <service> in the manifest")
    }

    @Test
    fun `the voice interaction service names a recognition service and a session service`() {
        assertEquals("com.maggie.app.voice.MaggieRecognitionService", info.getAttributeNS(android, "recognitionService"))
        assertEquals(
            "com.maggie.app.voice.MaggieVoiceInteractionSessionService",
            info.getAttributeNS(android, "sessionService"),
        )
    }

    @Test
    fun `both named services exist as classes`() {
        for (attribute in listOf("recognitionService", "sessionService")) {
            val name = info.getAttributeNS(android, attribute)
            assertNotNull("$name", Class.forName(name, false, javaClass.classLoader))
        }
    }

    @Test
    fun `both named services are declared in the manifest`() {
        serviceNamed(info.getAttributeNS(android, "recognitionService"))
        serviceNamed(info.getAttributeNS(android, "sessionService"))
    }

    @Test
    fun `the recognition service is the shape Android looks for`() {
        val service = serviceNamed("com.maggie.app.voice.MaggieRecognitionService")

        assertEquals("true", service.getAttributeNS(android, "exported"))
        assertEquals("android.permission.RECORD_AUDIO", service.getAttributeNS(android, "permission"))
        val actions = service.getElementsByTagName("action")
        val names = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(android, "name") }
        assertTrue(names.toString(), "android.speech.RecognitionService" in names)
    }
}
