package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** The scenario an add is previewed in: [editable] is false for an automatic one, which is duplicated, not added to. */
@Serializable
data class ProjectPreviewScenario(
    val id: String,
    val name: String,
    val editable: Boolean = false,
)

/** The project as typed; the preview never stores it. */
@Serializable
data class ProjectPreviewProject(
    val name: String = "",
    val amountCents: Int = 0,
    val currency: String = "EUR",
)

/** A project of the scenario: the month it is bought before and after the add (`YYYY-MM`, null past the 36 months). */
@Serializable
data class ProjectPreviewDate(
    val projectId: String,
    val name: String,
    val before: String? = null,
    val after: String? = null,
) {
    val moved: Boolean get() = before != after
}

/** The month N is reached before and after (`YYYY-MM`, null when not within 20 years). */
@Serializable
data class ProjectPreviewN(
    val before: String? = null,
    val after: String? = null,
) {
    val moved: Boolean get() = before != after
}

@Serializable
data class ProjectPreviewMetrics(
    val realCost: Int = 0,
    /** The month the new project is bought; null past the planning. */
    val availableAt: String? = null,
    /** Months N moves: positive later, negative earlier; null when N is out of reach before or after. */
    val nImpactMonths: Int? = null,
)

/**
 * What adding a project would change (MAG-553), from `POST /api/finance/projects/preview`: facts only, computed
 * without writing anything. [scenario] is null when no scenario is current (the projects in their order).
 */
@Serializable
data class ProjectPreview(
    val scenario: ProjectPreviewScenario? = null,
    val project: ProjectPreviewProject = ProjectPreviewProject(),
    val dates: List<ProjectPreviewDate> = emptyList(),
    val nDate: ProjectPreviewN = ProjectPreviewN(),
    val metrics: ProjectPreviewMetrics = ProjectPreviewMetrics(),
) {
    /** True when the project joins the scenario; false for an automatic one, which the project is created outside of. */
    val joinsScenario: Boolean get() = scenario?.editable == true
}

/** « Ajouter « Box à louer » au scénario courant ? », or to the projects when none is current. */
fun projectPreviewTitle(preview: ProjectPreview): String =
    "Ajouter « ${preview.project.name} » " + if (preview.scenario == null) "à vos projets ?" else "au scénario courant ?"

/** « mars 2027 », or the fact that the project falls past the 36 months. */
fun previewPurchaseLabel(month: String?): String = month?.let(::scenarioMonthLabel) ?: "au-delà de 36 mois"

fun previewNLabel(month: String?): String = month?.let(::scenarioMonthLabel) ?: "non atteinte avant 20 ans"

/** « 3 mois plus tard », « 1 mois plus tôt », « aucun décalage »; why it cannot be measured when null. */
fun previewImpactLabel(months: Int?): String = when {
    months == null -> "non mesurable : N n'est pas atteinte avant 20 ans dans l'un des deux cas"
    months == 0 -> "aucun décalage"
    else -> "${abs(months)} mois " + if (months > 0) "plus tard" else "plus tôt"
}
