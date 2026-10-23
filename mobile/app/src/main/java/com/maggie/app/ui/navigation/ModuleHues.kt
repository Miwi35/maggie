package com.maggie.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.theme.ModuleTheme

private val TASK_ROUTES = setOf(
    Screen.Tasks.route,
    Screen.TaskCreate.route,
    Screen.TaskEdit.route,
    Screen.TaskFocus.route,
    Screen.TaskBravo.route,
    DeepLinks.TASK_ROUTE,
    DeepLinks.TASK_FOCUS_ROUTE,
)

private val FINANCE_ONLY_ROUTES = setOf(
    Screen.ProjectForm.route,
    Screen.FinanceReviewActivity.route,
    DeepLinks.ACCOUNT_ROUTE,
    DeepLinks.CONTACT_ROUTE,
)

/** The module whose hue a route is drawn in, `null` for the screens that keep Maggie's violet. */
internal fun moduleHueOf(route: String?): ModuleHue? = when {
    route == null -> null
    FinanceSection.of(route) != null || route in FINANCE_ONLY_ROUTES -> ModuleHue.FINANCE
    route in TASK_ROUTES -> ModuleHue.TASKS
    else -> null
}

/** A [NavHost] whose screens are drawn in the hue of the module [route] belongs to. */
@Composable
internal fun ModuleNavHost(
    route: String?,
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
    popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
    builder: NavGraphBuilder.() -> Unit,
) {
    ModuleTheme(moduleHueOf(route)) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition,
            builder = builder,
        )
    }
}
