package com.maggie.app.data.model

import com.maggie.app.data.api.ApiCollection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
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
        // The API omits an empty note and the recorded world writes none (MAG-464).
        "Transaction" to setOf("note"),
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
    fun `proactions deserialise from the recorded response`() =
        assertCollectionContract("proactions", serializer<Proaction>(), "Proaction")

    @Test
    fun `meals deserialise from the recorded response`() =
        assertCollectionContract("meals", serializer<Meal>(), "Meal")

    @Test
    fun `recipes deserialise from the recorded response`() =
        assertCollectionContract("recipes", serializer<Recipe>(), "Recipe")

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
    fun `grocery lists deserialise from the recorded response`() =
        assertCollectionContract("grocery_lists", serializer<GroceryList>(), "GroceryList")

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
    fun `the current user deserialises from the recorded response`() =
        assertItemContract("users.me", serializer<User>(), "User")

    @Test
    fun `user preferences deserialise from the recorded response`() =
        assertItemContract("user_preferences.me", serializer<UserPreference>(), "UserPreference")

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
