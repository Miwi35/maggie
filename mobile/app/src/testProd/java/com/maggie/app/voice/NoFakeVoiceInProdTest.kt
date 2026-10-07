package com.maggie.app.voice

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The journeys' stand-ins — an engine that hears one fixed sentence, a recorder that
 * writes placeholder bytes — live in `src/e2e/`. A build that carried them would send
 * the owner's Maggie a sentence he never said, so this checks they are not in prod.
 *
 * In `src/testProd/`, so it runs with the prod variant (`koverXmlReportProdRelease` in
 * `ci.yml`) and not with the e2e one, where both classes do exist.
 */
class NoFakeVoiceInProdTest {

    @Test
    fun `prod carries no scripted speech engine`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.maggie.app.voice.ScriptedDeviceSpeech")
        }
    }

    @Test
    fun `prod carries no placeholder recorder`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.maggie.app.voice.PlaceholderAudioRecorder")
        }
    }
}
