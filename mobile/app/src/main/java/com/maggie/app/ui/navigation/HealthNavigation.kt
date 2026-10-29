package com.maggie.app.ui.navigation

import com.maggie.app.ui.components.BreadcrumbSection

/**
 * The pages of Santé (MAG-705), the entries of the breadcrumb's menu « Santé › Sport ▾ ». Only Sport exists: the menu
 * lists no placeholder. A tap on the current page does nothing.
 */
enum class HealthSection(val key: String, val label: String, val route: String) {
    SPORT("sport", "Sport", Screen.HealthSport.route),
    ;

    val breadcrumb: BreadcrumbSection get() = BreadcrumbSection(key, label)

    companion object {
        /** The section whose breadcrumb [route] wears, `null` for a form or a route outside the module. */
        fun pageOf(route: String?): HealthSection? = entries.firstOrNull { it.route == route }

        fun byKey(key: String?): HealthSection? = entries.firstOrNull { it.key == key }
    }
}
