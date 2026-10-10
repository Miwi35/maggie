package com.maggie.app.screentest

import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.api.EndErrandRemainingItem
import com.maggie.app.data.api.EndErrandRemainingStore
import com.maggie.app.data.api.EndErrandResponse
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AcceptRuleSuggestionsRequest
import com.maggie.app.data.model.AcceptRuleSuggestionsResult
import com.maggie.app.data.model.AcceptedRuleSuggestion
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.MealGroceryIngredient
import com.maggie.app.data.model.MealGroceryPackaging
import com.maggie.app.data.model.MealGroceryPreview
import com.maggie.app.data.model.MealGroceryToBuy
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.model.RuleSuggestion
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.BankConnectionRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.cookbook.grocery.GroceryViewModel
import com.maggie.app.ui.screens.cookbook.meals.MealIngredientChoiceViewModel
import com.maggie.app.ui.screens.finance.BankConnectionViewModel
import com.maggie.app.ui.screens.finance.CategoryViewModel
import com.maggie.app.ui.screens.finance.FinanceDashboardViewModel
import com.maggie.app.ui.screens.finance.RuleSuggestionViewModel
import com.maggie.app.ui.screens.finance.TransactionViewModel
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/*
 * The data layer a screen test runs against (MAG-242).
 *
 * One builder per screen, each giving back the screen's **real** ViewModel over
 * faked repositories. The ViewModel is not mocked on purpose: what the emulator
 * journeys proved about a screen was always « the list the server sent comes out
 * grouped, in this order, with that line hidden » — the state machine as much as
 * the drawing. Mocking the state out would leave the test asserting its own
 * fixture.
 *
 * Faked at the repository and not at the HTTP client, for the same reason the
 * ViewModel tests next door do (`GroceryViewModelTest`): what a repository
 * returns is already the app's own model, so a fixture is three lines instead of
 * a JSON document, and the parsing it would exercise is `DtoContractTest`'s job
 * against the recorded responses of `api/contract/` (MAG-104).
 *
 * The parts that are not a repository — Mercure, the token — are stubbed quiet:
 * a screen test is about one frozen state of the server, and real-time arrival
 * is what `08-grocery-realtime` still proves on a device.
 */

/** Signed in, with no Mercure traffic: the token is what makes a ViewModel load. */
private fun signedIn(): Pair<AuthRepository, MercureService> {
    val auth = mockk<AuthRepository>()
    val mercure = mockk<MercureService>()
    every { auth.token } returns flowOf("jwt")
    coEvery { auth.getUserId() } returns "user-neighbour"
    every { mercure.subscribe(any()) } returns emptyFlow()
    return auth to mercure
}

/**
 * The grocery screen over a list the fake keeps.
 *
 * A tiny in-memory server rather than canned answers, and for the reason
 * `07-grocery-errand` ran a script beside the flow: every step of the errand is
 * « the screen changed **and** the server did ». A stub that forgot the tick
 * would let the screen's optimistic update pass on its own, which is exactly the
 * failure the journey was written to catch. So a tick, a removal and the end of
 * an errand are applied here the way the API applies them — bought lines leave
 * the list, the unbought ones of that shop come back as the sheet's rows — and
 * the test reads the list afterwards through [items].
 *
 * What this cannot say is whether the API really does that; that is
 * `EndErrandControllerTest` and `GroceryToolsTest`, on a real database.
 */
