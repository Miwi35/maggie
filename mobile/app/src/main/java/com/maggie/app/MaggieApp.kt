package com.maggie.app

import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.Room
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.installApiTimeouts
import com.maggie.app.data.api.installJourneyHeader
import com.maggie.app.data.auth.AuthManager
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.auth.BiometricLockManager
import com.maggie.app.data.auth.signInStrategy
import com.maggie.app.data.fcm.FcmTokenSource
import com.maggie.app.data.fcm.FirebaseTokenSource
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushChannels
import com.maggie.app.data.car.CarMessages
import com.maggie.app.data.car.SharedCarConnection
import com.maggie.app.data.fcm.PushDelivery
import com.maggie.app.data.fcm.PushNotifier
import com.maggie.app.data.fcm.PushTokenRegistrar
import androidx.work.WorkManager
import com.maggie.app.data.location.ActivityTransitionHandler
import com.maggie.app.data.location.AndroidBatteryLevel
import com.maggie.app.data.location.AndroidCarConnection
import com.maggie.app.data.location.AndroidTripLauncher
import com.maggie.app.data.location.BatteryLevel
import com.maggie.app.data.location.CarConnectionSource
import com.maggie.app.data.location.GmsLocationUpdates
import com.maggie.app.data.location.LocationUpdates
import com.maggie.app.data.location.PassiveLocationRegistrar
import com.maggie.app.data.location.PassiveRegistrar
import com.maggie.app.data.location.TripLauncher
import com.maggie.app.data.location.TripTracker
import com.maggie.app.data.location.TripWatcher
import com.maggie.app.data.location.ActivityTransitionRegistrar
import com.maggie.app.data.location.AndroidLocationPermissions
import com.maggie.app.data.location.AndroidLocationScheduler
import com.maggie.app.data.location.DepartureTracking
import com.maggie.app.data.location.FusedPositionSource
import com.maggie.app.data.location.LocationPermissions
import com.maggie.app.data.location.LocationPreferences
import com.maggie.app.data.location.LocationReporter
import com.maggie.app.data.location.LocationScheduler
import com.maggie.app.data.location.ParkingDetector
import com.maggie.app.data.location.PositionSource
import com.maggie.app.data.location.TransitionRegistrar
import com.maggie.app.data.interruption.AndroidInterruptionAlert
import com.maggie.app.data.interruption.InterruptionAlert
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.interruption.InterruptionFeed
import com.maggie.app.data.interruption.InterruptionPreferences
import com.maggie.app.data.reward.AndroidRewardFeedback
import com.maggie.app.data.reward.RewardFeedback
import com.maggie.app.data.reward.TaskRewards
import com.maggie.app.data.local.MaggieDatabase
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.CalendarCacheFeed
import com.maggie.app.data.repository.ChatUnreadRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.GroceryCardRepository
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.NotificationRepository
import com.maggie.app.data.repository.CuisineCardRepository
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.BankConnectionRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.CushionRepository
import com.maggie.app.data.repository.TreasuryRepository
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.data.repository.PlacementRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.data.repository.BodyMeasurementRepository
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.data.repository.ScenarioRepository
import com.maggie.app.data.repository.FinanceCardRepository
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.UpcomingRepository
import com.maggie.app.data.repository.MonthBalanceRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.ContextRepository
import com.maggie.app.data.repository.ProactionRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.WeatherRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.CookingTimerRepository
import com.maggie.app.data.repository.SearchRepository
import com.maggie.app.data.repository.ErrandRepository
import com.maggie.app.data.repository.RecurringGroceryRepository
import com.maggie.app.data.repository.StoreRepository
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TasksCardRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.ui.screens.shared.SubtasksViewModel
import com.maggie.app.data.repository.ThemeChoiceRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.cookbook.grocery.PastErrandsViewModel
import com.maggie.app.ui.screens.cookbook.grocery.RecurringGroceryViewModel
import com.maggie.app.ui.screens.finance.AccountViewModel
import com.maggie.app.ui.screens.finance.BudgetViewModel
import com.maggie.app.ui.screens.finance.BankConnectionViewModel
import com.maggie.app.ui.screens.finance.CategorizationRuleViewModel
import com.maggie.app.ui.screens.finance.RecurringOperationViewModel
import com.maggie.app.ui.screens.finance.RuleSuggestionViewModel
import com.maggie.app.ui.screens.finance.EnvelopeAdjustmentsViewModel
import com.maggie.app.ui.screens.finance.ToQualifyViewModel
import com.maggie.app.ui.screens.finance.ReviewListViewModel
import com.maggie.app.ui.screens.finance.ContactViewModel
import com.maggie.app.ui.screens.finance.CushionViewModel
import com.maggie.app.ui.screens.finance.PeerDebtsViewModel
import com.maggie.app.ui.screens.finance.SavingsAdvancesViewModel
import com.maggie.app.ui.screens.finance.LoanDetailViewModel
import com.maggie.app.ui.screens.finance.LoanViewModel
import com.maggie.app.ui.screens.finance.PlacementViewModel
import com.maggie.app.ui.screens.health.SportViewModel
import com.maggie.app.ui.screens.health.SuiviViewModel
import com.maggie.app.ui.screens.finance.FinanceTimelineViewModel
import com.maggie.app.ui.screens.finance.ProjectViewModel
import com.maggie.app.ui.screens.finance.ScenarioViewModel
import com.maggie.app.ui.screens.finance.FinanceDashboardViewModel
import com.maggie.app.ui.screens.finance.UpcomingViewModel
import com.maggie.app.ui.screens.finance.MonthBalanceViewModel
import com.maggie.app.ui.screens.finance.FinanceSummaryViewModel
import com.maggie.app.ui.screens.finance.TreasuryViewModel
import com.maggie.app.ui.screens.finance.PeriodReviewViewModel
import com.maggie.app.ui.screens.cookbook.cooking.CookingGroceryViewModel
import com.maggie.app.ui.screens.cookbook.cooking.CookingResumeViewModel
import com.maggie.app.ui.screens.cookbook.cooking.CookingViewModel
import com.maggie.app.ui.screens.finance.ReviewActivityViewModel
import com.maggie.app.ui.screens.finance.CategoryViewModel
import com.maggie.app.ui.screens.finance.TransactionViewModel
import com.maggie.app.ui.screens.grocery.ProductViewModel
import com.maggie.app.ui.screens.grocery.StoreAislesViewModel
import com.maggie.app.ui.screens.grocery.StoreViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealCreateViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealDetailViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealIngredientChoiceViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeCreateViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeEditViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.screens.home.FinanceHomeViewModel
import com.maggie.app.ui.components.DrawerSummaryViewModel
import com.maggie.app.ui.screens.home.GroceryHomeViewModel
import com.maggie.app.ui.screens.home.AgendaHomeViewModel
import com.maggie.app.ui.screens.home.HomeViewModel
import com.maggie.app.ui.screens.home.TasksCardViewModel
import com.maggie.app.ui.screens.digest.DigestViewModel
import com.maggie.app.ui.screens.home.CuisineHomeViewModel
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.voice.MediaVoiceMessagePlayer
import com.maggie.app.voice.VoicePlayback
import com.maggie.app.ui.screens.contexts.ContextViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.ui.screens.login.LoginViewModel
import com.maggie.app.ui.interruption.InterruptionViewModel
import com.maggie.app.ui.interruption.InterruptionsListViewModel
import com.maggie.app.ui.screens.proactions.ProactionViewModel
import com.maggie.app.ui.screens.search.SearchViewModel
import com.maggie.app.ui.screens.tasks.BravoViewModel
import com.maggie.app.ui.screens.tasks.RoutinesViewModel
import com.maggie.app.ui.screens.tasks.FocusViewModel
import com.maggie.app.ui.screens.tasks.TasksViewModel
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
                    // `X-E2E-Journey` on every request of the e2e flavor; nothing in dev
                    // and prod (src/e2e/ and src/google/ data/api/JourneyHeader.kt).
                    installJourneyHeader()
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
            // One player for every voice bubble of the chat (MAG-686): a second message stops the first.
            single {
                val api = get<MaggieApiService>()
                val context = androidContext()
                VoicePlayback(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                    newPlayer = { MediaVoiceMessagePlayer(context) },
                    resolveUrl = { api.getMessageAudio(it).url },
                )
            }

            // Push
            single<FcmTokenSource> { FirebaseTokenSource() }
            single { PushTokenRegistrar(get(), get()) }
            single { PushActionHandler(get()) }

            // Partir à l'heure (MAG-651): the position every 30 minutes and the car's place
            single { LocationPreferences(androidContext()) }
            single<LocationPermissions> { AndroidLocationPermissions(androidContext()) }
            single<PositionSource> { FusedPositionSource(androidContext()) }
            single<TransitionRegistrar> { ActivityTransitionRegistrar(androidContext()) }
            single<PassiveRegistrar> { PassiveLocationRegistrar(androidContext()) }
            single<LocationScheduler> { AndroidLocationScheduler(WorkManager.getInstance(androidContext()), get(), get()) }
            single { LocationReporter(get(), get(), get(), get()) }
            single { ParkingDetector(get()) }
            single<LocationUpdates> { GmsLocationUpdates(androidContext()) }
            single { SharedCarConnection(AndroidCarConnection(androidContext()), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
            single<CarConnectionSource> { get<SharedCarConnection>() }
            single<BatteryLevel> { AndroidBatteryLevel(androidContext()) }
            single { TripTracker(get(), get(), get(), get(), get(), get()) }
            single<TripLauncher> { AndroidTripLauncher(androidContext(), get(), get()) }
            single { TripWatcher(get(), get()) }
            single { ActivityTransitionHandler(get(), get(), get(), get(), get()) }
            single { DepartureTracking(get(), get(), get()) }

            // Interruption (MAG-314): Maggie speaks inside the app while it is open
            single {
                val notifier = { PushNotifier(androidContext()) }
                val connection = get<SharedCarConnection>()
                CarMessages(
                    isConnected = { connection.isConnected },
                    post = { payload, nudge -> notifier().show(payload, inCar = true, nudge = nudge) },
                    isShowing = { tag -> notifier().isShowing(tag) },
                    scheduleNudge = { payload, delayMs -> notifier().scheduleNudge(payload, delayMs) },
                    cancelNudge = { payload -> notifier().cancelNudge(payload) },
                )
            }
            single { InterruptionCenter(car = get()) }
            single { InterruptionPreferences(androidContext()) }
            single<InterruptionAlert> { AndroidInterruptionAlert(androidContext(), get()) }
            single<RewardFeedback> { AndroidRewardFeedback(androidContext()) }
            single { TaskRewards(get(), get()) }
            single { PushDelivery(get(), { PushNotifier(androidContext()) }, get()) }
            single { InterruptionFeed(get(), get(), get(), get(), clearSystem = { PushNotifier(androidContext()).close(it) }) }

            // Repositories
            single { EventRepository(get(), get()) }
            single { TaskRepository(get(), get()) }
            single { RoutineRepository(get()) }
            single { ChatUnreadRepository(get(), get(), get()) }
            single { ChatRepository(get(), get()) }
            single { ApprovalRepository(get(), get(), get()) }
            single { AgendaRepository(get(), get()) }
            single { CalendarCacheFeed(get(), get(), get(), get()) }
            single { RecipeRepository(get(), get()) }
            single { CookingTimerRepository(get()) }
            single { ActivityStatusRepository(get()) }
            single { IngredientRepository(get()) }
            single { MealRepository(get()) }
            single { GroceryListRepository(get()) }
            single { ProductRepository(get()) }
            single { StoreRepository(get()) }
            single { RecurringGroceryRepository(get()) }
            single { ErrandRepository(get()) }
            single { AccountRepository(get()) }
            single { CategoryRepository(get()) }
            single { BudgetRepository(get()) }
            single { CategorizationRuleRepository(get()) }
            single { RecurringOperationRepository(get()) }
            single { BankConnectionRepository(get()) }
            single { CushionRepository(get()) }
            single { TreasuryRepository(get()) }
            single { SavingsAdvancesRepository(get()) }
            single { LoanRepository(get()) }
            single { ProjectRepository(get()) }
            single { ScenarioRepository(get()) }
            single { PlacementRepository(get()) }
            single { WorkoutRepository(get()) }
            single { BodyMeasurementRepository(get()) }
            single { ReviewRepository(get()) }
            single { FinanceDashboardRepository(get()) }
            single { UpcomingRepository(get()) }
            single { TasksCardRepository(get(), get(), get(), get()) }
            single { AgendaCardRepository(get(), get(), get()) }
            single { GroceryCardRepository(get(), get(), get()) }
            single { CuisineCardRepository(get(), get(), get()) }
            single { FinanceCardRepository(get(), get(), get()) }
            single { MonthBalanceRepository(get()) }
            single { TransactionRepository(get()) }
            single { ContactRepository(get()) }
            single { PeerDebtRepository(get()) }
            single { NotificationRepository(get()) }
            single { SearchRepository(get()) }
            single { ContextRepository(get()) }
            single { ProactionRepository(get()) }
            single { TripRepository(get()) }
            single { WeatherRepository(get()) }
            single { UserPreferenceRepository(get()) }
            single { ThemeChoiceRepository(androidContext()) }

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
            viewModel { HomeViewModel() }
            viewModel { DrawerSummaryViewModel(get(), get(), get(), get(), get()) }
            viewModel { GroceryHomeViewModel(get()) }
            viewModel { TasksCardViewModel(get(), get(), get()) }
            viewModel { CuisineHomeViewModel(get()) }
            viewModel { FinanceHomeViewModel(get()) }
            viewModel { AgendaHomeViewModel(get()) }
            viewModel { params -> DigestViewModel(params.getOrNull<String>(), get(), get(), get(), get(), get(), get(), get()) }
            viewModel { SubtasksViewModel(get()) }
            viewModel { TasksViewModel(get(), get(), get(), get(), get()) }
            viewModel { FocusViewModel(get()) }
            viewModel { BravoViewModel(get(), get()) }
            viewModel { RoutinesViewModel(get(), get(), get(), get(), get()) }
            viewModel { params -> FullCalendarViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), params.getOrNull<String>()) }
            viewModel {
                val car = get<SharedCarConnection>()
                ChatViewModel(get(), get(), get(), get(), get(), get(), inCar = car::isConnected)
            }
            viewModel { ContextViewModel(get(), get(), get()) }
            viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
            viewModel {
                InterruptionsListViewModel(
                    approvals = get(),
                    notifications = get(),
                    trips = get(),
                    center = get(),
                    mercureService = get(),
                    authRepository = get(),
                    closeSystem = { PushNotifier(androidContext()).close(it) },
                )
            }
            viewModel {
                InterruptionViewModel(
                    get(),
                    get(),
                    postpone = { PushNotifier(androidContext()).postpone(it) },
                    dismiss = { PushNotifier(androidContext()).close(it.systemTag) },
                )
            }
            viewModel { SearchViewModel(get()) }
            viewModel { ProactionViewModel(get(), get(), get()) }
            viewModel { RecipeListViewModel(get(), get(), get(), get()) }
            viewModel { (recipeId: String) -> RecipeDetailViewModel(recipeId, get(), get(), get(), get()) }
            viewModel { (recipeId: String) -> RecipeEditViewModel(recipeId, get(), get(), get()) }
            viewModel { RecipeCreateViewModel(get()) }
            viewModel { MealsWeekViewModel(get(), get(), get(), get(), get()) }
            viewModel { MealCreateViewModel(get()) }
            viewModel { MealDetailViewModel(get()) }
            viewModel { (mealId: String) -> MealIngredientChoiceViewModel(mealId, get()) }
            viewModel { GroceryViewModel(get(), get(), get(), get(), get()) }
            viewModel { ProductViewModel(get(), get()) }
            viewModel { RecurringGroceryViewModel(get(), get(), get()) }
            viewModel { PastErrandsViewModel(get(), get(), get()) }
            viewModel { StoreViewModel(get()) }
            viewModel { (storeId: String) -> StoreAislesViewModel(storeId, get(), get(), get()) }
            viewModel { AccountViewModel(get(), get(), get(), get(), get()) }
            viewModel { CategoryViewModel(get(), get(), get()) }
            viewModel { BudgetViewModel(get(), get(), get(), get(), get()) }
            viewModel { EnvelopeAdjustmentsViewModel(get()) }
            viewModel { CategorizationRuleViewModel(get(), get()) }
            viewModel { RuleSuggestionViewModel(get(), get()) }
            viewModel { ReviewListViewModel(get(), get(), get(), get(), get(), get()) }
            viewModel { ToQualifyViewModel(get(), get(), get()) }
            viewModel { ContactViewModel(get(), get()) }
            viewModel { RecurringOperationViewModel(get(), get(), get(), get(), get()) }
            viewModel { BankConnectionViewModel(get()) }
            viewModel { CushionViewModel(get()) }
            viewModel { SavingsAdvancesViewModel(get(), get()) }
            viewModel { PeerDebtsViewModel(get()) }
            viewModel { LoanViewModel(get()) }
            viewModel { LoanDetailViewModel(get()) }
            viewModel { ProjectViewModel(get(), get(), get()) }
            viewModel { ScenarioViewModel(get(), get(), get()) }
            viewModel { FinanceTimelineViewModel(get()) }
            viewModel { PlacementViewModel(get(), get(), get()) }
            viewModel { SportViewModel(get(), get(), get()) }
            viewModel { SuiviViewModel(get(), get(), get()) }
            viewModel { CookingViewModel(get(), get(), get(), get(), get(), get(), get()) }
            viewModel { CookingGroceryViewModel(get(), get(), get(), get()) }
            viewModel { CookingResumeViewModel(get(), get(), get(), get()) }
            viewModel { ReviewActivityViewModel(get(), get(), get(), get(), get(), get(), get()) }
            viewModel { PeriodReviewViewModel(get()) }
            viewModel { FinanceDashboardViewModel(get(), get(), get(), get(), get()) }
            viewModel { UpcomingViewModel(get(), get(), get()) }
            viewModel { MonthBalanceViewModel(get(), get(), get()) }
            viewModel { TreasuryViewModel(get(), get(), get()) }
            viewModel { FinanceSummaryViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
            viewModel { (accountId: String) -> TransactionViewModel(get(), get(), accountId, get(), get(), get(), get(), get(), get()) }
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
        // The transitions do not survive a reboot: registered again at every start while the switch is on;
        // signed out, everything stops.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { get<DepartureTracking>().follow(authRepository.isAuthenticated) }
        // A trip under way when the app starts, or a car that connects while it lives, brings the GPS service up.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { get<TripWatcher>().watch() }
        // The car going away brings back, on the phone, what Maggie said there and nobody answered (MAG-683).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val car = get<CarMessages>()
            val delivery = get<PushDelivery>()
            get<SharedCarConnection>().connected.collect { connected -> if (!connected) delivery.backOnPhone(car.left()) }
        }
        keepInterruptionsFed(get<InterruptionFeed>(), get<InterruptionCenter>(), authRepository)
        keepCalendarCacheFed(get<CalendarCacheFeed>(), authRepository)
        removeListeningLeftovers()
    }

    // The feeds only run while the app is open: in the background the push is what speaks, and the
    // held actions still waiting are picked up again when the app opens.
    private fun keepInterruptionsFed(feed: InterruptionFeed, center: InterruptionCenter, auth: AuthRepository) {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            // Whatever was said to one account is not said to the next.
            auth.isAuthenticated.collect { signedIn -> if (!signedIn) center.clear() }
        }
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feed.runWhileSignedIn(auth.isAuthenticated)
            }
        }
    }

    // Same rule as the interruptions: while the app is open the pushes keep the cached calendar current;
    // a period read before the app went to the background is read again once its hour is up.
    private fun keepCalendarCacheFed(feed: CalendarCacheFeed, auth: AuthRepository) {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feed.runWhileSignedIn(auth.isAuthenticated)
            }
        }
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
