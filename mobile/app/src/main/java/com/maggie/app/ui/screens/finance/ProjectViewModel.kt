package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.ProjectOptionsParams
import com.maggie.app.data.api.ProjectPreviewRequest
import com.maggie.app.data.api.ProjectWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.FundingKind
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectDateMode
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.ProjectPreview
import com.maggie.app.data.model.ProjectWellbeing
import com.maggie.app.data.model.projectIri
import com.maggie.app.data.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

enum class ProjectField { NAME, AMOUNT, TARGET_ON }

/** What the full-screen form holds while it is filled: text as typed, the date as picked. */
data class ProjectDraft(
    val name: String = "",
    val amount: String = "",
    val wellbeing: ProjectWellbeing = ProjectWellbeing.MEDIUM,
    val dateMode: ProjectDateMode = ProjectDateMode.NONE,
    /** Kept when the mode goes back to « Sans date », so a second change of mind costs nothing; sent only with a date mode. */
    val targetOn: LocalDate? = null,
    /** Plain ids of the projects this one waits for. */
    val dependsOn: List<String> = emptyList(),
    val note: String = "",
) {
    private val cents: Int? get() = parseCents(amount)?.takeIf { it > 0 }

    /** The API's rules, checked before the request: a name, an amount above zero, a date for a dated mode. */
    fun errors(): Map<ProjectField, String> = buildMap {
        if (name.isBlank()) put(ProjectField.NAME, "Donnez un nom au projet.")
        if (cents == null) put(ProjectField.AMOUNT, "Indiquez un montant supérieur à zéro.")
        if (dateMode.needsDate && targetOn == null) {
            put(
                ProjectField.TARGET_ON,
                if (dateMode == ProjectDateMode.FIXED) "Choisissez la date du projet." else "Choisissez la date limite.",
            )
        }
    }

    fun toRequest(): ProjectWriteRequest? {
        if (errors().isNotEmpty()) return null
        return ProjectWriteRequest(
            name = name.trim(),
            amountCents = cents ?: return null,
            wellbeing = wellbeing.key,
            dateMode = dateMode.key,
            targetOn = if (dateMode.needsDate) targetOn?.toString() else null,
            dependsOn = dependsOn.map(::projectIri),
            note = note.trim().ifEmpty { null },
        )
    }

    /** The same project as [toRequest], for the preview: nothing is created, dependencies are plain ids. */
    fun toPreviewRequest(): ProjectPreviewRequest? {
        val request = toRequest() ?: return null
        return ProjectPreviewRequest(
            name = request.name,
            amountCents = request.amountCents,
            wellbeing = request.wellbeing,
            dateMode = request.dateMode,
            targetOn = request.targetOn,
            dependsOn = dependsOn,
            note = request.note,
            scenarioId = null,
        )
    }

    companion object {
        fun of(project: Project): ProjectDraft = ProjectDraft(
            name = project.name,
            amount = String.format(Locale.FRANCE, "%.2f", project.amountCents / 100.0),
            wellbeing = ProjectWellbeing.of(project.wellbeing),
            dateMode = ProjectDateMode.of(project.dateMode),
            targetOn = project.targetOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            dependsOn = project.dependencyIds,
            note = project.note.orEmpty(),
        )
    }
}

/** The form on screen: a new project when [projectId] is null. [done] tells the screen to close. */
data class ProjectForm(
    val projectId: String? = null,
    val draft: ProjectDraft = ProjectDraft(),
    /** Set by the first save: an empty field is not an error while the form is still being filled. */
    val showErrors: Boolean = false,
    val isSaving: Boolean = false,
    /** What adding the project would change (MAG-553), on the tablet; null until asked, and again once cancelled or edited. */
    val preview: ProjectPreview? = null,
    val isPreviewing: Boolean = false,
    /** What the server refused, said in French (a cycle, a dependency on itself). */
    val error: String? = null,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = projectId == null
    val errors: Map<ProjectField, String> get() = if (showErrors) draft.errors() else emptyMap()
}

