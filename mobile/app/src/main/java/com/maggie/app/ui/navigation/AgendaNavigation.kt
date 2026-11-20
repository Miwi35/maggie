package com.maggie.app.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import com.maggie.app.ui.components.BreadcrumbSection
import com.maggie.app.ui.screens.fullcalendar.CalendarViewType

/**
 * The entries of the breadcrumb's menu « Agenda › Aujourd'hui ▾ » (MAG-815, MAG-859): the three views of the
 * calendar — [view] says which — then the periods' page.
 */
enum class AgendaSection(val key: String, val label: String, val route: String, val view: CalendarViewType?) {
    DAY("day", "Aujourd'hui", Screen.Calendar.route, CalendarViewType.DAY),
    WEEK("week", "Semaine", Screen.Calendar.route, CalendarViewType.WEEK),
    MONTH("month", "Mois", Screen.Calendar.route, CalendarViewType.MONTH),
    PERIODS("periods", "Périodes", Screen.PeriodList.route, null),
    ;

    val breadcrumb: BreadcrumbSection get() = BreadcrumbSection(key, label)

    companion object {
        fun byKey(key: String?): AgendaSection? = entries.firstOrNull { it.key == key }

        /** The section the calendar shows while it is in [view]. */
        fun of(view: CalendarViewType): AgendaSection = entries.first { it.view == view }
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