class FakeGrocery(
    list: GroceryList? = Seed.groceryList,
    private val stores: List<Store> = Seed.stores,
    private val loadFailure: Throwable? = null,
) {
    private val listId = list?.id
    private val current = list?.items.orEmpty().toMutableList()

    /** The list as the fake server now holds it — the database half of an assertion. */
    val items: List<GroceryItem> get() = current.toList()

    val viewModel: GroceryViewModel by lazy {
        val (auth, mercure) = signedIn()
        val groceryListRepository = mockk<GroceryListRepository>()
        val productRepository = mockk<ProductRepository>()
        val storeRepository = mockk<StoreRepository>()

        coEvery { groceryListRepository.getGroceryList() } answers {
            loadFailure?.let { return@answers Result.failure(it) }
            Result.success(listId?.let { GroceryList(id = it, items = current.toList()) })
        }
        coEvery { productRepository.getProducts() } returns Result.success(emptyList())
        coEvery { storeRepository.getStores() } returns Result.success(stores)

        coEvery { groceryListRepository.checkItem(any(), any()) } answers {
            val itemId = firstArg<String>()
            val checked = secondArg<Boolean>()
            replace(itemId) { it.copy(checked = checked) }
            Result.success(Unit)
        }
        coEvery { groceryListRepository.deleteItem(any()) } answers {
            current.removeAll { it.id == firstArg<String>() }
            Result.success(Unit)
        }
        coEvery {
            groceryListRepository.editItem(any(), any(), any(), any(), any(), any(), any())
        } answers {
            val itemId = firstArg<String>()
            val quantity = arg<Float?>(2)
            val storeId = arg<String?>(4)
            replace(itemId) { item ->
                item.copy(
                    quantity = quantity ?: item.quantity,
                    store = stores.firstOrNull { it.id == storeId } ?: item.store,
                )
            }
            Result.success(Unit)
        }
        // The server's own definition: what was ticked at this shop is bought and
        // leaves the list; what was not comes back to be transferred or kept.
        coEvery { groceryListRepository.endErrand(any()) } answers {
            val storeId = firstArg<String?>()
            val atStore = current.filter { it.store?.id == storeId }
            current.removeAll { it.store?.id == storeId && it.checked }
            val remaining = atStore
                .filter { !it.checked }
                .mapNotNull { item ->
                    item.id?.let {
                        EndErrandRemainingItem(
                            id = it,
                            label = item.label,
                            store = item.store?.let { s -> EndErrandRemainingStore(s.id, s.name) },
                        )
                    }
                }
            Result.success(
                EndErrandResponse(success = true, remainingItems = remaining, remainingCount = remaining.size),
            )
        }

        GroceryViewModel(groceryListRepository, productRepository, storeRepository, mercure, auth)
    }

    private fun replace(
        itemId: String,
        transform: (GroceryItem) -> GroceryItem,
    ) {
        val index = current.indexOfFirst { it.id == itemId }
        if (index >= 0) current[index] = transform(current[index])
    }
}

/**
 * The full calendar over a frozen set of events.
 *
 * No agenda, which is the simplest honest fixture: with none enabled the screen
 * draws the events that belong to no agenda, and that is every event here. The
 * agenda filter is `FullCalendarViewModel`'s and has its own tests.
 */
class FakeCalendar(private val events: List<Event>) {
    constructor(vararg events: Event) : this(events.toList())

    val viewModel: FullCalendarViewModel by lazy {
        val (auth, mercure) = signedIn()
        val eventRepository = mockk<EventRepository>()
        val taskRepository = mockk<TaskRepository>()
        val agendaRepository = mockk<AgendaRepository>()
        coEvery { eventRepository.refreshEvents() } returns Result.success(events)
        coEvery { eventRepository.getRecurringBefore(any()) } returns emptyList()
        coEvery { taskRepository.refreshTasks() } returns Result.success(emptyList())
        coEvery { taskRepository.getUndoneTasks(any()) } returns emptyList()
        coEvery { agendaRepository.refreshAgendas() } returns Result.success(emptyList())
        val userPreferenceRepository = mockk<UserPreferenceRepository>()
        every { userPreferenceRepository.preference } returns MutableStateFlow(null)
        coEvery { userPreferenceRepository.refresh() } returns Result.failure(IllegalStateException("no preference"))
        val mealRepository = mockk<MealRepository>()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(emptyList())
        FullCalendarViewModel(eventRepository, taskRepository, agendaRepository, mercure, auth, userPreferenceRepository, mealRepository)
    }
}

/**
 * The finance dashboard, over a month the server could not serve.
 *
 * Only the failing case, which is not a gap: what `06-finance-hub` asserted is
 * that the dashboard is the one door to the module and leads to every part of it,
 * and the accesses come from the `FINANCE_ACCESSES` constant, not from the
 * figures. Serving them over a failed load is the stronger version of the same
 * claim — a month the API cannot compute must not lock the user out of their
 * budgets — and it needs no `FinanceDashboard` fixture. The figures themselves
 * are `FinanceDashboardViewModelTest`'s.
 */
class FakeFinanceDashboard(private val error: String = "Service indisponible") {
    val viewModel: FinanceDashboardViewModel by lazy {
        val repository = mockk<FinanceDashboardRepository>()
        coEvery { repository.getDashboard(any(), any()) } returns
            Result.failure(IllegalStateException(error))
        FinanceDashboardViewModel(repository)
    }
}

