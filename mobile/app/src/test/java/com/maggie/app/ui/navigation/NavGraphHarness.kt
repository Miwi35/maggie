package com.maggie.app.ui.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.auth.BiometricLockManager
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.interruption.InterruptionAlert
import com.maggie.app.ui.interruption.InterruptionUiState
import com.maggie.app.ui.interruption.InterruptionViewModel
import com.maggie.app.ui.screens.chat.ChatUiState
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.contexts.ContextUiState
import com.maggie.app.ui.screens.contexts.ContextViewModel
import com.maggie.app.ui.screens.cookbook.grocery.GroceryUiState
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.cookbook.grocery.RecurringGroceryUiState
import com.maggie.app.ui.screens.cookbook.grocery.RecurringGroceryViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekUiState
import com.maggie.app.ui.screens.cookbook.meals.MealCreateUiState
import com.maggie.app.ui.screens.cookbook.meals.MealCreateViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealDetailUiState
import com.maggie.app.ui.screens.cookbook.meals.MealDetailViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailUiState
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListUiState
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.screens.home.HomeUiState
import com.maggie.app.ui.screens.home.HomeViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarUiState
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.ui.screens.login.LoginUiState
import com.maggie.app.ui.screens.login.LoginViewModel
import com.maggie.app.ui.screens.notifications.NotificationUiState
import com.maggie.app.ui.screens.notifications.NotificationViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal fun <T> state(value: T) = MutableStateFlow(value)

