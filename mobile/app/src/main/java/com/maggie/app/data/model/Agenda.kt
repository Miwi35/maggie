package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Agenda(
    val id: String,
    val name: String,
    val description: String? = null,
    val timeZone: String = "Europe/Paris",
    val color: String = "#9055FD",
    // Serialised as "default" by the API, for the same reason as
    // Account.isCushion.
    @SerialName("default") val isDefault: Boolean = false,
    val googleCalendarId: String? = null,
    /** The module that files in this agenda (`tasks`, `cookbook`, `agent`, `health`, `finance`…); null for the user's own. */
    val module: String? = null,
)

const val MODULE_TASKS = "tasks"
const val MODULE_COOKBOOK = "cookbook"
const val MODULE_AGENT = "agent"
