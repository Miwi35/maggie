package com.maggie.app.ui.navigation

import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What unfolding does to the back stack (MAG-263): the detail route leaves it and
 * the list takes its place, without a second copy of the list underneath.
 */
@RunWith(AndroidJUnit4::class)
class FoldDetailRouteScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private lateinit var nav: NavHostController

    private fun host(start: String) {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                composable(Screen.Dashboard.route) { Text("dashboard") }
                composable(Screen.Cookbook.route) { Text("list") }
                composable(Screen.RecipeDetail.route) { Text("detail") }
            }
        }
    }

    private fun stack(): List<String?> =
        nav.currentBackStack.value.map { it.destination.route }.filter { it != null && !it.startsWith("null") }

    @Test
    fun `the list opened from the list is replaced in place`() {
        host(Screen.Cookbook.route)
        compose.runOnIdle { nav.navigate(Screen.RecipeDetail.route) }
        compose.waitForIdle()

        compose.runOnIdle { nav.foldRouteInto(Screen.RecipeDetail.route, Screen.Cookbook.route) }
        compose.waitForIdle()

        assertEquals(listOf(Screen.Cookbook.route), stack())
    }

    @Test
    fun `a detail reached from elsewhere leaves the list on top of what was there`() {
        host(Screen.Dashboard.route)
        compose.runOnIdle { nav.navigate(Screen.RecipeDetail.route) }
        compose.waitForIdle()

        compose.runOnIdle { nav.foldRouteInto(Screen.RecipeDetail.route, Screen.Cookbook.route) }
        compose.waitForIdle()

        assertEquals(listOf(Screen.Dashboard.route, Screen.Cookbook.route), stack())
    }
}
