package com.maggie.app.ui.screens.finance

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The year over the first month of the Gantt's axis is read whole, « 2026 », never cut to « 202 » by its narrow column (MAG-634). */
@RunWith(AndroidJUnit4::class)
class ProjectGanttAxisScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the year of the axis is as wide as its text even over a column narrower than it`() {
        val planning = ProjectPlanning(startMonth = "2026-10", horizonMonths = 36, isIncomeKnown = true)
        val column = 2.dp
        compose.setContent { Axis(planning, column, column * planning.horizonMonths) }

        val year = compose.onNodeWithText("2026")
        year.assertIsDisplayed()
        val layout = mutableListOf<TextLayoutResult>()
        year.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        assertNotNull(layout.firstOrNull())
        val node = year.fetchSemanticsNode()
        assertTrue(
            "the year is ${node.size.width} px wide for a text of ${layout.first().multiParagraph.maxIntrinsicWidth}",
            node.size.width >= layout.first().multiParagraph.maxIntrinsicWidth,
        )
    }
}