/**
 * The suggestions screen over the merchant the dictionary could not file.
 *
 * A small server again rather than canned answers: accepting writes the rule and
 * the merchant stops being a question, which is what the screen then draws as
 * « Rien à proposer pour l'instant ». A stub that kept serving the card would let
 * a screen that never reloads pass.
 */
class FakeRuleSuggestions(
    suggestions: List<RuleSuggestion> = listOf(Seed.leclercSuggestion),
    private val categories: List<Category> = Seed.financeCategories,
) {
    private val remaining = suggestions.toMutableList()

    /** What the fake server was asked to write — the rule half of an assertion. */
    val accepted = mutableListOf<AcceptedRuleSuggestion>()

    val viewModel: RuleSuggestionViewModel by lazy {
        val ruleRepository = mockk<CategorizationRuleRepository>()
        val categoryRepository = mockk<CategoryRepository>()

        coEvery { categoryRepository.getCategories() } returns Result.success(categories)
        coEvery { ruleRepository.getSuggestions() } answers { Result.success(remaining.toList()) }
        coEvery { ruleRepository.acceptSuggestions(any()) } answers {
            val rules = firstArg<AcceptRuleSuggestionsRequest>().rules
            accepted += rules
            remaining.removeAll { suggestion -> rules.any { it.pattern == suggestion.pattern } }
            Result.success(
                AcceptRuleSuggestionsResult(
                    success = true,
                    created = rules.size,
                    categorized = rules.size * 3,
                    patterns = rules.map { it.pattern },
                ),
            )
        }

        RuleSuggestionViewModel(ruleRepository, categoryRepository)
    }
}

/**
 * The bank screen over the links the server holds (MAG-45, retour de recette).
 *
 * Canned answers are enough here: the screen test asserts how one frozen list
 * is drawn — whether a working link reads as working — and the fetch and the
 * reconnection are `BankConnectionViewModelTest`'s.
 */
class FakeBankConnections(private val connections: List<BankConnection>) {
    val viewModel: BankConnectionViewModel by lazy {
        val repository = mockk<BankConnectionRepository>()
        coEvery { repository.getConnections() } returns Result.success(connections)
        BankConnectionViewModel(repository)
    }
}

/**
 * One account's transactions, and the transfer endpoints over them (MAG-272), rejections
 * included (MAG-350).
 *
 * A small server rather than canned answers: marking or releasing a line is applied the
 * way the API applies it — the line changes kind, and a released line frees its
 * counterpart — so the screen has to show the new state it was handed, not the one
 * it assumed. [marked] is what the fake was asked to write.
 */
