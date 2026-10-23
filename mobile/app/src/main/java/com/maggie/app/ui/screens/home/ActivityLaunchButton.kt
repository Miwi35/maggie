package com.maggie.app.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.ACTIVITY_FINANCE_REVIEW
import com.maggie.app.data.model.HomeDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens

/** The tap area around the pill: 44 dp, as padding — the pill itself stays 28 dp. */
private val LAUNCH_TOUCH_TARGET = 44.dp
private val LAUNCH_PILL_HEIGHT = 28.dp

private const val LAUNCH_TINT_ALPHA = 0.12f
private const val LAUNCH_OUTLINE_ALPHA = 0.4f

/**
 * The route the activity an agenda event launches opens, from its `activity` field; null for an event that
 * is not one. Cuisine and Sport have no activity yet (MAG-568): their meal and workout lines open their
 * own sheet, which the card that draws the line wires itself.
 */
fun launchRouteFor(activity: String?): String? =
    if (activity == ACTIVITY_FINANCE_REVIEW) Screen.FinanceReviewActivity.route else null

/**
 * The discreet « ▶ Lancer » on an agenda line or a module card of the Accueil: a 28 dp pill on the
 * [module]'s colour at 12 % with a thin outline, a 14 dp ▶ and 13 sp text, in a 44 dp tap area. Spoken as
 * « Lancer [activity] ». Nothing for tomorrow.
 */
@Composable
fun ActivityLaunchButton(
    module: String,
    activity: String,
    day: HomeDay,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (day != HomeDay.TODAY) return

    val color = homeModuleColor(module)
    Box(
        modifier = modifier
            .testTag(UiTags.homeLaunch(module))
            .defaultMinSize(minHeight = LAUNCH_TOUCH_TARGET)
            .clickable(role = Role.Button, onClick = onLaunch)
            .semantics { contentDescription = "Lancer $activity" }
            .padding(vertical = (LAUNCH_TOUCH_TARGET - LAUNCH_PILL_HEIGHT) / 2),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .testTag(UiTags.homeLaunch(module) + "_pill")
                .height(LAUNCH_PILL_HEIGHT)
                .clearAndSetSemantics { },
            shape = RoundedCornerShape(MaggieTokens.Radius.pill),
            color = color.copy(alpha = LAUNCH_TINT_ALPHA),
            border = BorderStroke(1.dp, color.copy(alpha = LAUNCH_OUTLINE_ALPHA)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Text(
                    text = "Lancer",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = color,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
