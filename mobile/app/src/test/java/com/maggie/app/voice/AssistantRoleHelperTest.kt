package com.maggie.app.voice

import android.app.role.RoleManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRoleHelperTest {

    private val roleManager = mockk<RoleManager>()
    private val context = mockk<Context>()

    private fun withRole(available: Boolean, held: Boolean) {
        every { context.getSystemService(RoleManager::class.java) } returns roleManager
        every { roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) } returns available
        every { roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) } returns held
    }

    @Test
    fun `Maggie holding the role is shown as held`() {
        withRole(available = true, held = true)

        assertEquals(AssistantRoleState.HELD, AssistantRoleHelper.state(context))
    }

    @Test
    fun `an assistant role somebody else holds is still ours to ask for`() {
        withRole(available = true, held = false)

        val state = AssistantRoleHelper.state(context)
        assertEquals(AssistantRoleState.NOT_HELD, state)
        assertTrue(state.canRequest)
    }

    @Test
    fun `a device without the role is told apart from one where nothing is set`() {
        withRole(available = false, held = false)

        val state = AssistantRoleHelper.state(context)
        assertEquals(AssistantRoleState.UNAVAILABLE, state)
        assertFalse("nothing to open, so the row must not invite a tap", state.canRequest)
    }

    @Test
    fun `no role manager at all reads as unavailable rather than crashing`() {
        every { context.getSystemService(RoleManager::class.java) } returns null

        assertEquals(AssistantRoleState.UNAVAILABLE, AssistantRoleHelper.state(context))
    }

    @Test
    fun `each state says something different to the user`() {
        val labels = AssistantRoleState.entries.map { it.label }

        assertEquals(labels.distinct().size, labels.size)
        assertTrue(AssistantRoleState.HELD.label.contains("Maggie"))
        assertTrue(AssistantRoleState.NOT_HELD.label.startsWith("Non défini"))
    }

    @Test
    fun `the role request is asked of the role manager, by name`() {
        val intent = mockk<android.content.Intent>()
        every { context.getSystemService(RoleManager::class.java) } returns roleManager
        every { roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) } returns intent

        assertEquals(intent, AssistantRoleHelper.createRoleRequestIntent(context))
    }
}
