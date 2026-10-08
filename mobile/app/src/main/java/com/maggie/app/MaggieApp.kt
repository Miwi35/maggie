package com.maggie.app

import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.installApiTimeouts
import com.maggie.app.data.auth.AuthManager
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.auth.BiometricLockManager
import com.maggie.app.data.auth.signInStrategy
import com.maggie.app.data.fcm.FcmTokenSource
import com.maggie.app.data.fcm.FirebaseTokenSource
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushChannels
import com.maggie.app.data.fcm.PushTokenRegistrar
import com.maggie.app.data.local.MaggieDatabase
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.NotificationRepository
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.BankConnectionRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CushionRepository
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.MonthlyReviewRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.ContextRepository
import com.maggie.app.data.repository.ProactionRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.data.repository.SearchRepository
import com.maggie.app.data.repository.StoreRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.finance.AccountViewModel
import com.maggie.app.ui.screens.finance.BudgetViewModel
import com.maggie.app.ui.screens.finance.BankConnectionViewModel
import com.maggie.app.ui.screens.finance.CategorizationRuleViewModel
import com.maggie.app.ui.screens.finance.RuleSuggestionViewModel
import com.maggie.app.ui.screens.finance.CushionViewModel
import com.maggie.app.ui.screens.finance.LoanViewModel
import com.maggie.app.ui.screens.finance.FinanceDashboardViewModel
import com.maggie.app.ui.screens.finance.MonthlyReviewViewModel
import com.maggie.app.ui.screens.finance.CategoryViewModel
import com.maggie.app.ui.screens.finance.TransactionViewModel
import com.maggie.app.ui.screens.grocery.ProductViewModel
import com.maggie.app.ui.screens.grocery.StoreViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeEditViewModel
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
import com.maggie.app.util.SentrySetup
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.audioRecorderFactory
import com.maggie.app.voice.deviceSpeechFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
    val mercureToken: String? = null,
)

class MaggieApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Crash and ANR reports (prod release only: elsewhere the DSN is empty and
        // nothing starts). Before the handler below, so that one wraps Sentry's:
        // the swallowed Ktor NPE never reaches Sentry as a crash.
        SentrySetup.init(this, BuildConfig.SENTRY_DSN, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

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
            // `signInStrategy` is resolved by the flavor: Google in dev and
            // prod (src/google/), the seeded test login in e2e (src/e2e/).
            single { AuthManager(get(), signInStrategy(get())) }
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
                    installApiTimeouts()
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
                                    authRepository.updateTokens(refreshed.token, refreshed.refreshToken, refreshed.mercureToken)
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

            // Push
            single<FcmTokenSource> { FirebaseTokenSource() }
            single { PushTokenRegistrar(get(), get()) }
            single { PushActionHandler(get()) }

            // Repositories
            single { EventRepository(get(), get()) }
            single { TaskRepository(get(), get()) }
            single { ChatPreferencesRepository(androidContext()) }
            single { ChatRepository(get(), get()) }
            single { ApprovalRepository(get(), get(), get()) }
            single { AgendaRepository(get(), get()) }
            single { RecipeRepository(get(), get()) }
            single { IngredientRepository(get()) }
            single { MealRepository(get()) }
            single { GroceryListRepository(get()) }
            single { ProductRepository(get()) }
            single { StoreRepository(get()) }
            single { AccountRepository(get()) }
            single { CategoryRepository(get()) }
            single { BudgetRepository(get()) }
            single { CategorizationRuleRepository(get()) }
            single { BankConnectionRepository(get()) }
            single { CushionRepository(get()) }
            single { LoanRepository(get()) }
            single { MonthlyReviewRepository(get()) }
            single { FinanceDashboardRepository(get()) }
            single { TransactionRepository(get()) }
            single { NotificationRepository(get()) }
            single { SearchRepository(get()) }
            single { ContextRepository(get()) }
            single { ProactionRepository(get()) }
            single { UserPreferenceRepository(get()) }

            // Other
            single {
                VoiceManager(
                    androidContext(),
                    get(),
                    get(),
                    audioRecorderFactory(androidContext()),
                    deviceSpeechFactory(androidContext()),
                )
            }

            // ViewModels
            viewModel { LoginViewModel(get()) }
            viewModel { DashboardViewModel(get(), get(), get(), get(), get()) }
            viewModel { FullCalendarViewModel(get(), get(), get(), get(), get(), get()) }
            viewModel { ChatViewModel(get(), get(), get(), get(), get()) }
            viewModel { ContextViewModel(get(), get(), get()) }
            viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get()) }
            viewModel { NotificationViewModel(get(), get(), get()) }
            viewModel { SearchViewModel(get()) }
            viewModel { ProactionViewModel(get()) }
            viewModel { RecipeListViewModel(get(), get(), get()) }
            viewModel { (recipeId: String) -> RecipeDetailViewModel(recipeId, get(), get(), get()) }
            viewModel { (recipeId: String) -> RecipeEditViewModel(recipeId, get(), get(), get()) }
            viewModel { MealsWeekViewModel(get()) }
            viewModel { GroceryViewModel(get(), get(), get(), get(), get()) }
            viewModel { ProductViewModel(get(), get()) }
            viewModel { StoreViewModel(get()) }
            viewModel { AccountViewModel(get()) }
            viewModel { CategoryViewModel(get()) }
            viewModel { BudgetViewModel(get(), get()) }
            viewModel { CategorizationRuleViewModel(get(), get()) }
            viewModel { RuleSuggestionViewModel(get(), get()) }
            viewModel { BankConnectionViewModel(get()) }
            viewModel { CushionViewModel(get()) }
            viewModel { LoanViewModel(get()) }
            viewModel { MonthlyReviewViewModel(get()) }
            viewModel { FinanceDashboardViewModel(get()) }
            viewModel { (accountId: String) -> TransactionViewModel(get(), get(), accountId, get(), get()) }
        }

        startKoin {
            androidContext(this@MaggieApp)
            modules(appModule)
        }

        get<VoiceManager>().initialize()
        get<BiometricLockManager>().initialize()

        PushChannels.create(this)
        // Sent at every start of a signed-in app, not only when Firebase rotates the token.
        val registrar = get<PushTokenRegistrar>()
        val authRepository = get<AuthRepository>()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            registrar.keepRegistered(authRepository.isAuthenticated)
        }
        removeListeningLeftovers()
    }

    // The listening service of earlier versions is gone with its class, so nothing
    // can start it again; what it left behind (notifications, channels, its
    // preference file) is cleared once here.
    private fun removeListeningLeftovers() {
        val notifications = getSystemService(NotificationManager::class.java)
        listOf(2001, 2002).forEach { notifications.cancel(it) }
        listOf("wake_word", "wake_word_reactivate").forEach { notifications.deleteNotificationChannel(it) }
        File(filesDir, "datastore/wake_word_prefs.preferences_pb").delete()
    }
}
