package com.maggie.app.ui.screens.cookbook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekScreen
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import kotlinx.coroutines.launch

private val TABS = listOf("Recettes", "Repas")

@Composable
fun CookbookScreen(
    recipeListViewModel: RecipeListViewModel,
    mealsWeekViewModel: MealsWeekViewModel,
    onRecipeClick: (String) -> Unit,
    onCreateRecipe: () -> Unit,
    onCreateMeal: (day: String, slot: String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        floatingActionButton = {
            if (pagerState.currentPage == 0) {
                FloatingActionButton(onClick = onCreateRecipe) {
                    Icon(Icons.Default.Add, contentDescription = "Nouvelle recette")
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) },
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> RecipeListScreen(
                        viewModel = recipeListViewModel,
                        onRecipeClick = onRecipeClick,
                    )
                    1 -> MealsWeekScreen(
                        viewModel = mealsWeekViewModel,
                        onCreateMeal = onCreateMeal,
                    )
                }
            }
        }
    }
}
