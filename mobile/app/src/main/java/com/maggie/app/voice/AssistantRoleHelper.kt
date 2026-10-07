package com.maggie.app.voice

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Whether Maggie is the assistant the long press summons (MAG-30).
 *
 * Four states and not a boolean: a device can have no assistant role at all, and
 * « non défini » must not look the same as « impossible ici » — the first invites a
 * tap, the second would open nothing. [PARTIAL] is the one a role-only check misses:
 * Maggie holds the role but Android did not retain her voice interaction service
 * (`voice_interaction_service` empty, seen after a reinstall), so the side key does
 * nothing while the screen used to say « déjà assistante » (MAG-216).
 */
enum class AssistantRoleState {
    UNAVAILABLE,
    HELD,
    PARTIAL,
    NOT_HELD,
    ;

    val label: String
        get() = when (this) {
            UNAVAILABLE -> "Indisponible sur cet appareil"
            HELD -> "Maggie répond à l'appui long"
            PARTIAL -> "Maggie n'est pas tout à fait activée — touchez pour la choisir"
            NOT_HELD -> "Non défini — touchez pour choisir Maggie"
        }

    val canRequest: Boolean get() = this == NOT_HELD || this == PARTIAL
}

object AssistantRoleHelper {

    const val VOICE_INTERACTION_SETTING = "voice_interaction_service"

    /** Android does not let an app ask for the assistant role: the user picks it in Settings. */
    const val HELP = "Choisissez Maggie dans « Assistant numérique »."

    /** Most specific screen first; the second is the list of default apps, where « Assistant numérique » lives. */
    val settingsActions = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
    )

    fun state(
        context: Context,
        readSetting: (String) -> String? = { key ->
            runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()
        },
    ): AssistantRoleState {
        val roleManager = context.getSystemService(RoleManager::class.java)
            ?: return AssistantRoleState.UNAVAILABLE
        return when {
            !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) -> AssistantRoleState.UNAVAILABLE
            !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) -> AssistantRoleState.NOT_HELD
            isMaggieService(readSetting(VOICE_INTERACTION_SETTING), context.packageName) -> AssistantRoleState.HELD
            else -> AssistantRoleState.PARTIAL
        }
    }

    /** Accepts both flattened forms: `pkg/.voice.Service` and `pkg/pkg.voice.Service`. */
    fun isMaggieService(setting: String?, packageName: String): Boolean {
        val (pkg, cls) = setting?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return false
        val fullClass = if (cls.startsWith(".")) pkg + cls else cls
        return pkg == packageName && fullClass == MaggieVoiceInteractionService::class.java.name
    }

    /** The first of [settingsActions] the device can open, or null. */
    fun firstOpenable(canOpen: (String) -> Boolean): String? = settingsActions.firstOrNull(canOpen)

    fun settingsIntent(context: Context): Intent? {
        val pm = context.packageManager
        val action = firstOpenable { pm.resolveActivity(Intent(it), PackageManager.MATCH_DEFAULT_ONLY) != null }
        return action?.let { Intent(it) }
    }
}
