package com.maggie.app.ui.navigation

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.auth.BiometricLockManager
import com.maggie.app.data.model.Recipe
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.chat.ChatUiState
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.contexts.ContextUiState
import com.maggie.app.ui.screens.contexts.ContextViewModel
import com.maggie.app.ui.screens.cookbook.grocery.GroceryUiState
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekUiState
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailUiState
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListUiState
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.screens.dashboard.DashboardUiState
import com.maggie.app.ui.screens.dashboard.DashboardViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarUiState
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.ui.screens.login.LoginUiState
import com.maggie.app.ui.screens.login.LoginViewModel
import com.maggie.app.ui.screens.notifications.NotificationUiState
import com.maggie.app.ui.screens.notifications.NotificationViewModel
import com.maggie.app.ui.theme.MaggieTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class NavGraphHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaggieTheme { NavGraph() } }
    }
}

@RunWith(AndroidJUnit4::class)
class RotationNavGraphScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @After
    fun tearDown() = stopKoin()

    private fun <T> state(value: T) = MutableStateFlow(value)

    private fun startApp() {
        val auth = mockk<AuthRepository>(relaxed = true) { every { isAuthenticated } returns kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(200); emit(true) } }
        val lock = mockk<BiometricLockManager>(relaxed = true) { every { isLocked } returns MutableStateFlow(false) }
        val login = mockk<LoginViewModel>(relaxed = true) { every { uiState } returns state(LoginUiState()) }
        val chat = mockk<ChatViewModel>(relaxed = true) {
            every { uiState } returns state(ChatUiState())
            every { contextUpdates } returns MutableSharedFlow()
        }
        val ctx = mockk<ContextViewModel>(relaxed = true) { every { uiState } returns state(ContextUiState()) }
        val dash = mockk<DashboardViewModel>(relaxed = true) { every { uiState } returns state(DashboardUiState()) }
        val cal = mockk<FullCalendarViewModel>(relaxed = true) { every { uiState } returns state(FullCalendarUiState()) }
        val notif = mockk<NotificationViewModel>(relaxed = true) { every { uiState } returns state(NotificationUiState()) }
        val recipes = mockk<RecipeListViewModel>(relaxed = true) {
            every { uiState } returns state(RecipeListUiState(recipes = listOf(Recipe(id = "r1", name = "Chili"), Recipe(id = "r2", name = "Risotto"))))
        }
        val meals = mockk<MealsWeekViewModel>(relaxed = true) { every { uiState } returns state(MealsWeekUiState()) }
        val grocery = mockk<GroceryViewModel>(relaxed = true) { every { uiState } returns state(GroceryUiState()) }
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
                    viewModel { dash }
                    viewModel { cal }
                    viewModel { notif }
                    viewModel { recipes }
                    viewModel { meals }
                    viewModel { grocery }
                    viewModel { detail }
                },
            )
        }
    }

    private lateinit var scenario: ActivityScenario<NavGraphHostActivity>

    private fun launchOnCookbook(railed: Boolean) {
        startApp()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, NavGraphHostActivity::class.java))
        scenario = ActivityScenario.launch(NavGraphHostActivity::class.java)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        if (railed) {
            compose.onNodeWithTag(UiTags.railItem("cookbook")).performClick()
        } else {
            // The drawer is closed and its items are offscreen: act on the semantics, not on a touch.
            compose.onNodeWithTag(UiTags.drawerItem("cookbook")).performSemanticsAction(SemanticsActions.OnClick)
        }
        compose.waitForIdle()
    }

    private fun openChili() {
        compose.onNodeWithText("Chili").performClick()
        compose.waitForIdle()
    }

    private fun rotate(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        compose.waitForIdle()
    }

    private fun assertChiliInPane() {
        compose.onNodeWithTag(UiTags.LIST_PANE).assertIsDisplayed()
        compose.onNode(hasText("Chili") and hasAnyAncestor(hasTestTag(UiTags.DETAIL_PANE))).assertIsDisplayed()
    }

    private fun assertChiliOnItsOwnScreen() {
        compose.onNodeWithTag(UiTags.LIST_PANE).assertDoesNotExist()
        compose.onNodeWithText("Chili").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun `a phone turned to landscape with a recipe open shows it beside the list`() {
        launchOnCookbook(railed = false)
        openChili()
        assertChiliOnItsOwnScreen()

        rotate(PHONE_LANDSCAPE)

        assertChiliInPane()
        scenario.close()
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun `a phone back in portrait keeps the recipe open`() {
        launchOnCookbook(railed = false)
        openChili()
        rotate(PHONE_LANDSCAPE)

        rotate(PHONE_PORTRAIT)

        assertChiliOnItsOwnScreen()
        scenario.close()
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun `a recipe opened beside the list in landscape is still open once the phone is upright`() {
        launchOnCookbook(railed = true)
        openChili()
        assertChiliInPane()

        rotate(PHONE_PORTRAIT)

        assertChiliOnItsOwnScreen()
        scenario.close()
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun `leaving the reopened recipe shows the list, and the pane then waits for a choice`() {
        launchOnCookbook(railed = true)
        openChili()
        rotate(PHONE_PORTRAIT)
        assertChiliOnItsOwnScreen()

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.LIST_PANE).assertIsDisplayed()

        rotate(PHONE_LANDSCAPE)

        compose.onNodeWithText("Touchez une recette pour la voir ici.").assertIsDisplayed()
        scenario.close()
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun `recreating the activity with a recipe open does not blank the screen`() {
        launchOnCookbook(railed = false)
        openChili()

        scenario.recreate()
        compose.waitForIdle()

        assertChiliOnItsOwnScreen()
        scenario.close()
    }

    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun `a tablet turned upright and back keeps the recipe in its pane`() {
        launchOnCookbook(railed = true)
        openChili()
        assertChiliInPane()

        rotate(TABLET_PORTRAIT)
        rotate(TABLET_LANDSCAPE)

        assertChiliInPane()
        scenario.close()
    }

    private companion object {
        const val PHONE_PORTRAIT = "w411dp-h891dp-port-xhdpi"
        const val PHONE_LANDSCAPE = "w891dp-h411dp-land-xhdpi"
        const val TABLET_PORTRAIT = "w800dp-h1280dp-port-xhdpi"
        const val TABLET_LANDSCAPE = "w1280dp-h800dp-land-xhdpi"
    }
}
