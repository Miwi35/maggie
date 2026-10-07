package com.maggie.app.voice

import android.app.role.RoleManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRoleHelperTest {

    private val roleManager = mockk<RoleManager>()
    private val context = mockk<Context>()

    private val maggieService = "com.maggie.app/.voice.MaggieVoiceInteractionService"

    private fun stateWith(service: String?) = AssistantRoleHelper.state(context) { service }

    private fun withRole(available: Boolean, held: Boolean) {
        every { context.packageName } returns "com.maggie.app"
        every { context.getSystemService(RoleManager::class.java) } returns roleManager
        every { roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) } returns available
        every { roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) } returns held
    }

    @Test
    fun `the role held with Maggie's service active is shown as held`() {
        withRole(available = true, held = true)

        assertEquals(AssistantRoleState.HELD, stateWith(maggieService))
    }

    @Test
    fun `the role held with the service setting empty is only partly activated`() {
        withRole(available = true, held = true)

        val state = stateWith(null)
        assertEquals(AssistantRoleState.PARTIAL, state)
        assertTrue("the row must still open the system screen", state.canRequest)
        assertEquals(AssistantRoleState.PARTIAL, stateWith(""))
    }

    @Test
    fun `the role held with another voice interaction service is only partly activated`() {
        withRole(available = true, held = true)

        assertEquals(
            AssistantRoleState.PARTIAL,
            stateWith("com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"),
        )
    }

    @Test
    fun `both flattened forms of Maggie's service are recognised`() {
        val full = "com.maggie.app/com.maggie.app.voice.MaggieVoiceInteractionService"
        assertTrue(AssistantRoleHelper.isMaggieService(maggieService, "com.maggie.app"))
        assertTrue(AssistantRoleHelper.isMaggieService(full, "com.maggie.app"))
        assertFalse(AssistantRoleHelper.isMaggieService("com.maggie.app", "com.maggie.app"))
        assertFalse(AssistantRoleHelper.isMaggieService(null, "com.maggie.app"))
    }

    @Test
    fun `an assistant role somebody else holds is still ours to ask for`() {
        withRole(available = true, held = false)

        val state = stateWith(null)
        assertEquals(AssistantRoleState.NOT_HELD, state)
        assertTrue(state.canRequest)
    }

    @Test
    fun `a device without the role is told apart from one where nothing is set`() {
        withRole(available = false, held = false)

        val state = stateWith(null)
        assertEquals(AssistantRoleState.UNAVAILABLE, state)
        assertFalse("nothing to open, so the row must not invite a tap", state.canRequest)
    }

    @Test
    fun `no role manager at all reads as unavailable rather than crashing`() {
        every { context.getSystemService(RoleManager::class.java) } returns null

        assertEquals(AssistantRoleState.UNAVAILABLE, stateWith(null))
    }

    @Test
    fun `each state says something different to the user`() {
        val labels = AssistantRoleState.entries.map { it.label }

        assertEquals(labels.distinct().size, labels.size)
        assertTrue(AssistantRoleState.HELD.label.contains("Maggie"))
        assertTrue(AssistantRoleState.NOT_HELD.label.startsWith("Non défini"))
        assertTrue(AssistantRoleState.PARTIAL.label.contains("pas tout à fait activée"))
    }

    @Test
    fun `the button opens the voice input screen, or the default apps list when that one is missing`() {
        assertEquals(
            "android.settings.VOICE_INPUT_SETTINGS",
            AssistantRoleHelper.firstOpenable { true },
        )
        assertEquals(
            "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            AssistantRoleHelper.firstOpenable { it == "android.settings.MANAGE_DEFAULT_APPS_SETTINGS" },
        )
        assertNull(AssistantRoleHelper.firstOpenable { false })
    }
}
