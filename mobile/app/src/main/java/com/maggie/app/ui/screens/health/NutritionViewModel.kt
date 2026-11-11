package com.maggie.app.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.FoodIntakeWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FastingStatus
import com.maggie.app.data.model.FoodIntake
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.repository.NutritionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class NutritionUiState(
    /** Today's balance with its planned meals; `null` until it answered. */
    val balance: DailyBalance? = null,
    /** The seven days ending today, oldest first, without planned meals. */
    val week: List<DailyBalance> = emptyList(),
    /** Today's journal, by eatenAt. */
    val intakes: List<FoodIntake> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not an empty bilan. */
    val hasLoaded: Boolean = false,
    /** The meals whose « Mangé » is on its way: their button waits, so a second tap sends nothing. */
    val eating: Set<String> = emptySet(),
    /** The « Ajouter » dialog is open — kept here so a rotation does not close it. */
    val addOpen: Boolean = false,
    val isSaving: Boolean = false,
    /** Today's fast (MAG-770); `null` until it answered, and its protocol null when the owner does not fast. */
    val fasting: FastingStatus? = null,
    /** The journal's days of the last [FASTING_HISTORY_DAYS], for their night fasts (the tablet's chart). */
    val fastingDays: List<HealthDay> = emptyList(),
    /** The « Jeûne » sheet is open — kept here so a rotation does not close it. */
    val fastingSheetOpen: Boolean = false,
    val fastingSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/**
 * « Santé › Nutrition » (MAG-748): today's balance, the last seven days, the macros and the journal — « Mangé » on a
 * planned meal, a food added by hand, an entry deleted. Every change reads the day again: the figures are the API's.
 * Under the bilan, the intermittent fast (MAG-770): read again with the day, and when the profile changes elsewhere.
 * The [clock] says today, in Paris as the API counts the days.
 */
class NutritionViewModel(
    private val repository: NutritionRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    val clock: Clock = Clock.system(ZoneId.of("Europe/Paris")),
) : ViewModel() {

    private val _uiState = MutableStateFlow(NutritionUiState(isLoading = true))
    val uiState: StateFlow<NutritionUiState> = _uiState

    val zone: ZoneId get() = clock.zone

    val today: LocalDate get() = LocalDate.now(clock)

    init {
        refresh()
        subscribeToMercure()
    }

    // A dish Maggie records, or one eaten from the admin, reaches the journal without a reload; a protocol changed
    // elsewhere (the admin, Maggie) redraws the fast.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            launch {
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.FOOD_INTAKES))
                    .catch { /* MercureService reconnects by itself */ }
                    .collect { load(showLoading = false) }
            }
            launch {
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.HEALTH_PROFILE))
                    .catch { /* MercureService reconnects by itself */ }
                    .collect { loadFasting() }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val day = today
        val (balance, intakes, week) = coroutineScope {
            val balance = async { repository.getDailyBalance(day.toString()) }
            val intakes = async { repository.getFoodIntakes(day.toString()) }
            val week = async { repository.getDailyBalances(day.minusDays(BALANCE_WEEK_DAYS - 1L).toString(), day.toString()) }
            // The fast in the same breath: coroutineScope waits for it, so the state read below already holds it.
            launch { loadFasting() }
            Triple(balance.await(), intakes.await(), week.await())
        }
        var state = _uiState.value.copy(isLoading = false)
        balance.onSuccess { state = state.copy(balance = it, hasLoaded = true) }
        intakes.onSuccess { state = state.copy(intakes = it) }
        week.onSuccess { state = state.copy(week = it) }
        if (balance.isFailure || intakes.isFailure) state = state.copy(error = MESSAGE_LOAD)
        _uiState.value = state
    }

    /** Today's fast and the last nights'; a failure keeps what was shown — the card is not the screen. */
    private suspend fun loadFasting() {
        val day = today
        val (fasting, days) = coroutineScope {
            val fasting = async { repository.getFasting(day.toString()) }
            val days = async { repository.getHealthDays(day.minusDays(FASTING_HISTORY_DAYS - 1L).toString(), day.toString()) }
            fasting.await() to days.await()
        }
        fasting.onSuccess { _uiState.value = _uiState.value.copy(fasting = it) }
        days.onSuccess { _uiState.value = _uiState.value.copy(fastingDays = it) }
    }

    fun openFastingSheet() {
        _uiState.value = _uiState.value.copy(fastingSheetOpen = true)
    }

    fun closeFastingSheet() {
        _uiState.value = _uiState.value.copy(fastingSheetOpen = false)
    }

    /** « Enregistrer »: the protocol and the window's start go to the profile; the sheet closes once the API took them. */
    fun saveFasting(protocol: String, windowStart: String) {
        if (_uiState.value.fastingSaving) return
        _uiState.value = _uiState.value.copy(fastingSaving = true, error = null)
        viewModelScope.launch {
            repository.updateFasting(protocol, windowStart)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(fastingSaving = false, fastingSheetOpen = false, message = MESSAGE_FASTING_SAVED)
                    loadFasting()
                }
                .onFailure { _uiState.value = _uiState.value.copy(fastingSaving = false, error = refusal(it, MESSAGE_FASTING_SAVE)) }
        }
    }

    /** « Mangé »: the meal leaves « Prévu » for the journal once the API recorded it; a refusal says why, in its words. */
    fun eat(mealId: String) {
        if (mealId in _uiState.value.eating) return
        _uiState.value = _uiState.value.copy(eating = _uiState.value.eating + mealId, error = null)
        viewModelScope.launch {
            repository.eatPlannedMeal(mealId)
                .onSuccess { _uiState.value = _uiState.value.copy(message = MESSAGE_EATEN) }
                .onFailure { _uiState.value = _uiState.value.copy(error = refusal(it, MESSAGE_EAT)) }
            // Either way the day is read again: a meal already eaten elsewhere leaves « Prévu » too.
            load(showLoading = false)
            _uiState.value = _uiState.value.copy(eating = _uiState.value.eating - mealId)
        }
    }

    /** The entry leaves the journal at once; it comes back if the API refuses. */
    fun delete(id: String) {
        val before = _uiState.value
        val removed = before.intakes.firstOrNull { it.id == id } ?: return
        _uiState.value = before.copy(intakes = before.intakes - removed, error = null)
        viewModelScope.launch {
            repository.deleteFoodIntake(id)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(message = MESSAGE_DELETED)
                    load(showLoading = false)
                }
                .onFailure {
                    val restored = (_uiState.value.intakes + removed).sortedBy { it.eatenAt }
                    _uiState.value = _uiState.value.copy(intakes = restored, error = refusal(it, MESSAGE_DELETE))
                }
        }
    }

    fun openAdd() {
        _uiState.value = _uiState.value.copy(addOpen = true)
    }

    fun closeAdd() {
        _uiState.value = _uiState.value.copy(addOpen = false)
    }

    /** A food typed by hand, eaten now; the dialog closes once the API recorded it. */
    fun add(label: String, kcal: Int, proteinG: Double?, carbsG: Double?, fatG: Double?) {
        if (_uiState.value.isSaving || label.isBlank()) return
        _uiState.value = _uiState.value.copy(isSaving = true, error = null)
        val request = FoodIntakeWriteRequest(
            eatenAt = isoWithOffset(OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)),
            label = label.trim(),
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
        )
        viewModelScope.launch {
            repository.createFoodIntake(request)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false, addOpen = false, message = MESSAGE_ADDED)
                    load(showLoading = false)
                }
                .onFailure { _uiState.value = _uiState.value.copy(isSaving = false, error = refusal(it, MESSAGE_ADD)) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** A 400 carries the API's reason; anything else is the screen's own sentence. */
    private fun refusal(failure: Throwable, fallback: String): String =
        (failure as? ApiRefusalException)?.takeIf { it.status == 400 }?.message?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        const val MESSAGE_LOAD = "Le bilan du jour n'a pas pu être chargé."
        const val MESSAGE_EAT = "Le repas n'a pas été noté."
        const val MESSAGE_EATEN = "Repas noté"
        const val MESSAGE_DELETE = "L'aliment n'a pas été supprimé."
        const val MESSAGE_DELETED = "Aliment supprimé"
        const val MESSAGE_ADD = "L'aliment n'a pas été ajouté."
        const val MESSAGE_ADDED = "Aliment ajouté"
        const val MESSAGE_FASTING_SAVE = "Le jeûne n'a pas été enregistré."
        const val MESSAGE_FASTING_SAVED = "Jeûne enregistré"
    }
}
