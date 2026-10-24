package com.maggie.app.ui.screens.chat

/** What Maggie is doing right now, read from the AG-UI stream of the turn (MAG-403). */
enum class AgentState { IDLE, THINKING, ACTING }

enum class ToolCallStatus {
    RUNNING,
    SUCCESS,
    ERROR,

    /** The policy held the call back until the user answers it (MAG-4): a third outcome, not a failure. */
    PENDING_APPROVAL,
    ;

    companion object {
        fun fromWire(status: String): ToolCallStatus? = when (status) {
            "running" -> RUNNING
            "success" -> SUCCESS
            "error" -> ERROR
            "pending_approval" -> PENDING_APPROVAL
            else -> null
        }
    }
}

/** A tool call of the open session. [toolName] is the technical name: the screen shows [toolActivityLabel]. */
data class ToolCallItem(
    val id: String,
    val toolName: String,
    val status: ToolCallStatus,
)

private val ACTIVITY_LABELS = mapOf(
    "create_event" to "Création d'un événement",
    "update_event" to "Modification d'un événement",
    "delete_event" to "Suppression d'un événement",
    "get_event" to "Lecture d'un événement",
    "get_events_by_date" to "Consultation de l'agenda",
    "get_upcoming_events" to "Consultation de l'agenda",
    "check_conflicts" to "Vérification de l'agenda",
    "create_task" to "Création d'une tâche",
    "update_task" to "Modification d'une tâche",
    "delete_task" to "Suppression d'une tâche",
    "get_tasks" to "Consultation des tâches",
    "create_recipe" to "Création d'une recette",
    "update_recipe" to "Modification d'une recette",
    "delete_recipe" to "Suppression d'une recette",
    "get_recipe" to "Lecture d'une recette",
    "search_recipes" to "Recherche de recettes",
    "get_weather" to "Consultation de la météo",
    "read_web_page" to "Lecture d'une page web",
    "add_grocery_item" to "Ajout aux courses",
    "remove_grocery_item" to "Retrait des courses",
    "get_grocery_list" to "Consultation des courses",
    "generate_grocery_list" to "Préparation des courses",
    "search" to "Recherche",
)

/**
 * The tool as the owner reads it. A technical name never reaches the screen: one this
 * table does not know falls back on its verb, and on a generic line without one.
 */
fun toolActivityLabel(toolName: String): String {
    ACTIVITY_LABELS[toolName]?.let { return it }
    return when (toolName.substringBefore('_')) {
        "get", "list", "check", "search" -> "Consultation de tes données"
        "create", "add" -> "Création d'un élément"
        "update", "manage", "move", "reorder", "assign" -> "Modification d'un élément"
        "delete", "remove" -> "Suppression d'un élément"
        else -> "Action de Maggie"
    }
}
