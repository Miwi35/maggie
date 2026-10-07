package com.maggie.app.ui.screens.settings

import android.app.Application
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.AssistantRoleHelper
import com.maggie.app.voice.AssistantRoleState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Paramètres > Voix, without an emulator (MAG-242).
 *
 * `10-voice-settings` was a whole journey — sign in, open the drawer, open the
 * settings, open Voix — to assert four lines of a screen: the assistant role row
 * with its state, no wake word and no battery option since MAG-240, and the
 * « Ouvrir Maggie rapidement » help further down. `02-voice-overlay` ended on the
 * same screen, which cost it a relaunch and a drawer of its own. Neither needs a
 * device: the only thing on that screen that is not plain Compose is the
 * `RoleManager` lookup, and Robolectric has one.
 *
 * **Stronger than the flows on the one point they had to give up.** Both asserted
 * « the row shows one of its three states », as an alternation, because the role
 * may be held, free or absent depending on the emulator image and betting on one
 * would turn a required check red for a reason that has nothing to do with the
 * app. Here the device's answer is set, so each state is asserted as itself.
 *
 * What is still out of reach, and stays in Recette: choosing Maggie in the system's
 * « Assistant numérique » screen, and Android keeping her service afterwards. That is
 * the owner's, on his phone.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSettingsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    /**
     * The one screen test that mocks its ViewModel whole rather than building the
     * real one over faked repositories, and the exception the standard names:
     * `VoiceSection` takes no ViewModel at all. What is asserted here is a
     * `RoleManager` read and static copy, so a real `SettingsViewModel` would only
     * add six fakes to reach the list the first tap goes through.
     */
    private val viewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState())
    }

    /** The settings list, then Voix — the two taps the journeys spent a sign-in on. */
    private fun openVoiceSettings() {
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Assistant par défaut, mot d'activation").assertIsDisplayed()
        compose.onNodeWithText("Voix").performClick()
    }

    @Test
    fun `the voice screen carries the assistant role row`() {
        openVoiceSettings()

        compose.onNodeWithText("Assistant par défaut").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_ASSISTANT_ROLE).assertIsDisplayed()
        compose.onNodeWithText("Définir Maggie comme assistant").assertIsDisplayed()
    }

    /** MAG-240 took the wake word out; the battery option went with it. */
    @Test
    fun `the voice screen offers no wake word and no battery option`() {
        openVoiceSettings()

        compose.onAllNodesWithText("activation", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Batterie", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the quick ways to open Maggie are further down the same screen`() {
        openVoiceSettings()

        compose.onNodeWithText("Ouvrir Maggie rapidement (Galaxy S24)")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a device with no assistant role says so rather than inviting a tap`() {
        openVoiceSettings()

        compose.onNodeWithText(AssistantRoleState.UNAVAILABLE.label).assertIsDisplayed()
    }

    @Test
    fun `a device where the role is free invites the tap`() {
        roleIsAvailable()

        openVoiceSettings()

        compose.onNodeWithText(AssistantRoleState.NOT_HELD.label).assertIsDisplayed()
    }

    @Test
    fun `a device where Maggie holds the role and her service is active says she answers the long press`() {
        roleIsAvailable()
        shadowOf(roleManager()).addHeldRole(RoleManager.ROLE_ASSISTANT)
        setVoiceInteractionService("com.maggie.app/.voice.MaggieVoiceInteractionService")

        openVoiceSettings()

        compose.onNodeWithText(AssistantRoleState.HELD.label).assertIsDisplayed()
    }

    @Test
    fun `a role held with no active service is not shown as done`() {
        roleIsAvailable()
        shadowOf(roleManager()).addHeldRole(RoleManager.ROLE_ASSISTANT)

        openVoiceSettings()

        compose.onNodeWithText(AssistantRoleState.PARTIAL.label).assertIsDisplayed()
        compose.onNodeWithText(AssistantRoleHelper.HELP).assertIsDisplayed()
    }

    @Test
    fun `tapping the row opens the voice input settings`() {
        roleIsAvailable()
        resolvable(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

        openVoiceSettings()
        compose.onNodeWithTag(UiTags.SETTINGS_ASSISTANT_ROLE).performClick()

        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, shadowOf(application()).nextStartedActivity.action)
    }

    @Test
    fun `tapping the row falls back on the default apps list`() {
        roleIsAvailable()
        resolvable(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

        openVoiceSettings()
        compose.onNodeWithTag(UiTags.SETTINGS_ASSISTANT_ROLE).performClick()

        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, shadowOf(application()).nextStartedActivity.action)
    }

    @Test
    fun `tapping the row on a build with neither screen opens nothing and does not crash`() {
        roleIsAvailable()

        openVoiceSettings()
        compose.onNodeWithTag(UiTags.SETTINGS_ASSISTANT_ROLE).performClick()

        assertNull(shadowOf(application()).nextStartedActivity)
        compose.onNodeWithText(AssistantRoleHelper.HELP).assertIsDisplayed()
    }

    private fun application() = ApplicationProvider.getApplicationContext<Application>()

    private fun setVoiceInteractionService(value: String) {
        Settings.Secure.putString(application().contentResolver, "voice_interaction_service", value)
    }

    private fun resolvable(vararg actions: String) {
        val pm = shadowOf(application().packageManager)
        actions.forEach {
            pm.addResolveInfoForIntent(
                Intent(it),
                ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "Screen" } },
            )
        }
    }

    private fun roleIsAvailable() {
        shadowOf(roleManager()).addAvailableRole(RoleManager.ROLE_ASSISTANT)
    }

    private fun roleManager(): RoleManager =
        ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(RoleManager::class.java)
}
