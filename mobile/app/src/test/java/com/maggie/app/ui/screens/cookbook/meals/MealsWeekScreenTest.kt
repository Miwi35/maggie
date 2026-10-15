package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What a tap does in the week (MAG-378): an empty slot plans a meal, a meal opens its sheet.
 * A tap on a meal used to open the creation of a new one, so the owner planned the same
 * lunch twice.
 */
@RunWith(AndroidJUnit4::class)
class MealsWeekScreenTest {
    @get:Rule val compose = ScreenRule()

    private val monday = LocalDate.now().with(WeekFields.of(Locale.FRANCE).dayOfWeek(), 1)
    private val pasta = Meal(
        id = "meal-pasta",
        summary = "Pâtes",
        date = monday.toString(),
        slot = MealSlot.LUNCH,
        recipes = listOf("/api/recipes/r-pasta"),
    )

    private fun show(
        meals: List<Meal>,
        onOpenMeal: (Meal) -> Unit = {},
        onCreateMeal: (String, String) -> Unit,
    ) {
        val repository = mockk<MealRepository>()
        coEvery { repository.getMeals(any(), any()) } returns Result.success(meals)
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"

        compose.setContent {
            MealsWeekScreen(
                viewModel = MealsWeekViewModel(repository, mercure, auth),
                onCreateMeal = onCreateMeal,
                onOpenMeal = onOpenMeal,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `a tap on a meal does not plan another one`() {
        val created = mutableListOf<Pair<String, String>>()
        show(listOf(pasta)) { day, slot -> created += day to slot }

        compose.onNodeWithTag(UiTags.MEAL_ROW).performClick()
        compose.waitForIdle()

        assertEquals(emptyList<Pair<String, String>>(), created)
    }

    @Test
    fun `a tap on a meal opens its sheet`() {
        val opened = mutableListOf<Meal>()
        show(listOf(pasta), onOpenMeal = { opened += it }) { _, _ -> }

        compose.onNodeWithTag(UiTags.MEAL_ROW).performClick()
        compose.waitForIdle()

        assertEquals(listOf(pasta), opened)
    }

    @Test
    fun `the plus of a planned slot plans a second meal there`() {
        val created = mutableListOf<Pair<String, String>>()
        show(listOf(pasta)) { day, slot -> created += day to slot }

        compose.onNodeWithTag(UiTags.MEAL_ADD).performClick()
        compose.waitForIdle()

        assertEquals(listOf(monday.toString() to "lunch"), created)
    }

    @Test
    fun `a tap on an empty slot plans a meal there`() {
        val created = mutableListOf<Pair<String, String>>()
        show(emptyList()) { day, slot -> created += day to slot }

        compose.onNodeWithTag(UiTags.mealSlot("dinner", 0)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(monday.toString() to "dinner"), created)
    }
}
