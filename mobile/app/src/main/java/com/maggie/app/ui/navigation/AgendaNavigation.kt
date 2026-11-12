package com.maggie.app.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import com.maggie.app.ui.components.BreadcrumbSection

/** The two pages of Agenda (MAG-815), the entries of the breadcrumb's menu « Agenda › Jour ▾ ». */
enum class AgendaSection(val key: String, val label: String, val route: String) {
    DAY("day", "Jour", Screen.Calendar.route),
    PERIODS("periods", "Périodes", Screen.PeriodList.route),
    ;

    val breadcrumb: BreadcrumbSection get() = BreadcrumbSection(key, label)

    companion object {
        fun byKey(key: String?): AgendaSection? = entries.firstOrNull { it.key == key }
    }
}

/** What a tap on a section does to the back stack: the sections are siblings, the calendar stays underneath. */
internal data class AgendaSectionAction(val route: String, val popToCalendar: Boolean)

internal fun agendaSectionAction(target: AgendaSection, calendarInBackStack: Boolean): AgendaSectionAction =
    AgendaSectionAction(route = target.route, popToCalendar = calendarInBackStack)

/** What the header of Agenda needs from the graph; `null` wherever a screen is drawn without it. */
class AgendaNav(
    val onSelectSection: (AgendaSection) -> Unit,
    val onOpenDrawer: (() -> Unit)?,
)

val LocalAgendaNav = compositionLocalOf<AgendaNav?> { null }
