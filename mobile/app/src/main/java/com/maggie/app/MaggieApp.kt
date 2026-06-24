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
import com.maggie.app.data.repository.ContextRepository
import com.maggie.app.data.repository.ProactionRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.data.repository.SearchRepository
import com.maggie.app.data.repository.StoreRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.grocery.ProductViewModel
import com.maggie.app.ui.screens.grocery.StoreViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.screens.dashboard.DashboardViewModel
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.contexts.ContextViewModel
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
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

@Serializable
private data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
private data class RefreshResponse(
    val token: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

class MaggieApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Swallow a known Ktor 3.0.2 crash where OkHttpSSESession.onFailure
        // dereferences a null CompletableDeferred when the SSE connection
        // drops. Until we bump Ktor past the fix, log and keep the process
        // alive instead of taking the whole app down on every network blip.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val isKtorSseNpe = error is NullPointerException
                && error.stackTrace.any { it.className.contains("OkHttpSSESession") }
            if (isKtorSseNpe) {
                android.util.Log.w("MaggieApp", "Swallowed Ktor OkHttpSSESession NPE on ${thread.name}", error)
                return@setDefaultUncaughtExceptionHandler
            }
            previousHandler?.uncaughtException(thread, error)
        }

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
                val apiHost = Url(BuildConfig.API_BASE_URL).host
                HttpClient(OkHttp) {
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        })
                    }
                    install(Auth) {
                        bearer {
                            loadTokens {
                                val access = authRepository.getToken() ?: return@loadTokens null
                                BearerTokens(access, authRepository.getRefreshToken() ?: "")
                            }
                            // On a 401, Ktor calls this once to mint a fresh JWT from the
                            // refresh token, then retries the original request. If the refresh
                            // fails (expired/revoked), we clear auth so the app routes to login.
                            refreshTokens {
                                val refresh = authRepository.getRefreshToken()
                                if (refresh == null) {
                                    authRepository.clear()
                                    return@refreshTokens null
                                }
                                try {
                                    // `client` here is Ktor's refresh client: it does not
                                    // re-enter the Auth plugin, so this call won't loop.
                                    val refreshed: RefreshResponse =
                                        client.post("${BuildConfig.API_BASE_URL}/api/token/refresh") {
                                            contentType(ContentType.Application.Json)
                                            setBody(RefreshRequest(refresh))
                                        }.body()
                                    authRepository.updateTokens(refreshed.token, refreshed.refreshToken)
                                    BearerTokens(refreshed.token, refreshed.refreshToken ?: refresh)
                                } catch (e: Exception) {
                                    authRepository.clear()
                                    null
                                }
                            }
                            sendWithoutRequest { request -> request.url.host == apiHost }
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
            single { ProductRepository(get()) }
            single { StoreRepository(get()) }
            single { NotificationRepository(get()) }
            single { SearchRepository(get()) }
            single { ContextRepository(get()) }
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
            viewModel { ContextViewModel(get(), get(), get()) }
            viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get()) }
            viewModel { NotificationViewModel(get(), get(), get()) }
            viewModel { SearchViewModel(get()) }
            viewModel { ProactionViewModel(get()) }
            viewModel { RecipeListViewModel(get(), get()) }
            viewModel { MealsWeekViewModel(get()) }
            viewModel { GroceryViewModel(get(), get(), get(), get()) }
            viewModel { ProductViewModel(get(), get()) }
            viewModel { StoreViewModel(get()) }
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
