package com.maggie.app.data.api

import com.maggie.app.data.model.ONE_OFF_MODE
import com.maggie.app.data.model.Season
import com.maggie.app.ui.screens.finance.eurosText
import com.maggie.app.ui.screens.finance.parseEuros
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the tablet's editor sends back (MAG-419): nulls are spelled out, annual has no month and no seasons. */
class EnvelopeUpdateRequestTest {

    @Test
    fun `a monthly envelope writes its month, seasons and threshold`() {
        val json = EnvelopeUpdateRequest(
            amountCents = 2000, mode = "monthly", month = 7,
            seasonalAmounts = listOf(Season(11, 2, 4500)), alertThresholdPercent = 90,
        ).toJson()

        assertEquals(JsonPrimitive(2000), json["amountCents"])
        assertEquals(JsonPrimitive("monthly"), json["mode"])
        assertEquals(JsonPrimitive(7), json["month"])
        assertEquals(JsonPrimitive(90), json["alertThresholdPercent"])
        assertEquals(
            buildJsonArray {
                add(buildJsonObject { put("fromMonth", 11); put("toMonth", 2); put("amountCents", 4500) })
            },
            json["seasonalAmounts"],
        )
    }

    @Test
    fun `an annual envelope sends no month and no seasons, and a blank threshold is the default`() {
        val json = EnvelopeUpdateRequest(amountCents = 120000, mode = "annual").toJson()

        assertEquals(JsonNull, json["month"])
        assertEquals(JsonNull, json["alertThresholdPercent"])
        assertEquals(buildJsonArray { }, json["seasonalAmounts"])
    }

    @Test
    fun `a one-off envelope writes its name and amount only`() {
        val json = EnvelopeUpdateRequest(amountCents = 40000, mode = ONE_OFF_MODE, name = "Hellfest 2026").toJson()

        assertEquals(setOf("amountCents", "name"), json.keys)
        assertEquals(JsonPrimitive("Hellfest 2026"), json["name"])
    }

    @Test
    fun `euros typed in a field are cents, a comma or a point`() {
        assertEquals(10000, parseEuros("100"))
        assertEquals(4550, parseEuros(" 45,5 "))
        assertEquals(1230, parseEuros("12.30"))
        assertNull(parseEuros(""))
        assertNull(parseEuros("-3"))
        assertNull(parseEuros("abc"))
    }

    @Test
    fun `cents are shown as a field would take them back`() {
        assertEquals("400", eurosText(40000))
        assertEquals("45.50", eurosText(4550))
        assertEquals("0.05", eurosText(5))
        assertEquals(4550, parseEuros(eurosText(4550)))
    }
}
