package com.maggie.app.data.model

import com.maggie.app.data.api.ApiCollection
import com.maggie.app.data.api.ProjectFundingResult
import com.maggie.app.data.api.ProjectReorderResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A project as the API sends it (MAG-519): the recorded collection of `GET /api/finance/projects`,
 * and the answer of `POST /api/finance/projects/reorder`, whose dependencies are plain ids.
 */
class ProjectTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun recording(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "api/contract").isDirectory) dir = dir.parentFile
        return File(dir ?: error("api/contract not found"), "api/contract/responses/finance_projects.collection.json").readText()
    }

    @Test
    fun `the recorded collection reads every field of a project`() {
        val projects = json.decodeFromString(ApiCollection.serializer(Project.serializer()), recording()).member

        val move = projects[0]
        assertEquals("Recorded move", move.name)
        assertEquals(250000, move.amountCents)
        assertEquals(ProjectWellbeing.ESSENTIAL, ProjectWellbeing.of(move.wellbeing))
        assertEquals(4, move.wellbeingScore)
        assertEquals(ProjectDateMode.FIXED, ProjectDateMode.of(move.dateMode))
        assertEquals("2027-03-01", move.targetOn)
        assertNull(move.note)

        val sofa = projects[1]
        assertEquals(1, sofa.position)
        assertEquals(1, sofa.wellbeingScore)
        assertEquals(listOf("01ARZ3NDEKTSV4RRFFQ69G5FAV"), sofa.dependencyIds)
        assertEquals(listOf("01ARZ3NDEKTSV4RRFFQ69G5FAV"), sofa.unmetDependencies)
        assertEquals("Recorded note", sofa.note)
    }

    @Test
    fun `the reorder answer carries plain ids, read the same way as IRIs`() {
        val body = """
            {"success":true,"projects":[
              {"id":"p2","name":"Canapé","amountCents":120000,"currency":"EUR","wellbeing":"comfort","wellbeingScore":1,
               "position":0,"dateMode":"none","targetOn":null,"dependsOn":["p1"],"note":null,"unmetDependencies":["p1"]},
              {"id":"p1","name":"Déménagement","amountCents":250000,"currency":"EUR","wellbeing":"essential","wellbeingScore":4,
               "position":1,"dateMode":"fixed","targetOn":"2027-03-01","dependsOn":[],"note":null,"unmetDependencies":[]}
            ]}
        """.trimIndent()

        val result = json.decodeFromString(ProjectReorderResult.serializer(), body)

        assertTrue(result.success)
        assertEquals(listOf("p2", "p1"), result.projects.map { it.id })
        assertEquals(listOf("p1"), result.projects[0].dependencyIds)
    }

    @Test
    fun `the date reads as its mode, with the date in French when it has one`() {
        assertEquals("Sans date", projectDateLabel(Project(id = "a", name = "A")))
        assertEquals(
            "Date fixe · 1 mars 2027",
            projectDateLabel(Project(id = "a", name = "A", dateMode = "fixed", targetOn = "2027-03-01")),
        )
        assertEquals(
            "Date limite · 15 janvier 2027",
            projectDateLabel(Project(id = "a", name = "A", dateMode = "deadline", targetOn = "2027-01-15")),
        )
    }

    @Test
    fun `a dependency reads as the name of the project waited for`() {
        val move = Project(id = "p1", name = "Déménagement")
        val car = Project(id = "p3", name = "Voiture")
        val sofa = Project(id = "p2", name = "Canapé", dependsOn = listOf("/api/finance/projects/p1", "/api/finance/projects/p3"))

        assertEquals("après Déménagement, Voiture", projectDependenciesLabel(sofa, listOf(move, sofa, car)))
        assertNull(projectDependenciesLabel(move, listOf(move, sofa, car)))
    }

    @Test
    fun `an unknown wellbeing or date mode falls back rather than breaking`() {
        assertEquals(ProjectWellbeing.MEDIUM, ProjectWellbeing.of("nonsense"))
        assertEquals(ProjectDateMode.NONE, ProjectDateMode.of("nonsense"))
        assertEquals(listOf(4, 3, 2, 1), ProjectWellbeing.entries.map { it.score })
        assertEquals(listOf("Indispensable", "Élevé", "Moyen", "Confort"), ProjectWellbeing.entries.map { it.label })
    }

    @Test
    fun `a project reads its funding sources and the salary that is left`() {
        val body = """
            {"id":"p3","name":"Voiture","amountCents":1000000,"currency":"EUR","wellbeing":"medium","wellbeingScore":2,
             "position":2,"dateMode":"none","targetOn":null,"dependsOn":[],"note":null,"unmetDependencies":[],
             "salaryCents":300000,
             "fundingSources":[
               {"kind":"savings","amountCents":100000,"accountId":"a1","loanId":null},
               {"kind":"loan","amountCents":600000,"accountId":null,"loanId":"l1"}]}
        """.trimIndent()

        val project = json.decodeFromString(Project.serializer(), body)

        assertEquals(300000, project.salaryCents)
        assertEquals(listOf("savings", "loan"), project.fundingSources.map { it.kind })
        assertEquals("a1", project.fundingSources[0].accountId)
        assertEquals("l1", project.loanSource?.loanId)
        assertEquals("économies + emprunt + salaire", projectFundingSummary(project))
    }

    @Test
    fun `the summary names the kinds in a fixed order and the salary only when it pays`() {
        val capital = ProjectFundingSource(kind = "capital", amountCents = 1, accountId = "a")
        val savings = ProjectFundingSource(kind = "savings", amountCents = 1, accountId = "a")

        assertNull(projectFundingSummary(Project(id = "a", name = "A")))
        assertEquals("salaire", projectFundingSummary(Project(id = "a", name = "A", salaryCents = 5)))
        assertEquals("économies + capital", projectFundingSummary(Project(id = "a", name = "A", fundingSources = listOf(capital, savings))))
    }

    @Test
    fun `the options answer reads the three options and leaves absent delays null`() {
        val body = """
            {"projectId":"p3","amountCents":1000000,"currency":"EUR","netCapacityCents":50000,"loanRateBasisPoints":400,
             "loanMonths":24,"downPaymentCents":400000,"yieldBasisPoints":300,
             "cash":{"principalCents":0,"downPaymentCents":1000000,"totalCostCents":1000000,"netCostCents":1000000,
                     "monthlyPaymentCents":0,"durationMonths":0,"availableInMonths":20,"capitalKeptCents":0},
             "loan":{"totalCostCents":1100000,"netCostCents":1090000,"monthlyPaymentCents":45000,"durationMonths":24,"availableInMonths":0},
             "partial":{"totalCostCents":1050000,"netCostCents":1040000,"monthlyPaymentCents":25000,"durationMonths":24,
                        "availableInMonths":null,"nImpactMonths":2}}
        """.trimIndent()

        val options = json.decodeFromString(ProjectOptions.serializer(), body)

        assertEquals(1000000, options.cash.totalCostCents)
        assertEquals(20, options.cash.availableInMonths)
        assertEquals(45000, options.loan.monthlyPaymentCents)
        assertNull(options.cash.nImpactMonths)
        assertNull(options.partial.availableInMonths)
        assertEquals(2, options.partial.nImpactMonths)
    }

    @Test
    fun `the funding answer carries the project`() {
        val body = """{"success":true,"project":{"id":"p3","name":"Voiture","salaryCents":10,"fundingSources":[]}}"""

        val result = json.decodeFromString(ProjectFundingResult.serializer(), body)

        assertTrue(result.success)
        assertEquals(10, result.project?.salaryCents)
    }

    private val investmentBody = """
        {"id":"p9","name":"Box à louer","amountCents":1800000,"currency":"EUR","wellbeing":"comfort","wellbeingScore":1,
         "position":0,"dateMode":"none","targetOn":null,"dependsOn":[],"note":null,"unmetDependencies":[],
         "salaryCents":0,
         "fundingSources":[
           {"kind":"savings","amountCents":300000,"accountId":"a1","loanId":null},
           {"kind":"loan","amountCents":1500000,"accountId":null,"loanId":"l1"},
           {"kind":"resale","amountCents":400000,"accountId":null,"loanId":null,"sourceProjectId":"p7"},
           {"kind":"somethingNew","amountCents":5}],
         "investment":{"projectId":"p9","priceCents":1800000,"downPaymentCents":300000,"loanId":"l1","loanPaymentCents":14108,
           "monthlyRentCents":16000,"monthlyBalanceCents":1892,
           "resale":{"month":"2028-06","netAmountCents":1000000,"targetProjectId":"p2"}}}
    """.trimIndent()

    @Test
    fun `an investment reads its price, down payment, loan, rent, balance and resale (MAG-557)`() {
        val investment = json.decodeFromString(Project.serializer(), investmentBody).investment!!

        assertEquals(1800000, investment.priceCents)
        assertEquals(300000, investment.downPaymentCents)
        assertEquals("l1", investment.loanId)
        assertEquals(14108, investment.loanPaymentCents)
        assertEquals(16000, investment.monthlyRentCents)
        assertEquals(1892, investment.monthlyBalanceCents)
        assertEquals("2028-06", investment.resale?.month)
        assertEquals(1000000, investment.resale?.netAmountCents)
        assertEquals("p2", investment.resale?.targetProjectId)
    }

    @Test
    fun `a plain project has no investment, and an investment may have no resale`() {
        val plain = json.decodeFromString(Project.serializer(), """{"id":"p1","name":"A","investment":null}""")
        val noResale = json.decodeFromString(
            Project.serializer(),
            """{"id":"p1","name":"A","investment":{"monthlyRentCents":100,"resale":null}}""",
        )

        assertNull(plain.investment)
        assertNull(noResale.investment?.resale)
        assertNull(investmentResaleLabel(noResale.investment!!, emptyList()))
    }

    @Test
    fun `a resale funding source is read, kept apart from the editable ones and summed`() {
        val project = json.decodeFromString(Project.serializer(), investmentBody)

        assertEquals("p7", project.fundingSources.first { it.kind == "resale" }.sourceProjectId)
        assertEquals(400000, project.resaleCents)
        assertEquals(FundingKind.RESALE, FundingKind.of("resale"))
        assertFalse(FundingKind.RESALE.editable)
        assertTrue(FundingKind.entries.filter { it != FundingKind.RESALE }.all { it.editable })
        assertEquals("économies + emprunt + revente", projectFundingSummary(project))
    }

    @Test
    fun `a funding kind the app does not know is read rather than breaking`() {
        val project = json.decodeFromString(Project.serializer(), investmentBody)

        assertEquals("somethingNew", project.fundingSources.last().kind)
        assertNull(FundingKind.of("somethingNew"))
    }

    @Test
    fun `the investment reads as rent, loan payment, balance and resale in French`() {
        val move = Project(id = "p2", name = "Japon")
        val investment = ProjectInvestment(
            monthlyRentCents = 16000,
            loanPaymentCents = 14108,
            monthlyBalanceCents = 1892,
            resale = ProjectResale(month = "2028-06", netAmountCents = 1000000, targetProjectId = "p2"),
        )

        assertEquals("Loyer perçu : ${formatCents(16000)} par mois", investmentRentLabel(investment))
        assertEquals("Mensualité du prêt : ${formatCents(14108)} par mois", investmentLoanLabel(investment))
        assertEquals("Solde : +${formatCents(1892)} par mois", investmentBalanceLabel(investment))
        assertEquals(
            "Revendre en juin 2028 pour ${formatCents(1000000)} net, pour l'apport de Japon",
            investmentResaleLabel(investment, listOf(move)),
        )
    }

    @Test
    fun `a box that costs more than it brings shows a negative balance, and no loan shows no payment`() {
        val costly = ProjectInvestment(monthlyRentCents = 10000, loanPaymentCents = 14108, monthlyBalanceCents = -4108)
        val cash = ProjectInvestment(monthlyRentCents = 10000, monthlyBalanceCents = 10000)
        val flat = ProjectInvestment()

        assertEquals("Solde : −${formatCents(4108)} par mois", investmentBalanceLabel(costly))
        assertNull(investmentLoanLabel(cash))
        assertEquals("Solde : +${formatCents(10000)} par mois", investmentBalanceLabel(cash))
        assertEquals("Solde : ${formatCents(0)} par mois", investmentBalanceLabel(flat))
    }

    @Test
    fun `a resale with no target, or whose target is gone, speeds up the next projects`() {
        val noTarget = ProjectInvestment(resale = ProjectResale(month = "2028-06", netAmountCents = 500000))
        val gone = ProjectInvestment(resale = ProjectResale(month = "2028-06", netAmountCents = 500000, targetProjectId = "p404"))
        val expected = "Revendre en juin 2028 pour ${formatCents(500000)} net, pour avancer les projets suivants"

        assertEquals(expected, investmentResaleLabel(noTarget, emptyList()))
        assertEquals(expected, investmentResaleLabel(gone, listOf(Project(id = "p2", name = "Japon"))))
    }

    @Test
    fun `a month that does not parse is shown as it came`() {
        assertEquals("juin 2028", investmentMonthLabel("2028-06"))
        assertEquals("juin 2028", investmentMonthLabel("2028-06-15"))
        assertEquals("bientôt", investmentMonthLabel("bientôt"))
    }
}
