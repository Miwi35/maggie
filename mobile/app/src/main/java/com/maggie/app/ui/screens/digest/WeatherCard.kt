package com.maggie.app.ui.screens.digest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Weather
import com.maggie.app.ui.UiTags
import kotlin.math.roundToInt

/**
 * The Météo card of the Digest (MAG-710), kept plain: the max in big and the min under it, the word for the weather
 * between two rules with the quantity and the alert, the chance of rain and the wind aligned right. No illustration.
 */
@Composable
fun WeatherCard(weather: Weather, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = modifier.fillMaxWidth().testTag(UiTags.DIGEST_WEATHER),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("● Météo", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    weather.degrees(weather.maxC)?.let {
                        Text(it, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag(UiTags.DIGEST_WEATHER_MAX))
                    }
                    weather.degrees(weather.minC)?.let {
                        Text("min $it", style = MaterialTheme.typography.bodyMedium, color = dark, modifier = Modifier.testTag(UiTags.DIGEST_WEATHER_MIN))
                    }
                }
                VerticalDivider(modifier = Modifier.padding(horizontal = 12.dp).fillMaxHeight())
                Column(modifier = Modifier.weight(1f)) {
                    weather.conditionLabel?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(UiTags.DIGEST_WEATHER_CONDITION))
                    }
                    weather.quantityLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = dark, modifier = Modifier.testTag(UiTags.DIGEST_WEATHER_QUANTITY))
                    }
                    if (weather.alerts.isNotEmpty()) {
                        Text(
                            weather.alerts.joinToString(" · ").replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag(UiTags.DIGEST_WEATHER_ALERT),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(IntrinsicSize.Max)) {
                    weather.rainProbability?.let {
                        Text("pluie ${it.roundToInt()} %", style = MaterialTheme.typography.bodyMedium, color = dark, textAlign = TextAlign.End, modifier = Modifier.testTag(UiTags.DIGEST_WEATHER_RAIN))
                    }
                    weather.windKmh?.let {
                        Text("vent ${it.roundToInt()} km/h", style = MaterialTheme.typography.bodyMedium, color = dark, textAlign = TextAlign.End, modifier = Modifier.testTag(UiTags.DIGEST_WEATHER_WIND))
                    }
                }
            }
        }
    }
}
