package com.maggie.app.data.model

import com.maggie.app.data.api.ApiCollection
import com.maggie.app.data.api.ProjectFundingResult
import com.maggie.app.data.api.ProjectReorderResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
