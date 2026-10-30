package com.maggie.app.ui.navigation

import com.maggie.app.ui.theme.ModuleHue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleHuesTest {

    @Test
    fun `every finance section route is mint`() {
        FinanceSection.entries.flatMap { it.pages + it.details }.forEach {
            assertEquals(it, ModuleHue.FINANCE, moduleHueOf(it))
        }
    }

    @Test
    fun `finance screens outside the sections are mint too`() {
        listOf(Screen.ProjectForm.route, Screen.FinanceReviewActivity.route, DeepLinks.ACCOUNT_ROUTE, DeepLinks.CONTACT_ROUTE)
            .forEach { assertEquals(it, ModuleHue.FINANCE, moduleHueOf(it)) }
    }

    @Test
    fun `task screens are orchid`() {
        listOf(
            Screen.Tasks.route, Screen.TaskCreate.route, Screen.TaskEdit.route, Screen.TaskFocus.route,
            Screen.TaskBravo.route, DeepLinks.TASK_ROUTE, DeepLinks.TASK_FOCUS_ROUTE,
        ).forEach { assertEquals(it, ModuleHue.TASKS, moduleHueOf(it)) }
    }

    @Test
    fun `the Cuisine activity is periwinkle (MAG-580)`() {
        assertEquals(ModuleHue.CUISINE, moduleHueOf(Screen.CookingActivity.route))
    }

    @Test
    fun `every Cuisine screen is periwinkle (MAG-662)`() {
        listOf(
            Screen.Cookbook.route, Screen.RecipeDetail.route, Screen.RecipeCreate.route, Screen.RecipeEdit.route,
            Screen.MealCreate.route, Screen.MealIngredientChoice.route, DeepLinks.RECIPE_ROUTE,
        ).forEach { assertEquals(it, ModuleHue.CUISINE, moduleHueOf(it)) }
    }

    @Test
    fun `Santé's screens are coral (MAG-705, MAG-706, MAG-707)`() {
        listOf(Screen.HealthSuivi.route, Screen.HealthSport.route, Screen.HealthSportForm.route, Screen.HealthConfiguration.route)
            .forEach { assertEquals(it, ModuleHue.HEALTH, moduleHueOf(it)) }
    }

    @Test
    fun `other screens keep Maggie's violet`() {
        assertNull(moduleHueOf(Screen.Home.route))
        assertNull(moduleHueOf(Screen.Calendar.route))
        assertNull(moduleHueOf(Screen.Grocery.route))
        assertNull(moduleHueOf(Screen.Settings.route))
        assertNull(moduleHueOf(Screen.Loading.route))
        assertNull(moduleHueOf(null))
    }
}
