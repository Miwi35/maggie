package com.maggie.app.data.model

import com.maggie.app.ui.screens.finance.TimelineFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 20-year frise's DTOs against the answer as `FinanceTimelineControllerTest` pins it (MAG-554).
 *
 * Not yet a `DtoContractTest` case: `api/contract/responses/` records no `finance_timeline.read` yet.
 * Until it does, this reads a body copied from the API's own test, and checks the one thing that fails
 * silently — a declared name that the API spells differently, which would leave the default in place.
 */
class FinanceTimelineTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the followed scenario's events read under the names the API sends`() {
        val timeline = TimelineFixtures.alone()

        assertEquals("2026-10", timeline.startMonth)
        assertEquals(240, timeline.horizonMonths)
        assertEquals("s-mine", timeline.current.scenarioId)
        assertEquals("2045-12", timeline.current.nDate)
        val purchase = timeline.current.events.single { it.type == "project_purchase" }
        assertEquals(2500.0, purchase.amountEur!!, 0.0)
        assertEquals(3, purchase.nImpactMonths)
        assertNull(purchase.monthlyImpactEur)
        val debt = timeline.current.events.single { it.type == "debt_end" }
        assertEquals(180.0, debt.monthlyImpactEur!!, 0.0)
        assertEquals(-2, debt.nImpactMonths)
        assertNull(debt.sublabel)
        assertNull(timeline.compared)
        assertNull(timeline.difference)
    }

    @Test
    fun `a compared scenario and the gap of N come with their own sentence`() {
        val timeline = TimelineFixtures.compared()

        assertEquals("auto-quality", timeline.compared?.scenarioId)
        assertEquals("2043-09", timeline.compared?.nDate)
        assertEquals(-27, timeline.difference?.months)
        assertEquals("N 2 ans 3 mois plus tôt que « Mon plan »", timeline.difference?.sentence)
    }

    @Test
    fun `without a current scenario the line has no id and N may not be reached`() {
        val body = """{"current":{"scenarioId":null,"name":"Planning sans scénario","nDate":null,"events":[]}}"""
        val timeline = json.decodeFromString(serializer<FinanceTimeline>(), body)

        assertNull(timeline.current.scenarioId)
        assertNull(timeline.current.nDate)
        assertTrue(timeline.current.events.isEmpty())
    }

    @Test
    fun `every declared field exists in the body the API writes`() {
        val body = json.parseToJsonElement(TimelineFixtures.COMPARED_JSON).jsonObject
        val current = body["current"]!!.jsonObject

        declaredNames(serializer<FinanceTimeline>()).assertIn(body.keys, "FinanceTimeline")
        declaredNames(serializer<FinanceTimelineLine>()).assertIn(current.keys, "FinanceTimelineLine")
        declaredNames(serializer<TimelineCategory>()).assertIn(keysOf(body, "categories"), "TimelineCategory")
        declaredNames(serializer<TimelineEvent>()).assertIn(
            current["events"]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet(),
            "TimelineEvent",
        )
        declaredNames(serializer<TimelineDifference>()).assertIn(body["difference"]!!.jsonObject.keys, "TimelineDifference")
    }

    private fun keysOf(body: JsonObject, key: String): Set<String> =
        body[key]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet()

    private fun declaredNames(serializer: kotlinx.serialization.KSerializer<*>): List<String> =
        (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }

    private fun List<String>.assertIn(sent: Set<String>, dto: String) {
        val missing = filterNot { it in sent }
        assertTrue("$dto declares $missing, which the API does not send under that name.", missing.isEmpty())
    }
}