data class ProjectUiState(
    val projects: List<Project> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not the empty state. */
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val form: ProjectForm? = null,
    /** The Gantt's data (MAG-527); null until the first answer, and kept when a later read fails. */
    val planning: ProjectPlanning? = null,
    val isPlanningLoading: Boolean = true,
    /** The planning could not be read and there is none to keep: the Gantt says so and offers a retry. */
    val planningError: Boolean = false,
    /** The project whose sheet is open (MAG-525): its options and, on the tablet, its sources. */
    val sheet: ProjectSheet? = null,
    /** The accounts a source may draw on; read when the tablet edits the sources. */
    val accounts: List<Account> = emptyList(),
    /** The placements a capital source may leave (MAG-556); read with the accounts. */
    val placements: List<Placement> = emptyList(),
)

/**
 * « Projets » of Planification (MAG-519): the projects in priority order, reordered by dragging,
 * and the full-screen form that creates, edits and deletes one. One ViewModel for the list and the
 * form, which is only what is in front of the list — the navigation scopes it to the list's entry.
 * The sheet of a project (MAG-525) is the same: its three priced options and its funding sources.
 */
class ProjectViewModel(
    private val repository: ProjectRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectUiState(isLoading = true))
    val uiState: StateFlow<ProjectUiState> = _uiState

    /** The order before the finger moved anything; null while no drag is under way. */
    private var orderBeforeDrag: List<Project>? = null
    private var reorderInFlight = false

    /** Numbers the planning reads, so an answer that lands after a newer read was asked for is dropped. */
    private var planningRead = 0

    init {
        refresh()
        subscribeToMercure()
    }

    // A project written elsewhere (the admin, Maggie) reaches the list; never under a finger or a
    // reorder on its way, whose own answer is the newer list.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.FINANCE_PROJECTS))
                .catch { /* MercureService reconnects by itself */ }
                .coalesced()
                .collect {
                    if (orderBeforeDrag == null && !reorderInFlight) {
                        load(showLoading = false)
                        // The sources behind the sheet moved: so did the options priced from them.
                        if (_uiState.value.sheet != null) requestOptions()
                    }
                }
        }
        // The planning also moves with a loan, the cushion or an account; the list itself does not.
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            merge(
                *listOf(MercureTopics.LOANS, MercureTopics.SAFETY_CUSHIONS, MercureTopics.ACCOUNTS, MercureTopics.FINANCE_SCENARIOS).map { topic ->
                    mercureService.subscribe(MercureTopics.userScoped(userId, topic)).catch { /* MercureService reconnects by itself */ }
                }.toTypedArray(),
            )
                .coalesced()
                .collect { if (orderBeforeDrag == null && !reorderInFlight) loadPlanning() }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    /** « Réessayer » of the Gantt: only the planning is read again. */
    fun refreshPlanning() {
        _uiState.value = _uiState.value.copy(isPlanningLoading = true, planningError = false)
        viewModelScope.launch { loadPlanning() }
    }

    // A failed read keeps the Gantt already on screen: the order it shows is still the owner's.
    private suspend fun loadPlanning() {
        val mine = ++planningRead
        repository.getProjectPlanning()
            .onSuccess { planning ->
                if (mine != planningRead) return@onSuccess
                _uiState.value = _uiState.value.copy(planning = planning, isPlanningLoading = false, planningError = false)
            }
            .onFailure {
                if (mine != planningRead) return@onFailure
                _uiState.value = _uiState.value.copy(isPlanningLoading = false, planningError = _uiState.value.planning == null)
            }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        repository.getProjects()
            .onSuccess { projects ->
                // An answer that lands under a finger would undo the drag; the drag's own reorder follows.
                _uiState.value = if (orderBeforeDrag != null) {
                    _uiState.value.copy(isLoading = false, hasLoaded = true)
                } else {
                    _uiState.value.copy(projects = projects, isLoading = false, hasLoaded = true, sheet = refreshed(_uiState.value.sheet, projects))
                }
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD)
            }
        // Whatever moved the list (a save, a delete, a push) moved the planning with it.
        loadPlanning()
    }

    // The sheet follows its project: new sources replace an untouched draft, a deleted project closes it.
    private fun refreshed(sheet: ProjectSheet?, projects: List<Project>): ProjectSheet? {
        sheet ?: return null
        val project = projects.firstOrNull { it.id == sheet.projectId } ?: return null
        return if (sheet.dirty) sheet else sheet.copy(sources = FundingDraft.draftsOf(project.fundingSources))
    }

    fun openSheet(id: String) {
        val project = _uiState.value.projects.firstOrNull { it.id == id }
        if (project == null) {
            _uiState.value = _uiState.value.copy(sheet = null, error = MESSAGE_NOT_FOUND)
            return
        }
        _uiState.value = _uiState.value.copy(
            sheet = ProjectSheet(projectId = id, sources = FundingDraft.draftsOf(project.fundingSources)),
            error = null,
        )
        requestOptions()
    }

    fun closeSheet() {
        _uiState.value = _uiState.value.copy(sheet = null)
    }

    private fun updateSheet(transform: (ProjectSheet) -> ProjectSheet) {
        val sheet = _uiState.value.sheet ?: return
        _uiState.value = _uiState.value.copy(sheet = transform(sheet))
    }

    /** Prices the options with what the sheet holds; without a loan source, a rate, a duration and a down payment are needed first. */
    private fun requestOptions() {
        val sheet = _uiState.value.sheet ?: return
        val project = _uiState.value.projects.firstOrNull { it.id == sheet.projectId }
        val params = sheet.optionsDraft.toParams()
        when {
            params == null -> updateSheet { it.copy(options = null, optionsError = MESSAGE_OPTIONS_INVALID) }
            project != null && project.loanSource == null &&
                (params.loanRateBasisPoints == null || params.loanMonths == null || params.downPaymentCents == null) ->
                updateSheet { it.copy(options = null, isLoadingOptions = false, optionsError = MESSAGE_OPTIONS_TERMS) }
            else -> loadOptions(sheet.projectId, params)
        }
    }

    fun loadOptions(projectId: String, params: ProjectOptionsParams) {
        val sheet = _uiState.value.sheet?.takeIf { it.projectId == projectId } ?: ProjectSheet(projectId)
        _uiState.value = _uiState.value.copy(sheet = sheet.copy(isLoadingOptions = true, optionsError = null))
        viewModelScope.launch {
            repository.getOptions(projectId, params)
                .onSuccess { options ->
                    updateSheetOf(projectId) { it.copy(options = options, isLoadingOptions = false, optionsError = null) }
                }
                .onFailure { failure ->
                    updateSheetOf(projectId) {
                        it.copy(options = null, isLoadingOptions = false, optionsError = optionsRefusal(failure))
                    }
                }
        }
    }

    // An answer for a sheet that was closed or replaced meanwhile is not for the one on screen.
    private fun updateSheetOf(projectId: String, transform: (ProjectSheet) -> ProjectSheet) {
        if (_uiState.value.sheet?.projectId == projectId) updateSheet(transform)
    }

    /** The tablet types a parameter of the options; nothing is priced until [applyOptions]. */
    fun editOptions(transform: (ProjectOptionsDraft) -> ProjectOptionsDraft) {
        updateSheet { it.copy(optionsDraft = transform(it.optionsDraft)) }
    }

    fun applyOptions() = requestOptions()

    fun loadAccounts() {
        if (_uiState.value.accounts.isNotEmpty()) return
        viewModelScope.launch {
            repository.getAccounts().onSuccess { _uiState.value = _uiState.value.copy(accounts = it) }
        }
    }

    fun loadPlacements() {
        if (_uiState.value.placements.isNotEmpty()) return
        viewModelScope.launch {
            repository.getPlacements().onSuccess { _uiState.value = _uiState.value.copy(placements = it) }
        }
    }

    fun addSource(kind: FundingKind) {
        updateSheet {
            val added = FundingSourceDraft(kind = kind, loanTerms = if (kind == FundingKind.LOAN) LoanTermsDraft() else null)
            it.copy(sources = it.sources + added, dirty = true, fundingError = null, fundingSaved = false)
        }
    }

    fun removeSource(index: Int) {
        updateSheet {
            it.copy(sources = it.sources.filterIndexed { i, _ -> i != index }, dirty = true, fundingError = null, fundingSaved = false)
        }
    }

    fun editSource(index: Int, transform: (FundingSourceDraft) -> FundingSourceDraft) {
        updateSheet {
            it.copy(
                sources = it.sources.mapIndexed { i, source -> if (i == index) transform(source) else source },
                dirty = true,
                fundingError = null,
                fundingSaved = false,
            )
        }
    }

    /** Opens the conditions of a loan source: those of its loan when it has one, blank for a new one. */
    fun editLoanTerms(index: Int) {
        val source = _uiState.value.sheet?.sources?.getOrNull(index) ?: return
        if (source.loanTerms != null) return
        val loanId = source.loanId
        if (loanId == null) {
            editSource(index) { it.copy(loanTerms = LoanTermsDraft()) }
            return
        }
        viewModelScope.launch {
            repository.getLoan(loanId)
                .onSuccess { loan -> editSource(index) { it.copy(loanTerms = LoanTermsDraft.of(loan)) } }
                .onFailure { updateSheet { it.copy(fundingError = MESSAGE_LOAN_TERMS) } }
        }
    }

    /**
     * Replaces the sources of [projectId] by [sources]. The API's answer is the project: it replaces the one
     * in the list. A refusal leaves the project's sources as they were and says why, as the API said it.
     */
    fun saveFunding(projectId: String, sources: List<FundingSourceDraft>) {
        val project = _uiState.value.projects.firstOrNull { it.id == projectId } ?: return
        if (_uiState.value.sheet?.isSavingFunding == true) return
        FundingDraft.error(FundingDraft.editableAmountCents(project), sources)?.let { message ->
            updateSheetOf(projectId) { it.copy(fundingError = message) }
            return
        }
        val requests = sources.map { it.toRequest() ?: return }

        updateSheetOf(projectId) { it.copy(isSavingFunding = true, fundingError = null, fundingSaved = false) }
        viewModelScope.launch {
            repository.setFunding(projectId, requests)
                .onSuccess { saved ->
                    _uiState.value = _uiState.value.copy(
                        projects = _uiState.value.projects.map { if (it.id == projectId) saved else it },
                    )
                    updateSheetOf(projectId) {
                        it.copy(
                            sources = FundingDraft.draftsOf(saved.fundingSources),
                            dirty = false,
                            isSavingFunding = false,
                            fundingSaved = true,
                        )
                    }
                    requestOptions()
                }
                .onFailure { failure ->
                    updateSheetOf(projectId) {
                        it.copy(isSavingFunding = false, fundingError = (failure as? ApiRefusalException)?.message ?: MESSAGE_FUNDING)
                    }
                }
        }
    }

    fun openNew() {
        _uiState.value = _uiState.value.copy(form = ProjectForm(), error = null)
    }

    fun openEdit(id: String) {
        val project = _uiState.value.projects.firstOrNull { it.id == id }
        if (project == null) {
            _uiState.value = _uiState.value.copy(form = null, error = MESSAGE_NOT_FOUND)
            return
        }
        _uiState.value = _uiState.value.copy(form = ProjectForm(projectId = id, draft = ProjectDraft.of(project)), error = null)
    }

    fun editDraft(transform: (ProjectDraft) -> ProjectDraft) {
        val form = _uiState.value.form ?: return
        _uiState.value = _uiState.value.copy(form = form.copy(draft = transform(form.draft), error = null, preview = null))
    }

    fun closeForm() {
        _uiState.value = _uiState.value.copy(form = null)
    }

    fun save() {
        val form = _uiState.value.form ?: return
        if (form.isSaving) return
        val request = form.draft.toRequest()
        if (request == null) {
            _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true))
            return
        }

        _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true, isSaving = true, error = null))
        viewModelScope.launch {
            val id = form.projectId
            val result = if (id == null) repository.createProject(request) else repository.updateProject(id, request)
            result
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        form = _uiState.value.form?.copy(isSaving = false, done = true),
                        message = if (id == null) "Projet créé" else "Projet enregistré",
                    )
                    // A save moves positions (a new project goes last) and the unmet dependencies of others.
                    load(showLoading = false)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        form = _uiState.value.form?.copy(
                            isSaving = false,
                            error = refusal(failure, MESSAGE_SAVE),
                        ),
                    )
                }
        }
    }

    /**
     * The tablet's « Créer » (MAG-553): asks what adding the project would change in the current scenario,
     * without creating anything. The answer opens « Ajouter au scénario courant ? »; [confirmPreview] adds,
     * [dismissPreview] goes back to the form.
     */
    fun previewNew() {
        val form = _uiState.value.form ?: return
        if (!form.isNew || form.isSaving || form.isPreviewing) return
        val request = form.draft.toPreviewRequest()
        if (request == null) {
            _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true))
            return
        }

        _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true, isPreviewing = true, preview = null, error = null))
        viewModelScope.launch {
            repository.previewProject(request)
                .onSuccess { preview ->
                    updateForm { it.copy(isPreviewing = false, preview = preview) }
                }
                .onFailure { failure ->
                    updateForm { it.copy(isPreviewing = false, error = previewRefusal(failure)) }
                }
        }
    }

    /** « Annuler »: the dialog closes, nothing was created, the form keeps what was typed. */
    fun dismissPreview() {
        updateForm { it.copy(preview = null) }
    }

    /**
     * « Ajouter »: the normal creation, then the project joins the scenario it was previewed in. A scenario that
     * does not take it leaves the project created and says so: the project is never lost.
     */
    fun confirmPreview() {
        val form = _uiState.value.form ?: return
        val preview = form.preview ?: return
        if (form.isSaving) return
        val request = form.draft.toRequest() ?: return

        _uiState.value = _uiState.value.copy(form = form.copy(isSaving = true, preview = null, error = null))
        viewModelScope.launch {
            repository.createProject(request)
                .onSuccess { created ->
                    val scenario = preview.scenario?.takeIf { it.editable }
                    val joined = scenario?.let { repository.addToScenario(it.id, created.id) }
                    val scenarioName = scenario?.name.orEmpty()
                    val outside = preview.scenario?.takeIf { !it.editable }
                    _uiState.value = _uiState.value.copy(
                        form = _uiState.value.form?.copy(isSaving = false, done = true),
                        message = when {
                            outside != null -> "Projet créé, hors du scénario « ${outside.name} » (proposition de Maggie)"
                            joined == null -> "Projet créé"
                            joined.isSuccess -> "Projet ajouté au scénario « $scenarioName »"
                            else -> "« ${created.name} » est créé, mais n'a pas rejoint le scénario « $scenarioName »."
                        },
                    )
                    load(showLoading = false)
                }
                .onFailure { failure ->
                    updateForm { it.copy(isSaving = false, error = refusal(failure, MESSAGE_SAVE)) }
                }
        }
    }

    private fun updateForm(transform: (ProjectForm) -> ProjectForm) {
        val form = _uiState.value.form ?: return
        _uiState.value = _uiState.value.copy(form = transform(form))
    }

    /** Takes the project out of the list at once; it comes back with the reason if the API refuses. */
    fun delete(id: String) {
        val before = _uiState.value.projects
        if (before.none { it.id == id }) return
        _uiState.value = _uiState.value.copy(
            projects = before.filter { it.id != id },
            form = _uiState.value.form?.let { if (it.projectId == id) it.copy(done = true) else it },
            error = null,
        )
        viewModelScope.launch {
            repository.deleteProject(id)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(message = "Projet supprimé")
                    load(showLoading = false)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        projects = before,
                        error = refusal(failure, MESSAGE_DELETE),
                    )
                }
        }
    }

    /**
     * Follows the finger: the order changes on screen at once, nothing is sent until [endMove].
     */
    fun previewMove(fromIndex: Int, toIndex: Int) {
        val projects = _uiState.value.projects
        if (fromIndex == toIndex || fromIndex !in projects.indices || toIndex !in projects.indices) return
        if (orderBeforeDrag == null) orderBeforeDrag = projects
        _uiState.value = _uiState.value.copy(
            projects = projects.toMutableList().apply { add(toIndex, removeAt(fromIndex)) },
        )
    }

    /**
     * The finger is lifted: the whole order goes to the API in one request. On a refusal the list
     * goes back to the order it had before the drag, and says why.
     */
    fun endMove() {
        val before = orderBeforeDrag ?: return
        orderBeforeDrag = null
        val after = _uiState.value.projects
        if (before.map { it.id } == after.map { it.id }) return

        reorderInFlight = true
        viewModelScope.launch {
            repository.reorderProjects(after.map { it.id })
                .onSuccess { stored ->
                    reorderInFlight = false
                    // A new drag started meanwhile: its own reorder carries the newer order.
                    if (orderBeforeDrag == null) _uiState.value = _uiState.value.copy(projects = stored)
                    loadPlanning()
                }
                .onFailure {
                    reorderInFlight = false
                    // The API's reason is English (a project missing from the list): the phone says it its own way.
                    _uiState.value = if (orderBeforeDrag == null) {
                        _uiState.value.copy(projects = before, error = MESSAGE_REORDER)
                    } else {
                        _uiState.value.copy(error = MESSAGE_REORDER)
                    }
                    // A project created elsewhere during the drag is often why the order was refused.
                    load(showLoading = false)
                }
        }
    }

    /** One step up or down, from the accessibility actions of a row: a drag of one place. */
    fun moveBy(id: String, offset: Int) {
        val index = _uiState.value.projects.indexOfFirst { it.id == id }
        if (index == -1) return
        previewMove(index, index + offset)
        endMove()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        /**
         * The API refuses in English: the refusals a user can cause from the form are said in French,
         * as the admin does (projectsApi.ts `translate`); anything else, and any failure that is not a
         * refusal (no network), is [fallback].
         */
        internal fun refusal(failure: Throwable, fallback: String): String {
            val message = (failure as? ApiRefusalException)?.message ?: return fallback
            return when {
                message.contains("cycle", ignoreCase = true) -> MESSAGE_CYCLE
                message.contains("depend on itself", ignoreCase = true) -> MESSAGE_SELF_DEPENDENCY
                else -> fallback
            }
        }

        /** A cycle or a self-dependency is said as the form does; any other failure is the preview's own sentence. */
        internal fun previewRefusal(failure: Throwable): String = refusal(failure, MESSAGE_PREVIEW)

        internal fun optionsRefusal(failure: Throwable): String {
            val message = (failure as? ApiRefusalException)?.message ?: return MESSAGE_OPTIONS
            return if (message.contains("Missing to price", ignoreCase = true)) MESSAGE_OPTIONS_TERMS else MESSAGE_OPTIONS
        }

        const val MESSAGE_OPTIONS = "Les options n'ont pas pu être chiffrées."
        const val MESSAGE_OPTIONS_TERMS = "Indiquez un taux, une durée et un apport pour chiffrer les options."
        const val MESSAGE_OPTIONS_INVALID = "Un des paramètres n'est pas un nombre."
        const val MESSAGE_FUNDING = "Les sources n'ont pas été enregistrées."
        const val MESSAGE_LOAN_TERMS = "Les conditions de l'emprunt n'ont pas pu être lues."
        const val MESSAGE_LOAD = "Les projets n'ont pas pu être chargés."
        const val MESSAGE_NOT_FOUND = "Ce projet n'existe plus. Tirez pour actualiser."
        const val MESSAGE_SAVE = "Le projet n'a pas été enregistré."
        const val MESSAGE_PREVIEW = "L'aperçu n'a pas pu être calculé."
        const val MESSAGE_DELETE = "Le projet n'a pas été supprimé."
        const val MESSAGE_REORDER = "L'ordre n'a pas pu être enregistré."
        const val MESSAGE_CYCLE = "Ces dépendances formeraient un cycle : un projet finirait par attendre lui-même."
        const val MESSAGE_SELF_DEPENDENCY = "Un projet ne peut pas dépendre de lui-même."
    }
}
