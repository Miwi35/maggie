package com.maggie.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeepLinksTest {
    private val scheme = DeepLinks.SCHEME

    private fun placeholders(text: String) = Regex("\\{(\\w+)}").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `every link the ticket names has a pattern`() {
        assertEquals(listOf("$scheme://event/{id}"), DeepLinks.patternsFor(DeepLinks.EVENT_ROUTE))
        assertEquals(listOf("$scheme://task/{id}"), DeepLinks.patternsFor(DeepLinks.TASK_ROUTE))
        assertEquals(listOf("$scheme://grocery/{id}"), DeepLinks.patternsFor(DeepLinks.GROCERY_ROUTE))
        assertEquals(listOf("$scheme://recipe/{id}"), DeepLinks.patternsFor(DeepLinks.RECIPE_ROUTE))
        assertEquals(listOf("$scheme://chat?message={message}"), DeepLinks.patternsFor(Screen.Chat.route))
        assertEquals(listOf("$scheme://finance"), DeepLinks.patternsFor(Screen.FinanceDashboard.route))
        assertEquals(
            listOf("$scheme://finance/recurring-operations"),
            DeepLinks.patternsFor(Screen.RecurringOperations.route),
        )
    }

    @Test
    fun `a task's Focus opens from task id focus, the link a reminder's Je m'y mets follows (MAG-510)`() {
        assertEquals(listOf("$scheme://task/{id}/focus"), DeepLinks.patternsFor(DeepLinks.TASK_FOCUS_ROUTE))
        assertEquals("$scheme://task/01JABCDEFGHJKMNPQRSTVWXYZ0/focus", DeepLinks.taskFocus("01JABCDEFGHJKMNPQRSTVWXYZ0"))
    }

    @Test
    fun `the treasury screen opens from finance treasury`() {
        assertEquals(listOf("$scheme://finance/treasury"), DeepLinks.patternsFor(Screen.Treasury.route))
    }

    @Test
    fun `every finance screen can be opened from outside`() {
        val financeRoutes = listOf(
            Screen.FinanceDashboard,
            Screen.AccountList,
            Screen.Contacts,
            Screen.BudgetList,
            Screen.CategoryList,
            Screen.CategorizationRuleList,
            Screen.RuleSuggestions,
            Screen.TransactionsToSort,
            Screen.BankConnectionList,
            Screen.Cushion,
            Screen.Treasury,
            Screen.SavingsAdvances,
            Screen.LoanList,
            Screen.ProjectList,
            Screen.MonthlyReview,
            Screen.RecurringOperations,
        ).map { it.route }

        financeRoutes.forEach { route ->
            assertTrue("$route has no deep link", DeepLinks.patternsFor(route).isNotEmpty())
        }
        assertTrue(DeepLinks.patternsFor(DeepLinks.ACCOUNT_ROUTE).single().startsWith("$scheme://finance/accounts/"))
    }

    @Test
    fun `the contacts open from finance contacts, and one contact from its id`() {
        assertEquals(listOf("$scheme://finance/contacts"), DeepLinks.patternsFor(Screen.Contacts.route))
        assertEquals(listOf("$scheme://finance/contacts/{id}"), DeepLinks.patternsFor(DeepLinks.CONTACT_ROUTE))
    }

    @Test
    fun `savings advances opens from maggie finance savings-advances`() {
        assertEquals(listOf("$scheme://finance/savings-advances"), DeepLinks.patternsFor(Screen.SavingsAdvances.route))
    }

    @Test
    fun `patterns all use the build's scheme and none is declared twice`() {
        val all = DeepLinks.ROUTES.flatMap { DeepLinks.patternsFor(it) }

        assertTrue(all.all { it.startsWith("$scheme://") })
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `a path placeholder is a placeholder of the route that receives it`() {
        DeepLinks.ROUTES.forEach { route ->
            DeepLinks.patternsFor(route).forEach { pattern ->
                val path = pattern.substringBefore('?')
                assertTrue(
                    "$pattern: ${placeholders(path)} not in $route",
                    placeholders(route).containsAll(placeholders(path)),
                )
            }
        }
    }

    @Test
    fun `the routes that resolve an entity do not collide with the screens they open`() {
        val entryRoutes = listOf(
            DeepLinks.EVENT_ROUTE,
            DeepLinks.TASK_ROUTE,
            DeepLinks.TASK_FOCUS_ROUTE,
            DeepLinks.GROCERY_ROUTE,
            DeepLinks.RECIPE_ROUTE,
            DeepLinks.ACCOUNT_ROUTE,
            DeepLinks.CONTACT_ROUTE,
        )
        val screens = listOf(
            Screen.EventCreate, Screen.EventEdit, Screen.TaskCreate, Screen.TaskEdit, Screen.TaskFocus, Screen.RecipeDetail,
            Screen.RecipeCreate, Screen.RecipeEdit, Screen.AccountTransactions, Screen.Grocery, Screen.Contacts,
        ).map { it.route }

        assertTrue(entryRoutes.none { it in screens })
        assertEquals(entryRoutes.size, entryRoutes.toSet().size)
    }

    @Test
    fun `ids that go into API paths are ULIDs or UUIDs only`() {
        assertTrue(DeepLinks.isValidId("01JABCDEFGHJKMNPQRSTVWXYZ0"))
        assertTrue(DeepLinks.isValidId("3f2b8c1e-9d4a-4b7e-8a61-0c5d2e7f9a10"))

        assertFalse(DeepLinks.isValidId(null))
        assertFalse(DeepLinks.isValidId(""))
        assertFalse(DeepLinks.isValidId("../users/me"))
        assertFalse(DeepLinks.isValidId("a/b"))
        assertFalse(DeepLinks.isValidId("a b"))
        assertFalse(DeepLinks.isValidId("a?x=1"))
        assertFalse(DeepLinks.isValidId("a".repeat(65)))
    }

    @Test
    fun `a chat link prefills the draft and trims it`() {
        assertEquals("Ajoute du lait", DeepLinks.chatDraft("  Ajoute du lait \n"))
        assertEquals("", DeepLinks.chatDraft(null))
        assertEquals("", DeepLinks.chatDraft("   "))
    }

    @Test
    fun `the activity takes VIEW links, is singleTop to receive them, and is not browsable`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore("</activity>")
        val viewFilter = activity.substringAfter("android.intent.action.VIEW").substringBefore("</intent-filter>")

        assertTrue(activity.contains("android:launchMode=\"singleTop\""))
        assertTrue(viewFilter.contains("android:scheme=\"\${deepLinkScheme}\""))
        assertTrue(viewFilter.contains("android.intent.category.DEFAULT"))
        assertFalse(viewFilter.contains("BROWSABLE"))
    }

    @Test
    fun `each build has its own scheme, prod keeping maggie`() {
        val gradle = File("build.gradle.kts").readText()
        val schemes = Regex("manifestPlaceholders\\[\"deepLinkScheme\"] = \"([\\w-]+)\"").findAll(gradle)
            .map { it.groupValues[1] }.toList()

        assertEquals(listOf("maggie-dev", "maggie", "maggie-e2e"), schemes)
        assertTrue(scheme in schemes)
    }
}
