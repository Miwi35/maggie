package com.maggie.app.ui.screens.digest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Weather
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Météo card of the Digest (MAG-710): plain, a word per condition, the quantity from 1 mm / 1 cm, the alert in amber. */
@RunWith(AndroidJUnit4::class)
class WeatherCardScreenTest {
    @get:Rule
    val compose = ScreenRule()

    private fun draw(weather: Weather) = compose.setContent { WeatherCard(weather) }

    @Test
    fun `the figures and the word of the weather are read`() {
        draw(Weather(minC = 5.0, maxC = 12.0, rainProbability = 20.0, windKmh = 15.0, condition = "partly_cloudy"))

        compose.onNodeWithText("● Météo").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_MAX).assertTextEquals("12°")
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_MIN).assertTextEquals("min 5°")
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_RAIN).assertTextEquals("pluie 20 %")
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_WIND).assertTextEquals("vent 15 km/h")
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_CONDITION).assertTextEquals("Éclaircies")
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_QUANTITY).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_ALERT).assertDoesNotExist()
    }

    @Test
    fun `rain from 1 mm and snow from 1 cm are said, under that nothing`() {
        draw(Weather(maxC = 8.0, rainMm = 4.2, condition = "rain"))
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_QUANTITY).assertTextEquals("4 mm de pluie")
    }

    @Test
    fun `a little rain is not said`() {
        draw(Weather(maxC = 8.0, rainMm = 0.6, condition = "drizzle"))
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_QUANTITY).assertDoesNotExist()
    }

    @Test
    fun `snow is said in centimetres`() {
        draw(Weather(maxC = 0.0, snowCm = 3.4, rainMm = 5.0, condition = "snow"))
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_QUANTITY).assertTextEquals("3 cm de neige")
    }

    @Test
    fun `the alert is shown`() {
        draw(Weather(maxC = 20.0, alerts = listOf("orage"), condition = "storm"))
        compose.onNodeWithTag(UiTags.DIGEST_WEATHER_ALERT).assertTextEquals("Orage")
    }

    @Test
    fun `every condition has its word`() {
        val words = mapOf(
            "clear" to "Ensoleillé", "partly_cloudy" to "Éclaircies", "cloudy" to "Nuageux", "fog" to "Brouillard",
            "drizzle" to "Bruine", "rain" to "Pluie", "showers" to "Averses", "snow" to "Neige", "storm" to "Orages",
        )
        words.forEach { (condition, word) -> org.junit.Assert.assertEquals(word, Weather(condition = condition).conditionLabel) }
        org.junit.Assert.assertNull(Weather(condition = "unknown").conditionLabel)
        org.junit.Assert.assertNull(Weather().conditionLabel)
    }
}
