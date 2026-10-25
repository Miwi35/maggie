package com.maggie.app.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import com.maggie.app.ui.components.BreadcrumbSection

/**
 * The three pages of Courses (MAG-627), the entries of the breadcrumb's menu « Courses › Liste ▾ »: the list is the
 * home, the catalogue of products and the shops are its siblings. « Courses passées » and the aisles of a shop are
 * opened from them and wear a back arrow instead.
 */
enum class GrocerySection(val key: String, val label: String, val route: String) {
    LIST("list", "Liste", Screen.Grocery.route),
    PRODUCTS("products", "Produits", Screen.ProductList.route),
    STORES("stores", "Magasins", Screen.StoreList.route),
    ;

    val breadcrumb: BreadcrumbSection get() = BreadcrumbSection(key, label)

    companion object {
        /** The section whose breadcrumb [route] wears, `null` for a detail screen or a route outside the module. */
        fun pageOf(route: String?): GrocerySection? = entries.firstOrNull { it.route == route }

        fun byKey(key: String?): GrocerySection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a tap on a section does to the back stack: the sections are siblings, not a trail — Liste → Produits →
 * Magasins goes back to the Liste in one step. Without the list underneath, the current screen is replaced.
 */
internal data class GrocerySectionAction(val route: String, val popToList: Boolean)

internal fun grocerySectionAction(target: GrocerySection, listInBackStack: Boolean): GrocerySectionAction =
    GrocerySectionAction(route = target.route, popToList = listInBackStack)

/**
 * What the header of Courses needs from the graph: the sections menu and the drawer. [onOpenDrawer] is `null` when a
 * rail already shows the destinations. Absent (`null`) wherever a screen is drawn without the graph.
 */
class GroceryNav(
    val onSelectSection: (GrocerySection) -> Unit,
    val onOpenDrawer: (() -> Unit)?,
)

val LocalGroceryNav = compositionLocalOf<GroceryNav?> { null }