/** The Koin graph `NavGraph` needs, every view model a relaxed mock: what the nav graph screen tests start the app on. */
internal fun startNavGraphApp() {
    val auth = mockk<AuthRepository>(relaxed = true) { every { isAuthenticated } returns kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(200); emit(true) } }
    val lock = mockk<BiometricLockManager>(relaxed = true) { every { isLocked } returns MutableStateFlow(false) }
    val login = mockk<LoginViewModel>(relaxed = true) { every { uiState } returns state(LoginUiState()) }
    val chat = mockk<ChatViewModel>(relaxed = true) {
        every { uiState } returns state(ChatUiState())
        every { contextUpdates } returns MutableSharedFlow()
    }
    val ctx = mockk<ContextViewModel>(relaxed = true) {
        every { uiState } returns state(ContextUiState())
        every { threadEvents } returns MutableSharedFlow()
    }
    val home = mockk<HomeViewModel>(relaxed = true) {
        every { uiState } returns state(HomeUiState())
        every { cards(any()) } answers { com.maggie.app.ui.screens.home.HomeLayout.file.screenCards(firstArg()) }
    }
    val groceryHome = mockk<com.maggie.app.ui.screens.home.GroceryHomeViewModel>(relaxed = true) {
        every { uiState } returns state(com.maggie.app.ui.screens.home.GroceryHomeUiState(isLoading = false))
    }
    val nutritionHome = mockk<com.maggie.app.ui.screens.home.NutritionHomeViewModel>(relaxed = true) {
        every { uiState } returns state(com.maggie.app.ui.screens.home.NutritionHomeUiState(isLoading = false))
    }
    val tasksCard = mockk<com.maggie.app.ui.screens.home.TasksCardViewModel>(relaxed = true) {
        every { uiState } returns state(com.maggie.app.ui.screens.home.TasksCardUiState())
    }
    val financeHome = mockk<com.maggie.app.ui.screens.home.FinanceHomeViewModel>(relaxed = true) {
        every { uiState } returns state(com.maggie.app.ui.screens.home.FinanceHomeUiState(isLoading = false))
    }
    val agendaHome = mockk<com.maggie.app.ui.screens.home.AgendaHomeViewModel>(relaxed = true) {
        every { uiState } returns state(com.maggie.app.ui.screens.home.AgendaHomeUiState())
    }
    val tasks = mockk<com.maggie.app.ui.screens.tasks.TasksViewModel>(relaxed = true) {
        every { uiState } returns state(com.maggie.app.ui.screens.tasks.TasksUiState())
    }
    val routines = mockk<com.maggie.app.ui.screens.tasks.RoutinesViewModel>(relaxed = true) {
        every { uiState } returns state(com.maggie.app.ui.screens.tasks.RoutinesUiState())
    }
    val cal = mockk<FullCalendarViewModel>(relaxed = true) { every { uiState } returns state(FullCalendarUiState()) }
    val notif = mockk<NotificationViewModel>(relaxed = true) { every { uiState } returns state(NotificationUiState()) }
    val drawerSummary = mockk<com.maggie.app.ui.components.DrawerSummaryViewModel>(relaxed = true) {
        every { summary } returns state(com.maggie.app.ui.components.DrawerSummary())
    }
    val recipes = mockk<RecipeListViewModel>(relaxed = true) {
        every { uiState } returns state(RecipeListUiState(recipes = listOf(Recipe(id = "r1", name = "Chili"), Recipe(id = "r2", name = "Risotto"))))
    }
    val meals = mockk<MealsWeekViewModel>(relaxed = true) { every { uiState } returns state(MealsWeekUiState()) }
    val mealCreate = mockk<MealCreateViewModel>(relaxed = true) { every { uiState } returns state(MealCreateUiState()) }
    val mealDetail = mockk<MealDetailViewModel>(relaxed = true) { every { uiState } returns state(MealDetailUiState()) }
    val grocery = mockk<GroceryViewModel>(relaxed = true) { every { uiState } returns state(GroceryUiState()) }
    val recurring = mockk<RecurringGroceryViewModel>(relaxed = true) { every { uiState } returns state(RecurringGroceryUiState()) }
    val interruption = mockk<InterruptionViewModel>(relaxed = true) {
        every { uiState } returns state(InterruptionUiState())
        every { events } returns kotlinx.coroutines.flow.emptyFlow()
    }
    val cookingResume = mockk<com.maggie.app.ui.screens.cookbook.cooking.CookingResumeViewModel>(relaxed = true) {
        every { resume } returns MutableStateFlow(null)
    }
    val detail = mockk<RecipeDetailViewModel>(relaxed = true) {
        every { uiState } returns state(RecipeDetailUiState(recipe = Recipe(id = "r1", name = "Chili"), isLoading = false))
    }

    startKoin {
        androidContext(ApplicationProvider.getApplicationContext<Application>())
        modules(
            module {
                single { auth }
                single { lock }
                single { mockk<com.maggie.app.voice.VoiceManager>(relaxed = true) }
                single { mockk<com.maggie.app.data.repository.EventRepository>(relaxed = true) }
                single { mockk<com.maggie.app.data.repository.TaskRepository>(relaxed = true) }
                single { mockk<com.maggie.app.data.repository.AgendaRepository>(relaxed = true) }
                single { mockk<com.maggie.app.data.repository.RecipeRepository>(relaxed = true) }
                single { mockk<com.maggie.app.data.repository.MealRepository>(relaxed = true) }
                single { mockk<com.maggie.app.data.api.MaggieApiService>(relaxed = true) }
                viewModel { login }
                viewModel { chat }
                viewModel { ctx }
                viewModel { home }
                viewModel { groceryHome }
                viewModel { tasksCard }
                viewModel { nutritionHome }
                viewModel { cookingResume }
                viewModel { financeHome }
                viewModel { agendaHome }
                viewModel { cal }
                viewModel { notif }
                viewModel { drawerSummary }
                viewModel { recipes }
                viewModel { meals }
                viewModel { mealCreate }
                viewModel { mealDetail }
                viewModel { grocery }
                viewModel { recurring }
                viewModel { detail }
                viewModel { interruption }
                viewModel { com.maggie.app.ui.screens.shared.SubtasksViewModel(get()) }
                viewModel { com.maggie.app.ui.screens.tasks.FocusViewModel(get()) }
                viewModel {
                    com.maggie.app.ui.screens.tasks.BravoViewModel(
                        get(),
                        com.maggie.app.data.reward.TaskRewards(get(), mockk(relaxed = true)),
                    )
                }
                viewModel { tasks }
                viewModel { routines }
                single<InterruptionAlert> { InterruptionAlert {} }
            },
        )
    }
}
