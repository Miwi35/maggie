package com.maggie.app

import android.app.Application
import androidx.room.Room
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthManager
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.auth.BiometricLockManager
import com.maggie.app.data.fcm.MaggieFcmService
import com.maggie.app.data.local.MaggieDatabase
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.NotificationRepository
import com.maggie.app.data.repository.ProactionRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.data.repository.SearchRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.screens.dashboard.DashboardViewModel
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.ui.screens.login.LoginViewModel
import com.maggie.app.ui.screens.notifications.NotificationViewModel
import com.maggie.app.ui.screens.proactions.ProactionViewModel
import com.maggie.app.ui.screens.search.SearchViewModel
import com.maggie.app.ui.screens.settings.SettingsViewModel
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.WakeWordManager
import com.maggie.app.voice.WakeWordService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

class MaggieApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val appModule = module {
            // Auth
            single { AuthRepository(androidContext()) }
            single { AuthManager(get(), get()) }
            single { BiometricLockManager() }

            // Database
            single {
                Room.databaseBuilder(
                    androidContext(),
                    MaggieDatabase::class.java,
                    "maggie-database",
                ).fallbackToDestructiveMigration().build()
            }
            single { get<MaggieDatabase>().eventDao() }
            single { get<MaggieDatabase>().chatMessageDao() }
            single { get<MaggieDatabase>().taskDao() }
            single { get<MaggieDatabase>().agendaDao() }
            single { get<MaggieDatabase>().recipeDao() }

            // Network
            single {
                val authRepository: AuthRepository = get()
                HttpClient(OkHttp) {
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        })
                    }
                    defaultRequest {
                        val token = runBlocking { authRepository.getToken() }
                        if (token != null) {
                            header("Authorization", "Bearer $token")
                        }
                    }
                }
            }
            single { MaggieApiService(get()) }
            single { MercureService(get()) }

            // Repositories
            single { EventRepository(get(), get()) }
            single { TaskRepository(get(), get()) }
            single { ChatPreferencesRepository(androidContext()) }
            single { ChatRepository(get(), get()) }
            single { AgendaRepository(get(), get()) }
            single { RecipeRepository(get(), get()) }
            single { IngredientRepository(get()) }
            single { MealRepository(get()) }
            single { GroceryListRepository(get()) }
            single { NotificationRepository(get()) }
            single { SearchRepository(get()) }
            single { ProactionRepository(get()) }
            single { UserPreferenceRepository(get()) }

            // Other
            single { VoiceManager(androidContext(), get(), get()) }
            single { WakeWordManager(androidContext()) }

            // ViewModels
            viewModel { LoginViewModel(get()) }
            viewModel { DashboardViewModel(get(), get(), get(), get(), get()) }
            viewModel { FullCalendarViewModel(get(), get(), get(), get(), get()) }
            viewModel { ChatViewModel(get(), get(), get()) }
            viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get()) }
            viewModel { NotificationViewModel(get(), get(), get()) }
            viewModel { SearchViewModel(get()) }
            viewModel { ProactionViewModel(get()) }
            viewModel { RecipeListViewModel(get(), get()) }
            viewModel { MealsWeekViewModel(get()) }
            viewModel { GroceryViewModel(get()) }
        }

        startKoin {
            androidContext(this@MaggieApp)
            modules(appModule)
        }

        get<VoiceManager>().initialize()
        get<BiometricLockManager>().initialize()

        MaggieFcmService.createNotificationChannels(this)
        WakeWordService.createNotificationChannel(this)
        get<WakeWordManager>().restoreIfEnabled()
    }
}
