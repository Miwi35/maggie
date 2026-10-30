package com.maggie.app.ui.navigation

import com.maggie.app.ui.components.BreadcrumbSection

/**
 * The pages of Santé, the entries of the breadcrumb's menu « Santé › Suivi ▾ »: Suivi (MAG-706), the module's home,
 * then Sport (MAG-705), then Configuration (MAG-707), the last. The menu lists no placeholder. A tap on the current page
 * does nothing.
 */
enum class HealthSection(val key: String, val label: String, val route: String) {
    SUIVI("suivi", "Suivi", Screen.HealthSuivi.route),
    SPORT("sport", "Sport", Screen.HealthSport.route),
    CONFIGURATION("configuration", "Configuration", Screen.HealthConfiguration.route),
    ;

    val breadcrumb: BreadcrumbSection get() = BreadcrumbSection(key, label)

    companion object {
        /** The page the drawer opens: the first of the menu. */
        val HOME: HealthSection get() = entries.first()

        /** The section whose breadcrumb [route] wears, `null` for a form or a route outside the module. */
        fun pageOf(route: String?): HealthSection? = entries.firstOrNull { it.route == route }

        fun byKey(key: String?): HealthSection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a tap on a section does to the back stack, as in Courses: the sections are siblings over Suivi — Suivi → Sport
 * goes back to Suivi in one step. Without Suivi underneath (a deep link to Sport), the current page is replaced.
 */
internal data class HealthSectionAction(val route: String, val popToHome: Boolean)

internal fun healthSectionAction(target: HealthSection, homeInBackStack: Boolean): HealthSectionAction =
    HealthSectionAction(route = target.route, popToHome = homeInBackStack)
