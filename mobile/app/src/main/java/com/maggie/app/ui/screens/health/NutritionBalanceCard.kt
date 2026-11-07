package com.maggie.app.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.theme.maggieSurface
import kotlin.math.abs

/**
 * The colour of a place on the bar (MAG-748): the chart palette's mint for the balanced band, its violet above, its
 * blue below — never Material's error or attention roles, so no day reads as an alarm.
 */
@Composable
@ReadOnlyComposable
fun balanceColor(zone: BalanceZone?): Color {
    val dark = isMaggieDark()
    fun pick(pair: Pair<Color, Color>) = if (dark) pair.second else pair.first
    return when (zone) {
        BalanceZone.BALANCED -> pick(MaggieTokens.chartCategorical[0])
        BalanceZone.SURPLUS -> pick(MaggieTokens.chartCategorical[1])
        BalanceZone.DEFICIT -> pick(MaggieTokens.chartCategorical[3])
        null -> maggieSurface().caption
    }
}

/**
 * « Bilan du jour »: the signed balance, the bar centred on zero with its balanced band, and « Mangé · Prévu · Dépensé ».
 * Without a balance (no expenditure yet) the eaten and planned kcal stand alone, with the way to complete the profile.
 * [onOpen] makes the whole card a link (Santé › Suivi opens Nutrition), [compact] its smaller form.
 */
@Composable
fun BalanceCard(
    balance: DailyBalance,
    onCompleteProfile: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val surface = maggieSurface()
    val label = balanceLabel(balance.balanceKcal)
    MaggieCard(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Bilan du jour" }.testTag(UiTags.NUTRITION_BALANCE),
        onClick = onOpen,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Bilan du jour", fontSize = 14.sp, color = surface.textMuted)
                Text("Aujourd’hui", fontSize = 12.sp, color = surface.caption)
            }
            Text(
                label ?: formatKcal(balance.eatenKcal + balance.plannedKcal),
                fontSize = if (compact) 24.sp else 30.sp,
                fontWeight = FontWeight.Light,
                color = if (label != null) balanceColor(balanceZone(balance.balanceKcal)) else surface.text,
                modifier = Modifier.testTag(UiTags.NUTRITION_BALANCE_VALUE),
            )
            if (balance.balanceKcal != null) {
                BalanceBar(balance.balanceKcal, Modifier.fillMaxWidth().height(if (compact) 14.dp else 20.dp))
            }
            Text(
                balanceFigures(balance),
                fontSize = 13.sp,
                color = surface.textMuted,
                modifier = Modifier.testTag(UiTags.NUTRITION_BALANCE_FIGURES),
            )
            if (balance.balanceKcal == null) {
                TextButton(onClick = onCompleteProfile, modifier = Modifier.testTag(UiTags.NUTRITION_PROFILE_HINT)) {
                    Text("Complétez votre profil pour voir le bilan")
                }
            }
        }
    }
}

/** The bar centred on zero: the track, the shaded balanced band ± 150 kcal, zero, and the day's balance from zero. */
@Composable
private fun BalanceBar(balanceKcal: Int, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    val band = balanceColor(BalanceZone.BALANCED).copy(alpha = 0.22f)
    val value = balanceColor(balanceZone(balanceKcal))
    val half = balanceHalfRange(balanceKcal)
    Canvas(modifier = modifier) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(surface.track, size = size, cornerRadius = radius)
        val bandStart = balanceFraction(-BALANCE_ZONE_KCAL, half) * size.width
        val bandEnd = balanceFraction(BALANCE_ZONE_KCAL, half) * size.width
        drawRect(band, Offset(bandStart, 0f), Size(bandEnd - bandStart, size.height))
        val zero = size.width / 2f
        val end = balanceFraction(balanceKcal, half) * size.width
        val inset = size.height * 0.25f
        drawRoundRect(
            value,
            Offset(minOf(zero, end), inset),
            Size(abs(end - zero).coerceAtLeast(2f), size.height - 2 * inset),
            cornerRadius = CornerRadius(inset),
        )
        drawRect(surface.caption, Offset(zero - 1f, 0f), Size(2f, size.height))
    }
}

/** « 7 jours »: one bar per day around zero, the balanced band shaded, the weekday under it; a day without balance has none. */
@Composable
fun BalanceWeekChart(week: List<DailyBalance>, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    val band = balanceColor(BalanceZone.BALANCED).copy(alpha = 0.18f)
    val colors = week.map { balanceColor(balanceZone(it.balanceKcal)) }
    val scale = balanceWeekScale(week)
    MaggieCard(modifier = modifier.fillMaxWidth().semantics { contentDescription = "7 jours" }.testTag(UiTags.NUTRITION_WEEK)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("7 jours", fontSize = 14.sp, color = surface.textMuted)
            Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                if (week.isEmpty()) return@Canvas
                val mid = size.height / 2f
                fun y(kcal: Int) = mid - kcal / scale.toFloat() * mid
                drawRect(band, Offset(0f, y(BALANCE_ZONE_KCAL)), Size(size.width, y(-BALANCE_ZONE_KCAL) - y(BALANCE_ZONE_KCAL)))
                drawRect(surface.caption.copy(alpha = 0.5f), Offset(0f, mid - 0.5f), Size(size.width, 1f))
                val slot = size.width / week.size
                val bar = slot * 0.5f
                week.forEachIndexed { i, day ->
                    val kcal = day.balanceKcal ?: return@forEachIndexed
                    val top = minOf(mid, y(kcal))
                    val height = abs(y(kcal) - mid).coerceAtLeast(2f)
                    drawRoundRect(colors[i], Offset(i * slot + (slot - bar) / 2f, top), Size(bar, height), CornerRadius(3f))
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Text(
                        weekdayInitial(day.date),
                        fontSize = 11.sp,
                        color = surface.caption,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
