package com.maggie.app.voice

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Whether Maggie is the assistant the long press summons (MAG-30).
 *
 * Three states and not a boolean: a device can have no assistant role at all,
 * and « non défini » must not look the same as « impossible ici » — the first
 * invites a tap, the second would open nothing.
 */
enum class AssistantRoleState {
    UNAVAILABLE,
    HELD,
    NOT_HELD,
    ;

    val label: String
        get() = when (this) {
            UNAVAILABLE -> "Indisponible sur cet appareil"
            HELD -> "Maggie répond à l'appui long"
            NOT_HELD -> "Non défini — touchez pour choisir Maggie"
        }

    val canRequest: Boolean get() = this == NOT_HELD
}

object AssistantRoleHelper {

    fun state(context: Context): AssistantRoleState {
        val roleManager = context.getSystemService(RoleManager::class.java)
            ?: return AssistantRoleState.UNAVAILABLE
        return when {
            !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) -> AssistantRoleState.UNAVAILABLE
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) -> AssistantRoleState.HELD
            else -> AssistantRoleState.NOT_HELD
        }
    }

    fun createRoleRequestIntent(context: Context): Intent? =
        context.getSystemService(RoleManager::class.java)
            ?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)

    /**
     * Where to send the user when the role dialog is not handled — some OEM
     * builds answer the request intent with nothing at all, and an entry that
     * does nothing when tapped is worse than one that opens the system list.
     */
    fun voiceInputSettingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
}
