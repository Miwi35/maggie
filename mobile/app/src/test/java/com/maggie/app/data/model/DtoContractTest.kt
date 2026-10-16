package com.maggie.app.data.model

import com.maggie.app.data.api.ApiCollection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The DTOs, against responses the API actually produced.
 *
 * The files in `api/contract/responses/` are not written by hand: RecordedResponseContractTest
 * on the API side performs each request and records what came back. So these
 * tests fail when the API changes, in the same commit, without a stack or an
 * emulator.
 *
 * Both halves matter, and the second is the one that finds things:
 *
 *  - deserialising has to succeed, which catches a type that changed or a
 *    required field that disappeared;
 *  - every field a DTO declares has to exist in the recording, which catches
 *    a field that is merely *named* differently. That failure is invisible at
 *    runtime: kotlinx.serialization fills the declared default and the app
 *    shows a value nobody chose. Symfony serialises `isCushion()` as
 *    `cushion`, and the DTO that declares `isCushion` reads `false` forever.
 */
class DtoContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * DTO fields with no counterpart in the API response, and why that is
     * accepted rather than a bug waiting to be found.
     *
     * Anything not listed here must appear in the recording. Adding a line is
     * a decision — write down the reason.
     */
    private val fieldsTheApiDoesNotEmit: Map<String, Set<String>> = mapOf(
        // Set by the recurrence expansion on the client, and by the API only
        // on an exception instance — which the recorded world has none of.
        "Event" to setOf("recurringEvent", "originalStartAt"),
        // Filled by the agent's profile endpoint, not by /api/users/me.
        "User" to setOf("avatar", "googleTaskListId"),
        // Only an `ambiguous` proposal (two series claim one line) carries its candidates, and the
        // recorded world has none: the reading of a candidate is the ProposalClaim half of the
        // proposal, whose fields the recorded proposal does carry.
        "RecurringProposal" to setOf("candidates"),
        // The API omits an empty note and the recorded world writes none (MAG-464). It also omits
        // who qualified a line while nobody did, and the series or rule behind it (MAG-479).
        "Transaction" to setOf("note", "qualifiedBy", "qualifiedBySeries", "qualifiedByRule"),
    )

    @Test
    fun `agendas deserialise from the recorded response`() =
        assertCollectionContract("agendas", serializer<Agenda>(), "Agenda")

    @Test
    fun `events deserialise from the recorded response`() =
        assertCollectionContract("events", serializer<Event>(), "Event")

    @Test
    fun `tasks deserialise from the recorded response`() =
        assertCollectionContract("tasks", serializer<Task>(), "Task")

    @Test
    fun `a paged task view deserialises from the recorded response`() {
        val body = readRecording("tasks.view_done")
        json.decodeFromJsonElement(serializer<TaskViewResponse>(), body)

        assertDeclaredFieldsExist("TaskViewResponse", serializer<TaskViewResponse>(), body.keys, "tasks.view_done")
        assertDeclaredFieldsExist("TaskViewCounts", serializer<TaskViewCounts>(), body["counts"]!!.jsonObject.keys, "tasks.view_done")
    }

    @Test
    fun `an unpaged task view deserialises from the recorded response, tasks and counts`() {
        val body = readRecording("tasks.view_today")
        val view = json.decodeFromJsonElement(serializer<TaskViewResponse>(), body)

        assertTrue("The recording tasks.view_today is empty, so it proves nothing.", view.member.isNotEmpty())
        assertDeclaredFieldsExist("TaskViewCounts", serializer<TaskViewCounts>(), body["counts"]!!.jsonObject.keys, "tasks.view_today")
    }

    @Test
    fun `the day deserialises from the recorded response, its slots, now and reserve`() {
        val body = readRecording("day_plans.read")
        val day = json.decodeFromJsonElement(serializer<DayPlan>(), body)

        assertTrue("The recording day_plans.read has no slot, so it proves nothing.", day.slots.isNotEmpty())
        assertTrue("The recording day_plans.read has an empty reserve, so it proves nothing.", day.reserve.isNotEmpty())
        assertDeclaredFieldsExist("DayPlan", serializer<DayPlan>(), body.keys, "day_plans.read")
        assertDeclaredFieldsExist("DaySlot", serializer<DaySlot>(), keysOf(body, "slots"), "day_plans.read")
        val tasks = body["slots"]!!.jsonArray.flatMap { it.jsonObject["task"]!!.jsonObject.keys } + keysOf(body, "reserve")
        assertDeclaredFieldsExist("DayTask", serializer<DayTask>(), tasks.toSet(), "day_plans.read")
        assertEquals(20, day.now?.task?.estimatedMinutes)
        assertEquals("Ouvrir le dossier", day.now?.task?.firstStep)
        assertEquals("2026-03-20", day.now?.task?.postponedSince)
        assertEquals(day.total - day.done, day.left)
    }

    @Test
    fun `the evening's review deserialises from the recorded response under the names the API sends (MAG-500)`() {
        assertItemContract("day_reviews.read", serializer<DayReview>(), "DayReview")
        val review = json.decodeFromJsonElement(serializer<DayReview>(), readRecording("day_reviews.read"))

        assertEquals(listOf(DayReview.START_SMALL, DayReview.TIMER), review.helped)
        assertTrue(review.isDone)
        assertEquals(DayReview.HELPED.map { it.first }.toSet(), setOf("start_small", "morning", "timer"))
    }

    @Test
    fun `the morning's proposal deserialises from the recorded response, its slots and its reserve`() {
        val body = readRecording("day_plans.proposal")
        val proposal = json.decodeFromJsonElement(serializer<DayProposal>(), body)

        assertTrue("The recording day_plans.proposal has no slot, so it proves nothing.", proposal.slots.isNotEmpty())
        assertTrue("The recording day_plans.proposal has an empty reserve, so it proves nothing.", proposal.reserveRanked.isNotEmpty())
        assertDeclaredFieldsExist("DayProposal", serializer<DayProposal>(), body.keys, "day_plans.proposal")
        assertDeclaredFieldsExist("ProposalSlot", serializer<ProposalSlot>(), keysOf(body, "slots"), "day_plans.proposal")
        assertDeclaredFieldsExist("ProposalEntry", serializer<ProposalEntry>(), keysOf(body, "reserveRanked"), "day_plans.proposal")
        val tasks = body["slots"]!!.jsonArray.flatMap { it.jsonObject["task"]!!.jsonObject.keys } +
            body["reserveRanked"]!!.jsonArray.flatMap { it.jsonObject["task"]!!.jsonObject.keys }
        assertDeclaredFieldsExist("DayTask", serializer<DayTask>(), tasks.toSet(), "day_plans.proposal")
        assertEquals("postponed", proposal.slots.first().reason)
        assertEquals(20, proposal.slots.first().minutes)
        assertEquals(proposal.slots.sumOf { it.minutes }, proposal.totalMinutes)
    }

    @Test
    fun `a task carries its estimate, first step and the day it was put off under the names the API sends`() {
        val view = json.decodeFromJsonElement(serializer<TaskViewResponse>(), readRecording("tasks.view_today"))
        val open = view.member.first { it.title == "Recorded open task" }

        assertEquals(20, open.estimatedMinutes)
        assertEquals("Ouvrir le dossier", open.firstStep)
        assertEquals("2026-03-20", open.postponedSince)
    }

    @Test
    fun `proactions deserialise from the recorded response`() =
        assertCollectionContract("proactions", serializer<Proaction>(), "Proaction")

    @Test
    fun `meals deserialise from the recorded response`() =
        assertCollectionContract("meals", serializer<Meal>(), "Meal")

    @Test
    fun `recipes deserialise from the recorded response`() =
        assertCollectionContract("recipes", serializer<Recipe>(), "Recipe")

    @Test
    fun `the steps and utensils of a recipe deserialise from the recorded response`() {
        val recipe = readRecording("recipes.collection")["member"]!!.jsonArray.first().jsonObject
        val steps = recipe["steps"]!!.jsonArray.map { it.jsonObject }
        val utensils = recipe["utensils"]!!.jsonArray.map { it.jsonObject }

        assertTrue("The recording carries no step: it proves nothing about them.", steps.isNotEmpty())
        assertTrue("The recording carries no utensil: it proves nothing about them.", utensils.isNotEmpty())

        assertDeclaredFieldsExist("RecipeStep", serializer<RecipeStep>(), steps.flatMap { it.keys }.toSet(), "recipes.collection (steps)")
        assertDeclaredFieldsExist("Utensil", serializer<Utensil>(), utensils.flatMap { it.keys }.toSet(), "recipes.collection (utensils)")
        assertDeclaredFieldsExist(
            "StepIngredient",
            serializer<StepIngredient>(),
            steps.flatMap { it["ingredients"]!!.jsonArray }.flatMap { it.jsonObject.keys }.toSet(),
            "recipes.collection (step ingredients)",
        )

        val decoded = json.decodeFromJsonElement(serializer<Recipe>(), recipe)
        assertEquals(listOf(1, 2), decoded.steps.map { it.position })
        assertEquals(listOf(1), decoded.steps[1].waitsFor)
        assertEquals(12, decoded.steps[1].durationMinutes)
        assertTrue(decoded.steps[1].background)
    }

    @Test
    fun `ingredients deserialise from the recorded response`() =
        assertCollectionContract("ingredients", serializer<Ingredient>(), "Ingredient")

    @Test
    fun `products deserialise from the recorded response`() =
        assertCollectionContract("products", serializer<Product>(), "Product")

    @Test
    fun `stores deserialise from the recorded response`() =
        assertCollectionContract("stores", serializer<Store>(), "Store")

    @Test
    fun `aisles deserialise from the recorded response`() =
        assertCollectionContract("aisles", serializer<Aisle>(), "Aisle")

    @Test
    fun `a product's aisles carry a store and an aisle under the names the API sends`() {
        val keys = readRecording("products.collection")["member"]!!.jsonArray
            .flatMap { it.jsonObject["aisles"]?.jsonArray.orEmpty() }
            .flatMap { it.jsonObject.keys }
            .toSet()
        assertTrue("No recorded product has an aisle: the recording proves nothing about it.", keys.isNotEmpty())

        assertDeclaredFieldsExist("ProductAisle", serializer<ProductAisle>(), keys, "products.collection")
    }

    @Test
    fun `grocery lists deserialise from the recorded response`() =
        assertCollectionContract("grocery_lists", serializer<GroceryList>(), "GroceryList")

    @Test
    fun `past errands deserialise from the recorded response`() =
        assertCollectionContract("errands", serializer<Errand>(), "Errand")

    @Test
    fun `the lines of a past errand deserialise from the recorded response`() {
        val members = readRecording("errands.collection")["member"]!!.jsonArray
        val itemKeys = members
            .flatMap { it.jsonObject["items"]?.jsonArray.orEmpty() }
            .flatMap { it.jsonObject.keys }
            .toSet()
        assertTrue("No recorded errand carries a line: the recording proves nothing about it.", itemKeys.isNotEmpty())

        assertDeclaredFieldsExist("PurchasedItem", serializer<PurchasedItem>(), itemKeys, "errands.collection")
    }

    @Test
    fun `grocery suggestions deserialise from the recorded response`() {
        // A plain JSON array, not a Hydra collection (MAG-495).
        val file = File(repositoryRoot(), "api/contract/responses/grocery_suggestions.json")
        assertTrue("The recording ${file.absolutePath} is missing. The API suite writes it: " +
            "UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract", file.isFile)
        val members = Json.parseToJsonElement(file.readText()).jsonArray
        assertTrue("The recording grocery_suggestions is empty, so it proves nothing.", members.isNotEmpty())

        val suggestions = json.decodeFromJsonElement(ListSerializer(serializer<PurchaseSuggestion>()), members)

        assertDeclaredFieldsExist(
            "PurchaseSuggestion", serializer<PurchaseSuggestion>(), members.flatMap { it.jsonObject.keys }.toSet(), "grocery_suggestions",
        )
        assertDeclaredFieldsExist(
            "SuggestedProduct",
            serializer<SuggestedProduct>(),
            members.flatMap { it.jsonObject["product"]!!.jsonObject.keys }.toSet(),
            "grocery_suggestions",
        )
        assertTrue("usualQuantity fell back to its default", suggestions.first().usualQuantity != null)
        assertTrue("usualUnit fell back to its default", suggestions.first().usualUnit != null)
    }

    @Test
    fun `recurring grocery items deserialise from the recorded response`() =
        assertCollectionContract("recurring_grocery_items", serializer<RecurringGroceryItem>(), "RecurringGroceryItem")

    @Test
    fun `notifications deserialise from the recorded response`() =
        assertCollectionContract("notifications", serializer<Notification>(), "Notification")

    @Test
    fun `accounts deserialise from the recorded response`() =
        assertCollectionContract("accounts", serializer<Account>(), "Account")

    @Test
    fun `categories deserialise from the recorded response`() =
        assertCollectionContract("categories", serializer<Category>(), "Category")

    @Test
    fun `transactions deserialise from the recorded response`() =
        assertCollectionContract("transactions", serializer<Transaction>(), "Transaction")

    @Test
    fun `the to-sort pile deserialises from the recorded response`() {
        val body = readRecording("finance_transactions_to_sort.read")

        val pile = json.decodeFromJsonElement(serializer<ToSortPile>(), body)

        assertDeclaredFieldsExist("ToSortPile", serializer<ToSortPile>(), body.keys, "finance_transactions_to_sort.read")
        assertDeclaredFieldsExist(
            "TransactionToSort", serializer<TransactionToSort>(), keysOf(body, "transactions"), "finance_transactions_to_sort.read",
        )
        val suggestionKeys = body["transactions"]!!.jsonArray
            .mapNotNull { it.jsonObject["suggestion"] as? JsonObject }
            .flatMap { it.keys }
            .toSet()
        assertDeclaredFieldsExist("SortSuggestion", serializer<SortSuggestion>(), suggestionKeys, "finance_transactions_to_sort.read")
        val suggested = pile.transactions.first { it.suggestion != null }
        assertEquals("rule", suggested.suggestion?.source)
        assertTrue("reason fell back to its default", suggested.suggestion?.reason.orEmpty().isNotEmpty())
        assertEquals(pile.total, pile.transactions.size)
    }

    @Test
    fun `recurring operations deserialise from the recorded response`() =
        assertCollectionContract("recurring_operations", serializer<RecurringOperationRecord>(), "RecurringOperationRecord")

    @Test
    fun `recurring operation suggestions deserialise from the recorded response`() {
        val body = readRecording("recurring_operations.suggestions")

        json.decodeFromJsonElement(serializer<RecurringSuggestionsResponse>(), body)

        assertDeclaredFieldsExist(
            "RecurringSuggestion", serializer<RecurringSuggestion>(), keysOf(body, "suggestions"), "recurring_operations.suggestions",
        )
    }

    @Test
    fun `the recurring operations overview deserialises from the recorded response`() {
        val body = readRecording("recurring_operations.overview")

        json.decodeFromJsonElement(serializer<RecurringOperationsOverview>(), body)

        assertDeclaredFieldsExist(
            "RecurringOperationsOverview", serializer<RecurringOperationsOverview>(), body.keys, "recurring_operations.overview",
        )
        assertDeclaredFieldsExist(
            "RecurringSeries", serializer<RecurringSeries>(), keysOf(body, "operations"), "recurring_operations.overview",
        )
        assertDeclaredFieldsExist(
            "RecurringProposal", serializer<RecurringProposal>(), keysOf(body, "proposals"), "recurring_operations.overview",
        )
        assertDeclaredFieldsExist(
            "RecurringTotals", serializer<RecurringTotals>(), body["totals"]!!.jsonObject.keys, "recurring_operations.overview",
        )
        // The nested objects are present on at least one recorded series, so their names are proved too.
        assertDeclaredFieldsExist(
            "LastOccurrence", serializer<LastOccurrence>(), nestedKeys(body, "lastOccurrence"), "recurring_operations.overview",
        )
        assertDeclaredFieldsExist(
            "MissedOccurrence", serializer<MissedOccurrence>(), nestedKeys(body, "missedOccurrence"), "recurring_operations.overview",
        )
        assertDeclaredFieldsExist(
            "PriceChange", serializer<PriceChange>(), nestedKeys(body, "priceChange"), "recurring_operations.overview",
        )
    }

    @Test
    fun `envelopes deserialise from the recorded response`() =
        assertCollectionContract("envelopes", serializer<Envelope>(), "Envelope")

    @Test
    fun `an envelope's seasons deserialise from the recorded response`() {
        val members = readRecording("envelopes.collection")["member"]!!.jsonArray
        val seasonKeys = members
            .flatMap { it.jsonObject["seasonalAmounts"]?.jsonArray.orEmpty() }
            .flatMap { it.jsonObject.keys }
            .toSet()
        assertTrue("No recorded envelope carries a season: the recording proves nothing about it.", seasonKeys.isNotEmpty())

        assertDeclaredFieldsExist("Season", serializer<Season>(), seasonKeys, "envelopes.collection")
    }

    @Test
    fun `the budget status deserialises from the recorded response, with what lies outside the month`() {
        val body = readRecording("finance_budget_status.read")

        json.decodeFromJsonElement(serializer<BudgetStatus>(), body)

        val outside = body["outsideMonthlyBudget"]!!.jsonObject
        assertDeclaredFieldsExist("BudgetStatus", serializer<BudgetStatus>(), body.keys, "finance_budget_status.read")
        assertDeclaredFieldsExist("BudgetLine", serializer<BudgetLine>(), keysOf(body, "budgets"), "finance_budget_status.read")
        assertDeclaredFieldsExist("OutsideMonthlyBudget", serializer<OutsideMonthlyBudget>(), outside.keys, "finance_budget_status.read")
        assertDeclaredFieldsExist("BudgetLine", serializer<BudgetLine>(), keysOf(outside, "annual"), "finance_budget_status.read")
        assertDeclaredFieldsExist("OneOffBudgetLine", serializer<OneOffBudgetLine>(), keysOf(outside, "oneOff"), "finance_budget_status.read")
    }

    @Test
    fun `the finance scores deserialise from the recorded response, with the rhythm, its curve and its debits`() {
        val body = readRecording("finance_scores.read")

        val scores = json.decodeFromJsonElement(serializer<FinanceScores>(), body)

        val week = body["week"]!!.jsonObject
        val month = body["month"]!!.jsonObject
        val weekRhythm = week["rhythm"]!!.jsonObject
        val monthRhythm = month["rhythm"]!!.jsonObject
        val source = "finance_scores.read"
        assertDeclaredFieldsExist("FinanceScores", serializer<FinanceScores>(), body.keys, source)
        assertDeclaredFieldsExist("PeriodScore", serializer<PeriodScore>(), week.keys + month.keys, source)
        assertDeclaredFieldsExist("GlobalScore", serializer<GlobalScore>(), body["global"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("ScoreReason", serializer<ScoreReason>(), keysOf(week, "reasons") + keysOf(month, "reasons"), source)
        assertDeclaredFieldsExist("Rhythm", serializer<Rhythm>(), weekRhythm.keys + monthRhythm.keys, source)
        assertDeclaredFieldsExist("RhythmPoint", serializer<RhythmPoint>(), keysOf(monthRhythm, "curve"), source)
        assertDeclaredFieldsExist("RhythmDebit", serializer<RhythmDebit>(), keysOf(monthRhythm, "debits"), source)

        // The days to come carry no spending: null on the wire, not zero.
        assertTrue(scores.month.rhythm.curve.any { it.spentCents == null })
        assertTrue(scores.month.rhythm.debits.isNotEmpty())
    }

    @Test
    fun `the current user deserialises from the recorded response`() =
        assertItemContract("users.me", serializer<User>(), "User")

    @Test
    fun `user preferences deserialise from the recorded response`() =
        assertItemContract("user_preferences.me", serializer<UserPreference>(), "UserPreference")

    @Test
    fun `task settings deserialise from the recorded response`() {
        assertItemContract("task_settings.me", serializer<TaskSettings>(), "TaskSettings")
        val body = readRecording("task_settings.me")
        val defaults = body["defaults"]!!.jsonObject
        assertDeclaredFieldsExist("TaskSettingsValues", serializer<TaskSettingsValues>(), defaults.keys, "task_settings.me")
        // Every urgency's rule, in the settings and in the defaults, so each key is proved where the app reads it.
        listOf(body, defaults).forEach { settings ->
            settings["alertSchedule"]!!.jsonObject.values.forEach { rule ->
                assertDeclaredFieldsExist("AlertRule", serializer<AlertRule>(), rule.jsonObject.keys, "task_settings.me")
            }
        }
    }

    @Test
    fun `the task order preview deserialises from the recorded response`() {
        val body = readRecording("task_settings.preview")
        val members = body["member"]!!.jsonArray
        assertTrue("The recording \"task_settings.preview\" is empty, so it proves nothing.", members.isNotEmpty())

        val preview = json.decodeFromJsonElement(ApiCollection.serializer(serializer<TaskOrderPreviewItem>()), body).member

        assertDeclaredFieldsExist(
            "TaskOrderPreviewItem",
            serializer<TaskOrderPreviewItem>(),
            members.flatMap { it.jsonObject.keys }.toSet(),
            "task_settings.preview",
        )
        assertEquals("critical", preview.first().urgency)
        assertEquals("2026-03-20", preview.first().dueDate)
        assertEquals(111, preview.first().orderScore)
    }

    @Test
    fun `meal settings deserialise from the recorded response`() {
        assertItemContract("meal_settings.me", serializer<MealSettings>(), "MealSettings")
        val body = readRecording("meal_settings.me")
        assertDeclaredFieldsExist("MealSettingsValues", serializer<MealSettingsValues>(), body["defaults"]!!.jsonObject.keys, "meal_settings.me")
    }

    @Test
    fun `a meal carries the guests that count, not the DTO default`() {
        val meal = json.decodeFromJsonElement(serializer<Meal>(), readRecording("meals.collection")["member"]!!.jsonArray.first())

        assertEquals(2, meal.guests)
    }

    @Test
    fun `the monthly review carries its reference salary under the names the API sends`() {
        val review = json.decodeFromJsonElement(serializer<MonthlyReview>(), readRecording("finance_monthly_review.read"))

        assertEquals(ReferenceSalary(monthlyCents = 495000, source = "expected"), review.referenceSalary)
        assertTrue("consumedVsReference fell back to its default", review.consumedVsReference != ConsumedVsReference())
    }

    @Test
    fun `the finance dashboard score carries its reference salary under the names the API sends`() {
        val dashboard = json.decodeFromJsonElement(serializer<FinanceDashboard>(), readRecording("finance_dashboard.read"))

        assertEquals(ReferenceSalary(monthlyCents = 495000, source = "expected"), dashboard.score.referenceSalary)
    }

    private fun <T> assertCollectionContract(name: String, serializer: KSerializer<T>, dtoName: String) {
        val body = readRecording("$name.collection")
        val members = body["member"]?.jsonArray
            ?: throw AssertionError("The recording \"$name.collection\" has no \"member\" key. API Platform 4 collections use it; a client reading \"hydra:member\" gets nothing.")

        assertTrue("The recording \"$name.collection\" is empty, so it proves nothing.", members.isNotEmpty())

        // Deserialising through ApiCollection is what the app itself does, so
        // a change in the envelope fails here too.
        json.decodeFromJsonElement(ApiCollection.serializer(serializer), body)

        assertDeclaredFieldsExist(
            dtoName = dtoName,
            serializer = serializer,
            // Across all members: the API omits a null field, so a value that
            // appears on one member is enough to prove the name matches.
            emitted = members.flatMap { it.jsonObject.keys }.toSet(),
            source = "$name.collection",
        )
    }

    private fun keysOf(body: JsonObject, key: String): Set<String> =
        body[key]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet()

    private fun nestedKeys(body: JsonObject, key: String): Set<String> =
        body["operations"]!!.jsonArray
            .mapNotNull { it.jsonObject[key] as? JsonObject }
            .flatMap { it.keys }
            .toSet()
            .also { assertTrue("No recorded series carries \"$key\": the recording proves nothing about it.", it.isNotEmpty()) }

    private fun <T> assertItemContract(name: String, serializer: KSerializer<T>, dtoName: String) {
        val body = readRecording(name)

        json.decodeFromJsonElement(serializer, body)

        assertDeclaredFieldsExist(dtoName, serializer, body.keys, name)
    }

    private fun <T> assertDeclaredFieldsExist(
        dtoName: String,
        serializer: KSerializer<T>,
        emitted: Set<String>,
        source: String,
    ) {
        val descriptor = serializer.descriptor
        val accepted = fieldsTheApiDoesNotEmit[dtoName].orEmpty()

        val missing = (0 until descriptor.elementsCount)
            .map { descriptor.getElementName(it) }
            .filter { it !in emitted && it !in accepted }

        assertTrue(
            "$dtoName declares ${missing.joinToString(", ")}, which GET on $source never returns.\n" +
                "kotlinx.serialization fills the declared default instead, so the app shows a value the user never chose — no exception, no log.\n" +
                "Either the field is named differently on the wire (@SerialName fixes that), or nothing sends it and the DTO should not declare it.\n" +
                "The response carries: ${emitted.sorted().joinToString(", ")}",
            missing.isEmpty(),
        )
    }

    private fun readRecording(name: String): JsonObject {
        val file = File(repositoryRoot(), "api/contract/responses/$name.json")
        assertTrue(
            "The recording ${file.absolutePath} is missing. The API suite writes it: " +
                "UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract",
            file.isFile,
        )

        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "api/contract").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("No directory containing api/contract/ found above ${File("").absolutePath}")
    }
}