class FakeTransactionTransfers(
    transactions: List<Transaction> = listOf(Seed.transferOut, Seed.groceries),
    private val counterpart: TransferLeg = Seed.transferIn,
    private val candidates: List<TransferLeg> = listOf(Seed.transferIn),
) {
    private val lines = transactions.toMutableList()
    private val paired = lines.filter { it.isInternalTransfer }.map { it.id }.toMutableSet()

    /** The counterpart ids the screen asked to mark with, `null` for « none ». */
    val marked = mutableListOf<String?>()
    val released = mutableListOf<String>()

    val viewModel: TransactionViewModel by lazy {
        val repository = mockk<TransactionRepository>()
        val categoryRepository = mockk<CategoryRepository>()
        val (auth, mercure) = signedIn()

        coEvery { categoryRepository.getCategories() } returns Result.success(emptyList())
        // The API leaves the rejected payments out of the list and serves them as incidents (MAG-375).
        coEvery { repository.getTransactions(any()) } answers { Result.success(lines.filterNot { it.isRejected }) }
        coEvery { repository.getIncidents(any()) } answers { Result.success(incidents()) }
        coEvery { repository.getTransfer(any()) } answers {
            val line = lines.first { it.id == firstArg<String>() }
            Result.success(
                TransferInfo(
                    transferKind = line.transferKind,
                    transferSource = line.transferSource,
                    counterpart = if (line.isRejected) {
                        rejectionLeg(line)?.let(Seed::legOf)
                    } else {
                        counterpart.takeIf { line.isInternalTransfer && line.id in paired }
                    },
                ),
            )
        }
        coEvery { repository.getTransferCandidates(any()) } returns Result.success(candidates)
        coEvery { repository.setTransfer(any(), any(), any()) } answers {
            val id = firstArg<String>()
            val internal = secondArg<Boolean>()
            val counterpartId = thirdArg<String?>()
            if (internal) marked += counterpartId else released += id
            if (internal && counterpartId != null) paired += id else paired -= id
            val index = lines.indexOfFirst { it.id == id }
            // A rejection's two legs sit on this account: releasing one frees the other here too.
            if (!internal) {
                rejectionLeg(lines[index])?.let { leg ->
                    val legIndex = lines.indexOf(leg)
                    lines[legIndex] = leg.copy(transferKind = "none", transferSource = "manual")
                }
            }
            lines[index] = lines[index].copy(
                transferKind = if (internal) "internal" else "none",
                transferSource = "manual",
            )
            Result.success(
                TransferInfo(
                    transferKind = lines[index].transferKind,
                    transferSource = "manual",
                    counterpart = counterpart.takeIf { counterpartId != null && internal },
                ),
            )
        }

        TransactionViewModel(repository, categoryRepository, "acc-savings", mercure, auth)
    }

    /** One incident per rejected debit, with the credit that gave it back when this account has it. */
    private fun incidents(): List<AccountIncident> =
        lines.filter { it.isRejected && it.amountCents < 0 }.map { debit ->
            val credit = rejectionLeg(debit)
            AccountIncident(
                debitId = debit.id,
                creditId = credit?.id,
                bookedAt = debit.bookedAt ?: "",
                rejectedAt = credit?.bookedAt,
                counterpartyName = debit.label.removePrefix("PRELEVEMENT "),
                amountCents = -debit.amountCents,
                kind = "direct_debit",
            )
        }

    /** The other leg of a rejection: the rejected line of opposite amount on this account. */
    private fun rejectionLeg(line: Transaction): Transaction? =
        lines.firstOrNull { line.isRejected && it.isRejected && it.amountCents == -line.amountCents }
}

/**
 * The conversation, over a history the fake holds (MAG-35).
 *
 * Built for `ChatPanelScreenTest`: the permanent side panel of a wide window
 * draws the same conversation as the sheet, and the sheet's half has the Maestro
 * flows as a net while the panel has nothing — the CI emulator is a phone and
 * will never see a 1280 dp window.
 *
 * What a sent message is read back through is [sent], not the screen: the panel's
 * send button is only right if it reaches the repository with what was typed.
 */
class FakeChat(
    private val history: List<ChatMessage> = Seed.conversation,
    private val pending: List<PendingApproval> = emptyList(),
    /** What the agent streams back to a message — a test drives it by hand to grow an answer. */
    private val reply: Flow<AgUiEvent> = emptyFlow(),
) {
    private val outgoing = mutableListOf<String>()

    /** The repository the held actions are answered through: verify the calls it received. */
    val approvals: ApprovalRepository by lazy { mockk<ApprovalRepository>() }

    /** What the panel asked the server to send, in order. */
    val sent: List<String> get() = outgoing.toList()

    val viewModel: ChatViewModel by lazy {
        val (auth, mercure) = signedIn()
        val repository = mockk<ChatRepository>()
        val preferences = mockk<ChatPreferencesRepository>()

        every { repository.observeMessages() } returns flowOf(history)
        coEvery { repository.syncMessages() } returns Unit
        coEvery { repository.loadRecentMessages(any()) } returns history
        coEvery { repository.persistMessage(any()) } returns Unit
        coEvery { repository.sendMessage(any()) } answers {
            outgoing += firstArg<String>()
            emptyList()
        }
        every { repository.sendMessageStream(any(), any(), any()) } answers {
            outgoing += firstArg<String>()
            reply
        }
        coEvery { preferences.getLastReadMessageId() } returns null
        coEvery { preferences.saveLastReadMessageId(any()) } returns Unit

        coEvery { approvals.getPending() } returns Result.success(pending)
        every { approvals.observe() } returns emptyFlow()
        coEvery { approvals.describe(any()) } returns null
        // Result is a value class: `returns` is what mockk unwraps correctly, `answers` is not.
        pending.forEach { held ->
            coEvery { approvals.approve(held.id) } returns
                Result.success(held.copy(status = PendingApproval.STATUS_APPROVED))
            coEvery { approvals.deny(held.id) } returns
                Result.success(held.copy(status = PendingApproval.STATUS_DENIED))
        }

        ChatViewModel(repository, mercure, preferences, auth, approvals)
    }
}

