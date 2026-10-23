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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // Only GET /api/finance/peer-debts (MAG-455) carries a créance's history; the API Platform resource
        // and the my-share / debt endpoints do not. The test of that recording asserts the field itself.
        "PeerDebt" to setOf("history"),
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
    fun `an account's timeline deserialises from the recorded response, its days, transfer, increase and late occurrence (MAG-422)`() {
        val body = readRecording("finance_account_timeline.read")
        val timeline = json.decodeFromJsonElement(serializer<AccountTimeline>(), body)
        val source = "finance_account_timeline.read"

        assertDeclaredFieldsExist("AccountTimeline", serializer<AccountTimeline>(), body.keys, source)
        assertDeclaredFieldsExist("TimelineAccount", serializer<TimelineAccount>(), body["account"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("TimelineDay", serializer<TimelineDay>(), keysOf(body, "days"), source)

        val lines = body["days"]!!.jsonArray.flatMap { it.jsonObject["transactions"]!!.jsonArray }.map { it.jsonObject }
        assertDeclaredFieldsExist("TimelineLine", serializer<TimelineLine>(), lines.flatMap { it.keys }.toSet(), source)
        assertDeclaredFieldsExist(
            "TimelineCounterpartAccount",
            serializer<TimelineCounterpartAccount>(),
            lines.mapNotNull { it["counterpartAccount"] as? JsonObject }.flatMap { it.keys }.toSet(),
            source,
        )
        assertDeclaredFieldsExist(
            "RecurringIncrease",
            serializer<RecurringIncrease>(),
            lines.mapNotNull { it["recurringIncrease"] as? JsonObject }.flatMap { it.keys }.toSet(),
            source,
        )
        val missedKeys = body["days"]!!.jsonArray.flatMap { it.jsonObject["missedOccurrences"]!!.jsonArray }.flatMap { it.jsonObject.keys }.toSet()
        assertDeclaredFieldsExist("TimelineMissedOccurrence", serializer<TimelineMissedOccurrence>(), missedKeys, source)

        assertTrue("The recording has no manual account: isManual proves nothing.", timeline.account.isManual)
        val all = timeline.days.flatMap { it.transactions }
        val transfer = all.single { it.transferKind == "internal" }
        assertEquals("Recorded Savings", transfer.counterpartAccount?.name)
        assertEquals("vers Recorded Savings", transfer.transferBadge())
        assertEquals("+33 %", all.mapNotNull { it.recurringIncrease }.single().label())
        val missed = timeline.days.flatMap { it.missedOccurrences }
        assertTrue("Salle de sport attendu le 20 — en retard" in missed.map { it.lateLabel() })
        assertTrue("planned" in all.map { it.status })
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
        assertEquals(46520, pile.totalCents)
        // Only the spends can be judged: the transfer received (MAG-450) cannot.
        assertEquals(listOf(false, false, true), pile.transactions.map { it.amountCents > 0 })
        assertTrue(pile.transactions.filter { it.amountCents < 0 }.all { it.judgeable })
        assertEquals(RETROSPECT_UNRATED, pile.transactions.first().retrospect)
        // MAG-450: only the transfer nobody claims asks for a contact, and each line names the label to link.
        assertEquals(listOf("VIR INST DE RECORDED TOM"), pile.transactions.filter { it.asksForContact }.map { it.label })
        assertTrue("counterpartyLabel fell back to its default", pile.transactions.all { it.counterpartyLabel.isNotEmpty() })
        // ContactRef (id, name) is not proved here: no recorded line has a contact yet.
        // MAG-453: every line says why it waits; reason and asksForPeerKind are proved by the declared fields above.
        assertTrue("reason fell back to its default", pile.transactions.all { it.reason.isNotEmpty() })
    }

    @Test
    fun `contacts deserialise from the recorded response`() =
        assertCollectionContract("contacts", serializer<Contact>(), "Contact")

    @Test
    fun `a contact's labels deserialise from the recorded response, with their count and last day`() {
        assertCollectionContract("contact_counterparties", serializer<ContactCounterparty>(), "ContactCounterparty")
        val counterparty = json.decodeFromJsonElement(
            ApiCollection.serializer(serializer<ContactCounterparty>()),
            readRecording("contact_counterparties.collection"),
        ).member.first()

        assertEquals(1, counterparty.transactionCount)
        assertEquals("2026-03-17", counterparty.lastSeenOn)
        assertTrue(counterparty.contact.startsWith("/api/contacts/"))
    }

    @Test
    fun `a contact's counterparty suggestions deserialise from the recorded response`() {
        // A plain JSON array, not a Hydra collection.
        val file = File(repositoryRoot(), "api/contract/responses/finance_contact_counterparty_suggestions.read.json")
        assertTrue("The recording ${file.absolutePath} is missing. The API suite writes it: " +
            "UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract", file.isFile)
        val members = Json.parseToJsonElement(file.readText()).jsonArray
        assertTrue("The recording finance_contact_counterparty_suggestions.read is empty, so it proves nothing.", members.isNotEmpty())

        val suggestions = json.decodeFromJsonElement(ListSerializer(serializer<CounterpartySuggestion>()), members)

        assertDeclaredFieldsExist(
            "CounterpartySuggestion",
            serializer<CounterpartySuggestion>(),
            members.flatMap { it.jsonObject.keys }.toSet(),
            "finance_contact_counterparty_suggestions.read",
        )
        assertTrue("transactionCount fell back to its default", suggestions.all { it.transactionCount > 0 })
    }

    @Test
    fun `the créances deserialise from the recorded response, with each person's share`() {
        assertCollectionContract("peer_debts", serializer<PeerDebt>(), "PeerDebt")
        val body = readRecording("peer_debts.collection")
        val shareKeys = body["member"]!!.jsonArray.flatMap { keysOf(it.jsonObject, "shares") }.toSet()
        assertDeclaredFieldsExist("PeerDebtShare", serializer<PeerDebtShare>(), shareKeys, "peer_debts.collection")

        val debt = json.decodeFromJsonElement(ApiCollection.serializer(serializer<PeerDebt>()), body).member.first()
        assertEquals(PeerDebt.DIRECTION_OWED_TO_ME, debt.direction)
        assertTrue(debt.isOpen)
        assertEquals(5000, debt.shares.single().remainingCents)
        assertEquals("Recorded Tom", debt.shares.single().contactName)
    }

    @Test
    fun `my part of a shared spend deserialises from the recorded response, with its créance`() {
        assertItemContract("finance_transaction_my_share.read", serializer<MyShare>(), "MyShare")
        val body = readRecording("finance_transaction_my_share.read")
        val debtBody = body["peerDebt"]!!.jsonObject
        assertDeclaredFieldsExist("PeerDebt", serializer<PeerDebt>(), debtBody.keys, "finance_transaction_my_share.read")
        assertDeclaredFieldsExist("PeerDebtShare", serializer<PeerDebtShare>(), keysOf(debtBody, "shares"), "finance_transaction_my_share.read")

        val share = json.decodeFromJsonElement(serializer<MyShare>(), body)
        assertEquals(2900, share.myShareCents)
        assertTrue("canBeShared fell back to its default", share.canBeShared)
        assertEquals("Recorded ticket", share.peerDebt?.`object`)
        assertEquals(6000, share.peerDebt?.amountCents)
    }

    @Test
    fun `a received transfer's dette deserialises from the recorded response`() {
        assertItemContract("finance_transaction_debt.read", serializer<TransactionDebt>(), "TransactionDebt")
        val body = readRecording("finance_transaction_debt.read")
        val debtBody = body["peerDebt"]!!.jsonObject
        assertDeclaredFieldsExist("PeerDebt", serializer<PeerDebt>(), debtBody.keys, "finance_transaction_debt.read")
        assertDeclaredFieldsExist("PeerDebtShare", serializer<PeerDebtShare>(), keysOf(debtBody, "shares"), "finance_transaction_debt.read")

        val debt = json.decodeFromJsonElement(serializer<TransactionDebt>(), body)
        assertTrue("canBeDebt fell back to its default", debt.canBeDebt)
        assertEquals(40000, debt.amountCents)
        assertEquals(PeerDebt.DIRECTION_I_OWE, debt.peerDebt?.direction)
        assertEquals(28000, debt.peerDebt?.amountCents)
        assertEquals(debt.suggestedContactId, debt.peerDebt?.shares?.single()?.contactId)
    }

    @Test
    fun `a transfer's allocation deserialises from the recorded response, with what it covers and what it may (MAG-453)`() {
        val name = "finance_transaction_allocation.read"
        assertItemContract(name, serializer<TransactionAllocation>(), "TransactionAllocation")
        val body = readRecording(name)
        assertDeclaredFieldsExist("PeerAllocationCandidate", serializer<PeerAllocationCandidate>(), keysOf(body, "candidates"), name)
        assertDeclaredFieldsExist("PeerAllocationLine", serializer<PeerAllocationLine>(), keysOf(body, "allocations"), name)
        assertDeclaredFieldsExist("ContactRef", serializer<ContactRef>(), body["contact"]!!.jsonObject.keys, name)

        val allocation = json.decodeFromJsonElement(serializer<TransactionAllocation>(), body)
        assertTrue("canBeAllocated fell back to its default", allocation.canBeAllocated)
        assertTrue("no recorded candidate: the créances it may cover are not proved", allocation.candidates.isNotEmpty())
        assertTrue("no recorded allocation: what it covers is not proved", allocation.allocations.isNotEmpty())
        assertTrue("availableCents fell back to its default", allocation.candidates.all { it.availableCents > 0 })
        if (allocation.direction == PeerKind.INCOMING) {
            assertEquals(kotlin.math.abs(allocation.amountCents), allocation.allocatedCents + allocation.unallocatedCents)
        }
    }

    @Test
    fun `the créances' summary deserialises from the recorded response`() {
        assertItemContract("finance_peer_debts_summary.read", serializer<PeerDebtSummary>(), "PeerDebtSummary")
        val summary = json.decodeFromJsonElement(serializer<PeerDebtSummary>(), readRecording("finance_peer_debts_summary.read"))

        assertEquals(5000, summary.owedToMeCents)
    }

    @Test
    fun `the summary carries each person's net and whether the two directions compensate (MAG-455)`() {
        val name = "finance_peer_debts_summary.read"
        val body = readRecording(name)
        assertDeclaredFieldsExist("ContactBalance", serializer<ContactBalance>(), keysOf(body, "byContact"), name)

        val balance = json.decodeFromJsonElement(serializer<PeerDebtSummary>(), body).byContact.single()
        assertEquals(5000, balance.netCents)
        assertEquals("Recorded Tom", balance.name)
    }

    @Test
    fun `the créances and dettes list deserialises from the recorded response, with shares and history (MAG-455)`() {
        val name = "finance_peer_debts.read"
        val body = readRecording(name)
        assertItemContract(name, serializer<PeerDebtList>(), "PeerDebtList")
        val debtKeys = keysOf(body, "peerDebts")
        assertTrue("the recording carries no history", "history" in debtKeys)
        assertDeclaredFieldsExist("PeerDebt", serializer<PeerDebt>(), debtKeys, name)
        val historyKeys = body["peerDebts"]!!.jsonArray.flatMap { keysOf(it.jsonObject, "history") }.toSet()
        assertDeclaredFieldsExist("PeerDebtHistoryEntry", serializer<PeerDebtHistoryEntry>(), historyKeys, name)

        val debt = json.decodeFromJsonElement(serializer<PeerDebtList>(), body).peerDebts.single()
        assertEquals(1000, debt.history.single().amountCents)
        assertEquals("Recorded Tom", debt.history.single().contactName)
    }

    @Test
    fun `a shared spend carries my part in the transactions list`() {
        val transactions = json.decodeFromJsonElement(
            ApiCollection.serializer(serializer<Transaction>()),
            readRecording("transactions.collection"),
        ).member

        assertTrue("no recorded line carries myShareCents", transactions.any { it.myShareCents == 2900 })
    }

    @Test
    fun `the review queue deserialises from the recorded response`() {
        val name = "finance_review_queue.read"
        val body = readRecording(name)

        val queue = json.decodeFromJsonElement(serializer<ReviewQueue>(), body)

        assertDeclaredFieldsExist("ReviewQueue", serializer<ReviewQueue>(), body.keys, name)
        val items = body["items"]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet()
        assertDeclaredFieldsExist("ReviewQueueItem", serializer<ReviewQueueItem>(), items, name)

        assertEquals("2026-03", queue.month)
        assertEquals(queue.unrecognisedCount + queue.recognisedCount, queue.items.size)
        assertEquals(listOf(false, false, false, true), queue.items.map { it.recognised })
        assertEquals("Recorded Food › Recorded Courses", queue.items[2].suggestion?.categoryPath)
        assertEquals("Recorded Food › Recorded Courses", queue.items[3].categoryPath)
        assertEquals("rule", queue.items[3].qualifiedBy)
    }

    @Test
    fun `the review queue carries the period's counts and what the fiche asks for under the names the API sends (MAG-539)`() {
        val queue = json.decodeFromJsonElement(serializer<ReviewQueue>(), readRecording("finance_review_queue.read"))

        assertEquals(3, queue.unrecognisedCount)
        assertEquals(46520, queue.unrecognisedCents)
        assertEquals(1, queue.recognisedCount)
        assertEquals(4, queue.total)
        assertNull(queue.period)
        assertNull(queue.sinceReviewDate)
        // The flags are spelt `asksFor…` on the wire: a client DTO that spelt them otherwise would read its default.
        assertEquals(listOf(true, false, false, false), queue.items.map { it.asksForContact })
        assertEquals("VIR INST DE RECORDED TOM", queue.items[0].counterpartyLabel)
        assertEquals(TO_SORT_REASON_CATEGORY, queue.items[0].reason)
        assertEquals(listOf(false, true, true, true), queue.items.map { it.judgeable })
    }

    @Test
    fun `the review progress deserialises from the recorded response`() {
        val name = "finance_review_progress.read"
        val body = readRecording(name)

        val progress = json.decodeFromJsonElement(serializer<ReviewProgress>(), body)

        assertDeclaredFieldsExist("ReviewProgress", serializer<ReviewProgress>(), body.keys, name)
        assertEquals("2026-03", progress.month)
        assertEquals(REVIEW_STEP_BALANCE, progress.step)
        assertEquals(ReviewStep.BALANCE, ReviewStep.of(progress.step))
        assertEquals(listOf("01ARZ3NDEKTSV4RRFFQ69G5FAV"), progress.handledTransactionIds)
        assertNotNull(progress.updatedAt)
    }

    @Test
    fun `the review progress of a quarter's end carries the period closed and the next one`() {
        val name = "finance_review_progress.read"
        val body = readRecording(name)

        val progress = json.decodeFromJsonElement(serializer<ReviewProgress>(), body)

        assertDeclaredFieldsExist("PlanningPeriodRef", serializer<PlanningPeriodRef>(), body["period"]!!.jsonObject.keys, name)
        assertDeclaredFieldsExist("PlanningPeriodRef", serializer<PlanningPeriodRef>(), body["nextPeriod"]!!.jsonObject.keys, name)
        assertEquals(REVIEW_LEVEL_QUARTER, progress.level)
        assertEquals("2026-Q1", progress.period?.key)
        assertEquals("2026-Q2", progress.nextPeriod?.key)
        assertEquals(3, progress.nextPeriod?.months)
    }

    @Test
    fun `the bilan of a period deserialises from the recorded response`() {
        val name = "finance_period_balance.read"
        val body = readRecording(name)

        val balance = json.decodeFromJsonElement(serializer<PeriodBalance>(), body)

        assertDeclaredFieldsExist("PeriodBalance", serializer<PeriodBalance>(), body.keys, name)
        assertDeclaredFieldsExist("PeriodMonth", serializer<PeriodMonth>(), body["months"]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet(), name)
        assertDeclaredFieldsExist("PeriodTotals", serializer<PeriodTotals>(), body["totals"]!!.jsonObject.keys, name)
        assertDeclaredFieldsExist("PeriodEnvelope", serializer<PeriodEnvelope>(), body["envelopes"]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet(), name)
        assertDeclaredFieldsExist("PeriodProjects", serializer<PeriodProjects>(), body["projects"]!!.jsonObject.keys, name)

        assertEquals("2026-Q1", balance.period.key)
        assertEquals(listOf("2026-01", "2026-02", "2026-03"), balance.months.map { it.month })
        assertEquals(balance.months.sumOf { it.spentCents }, balance.totals.spentCents)
        assertEquals("over", balance.envelopes.single().status)
        assertEquals(1_485_000, balance.savings.plannedCents)
        assertNull(balance.satisfaction.keptPercent)
    }

    @Test
    fun `the plan of a period deserialises from the recorded response`() {
        val name = "finance_period_plan.read"
        val body = readRecording(name)

        val plan = json.decodeFromJsonElement(serializer<PeriodPlan>(), body)

        assertDeclaredFieldsExist("PeriodPlan", serializer<PeriodPlan>(), body.keys, name)
        val categories = body["categories"]!!.jsonArray.map { it.jsonObject }
        assertTrue("The recording \"$name\" has no category, so it proves nothing.", categories.isNotEmpty())
        assertDeclaredFieldsExist("PlanCategory", serializer<PlanCategory>(), categories.flatMap { it.keys }.toSet(), name)
        assertDeclaredFieldsExist("PlanLastPeriod", serializer<PlanLastPeriod>(), categories.flatMap { it["lastYear"]!!.jsonObject.keys }.toSet(), name)

        assertEquals("2026-Q2", plan.period.key)
        assertEquals("2026-Q1", plan.sourcePeriod.key)
        assertEquals(categories.size, plan.categories.size)
    }

    @Test
    fun `projects deserialise from the recorded response (MAG-519)`() =
        assertCollectionContract("finance_projects", serializer<Project>(), "Project")

    @Test
    fun `the project planning deserialises from the recorded response, with the investment's rent and resale (MAG-557)`() {
        val name = "finance_project_planning.read"
        val body = readRecording(name)

        val planning = json.decodeFromJsonElement(serializer<ProjectPlanning>(), body)

        assertDeclaredFieldsExist("ProjectPlanning", serializer<ProjectPlanning>(), body.keys, name)
        assertDeclaredFieldsExist("PlanningMonth", serializer<PlanningMonth>(), keysOf(body, "months"), name)
        assertDeclaredFieldsExist("PlannedProject", serializer<PlannedProject>(), keysOf(body, "projects"), name)
        assertDeclaredFieldsExist("PlannedCushion", serializer<PlannedCushion>(), body["cushion"]!!.jsonObject.keys, name)
        val projects = body["projects"]!!.jsonArray.map { it.jsonObject }
        assertDeclaredFieldsExist(
            "PlannedLoan",
            serializer<PlannedLoan>(),
            projects.mapNotNull { it["loan"] as? JsonObject }.flatMap { it.keys }.toSet(),
            name,
        )
        val investments = projects.mapNotNull { it["investment"] as? JsonObject }
        assertTrue("The recording \"$name\" has no investment, so it proves nothing.", investments.isNotEmpty())
        assertDeclaredFieldsExist("PlannedInvestment", serializer<PlannedInvestment>(), investments.flatMap { it.keys }.toSet(), name)

        val box = planning.projects.first { it.investment != null }
        assertEquals(16000, box.investment?.monthlyRentCents)
        assertEquals("2027-11", box.investment?.rentFromMonth)
        assertEquals("2028-06", box.investment?.resaleMonth)
        assertEquals(36, planning.months.size)
    }

    @Test
    fun `the OpenAPI document sends an investment and a resale source under the names the app reads (MAG-557)`() {
        val schemas = (Json.parseToJsonElement(File(repositoryRoot(), "api/contract/openapi.json").readText()) as JsonObject)["components"]!!
            .jsonObject["schemas"]!!.jsonObject
        fun properties(schema: String): Set<String> = schemas[schema]?.jsonObject?.get("properties")?.jsonObject?.keys
            ?: throw AssertionError("The OpenAPI contract has no schema \"$schema\".")

        val investment = properties("ProjectInvestment_noid")
        assertDeclaredFieldsExist("ProjectInvestment", serializer<ProjectInvestment>(), investment, "the OpenAPI schema ProjectInvestment")
        assertDeclaredFieldsExist("ProjectFundingSource", serializer<ProjectFundingSource>(), properties("ProjectFundingSource_noid"), "the OpenAPI schema ProjectFundingSource")
        assertTrue("investment" in properties("Project"))
    }

    @Test
    fun `placements deserialise from the recorded response (MAG-555)`() =
        assertCollectionContract("finance_placements", serializer<Placement>(), "Placement")

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
    fun `the envelope adjustments deserialise from the recorded response, with a suggestion and its season`() {
        val body = readRecording("finance_envelope_adjustments.read")
        val source = "finance_envelope_adjustments.read"

        val adjustments = json.decodeFromJsonElement(serializer<EnvelopeAdjustments>(), body)

        val suggestions = body["envelopes"]!!.jsonArray.mapNotNull { it.jsonObject["suggestion"] as? JsonObject }
        assertDeclaredFieldsExist("EnvelopeAdjustments", serializer<EnvelopeAdjustments>(), body.keys, source)
        assertDeclaredFieldsExist("EnvelopeAdjustment", serializer<EnvelopeAdjustment>(), keysOf(body, "envelopes"), source)
        assertDeclaredFieldsExist("AdjustmentSuggestion", serializer<AdjustmentSuggestion>(), suggestions.flatMap { it.keys }.toSet(), source)
        assertDeclaredFieldsExist(
            "AdjustmentSeason", serializer<AdjustmentSeason>(),
            suggestions.mapNotNull { it["seasonal"] as? JsonObject }.flatMap { it.keys }.toSet(), source,
        )
        val suggested = adjustments.envelopes.filter { it.suggestion != null }
        assertEquals(adjustments.suggestionCount, suggested.size)
        assertTrue("reason fell back to its default", suggested.all { it.suggestion?.reason.orEmpty().isNotEmpty() })
        assertTrue("No recorded suggestion is seasonal.", suggested.any { it.suggestion?.seasonal != null })
    }

    @Test
    fun `the upcoming projection deserialises from the recorded response, both windows and the envelopes of the month`() {
        val body = readRecording("finance_upcoming.read")
        val source = "finance_upcoming.read"

        val upcoming = json.decodeFromJsonElement(serializer<UpcomingProjection>(), body)

        val week = body["week"]!!.jsonObject
        val month = body["month"]!!.jsonObject
        assertDeclaredFieldsExist("UpcomingProjection", serializer<UpcomingProjection>(), body.keys, source)
        assertDeclaredFieldsExist("UpcomingWeek", serializer<UpcomingWeek>(), week.keys, source)
        assertDeclaredFieldsExist("UpcomingMonth", serializer<UpcomingMonth>(), month.keys, source)
        assertDeclaredFieldsExist(
            "UpcomingItem", serializer<UpcomingItem>(),
            (keysOf(week, "incomes") + keysOf(week, "expenses") + keysOf(month, "incomes") + keysOf(month, "expenses")), source,
        )
        assertDeclaredFieldsExist("UpcomingDay", serializer<UpcomingDay>(), keysOf(month, "days"), source)
        assertDeclaredFieldsExist("UpcomingEnvelope", serializer<UpcomingEnvelope>(), keysOf(month, "envelopes"), source)
        assertTrue("The recording has no week income, so it proves nothing.", upcoming.week.incomes.isNotEmpty())
        assertTrue("The recording has no envelope, so it proves nothing.", upcoming.month.envelopes.isNotEmpty())
        assertTrue("isOvercommitted fell back to its default", upcoming.month.envelopes.any { it.isOvercommitted })
        assertEquals(upcoming.month.incomeCents - upcoming.month.projectedExpenseCents, upcoming.month.remainingCents)
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
    fun `the reward switches and the tone come with the task settings and their defaults (MAG-508)`() {
        val settings = json.decodeFromJsonElement(serializer<TaskSettings>(), readRecording("task_settings.me"))

        assertTrue(settings.rewardAnimation && settings.rewardVibration && settings.rewardSound)
        assertEquals(TaskSettings.TONE_DOUX, settings.tone)
        assertEquals(settings.values, settings.defaults)
    }

    @Test
    fun `the reminders' cap, quiet hours and moments come with the task settings and their defaults (MAG-510)`() {
        val settings = json.decodeFromJsonElement(serializer<TaskSettings>(), readRecording("task_settings.me"))

        assertEquals(6, settings.reminderDailyCap)
        assertEquals("21:00", settings.quietStart)
        assertEquals("08:00", settings.quietEnd)
        assertEquals(
            mapOf(
                TaskSettings.MOMENT_WAKE to "07:30",
                TaskSettings.MOMENT_AFTER_BREAKFAST to "08:30",
                TaskSettings.MOMENT_AFTER_LUNCH to "13:30",
                TaskSettings.MOMENT_AFTER_DINNER to "20:00",
            ),
            settings.dayMoments,
        )
        assertEquals(settings.values, settings.defaults)
    }

    @Test
    fun `a reminder's answer deserialises from the recorded response under the names the API sends (MAG-510)`() {
        assertItemContract("task_reminders.action", serializer<TaskReminderAnswer>(), "TaskReminderAnswer")
        val body = readRecording("task_reminders.action")
        assertDeclaredFieldsExist("TaskReminder", serializer<TaskReminder>(), body["reminder"]!!.jsonObject.keys, "task_reminders.action")
        assertDeclaredFieldsExist("Reward", serializer<Reward>(), body["reward"]!!.jsonObject.keys, "task_reminders.action")
        assertDeclaredFieldsExist(
            "TaskReminderNotification",
            serializer<TaskReminderNotification>(),
            body["notification"]!!.jsonObject.keys,
            "task_reminders.action",
        )

        val answer = json.decodeFromJsonElement(serializer<TaskReminderAnswer>(), body)
        assertEquals("done", answer.reminder.action)
        assertEquals("due", answer.reminder.kind)
        assertNull(answer.next)
        assertTrue(answer.reward!!.message.isNotBlank())
        assertEquals(answer.reward!!.message, answer.notification!!.title)
        assertTrue(answer.notification!!.actions.isEmpty())
    }

    @Test
    fun `a reward deserialises from the recorded response under the names the API sends (MAG-508)`() {
        assertItemContract("rewards.read", serializer<Reward>(), "Reward")
        val reward = json.decodeFromJsonElement(serializer<Reward>(), readRecording("rewards.read"))

        assertEquals(Reward.SUBJECT_TASK, reward.subjectType)
        assertEquals("waited", reward.surprise)
        assertTrue(reward.isShootingStar)
        assertTrue(reward.star)
        assertTrue(reward.message.isNotBlank())
    }

    @Test
    fun `a palier deserialises from the recorded response, gold and with no star in the month (MAG-509)`() {
        assertItemContract("rewards.palier", serializer<Reward>(), "Reward")
        val reward = json.decodeFromJsonElement(serializer<Reward>(), readRecording("rewards.palier"))

        assertTrue(reward.isPalier)
        assertFalse(reward.star)
        assertNull(reward.surprise)
        assertTrue(!reward.title.isNullOrBlank())
        assertTrue(reward.message.isNotBlank())
    }

    @Test
    fun `a standard reward carries no title (MAG-509)`() {
        val reward = json.decodeFromJsonElement(serializer<Reward>(), readRecording("rewards.read"))

        assertNull(reward.title)
        assertFalse(reward.isPalier)
    }

    @Test
    fun `the constellation of a month deserialises from the recorded response (MAG-509)`() {
        assertItemContract("stars.read", serializer<Stars>(), "Stars")
        val stars = json.decodeFromJsonElement(serializer<Stars>(), readRecording("stars.read"))

        assertTrue(stars.month.matches(Regex("""\d{4}-\d{2}""")))
        assertTrue(stars.total >= stars.count)
        assertEquals(minOf(stars.count, Stars.MAX_DRAWN), stars.drawn)
        assertEquals("Ta constellation ${Stars.monthOf(stars.month)} · ${Stars.starsLabelOf(stars.count)}", stars.title)
    }

    @Test
    fun `routines deserialise from the recorded response under the names the API sends (MAG-511)`() {
        assertCollectionContract("routines", serializer<Routine>(), "Routine")
        val body = readRecording("routines.collection")
        val members = body["member"]!!.jsonArray.map { it.jsonObject }
        assertDeclaredFieldsExist(
            "RoutineRecurrence",
            serializer<RoutineRecurrence>(),
            members.flatMap { it["recurrence"]!!.jsonObject.keys }.toSet(),
            "routines.collection",
        )
        assertDeclaredFieldsExist(
            "RoutineDay",
            serializer<RoutineDay>(),
            members.flatMap { member -> member["recentDays"]!!.jsonArray.flatMap { it.jsonObject.keys } }.toSet(),
            "routines.collection",
        )

        val routines = json.decodeFromJsonElement(ApiCollection.serializer(serializer<Routine>()), body).member
        val weekly = routines.first { it.recurrence.kind == RoutineRecurrence.WEEKLY }
        assertTrue(weekly.recurrence.weekday in 1..7)
        assertEquals(7, weekly.recentDays.size)
        assertTrue("No recorded routine is ticked today, so checkedToday proves nothing.", routines.any { it.checkedToday })
        assertTrue("No recorded routine is due today, so dueToday proves nothing.", routines.any { it.dueToday })
        assertTrue(routines.any { it.anchor == Routine.ANCHOR_TIME && it.anchorTime == it.time })
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
    fun `the finance dashboard carries its scores and its three tiles under the names the API sends`() {
        val body = readRecording("finance_dashboard.read")
        val source = "finance_dashboard.read"

        val dashboard = json.decodeFromJsonElement(serializer<FinanceDashboard>(), body)

        val tiles = body["tiles"]!!.jsonObject
        val accounts = tiles["accounts"]!!.jsonObject
        val budget = tiles["budget"]!!.jsonObject
        val planning = tiles["planning"]!!.jsonObject
        assertDeclaredFieldsExist("FinanceDashboard", serializer<FinanceDashboard>(), body.keys, source)
        assertDeclaredFieldsExist("FinanceTiles", serializer<FinanceTiles>(), tiles.keys, source)
        assertDeclaredFieldsExist("AccountsTile", serializer<AccountsTile>(), accounts.keys, source)
        assertDeclaredFieldsExist("TileAccount", serializer<TileAccount>(), keysOf(accounts, "accounts"), source)
        assertDeclaredFieldsExist("BudgetTile", serializer<BudgetTile>(), budget.keys, source)
        assertDeclaredFieldsExist("TileEnvelope", serializer<TileEnvelope>(), keysOf(budget, "envelopes"), source)
        assertDeclaredFieldsExist("TileCushion", serializer<TileCushion>(), budget["cushion"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("ExpectedSalary", serializer<ExpectedSalary>(), budget["expectedSalary"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("PlanningTile", serializer<PlanningTile>(), planning.keys, source)
        assertDeclaredFieldsExist(
            "IndependenceCounter",
            serializer<IndependenceCounter>(),
            planning["independence"]!!.jsonObject.keys,
            source,
        )
        // The recording's curve is empty (no train de vie measured), so CoveragePoint's own fields
        // are checked by IndependenceProjectionTest against a payload that carries points.
        assertDeclaredFieldsExist(
            "IndependenceProjection",
            serializer<IndependenceProjection>(),
            planning["independence"]!!.jsonObject["projection"]!!.jsonObject.keys,
            source,
        )
        assertDeclaredFieldsExist(
            "SavingCapacity",
            serializer<SavingCapacity>(),
            planning["savingCapacity"]!!.jsonObject.keys,
            source,
        )
        assertDeclaredFieldsExist("FinanceScores", serializer<FinanceScores>(), body["scores"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("FinanceTodo", serializer<FinanceTodo>(), body["todo"]!!.jsonObject.keys, source)

        assertEquals(ExpectedSalary(monthlyCents = 495000, source = "expected"), dashboard.tiles.budget.expectedSalary)
        // Null on the wire means « unknown », and stays null: never a 0 € balance.
        assertNull(dashboard.tiles.budget.balanceBeforeSalary)
        // The projection is read as sent: a start month, no N, no curve, four steps not reached.
        val projection = dashboard.tiles.planning.independence.projection
        assertTrue(projection.isKnown)
        assertNull(projection.nDate)
        assertTrue(projection.curve.isEmpty())
        assertEquals(setOf("25", "50", "75", "100"), projection.milestones.keys)
        assertTrue(projection.milestones.values.all { it == null })
        assertTrue(dashboard.tiles.accounts.accounts.single().isCushion)
        assertTrue(dashboard.tiles.budget.envelopes.single().isOverspent)
        assertNotNull(dashboard.scores)
    }

    @Test
    fun `the month balance carries its parts under the names the API sends`() {
        val body = readRecording("finance_month_balance.read")
        val source = "finance_month_balance.read"

        val balance = json.decodeFromJsonElement(serializer<MonthBalance>(), body)

        assertDeclaredFieldsExist("MonthBalance", serializer<MonthBalance>(), body.keys, source)
        assertDeclaredFieldsExist("PeriodScore", serializer<PeriodScore>(), body["spending"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("BalanceEnvelope", serializer<BalanceEnvelope>(), keysOf(body, "envelopes"), source)
        assertDeclaredFieldsExist("BalanceSatisfaction", serializer<BalanceSatisfaction>(), body["satisfaction"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("BalanceSavings", serializer<BalanceSavings>(), body["savings"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("TileCushion", serializer<TileCushion>(), body["cushion"]!!.jsonObject.keys, source)
        assertDeclaredFieldsExist("BalanceToQualify", serializer<BalanceToQualify>(), body["toQualify"]!!.jsonObject.keys, source)

        assertEquals("over", balance.envelopes.single().status)
        // Nothing judged is null on the wire, and stays null: never a 0 %.
        assertNull(balance.satisfaction.keptPercent)
        assertEquals(495000, balance.savings.plannedCents)
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