/**
 * The finance dashboard over a month the server did compute.
 *
 * Only what the caller cares about is filled in: the cards read the dashboard
 * field by field, and every DTO carries a default, so an unset part of the
 * fixture is the empty state the screen also has to draw.
 */
class FakeLoadedFinanceDashboard(private val dashboard: FinanceDashboard) {
    val viewModel: FinanceDashboardViewModel by lazy {
        val repository = mockk<FinanceDashboardRepository>()
        coEvery { repository.getDashboard(any(), any()) } returns Result.success(dashboard)
        FinanceDashboardViewModel(repository)
    }
}

/**
 * The category list over a server that keeps what it is sent.
 *
 * A small server rather than a canned list: the list is only right if the
 * request left with the box ticked *and* the row comes back as the API would
 * return it — [created] is the request half of an assertion, the list on screen
 * is the other.
 */
class FakeCategories(initial: List<Category> = Seed.financeCategories) {
    private val stored = initial.toMutableList()

    /** What the fake server was asked to create, in order. */
    val created = mutableListOf<CategoryCreateRequest>()

    val viewModel: CategoryViewModel by lazy {
        val repository = mockk<CategoryRepository>()
        coEvery { repository.getCategories() } answers { Result.success(stored.toList()) }
        coEvery { repository.createCategory(any()) } answers {
            val request = firstArg<CategoryCreateRequest>()
            created += request
            val category = Category(
                id = "cat-created-${created.size}",
                name = request.name,
                obligation = request.obligation,
                passiveIncome = request.passiveIncome,
            )
            stored += category
            Result.success(category)
        }
        CategoryViewModel(repository)
    }
}

/**
 * The ingredients of « Riz au curry » as the server previews them (MAG-297): the rice
 * out of stock, bought by the 500 g pack, and the vegetables in the cupboard.
 *
 * [refuse] makes the API turn the send down.
 */
class FakeMealIngredientChoice(private val refuse: Boolean = false) {

    /** What the fake server was asked to put on the list, one entry per send. */
    val sent = mutableListOf<List<String>>()

    val viewModel: MealIngredientChoiceViewModel by lazy {
        val mealRepository = mockk<MealRepository>()
        coEvery { mealRepository.groceryPreview(MEAL_ID) } returns Result.success(preview)
        coEvery { mealRepository.addToGroceries(MEAL_ID, any()) } answers {
            sent += secondArg<List<String>>()
            if (refuse) Result.failure(RuntimeException("400")) else Result.success(preview)
        }

        MealIngredientChoiceViewModel(MEAL_ID, mealRepository)
    }

    companion object {
        const val MEAL_ID = "meal-curry"

        val rice = MealGroceryIngredient(
            ingredientId = "rice",
            name = "Riz",
            quantity = 300f,
            unit = CookbookUnit.G,
            packaging = MealGroceryPackaging(CookbookUnit.PACK, 500f, CookbookUnit.G),
            toBuy = MealGroceryToBuy(1f, CookbookUnit.PACK),
            stockState = ProductStockState.OUT,
            suggested = true,
        )

        val vegetables = MealGroceryIngredient(
            ingredientId = "vegetables",
            name = "Légumes pour couscous",
            quantity = 1f,
            unit = CookbookUnit.JAR,
            packaging = MealGroceryPackaging(CookbookUnit.JAR),
            toBuy = MealGroceryToBuy(1f, CookbookUnit.JAR),
            stockState = ProductStockState.IN_STOCK,
            suggested = false,
        )

        val flour = MealGroceryIngredient(
            ingredientId = "flour",
            name = "Farine",
            quantity = 600f,
            unit = CookbookUnit.G,
            packaging = MealGroceryPackaging(CookbookUnit.PACK, 500f, CookbookUnit.G),
            toBuy = MealGroceryToBuy(2f, CookbookUnit.PACK),
            stockState = ProductStockState.LOW,
            suggested = true,
        )

        val preview = MealGroceryPreview(MEAL_ID, null, listOf(rice, flour, vegetables))
    }
}
